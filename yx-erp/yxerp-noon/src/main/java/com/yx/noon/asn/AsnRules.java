package com.yx.noon.asn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * M4 第三轮第三批 ASN 通用校验/工具（只在 {@code com.yx.noon.asn} 内使用）。
 *
 * <p>与调拨 {@code TransferRules} / 第一轮 {@code InventoryRules} 同口径（两者均包私有，无法跨包复用，
 * 故按契约「同口径实现」重建）：幂等键格式、短少事件号格式、业务时刻 UTC+8、分页边界、站点编码、
 * 金额 4 位 HALF_UP、内容指纹 SHA-256。站点授权本身一律走 {@link SiteAuthGuard}，不在此实现。
 */
final class AsnRules
{
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;

    /** 幂等键格式（对齐 M3 v2 / M4 前两轮）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * 短少事件号格式（进入 {@code biz_event_key='ASNSHORT:'+asnItemId+':'+shortEventId}，
     * 列宽 VARCHAR(160)）：1–64 位，仅字母数字下划线短横；禁止含冒号等会破坏事件键结构的字符。
     */
    private static final Pattern SHORT_EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private AsnRules()
    {
    }

    /** 必填正整数 id。 */
    static void requiredId(String field, Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
    }

    /** 分页 limit：非法收敛到默认值，上限 200（与 masterdata/inventory/transfer 口径一致）。 */
    static int limit(int limit)
    {
        if (limit < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    static void offset(int offset)
    {
        if (offset < 0) throw new IllegalArgumentException("offset 不能为负数");
    }

    /** 站点编码只允许 AE/SA，与 {@link SiteAuthGuard#SUPPORTED_SITES} 同源。 */
    static String siteCode(String field, String value)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 16) throw new IllegalArgumentException(field + " 长度不能超过 16");
        if (!SiteAuthGuard.SUPPORTED_SITES.contains(code))
            throw new IllegalArgumentException(field + " 只允许 AE/SA");
        return code;
    }

    /** FBN 仓编码（{@code md_warehouse.warehouse_code}，列宽 64）：非空、trim 后 ≤64。 */
    static String warehouseCode(String value)
    {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("warehouseCode 不能为空");
        String code = value.trim();
        if (code.length() > 64)
            throw new IllegalArgumentException("warehouseCode 长度不能超过 64");
        return code;
    }

    /** SQL IN 占位符：返回 "?,?" 形式。 */
    static String placeholders(int size)
    {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    /** 金额统一 4 位小数（DECIMAL(18,4)，HALF_UP）。 */
    static BigDecimal money(BigDecimal value)
    {
        if (value == null) throw new IllegalArgumentException("金额/成本不能为空");
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 口径一致）。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 期间归属 YYYY-MM（UTC+8），与 {@code inv_batch_consumption.period} 口径一致。 */
    static String period(LocalDateTime at)
    {
        return String.format(Locale.ROOT, "%04d-%02d", at.getYear(), at.getMonthValue());
    }

    /** 幂等键校验：非空白、长度 8–64、仅 [A-Za-z0-9_-]，否则 400 统一文案。 */
    static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException("ASN 请求缺少有效的 requestId");
        return requestId;
    }

    /** 短少事件号校验：短少（diff &gt; 0）时必须提供合法事件号，否则无法生成稳定的事件键。 */
    static String requireShortEventId(String shortEventId)
    {
        String normalized = optionalShortEventId(shortEventId);
        if (normalized == null)
            throw new IllegalArgumentException("短少必须提供有效的 shortEventId（1–64 位 [A-Za-z0-9_-]）");
        return normalized;
    }

    /**
     * shortEventId 规范化：空白 → null（不参与业务，但按规范化结果参与指纹）；
     * 非空时必须符合 1–64 位 {@code [A-Za-z0-9_-]}（含冒号等会破坏 {@code ASNSHORT:item:event} 结构）。
     */
    static String optionalShortEventId(String shortEventId)
    {
        if (shortEventId == null || shortEventId.isBlank()) return null;
        String normalized = shortEventId.trim();
        if (!SHORT_EVENT_ID.matcher(normalized).matches())
            throw new IllegalArgumentException("shortEventId 格式无效（1–64 位 [A-Za-z0-9_-]）");
        return normalized;
    }

    // ------------------------------------------------------------ 内容指纹（裁决点 D1）

    /**
     * 建单内容指纹（契约 §4.3）：规范化串
     * {@code "CREATE|<site>|<warehouseCode>|<按(skuId,batchNo)升序的 skuId:batchNo:qtyShipped:partnerSku>"}，
     * 行之间逗号连接，UTF-8 后 SHA-256 取 64 位小写十六进制。
     *
     * <p><b>null/空串区分</b>：所有字符串字段用 {@link #field(String)} 编码——null 编码为 {@code ~}，
     * 非 null 编码为 {@code =} 前缀 + 转义（{@code \\ | : , = ~} 前加反斜杠）。故 null 与 "" 编码不同，
     * 含分隔符的值也不会与相邻字段混淆（避免 "a|b" 与 "a","b" 撞成同一串）。
     * 业务上 {@code partnerSku} 空白会被规范化为 null（同一语义不产生两个不同指纹），已在报告说明。
     */
    static String createFingerprint(String siteCode, String warehouseCode, List<AsnCreateLine> lines)
    {
        List<AsnCreateLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(AsnCreateLine::skuId)
                .thenComparing(AsnCreateLine::batchNo));
        StringBuilder canonical = new StringBuilder("CREATE|")
                .append(field(siteCode)).append('|').append(field(warehouseCode)).append('|');
        for (int i = 0; i < sorted.size(); i++)
        {
            if (i > 0) canonical.append(',');
            AsnCreateLine line = sorted.get(i);
            canonical.append(line.skuId()).append(':')
                    .append(field(line.batchNo())).append(':')
                    .append(line.qtyShipped()).append(':')
                    .append(field(line.partnerSku()));
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * 封单指纹：{@code "SEAL|<asnId>"}。封单无客户端明细（发运量 = 登记量），动作进入前缀，
     * 故 CREATE 的 requestId 拿去 SEAL 是不同键（动作级隔离）；同 action 换 ASN 必然内容不一致 400。
     */
    static String sealFingerprint(long asnId)
    {
        return sha256Hex("SEAL|" + asnId);
    }

    /**
     * 收货指纹：{@code "RECEIVE|<asnId>|<按 asnItemId 升序的 asnItemId:qtyReceived:shortEventId>"}。
     * 动作前缀隔离，行序无关；短少事件号纳入指纹（同键换事件号即 400，避免同一请求被换事件键重记短少）。
     */
    static String receiveFingerprint(long asnId, List<AsnReceiveLine> lines)
    {
        List<AsnReceiveLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(AsnReceiveLine::asnItemId));
        StringBuilder canonical = new StringBuilder("RECEIVE|").append(asnId).append('|');
        for (int i = 0; i < sorted.size(); i++)
        {
            if (i > 0) canonical.append(',');
            AsnReceiveLine line = sorted.get(i);
            canonical.append(line.asnItemId()).append(':')
                    .append(line.qtyReceived()).append(':')
                    .append(field(line.shortEventId()));
        }
        return sha256Hex(canonical.toString());
    }

    /** 指纹字段编码：null → {@code ~}；非 null → {@code =} + 转义值（空串编码为 {@code =}，与 null 不同）。 */
    private static String field(String value)
    {
        if (value == null) return "~";
        StringBuilder out = new StringBuilder(value.length() + 1).append('=');
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '\\' || c == '|' || c == ':' || c == ',' || c == '=' || c == '~')
                out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    static String sha256Hex(String canonical)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
