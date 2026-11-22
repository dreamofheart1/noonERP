package com.yx.noon.asn;

import java.util.List;

/**
 * ASN 收货入参（契约 §3/§6）。支持按行分批：不同 {@code requestId} 可覆盖不同行，
 * 同一行只能被收一次（在途子批次离开 IN_TRANSIT 即视为已收）。{@code operatorId} 仅结构保留。
 */
public record AsnReceiveInput(Long asnId, String requestId, Long operatorId, List<AsnReceiveLine> lines)
{
    public AsnReceiveInput
    {
        lines = lines == null ? null : List.copyOf(lines);
    }
}
