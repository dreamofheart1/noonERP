package com.yx.noon.masterdata;

/** SKU 读模型（id + md_sku 业务列，不含 DB 生成时间列）；image_url 为 Noon 官方回读的真实首图。 */
public record SkuRow(long id, String skuCode, String spuId, String nameEn, String nameAr,
                     String categoryCode, String hsCode, String originCountry, String brand,
                     String imageUrl, String specJson, String status)
{
}
