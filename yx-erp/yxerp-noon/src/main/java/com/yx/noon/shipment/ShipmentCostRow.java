package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 费用列表行（契约 §2）：摘要字段，供分页列表展示；详情见 {@link ShipmentCostDetail}。
 *
 * @param poIds 本费用多选关联的采购单 id（升序、去重），无关联为空列表
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
        LocalDateTime createdAt,
        List<Long> poIds)
{
    /** 兼容旧调用（无采购单多选）的 11 参构造器，poIds 视为空。 */
    public ShipmentCostRow(long id, String costNo, Long asnId, String siteCode, String period,
                           String feeType, BigDecimal amount, String currency, BigDecimal fxRate,
                           String allocationFlag, LocalDateTime createdAt)
    {
        this(id, costNo, asnId, siteCode, period, feeType, amount, currency, fxRate,
                allocationFlag, createdAt, List.of());
    }
}
