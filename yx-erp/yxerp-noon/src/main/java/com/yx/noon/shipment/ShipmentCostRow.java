package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用列表行（契约 §2）：摘要字段，供分页列表展示；详情见 {@link ShipmentCostDetail}。
 */
public record ShipmentCostRow(
        long id,
        String costNo,
        Long asnId,
        String siteCode,
        String period,
        String feeType,
        BigDecimal amount,
        String currency,
        BigDecimal fxRate,
        String allocationFlag,
        LocalDateTime createdAt)
{
}
