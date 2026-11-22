package com.yx.noon.masterdata;

/** Partner SKU 写入入参（字段名对应 md_partner_sku 列 camelCase）。 */
public record PartnerSkuInput(String partnerSku, Long skuId, String siteCode, String businessModel,
                              String offerCode, String barcode, String status)
{
}
