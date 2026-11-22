package com.yx.noon.masterdata;

/** 仓库读模型（id + md_warehouse 业务列，不含 DB 生成时间列）。 */
public record WarehouseRow(long id, String warehouseCode, String siteCode, String warehouseName,
                           String warehouseType, String fulfillmentMode, Boolean isFbn, String status)
{
}
