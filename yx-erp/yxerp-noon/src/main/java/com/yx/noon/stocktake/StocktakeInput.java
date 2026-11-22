package com.yx.noon.stocktake;

import java.util.List;

/**
 * 盘点建单入参（契约 §2）。
 *
 * <p>{@code siteCode} 不进入入参：站点授权一律按 {@code md_warehouse} 的<b>真实</b>归属站点
 * {@code requireSite}，禁止请求体自报站点提权（与 M4 第一轮入库同口径）。
 *
 * <p>{@code period} 为归属期间 {@code YYYY-MM}（UTC+8）；为空时取业务时刻当月。
 * {@code requestId} 为调用方生成的幂等键（8–64 位 {@code [A-Za-z0-9_-]}）。
 */
public record StocktakeInput(Long warehouseId, String period, String requestId, List<StocktakeLineInput> lines)
{
}
