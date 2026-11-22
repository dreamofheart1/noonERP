package com.yx.noon.asn;

import java.util.List;

/**
 * ASN 建单入参（契约 §3/§4；M6 §3.1 扩展 {@code sourceMode}）。
 *
 * <p>{@code siteCode} 是<b>被授权的对象</b>（服务端先 {@code guard.requireSite} 再按
 * {@code (warehouseCode, siteCode)} 解析 FBN 目标仓），不是可自报的权限来源。
 * {@code operatorId} 仅为契约签名保留，服务端一律使用 {@code SiteAuthGuard.requireCurrentUserId()}，
 * 不信任请求体身份。
 *
 * <p>{@code sourceMode}：{@code LOCAL_BATCH}（默认，空/空白等价）/ {@code DIRECT_PURCHASE}。
 * 旧调用用四参兼容构造器（见下），等价于 {@code LOCAL_BATCH}，行为与 M4 逐字节一致。
 */
public record AsnCreateInput(String siteCode, String warehouseCode, String requestId, Long operatorId,
                             List<AsnCreateLine> lines, String sourceMode)
{
    public AsnCreateInput
    {
        lines = lines == null ? null : List.copyOf(lines);
    }

    /** 兼容构造器：M4/M5 旧调用（无 sourceMode → 服务端按 LOCAL_BATCH 处理）。 */
    public AsnCreateInput(String siteCode, String warehouseCode, String requestId, Long operatorId,
                          List<AsnCreateLine> lines)
    {
        this(siteCode, warehouseCode, requestId, operatorId, lines, null);
    }
}
