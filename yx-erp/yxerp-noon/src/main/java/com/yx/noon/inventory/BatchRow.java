package com.yx.noon.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** FIFO 批次只读行（契约 §3），字段与 {@code inv_batch} 对齐，可被现有 FIFO 出库按站点消费。 */
public record BatchRow(Long id, String batchNo, Long skuId, Long warehouseId, String siteCode,
                       LocalDateTime receivedAt, Integer qtyIn, Integer qtyRemaining,
                       BigDecimal unitCost, String status)
{
}
