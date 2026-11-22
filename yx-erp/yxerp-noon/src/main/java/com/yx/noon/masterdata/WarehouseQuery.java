package com.yx.noon.masterdata;

/** 仓库列表查询条件（契约 v3）。siteCode 与授权闸门取交集，避免同一站点条件重复冲突。 */
public record WarehouseQuery(String warehouseCode, String siteCode, String status)
{
}
