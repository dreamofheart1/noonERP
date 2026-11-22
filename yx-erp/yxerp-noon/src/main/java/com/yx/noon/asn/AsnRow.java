package com.yx.noon.asn;

import java.time.LocalDateTime;

/**
 * ASN 列表行（契约 §3/§7）。{@code itemCount} = 明细行数；
 * {@code qtyShippedTotal} = 计划/实发量合计（便于列表显示进度）。字段名与前端约定的映射见报告。
 */
public record AsnRow(Long asnId, String asnNo, String siteCode, String warehouseCode, String status,
                     Integer itemCount, Integer qtyShippedTotal, Integer qtyReceivedTotal,
                     LocalDateTime sealedAt, LocalDateTime createdAt, String sourceMode)
{
    /** 兼容旧调用（无来源模式字段）的构造器，sourceMode 视为本地仓批次。 */
    public AsnRow(Long asnId, String asnNo, String siteCode, String warehouseCode, String status,
                  Integer itemCount, Integer qtyShippedTotal, Integer qtyReceivedTotal,
                  LocalDateTime sealedAt, LocalDateTime createdAt)
    {
        this(asnId, asnNo, siteCode, warehouseCode, status, itemCount, qtyShippedTotal,
                qtyReceivedTotal, sealedAt, createdAt, "LOCAL_BATCH");
    }
}
