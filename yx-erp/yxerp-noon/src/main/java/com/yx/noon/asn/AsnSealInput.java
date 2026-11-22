package com.yx.noon.asn;

/**
 * ASN 封单发运入参（契约 §3/§5）。发货量 = create 登记量（{@code log_asn_item.qty_shipped}），
 * 不接受客户端改量，故无明细行。{@code operatorId} 仅结构保留，服务端取真实登录人。
 */
public record AsnSealInput(Long asnId, String requestId, Long operatorId)
{
}
