package com.yx.noon.asn;

/**
 * ASN 明细行 + 子批次血缘（契约 §7）。{@code id} 为 {@code log_asn_item.id}（前端按 items[].id 绑定）；
 * {@code childBatchId/childBatchNo/childStatus/childSplitSeq}
 * 由 {@code asn_no + parent_batch_id} 关联出的在途/收货子批次；未封单前为 null。
 */
public record AsnItemRow(Long id, Long skuId, String partnerSku, String batchNo, Integer qtyShipped,
                         Integer qtyReceived, Integer diffQty, Long childBatchId, String childBatchNo,
                         String childStatus, Integer childSplitSeq)
{
}
