package com.yx.framework.reversal;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * NOON-20261121-REVERSAL 三次复核：调用方有界同键重试组件。
 *
 * <p><b>职责边界</b>：本类把"锁异常后重试"从测试私有范式提升为可复用的正式组件，供未来接入层
 * （HTTP/Controller 仍未实现）调用。它<b>不是</b> service 内部逻辑：{@link ReversalService#postReversal}
 * 仍不吞锁异常、事务整体回滚、无部分写入；本组件在<b>事务外</b>调用 service（每次调用是独立事务），
 * 仅对可重试锁异常做有界重试，用尽即向上抛，由调用方决定隔离/告警。
 *
 * <p><b>三要素</b>：
 * <ul>
 *   <li>事务边界（五次复核强化）：本组件<b>不</b>加 {@code @Transactional}，也<b>不</b>盲目用
 *       {@code REQUIRES_NEW}（那会破坏未来调用方业务原子性）。入口显式检查：若当前已存在活动 Spring
 *       事务（{@code TransactionSynchronizationManager.isActualTransactionActive()}），立即拒绝并抛
 *       {@link IllegalStateException}——因为 service 的 {@code postReversal} 是默认 REQUIRED，在外层事务中
 *       会加入外层事务，锁异常回滚标记会污染外层，在同一已回滚事务里重试没有意义。只有在<b>事务外</b>调用时，
 *       每次 {@code postReversal} 才由 service 代理各开一个独立事务，失败那次整体回滚后才重试。</li>
 *   <li>有界：attempts 上限可配，用尽后抛出最后一次锁异常，不无限重试。</li>
 *   <li>同键：整个重试循环使用<b>同一个入参</b>（同一 sourceEventId / bizEventKey）。service 前置幂等
 *       判定保证：若某次实际已提交但客户端未收到响应，下次重试命中 ALREADY_POSTED 返回同一行 ID，
 *       绝不重复入账。</li>
 * </ul>
 *
 * <p><b>可重试异常</b>：仅 {@link PessimisticLockingFailureException}（含 CannotAcquireLock /
 * DeadlockLoser / PessimisticLocking）与 {@link QueryTimeoutException}；其余异常原样上抛。
 */
@Component
public class ReversalRetryInvoker
{
    private final ReversalService service;

    public ReversalRetryInvoker(ReversalService service)
    {
        this.service = service;
    }

    /** 带重试结果的返回：attempts=实际调用 service 的次数（1 表示首次即成功）。 */
    public record RetryResult(ReversalService.Result result, int attempts)
    {
        public boolean posted() { return result != null && result.posted(); }
    }

    /**
     * 事务外、有界、同键重试。
     *
     * @param in        红冲入参；整个循环复用同一对象，保证同键
     * @param attempts  最大尝试次数（含首次），必须 >=1
     * @param backoffMs 每次重试前的退避毫秒数，>0 才 sleep；0 表示不退避
     * @return 成功结果 + 实际尝试次数
     * @throws IllegalArgumentException attempts &lt; 1
     * @throws IllegalStateException 调用方已处于活动事务中（本组件必须在事务外调用，见类注释）
     * @throws RuntimeException 用尽 attempts 后抛最后一次锁异常；非锁异常原样上抛
     */
    public RetryResult postWithRetry(ReversalService.ReversalInput in, int attempts, long backoffMs)
    {
        if (attempts < 1) throw new IllegalArgumentException("attempts 必须 >= 1");
        // 五次复核意见 1：显式拒绝已有事务。若外层已有活动事务，service.postReversal 默认 REQUIRED
        // 会加入外层；锁异常回滚会把外层标记为 rollback-only，循环在已回滚事务里重试无意义且不可控。
        // 此处 fail-fast，把"每次调用独立事务"作为前置契约，而非盲目 REQUIRES_NEW 破坏未来业务原子性。
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "ReversalRetryInvoker 必须在事务外调用：检测到当前存在活动事务；"
                            + "请在事务提交/回滚后重试，勿在 @Transactional 方法内调用本组件");
        RuntimeException lastLockFailure = null;
        for (int i = 1; i <= attempts; i++)
        {
            try
            {
                return new RetryResult(service.postReversal(in), i);
            }
            catch (PessimisticLockingFailureException | QueryTimeoutException lockEx)
            {
                lastLockFailure = lockEx;
                if (i == attempts) break; // 用尽，向上抛最后一次锁异常
            }
            if (backoffMs > 0)
            {
                try { Thread.sleep(backoffMs); }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试退避被中断", ie);
                }
            }
        }
        throw lastLockFailure == null
                ? new IllegalStateException("unreachable")
                : lastLockFailure;
    }
}
