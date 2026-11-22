package com.yx.noon.transfer;

/**
 * 调拨发货行（契约 §3）：{@code itemId} 为 {@code inv_transfer_item.id}，{@code qtyShipped} 为本次实际
 * 发货量，必须 &gt;0、不超过该行计划量、不超过来源批次剩余量。
 */
public record TransferShipLine(Long itemId, int qtyShipped)
{
}
