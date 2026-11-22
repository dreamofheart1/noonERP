package com.yx.noon.stocktake;

/**
 * 盘点明细入参（契约 §2）：一条「某 SKU 的实盘数量」。
 *
 * <p>本轮盘点基线是 <b>SKU 级</b>四态台账 {@code qty_available}（台账无批次维度），因此一张盘点单内
 * 同一 {@code skuId} 只能出现一次；{@code batchNo} 仅作账实核对的追溯标签（可空），
 * 不参与差异计算，也不构成「按批次盘点」的语义。
 *
 * <p>{@code countEventId} 是盘点批次（点数事件）身份，1–64 位 {@code [A-Za-z0-9_-]}，
 * 过账时进入批次消耗幂等键 {@code STK:<stocktake_item_id>:<countEventId>}；
 * 客户端<b>不能</b>传成本/单价（record 结构上不接收），盘盈成本由服务端按现有 OPEN 批次加权平均带出。
 */
public record StocktakeLineInput(Long skuId, String batchNo, int qtyActual, String countEventId)
{
}
