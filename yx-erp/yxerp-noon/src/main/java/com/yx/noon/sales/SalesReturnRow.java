package com.yx.noon.sales;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货列表行（只读，站点隔离）。含分级处置结果字段，便于处置页直接展示。
 */
public record SalesReturnRow(
        Long id,
        String returnNo,
        String siteCode,
        String orderNo,
        Long skuId,
        Integer qty,
        String grade,
        String status,
        BigDecimal compensationAmount,
        BigDecimal refundAmount,
        BigDecimal returnLossAmount,
        String restockBatchNo,
        String period,
        String originalPeriod,
        Long salOrderItemId,
        LocalDateTime createdAt)
{
}
