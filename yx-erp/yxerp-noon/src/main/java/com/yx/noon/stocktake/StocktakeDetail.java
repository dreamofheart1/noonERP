package com.yx.noon.stocktake;

import java.util.List;

/**
 * 盘点单详情（契约 §2）：单头 + 明细行（含 {@code qtyBook/qtyActual/qtyDiff}）。
 */
public record StocktakeDetail(StocktakeRow head, List<StocktakeItemRow> items)
{
}
