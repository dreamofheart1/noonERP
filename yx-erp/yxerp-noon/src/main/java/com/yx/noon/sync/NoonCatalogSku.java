package com.yx.noon.sync;

/** Noon 商品目录导出行：平台 SKU 与内部 partner_sku/条码的对应关系。 */
public record NoonCatalogSku(String partnerSku, String noonSku, String barcode)
{
}
