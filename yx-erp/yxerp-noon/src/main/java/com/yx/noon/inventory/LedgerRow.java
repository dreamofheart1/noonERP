package com.yx.noon.inventory;

/** 四态台账只读行（契约 §3）。本轮只累加 {@code qtyAvailable}，其余三态不动。 */
public record LedgerRow(Long id, Long skuId, Long warehouseId, String siteCode, Integer qtyAvailable,
                        Integer qtyInTransit, Integer qtyReserved, Integer qtyUnsellable)
{
}
