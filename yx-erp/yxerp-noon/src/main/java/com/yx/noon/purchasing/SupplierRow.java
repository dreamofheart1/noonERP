package com.yx.noon.purchasing;

/** 供应商读模型（契约 4.1，不含 DB 生成时间列）。 */
public record SupplierRow(long id, String supplierNo, String supplierName, String contactJson, String status)
{
}
