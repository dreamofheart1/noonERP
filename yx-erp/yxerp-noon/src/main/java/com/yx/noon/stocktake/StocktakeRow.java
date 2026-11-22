package com.yx.noon.stocktake;

/**
 * 盘点单列表行（契约 §2）。{@code siteCode} 由 {@code md_warehouse} 真实归属站点带出，
 * 不落冗余列（口径同 M4 第一轮入库单）。
 */
public record StocktakeRow(long id, String stocktakeNo, long warehouseId, String siteCode, String period,
                           String status)
{
}
