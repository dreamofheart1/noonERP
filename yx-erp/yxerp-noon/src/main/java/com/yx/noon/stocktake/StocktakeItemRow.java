package com.yx.noon.stocktake;

/**
 * 盘点明细行（契约 §2）。
 *
 * <p>{@code qtyBook} 建单时为台账快照、apply 后被<b>实时台账</b>可用量覆盖；
 * {@code qtyDiff = qtyActual - qtyBook} 由服务端显式写入（不信客户端）。
 */
public record StocktakeItemRow(long id, long skuId, String batchNo, int qtyBook, int qtyActual, int qtyDiff)
{
}
