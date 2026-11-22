package com.yx.noon.transfer;

import java.util.List;

/**
 * 调拨动作结果（契约 §3）。首次执行 {@code replayed=false}；同 requestId 同内容重放原样返回首次结果并
 * 标记 {@code replayed=true}，不重复拆批/改台账/写流水/记短少/写审计。
 *
 * @param transferId    调拨单 id
 * @param transferNo    调拨单号
 * @param replayed      是否幂等重放
 * @param action        {@code CREATE} / {@code SHIP} / {@code RECEIVE}
 * @param childBatchIds 本次动作创建（ship）或处理（receive）的在途/收货子批次 id；CREATE 恒为空
 */
public record TransferResult(Long transferId, String transferNo, boolean replayed, String action,
                             List<Long> childBatchIds)
{
    public TransferResult
    {
        childBatchIds = childBatchIds == null ? List.of() : List.copyOf(childBatchIds);
    }
}
