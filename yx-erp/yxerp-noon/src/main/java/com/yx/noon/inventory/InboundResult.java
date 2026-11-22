package com.yx.noon.inventory;

import java.util.List;

/**
 * 入库确认结果（契约 §3）。首次确认 {@code replayed=false}；同 requestId 同内容重放
 * 原样返回首次结果并标记 {@code replayed=true}，不重复生成批次/台账/流水/审计。
 */
public record InboundResult(Long inboundId, String inboundNo, boolean replayed, List<Long> batchIds)
{
}
