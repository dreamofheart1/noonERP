package com.yx.noon.masterdata;

/** 仓库写入入参（字段名对应 md_warehouse 列 camelCase）。 */
public record WarehouseInput(String warehouseCode, String siteCode, String warehouseName,
                             String warehouseType, String fulfillmentMode, Boolean isFbn, String status)
{
}
