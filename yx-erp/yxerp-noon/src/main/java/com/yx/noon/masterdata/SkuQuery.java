package com.yx.noon.masterdata;

/** SKU 列表查询条件（契约 v3）。SKU 为跨站共享主数据，无站点条件。 */
public record SkuQuery(String skuCode, String nameEn, String brand, String status)
{
}
