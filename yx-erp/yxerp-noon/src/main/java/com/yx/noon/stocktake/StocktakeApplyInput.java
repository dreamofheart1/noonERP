package com.yx.noon.stocktake;

/**
 * 应用盘点入参（契约 §2）：只认单 + 幂等键。
 *
 * <p>行内容（SKU / 实盘量 / 点数事件号）在建单时已固定，apply <b>不接收</b>任何明细或成本，
 * 差异基线在 apply 时按实时四态台账重算（{@code qty_diff = qty_actual - 实时 qty_available}）。
 */
public record StocktakeApplyInput(Long stocktakeId, String requestId)
{
}
