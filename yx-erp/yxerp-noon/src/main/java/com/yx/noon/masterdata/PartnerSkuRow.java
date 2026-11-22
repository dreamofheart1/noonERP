package com.yx.noon.masterdata;

/** Partner SKU 读模型（id + md_partner_sku 写入列）。Offer 回读列按契约不对外暴露。 */
public record PartnerSkuRow(long id, String partnerSku, Long skuId, String siteCode,
                            String businessModel, String offerCode, String barcode, String status)
{
}
