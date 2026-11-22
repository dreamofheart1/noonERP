package com.yx.noon.transfer;

/**
 * 调拨收货行（契约 §3）：{@code itemId} 为 {@code inv_transfer_item.id}；{@code qtyReceived} 为本次实际
 * 收货量（0 ≤ 收货量 ≤ 发货量）；短少（0 &lt; 发货量 − 收货量）时 {@code shortEventId} 必填，
 * 用于生成事件键 {@code TSHORT:itemId:shortEventId}，同事件重放被唯一键拦截不双计。
 */
public record TransferReceiveLine(Long itemId, int qtyReceived, String shortEventId)
{
}
