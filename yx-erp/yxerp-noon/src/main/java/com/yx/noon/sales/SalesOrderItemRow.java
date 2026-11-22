package com.yx.noon.sales;

import java.math.BigDecimal;

/**
 * 订单行（FIFO 归集粒度 + 部分妥投/退货累计，供前端展示守恒状态）。
 */
public record SalesOrderItemRow(
        Long id,
        Long salOrderId,
        String orderNo,
        Long skuId,
        Integer qty,
        Integer qtyDelivered,
        Integer qtyReturned,
        Integer qtyCancelled,
        BigDecimal unitPrice)
{
}
