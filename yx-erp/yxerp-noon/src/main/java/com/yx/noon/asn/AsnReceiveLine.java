package com.yx.noon.asn;

/**
 * ASN 收货明细行（契约 §3/§6）：{@code asnItemId} = {@code log_asn_item.id}；
 * {@code qtyReceived} = FBN 仓实收量（0 表示整行短少）；
 * {@code shortEventId} 在短少（{@code qtyShipped - qtyReceived > 0}）时必填，进入
 * {@code biz_event_key='ASNSHORT:'+asnItemId+':'+shortEventId}。
 */
public record AsnReceiveLine(Long asnItemId, Integer qtyReceived, String shortEventId)
{
}
