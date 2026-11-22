package com.yx.noon.purchasing;

import java.util.List;

/**
 * 收货结果（契约 4.4 / v2 §9.1）：返回本次收货后的订单状态、累计收货总数与本次写入的明细行。
 *
 * <p>{@code replayed=true} 表示命中幂等记录：该 requestId 此前已入账，本次原样返回首次结果、未重复入账。
 * 首次成功固定为 {@code false}；该值同时写入幂等表的 result_json。
 */
public record ReceiptResult(long orderId, String status, int receivedTotalQty, List<ReceiptRow> lines,
                            boolean replayed)
{
    public ReceiptResult
    {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
