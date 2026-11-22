package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用详情（契约 §2）：包含全部字段（含 remark）。
 */
public record ShipmentCostDetail(
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
        String remark,
        LocalDateTime createdAt)
{
}
