package com.yx.noon.asn;

import java.util.List;

/**
 * ASN 建单入参（契约 §3/§4）。
 *
 * <p>{@code siteCode} 是<b>被授权的对象</b>（服务端先 {@code guard.requireSite} 再按
 * {@code (warehouseCode, siteCode)} 解析 FBN 目标仓），不是可自报的权限来源。
 * {@code operatorId} 仅为契约签名保留，服务端一律使用 {@code SiteAuthGuard.requireCurrentUserId()}，
 * 不信任请求体身份。
 */
public record AsnCreateInput(String siteCode, String warehouseCode, String requestId, Long operatorId,
                             List<AsnCreateLine> lines)
{
    public AsnCreateInput
    {
        lines = lines == null ? null : List.copyOf(lines);
    }
}
