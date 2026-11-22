package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收货明细读模型（契约 4.4）。unitCostCny = item.unit_price × item.fx_rate，只含采购价。
 * receivedAt 为 UTC+8 业务时刻。
 */
public record ReceiptRow(long id, Long orderId, Long orderItemId, int qty,
                         BigDecimal unitCostCny, LocalDateTime receivedAt)
{
}
