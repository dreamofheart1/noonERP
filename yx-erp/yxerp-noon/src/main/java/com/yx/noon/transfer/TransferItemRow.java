package com.yx.noon.transfer;

/**
 * 调拨单行（契约 §3 允许自行定义，至少含 itemId/skuId/batchNo/qtyShipped/qtyReceived/diffQty）。
 *
 * <p>{@code batchNo} 是来源父批次号（拆批血缘根，发货后保持不变）；
 * {@code childBatchId/childBatchNo/childStatus} 是发货后生成的子批次（发货前为 null）。
 * 子批次查询口径：{@code inv_batch.parent_batch_id = 来源父批次.id AND transfer_no = 本单号}。
 *
 * @param itemId         {@code inv_transfer_item.id}
 * @param skuId          SKU（服务端从调拨单行带出，客户端不可传）
 * @param batchNo        来源父批次号
 * @param qtyShipped     实际发货量（发货前为计划量）
 * @param qtyReceived    已收货量（未收货为 0）
 * @param diffQty        短少量 = 发货量 − 收货量（未收货为 0）
 * @param childBatchId   在途/收货子批次 id
 * @param childBatchNo   子批次号（{@code B:TR:UUID}）
 * @param childStatus    子批次状态 OPEN/IN_TRANSIT/CLOSED
 */
public record TransferItemRow(Long itemId, Long skuId, String batchNo, Integer qtyShipped,
                              Integer qtyReceived, Integer diffQty, Long childBatchId,
                              String childBatchNo, String childStatus)
{
}
