package com.yx.noon.masterdata;

/** Partner SKU 列表查询条件（契约 v3）。siteCode 与授权闸门取交集，避免同一站点条件重复冲突。 */
public record PartnerSkuQuery(String partnerSku, Long skuId, String siteCode, String status)
{
}
