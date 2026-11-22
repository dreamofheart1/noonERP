package com.yx.noon.asn;

/**
 * ASN 建单明细行（契约 §3）：{@code batchNo} 必须是本站点本地仓的 OPEN 成本批次（承载成本），
 * {@code qtyShipped} 为计划/实发量。{@code partnerSku} 可空（Noon 侧货号，仅登记不参与成本）。
 */
public record AsnCreateLine(Long skuId, String batchNo, Integer qtyShipped, String partnerSku)
{
}
