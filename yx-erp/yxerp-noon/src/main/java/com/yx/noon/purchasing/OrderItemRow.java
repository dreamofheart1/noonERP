package com.yx.noon.purchasing;

import java.math.BigDecimal;

/** 采购单行读模型（契约 4.3）。qtyReceived 只由收货流程写入。 */
public record OrderItemRow(long id, long purOrderId, long skuId, int qtyOrdered, int qtyReceived,
                           BigDecimal unitPrice, String currency, BigDecimal fxRate)
{
}
