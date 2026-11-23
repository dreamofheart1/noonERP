package com.yx.noon.sync;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Noon 主动请求的进程内统一限速器（用户约定的保守操作口径：连续请求间隔约 3 秒）。
 *
 * <p>注意：3 秒是用户指定的操作约定，不是官方限流阈值，也不保证不会被平台限制。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>单个进程内所有调用方共享一个令牌：用公平信号量串行化，避免并发路径各自 sleep 绕过间隔；</li>
 *   <li>间隔按“上一次请求开始 → 本次请求开始”计算，保证任意两次请求实际间隔 ≥ interval；</li>
 *   <li>每次尝试只执行一次请求（{@link Request#execute()} 返回状态码与结果），不会为读状态重复发送；</li>
 *   <li>遇到 429/503 按指数退避重试，重试次数有上限，不无限重试；其它错误直接返回交给调用方处理。</li>
 * </ul>
 * 跨进程/多实例部署时本限速器不共享，需外部统一限流，不得把它当作全局保证。
 */
public final class NoonRequestThrottle
{
    private final Semaphore gate = new Semaphore(1, true);
    private final long intervalMillis;
    private final int maxRetries;
    private final AtomicLong lastRequestStart = new AtomicLong(0L);

    public NoonRequestThrottle(long intervalMillis, int maxRetries)
    {
        if (intervalMillis < 0) throw new IllegalArgumentException("intervalMillis 不能为负");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries 不能为负");
        this.intervalMillis = intervalMillis;
        this.maxRetries = maxRetries;
    }

    /** 默认 3 秒间隔，最多 3 次退避重试。 */
    public static NoonRequestThrottle defaults()
    {
        return new NoonRequestThrottle(3000L, 3);
    }

    /** 一次请求的结果：状态码 + 业务结果。 */
    public record Response<T>(int status, T body)
    {
    }

    @FunctionalInterface
    public interface Request<T>
    {
        /** 执行一次真实请求并返回状态码与结果。 */
        Response<T> execute() throws Exception;
    }

    /**
     * 以统一间隔执行请求；429/503 时指数退避（间隔 + 2^n 秒），超过 maxRetries 抛异常。
     * 非限流错误原样返回，由调用方决定如何处理。
     */
    public <T> Response<T> run(Request<T> request)
    {
        boolean acquired = false;
        try
        {
            gate.acquire();
            acquired = true;
            for (int attempt = 0; ; attempt++)
            {
                waitInterval();
                Response<T> response = request.execute();
                if (response.status() != 429 && response.status() != 503) return response;
                if (attempt >= maxRetries)
                    throw new IllegalStateException(
                            "Noon 请求被限流/服务不可用，已退避 " + maxRetries + " 次仍失败: http=" + response.status());
                sleep(intervalMillis + (2L << Math.min(attempt, 10)) * 1000L);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Noon 请求限速等待被中断", e);
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Noon 请求执行失败", e);
        }
        finally
        {
            if (acquired) gate.release();
        }
    }

    private void waitInterval() throws InterruptedException
    {
        long elapsed = System.currentTimeMillis() - lastRequestStart.get();
        if (elapsed < intervalMillis) sleep(intervalMillis - elapsed);
        lastRequestStart.set(System.currentTimeMillis());
    }

    private static void sleep(long millis) throws InterruptedException
    {
        if (millis > 0) Thread.sleep(millis);
    }
}
