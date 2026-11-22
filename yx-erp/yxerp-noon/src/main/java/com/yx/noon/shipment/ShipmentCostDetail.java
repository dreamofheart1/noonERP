package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 费用详情（契约 §2）：包含全部字段（含 remark）。
 *
 * @param poIds 本费用多选关联的采购单 id（升序、去重），无关联为空列表
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
        LocalDateTime createdAt,
        List<Long> poIds)
{
    /** 兼容旧调用（无采购单多选）的 12 参构造器，poIds 视为空。 */
    public ShipmentCostDetail(long id, String costNo, Long asnId, String siteCode, String period,
                              String feeType, BigDecimal amount, String currency, BigDecimal fxRate,
                              String allocationFlag, String remark, LocalDateTime createdAt)
    {
        this(id, costNo, asnId, siteCode, period, feeType, amount, currency, fxRate,
                allocationFlag, remark, createdAt, List.of());
    }
}
