package com.yx.noon.inventory;

import java.math.BigDecimal;

/**
 * 入库单行（契约 §3）。{@code unitCost} 为 M3 收货权威成本（unit_price × fx_rate，只含采购价），
 * {@code batchNo} 对应已生成的 {@code inv_batch.batch_no}。
 */
public record InboundItemRow(Long id, Long purOrderItemId, Long skuId, String batchNo,
                             Integer qty, Integer qtyQualified, BigDecimal unitCost)
{
}
