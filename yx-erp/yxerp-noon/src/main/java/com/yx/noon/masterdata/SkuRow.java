package com.yx.noon.masterdata;

/** SKU 读模型（id + md_sku 业务列，不含 DB 生成时间列）。 */
public record SkuRow(long id, String skuCode, String spuId, String nameEn, String nameAr,
                     String categoryCode, String hsCode, String originCountry, String brand,
                     String specJson, String status)
{
}
