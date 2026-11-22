package com.yx.noon.masterdata;

/** SKU 写入入参（字段名对应 md_sku 列 camelCase；specJson 为 JSON 文本）。 */
public record SkuInput(String skuCode, String spuId, String nameEn, String nameAr, String categoryCode,
                       String hsCode, String originCountry, String brand, String specJson, String status)
{
}
