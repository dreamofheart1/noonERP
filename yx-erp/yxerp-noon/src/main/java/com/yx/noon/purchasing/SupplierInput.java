package com.yx.noon.purchasing;

/**
 * 供应商新增/修改入参（契约 4.1）。contactJson 为合法 JSON 或 null。
 */
public record SupplierInput(String supplierNo, String supplierName, String contactJson, String status)
{
}
