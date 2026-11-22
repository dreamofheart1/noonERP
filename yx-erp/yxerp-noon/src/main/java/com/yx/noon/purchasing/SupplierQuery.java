package com.yx.noon.purchasing;

/** 供应商列表筛选条件（契约 4.1）：编码/名称模糊、状态精确，空白不过滤。 */
public record SupplierQuery(String supplierNo, String supplierName, String status)
{
}
