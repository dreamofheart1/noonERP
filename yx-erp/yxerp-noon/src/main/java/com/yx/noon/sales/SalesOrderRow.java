package com.yx.noon.sales;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单列表行（只读，站点隔离后返回）。字段覆盖前端列表所需，不含平台原始报文。
 */
public record SalesOrderRow(
        Long id,
        String orderNo,
        String siteCode,
        Long storeId,
        String partnerSku,
        Long skuId,
        Integer qty,
        BigDecimal grossAmount,
        String currency,
        String sourceChannel,
        String status,
        LocalDateTime createdAt)
{
}
