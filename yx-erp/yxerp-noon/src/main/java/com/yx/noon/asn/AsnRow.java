package com.yx.noon.asn;

import java.time.LocalDateTime;

/**
 * ASN 列表行（契约 §3/§7）。{@code itemCount} = 明细行数；
 * {@code qtyShippedTotal} = 计划/实发量合计（便于列表显示进度）。字段名与前端约定的映射见报告。
 */
public record AsnRow(Long asnId, String asnNo, String siteCode, String warehouseCode, String status,
                     Integer itemCount, Integer qtyShippedTotal, Integer qtyReceivedTotal,
                     LocalDateTime sealedAt, LocalDateTime createdAt)
{
}
