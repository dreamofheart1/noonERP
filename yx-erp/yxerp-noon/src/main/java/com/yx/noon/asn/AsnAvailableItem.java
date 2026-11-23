package com.yx.noon.asn;

/**
 * 直发可选采购明细（v2 契约 §9.3）。
 *
 * <p>{@code qtyReceived} = 收货池 R（{@code noon_m3_receipt.qty} 合计）；
 * {@code directShipped} = 已建直发 ASN 发运量 D（DRAFT/SEALED/RECEIVED 全计）；
 * {@code inboundQty} = 已本地入库合格量 I（仅 CONFIRMED/CLOSED 入库单）；
 * {@code avail} = {@code R − D − I}（负数归零），即 {@code A_direct}。
 *
 * <p>只读展示用（无锁）：下单时仍由 {@code AsnService.create} 在 {@code pur_order_item} 行锁内按同一口径权威校验。
 */
public record AsnAvailableItem(long purOrderItemId, long orderId, String orderNo, long skuId,
                               long qtyReceived, long directShipped, long inboundQty, long avail)
{
}
