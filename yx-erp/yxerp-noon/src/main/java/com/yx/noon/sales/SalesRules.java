package com.yx.noon.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * M5 编排层通用校验/工具（只在 {@code com.yx.noon.sales} 内使用）。
 *
 * <p>与 asn 包 {@code AsnRules} 同口径（该处为包私有无法跨包复用）：幂等键格式、内容指纹
 * SHA-256、null/空串区分编码、业务时刻 UTC+8、期间归属 YYYY-MM、分页边界、金额 4 位 HALF_UP。
 * 站点授权一律走 {@code SiteAuthGuard}，不在此实现。
 */
final class SalesRules
{
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MONEY_SCALE = 4;

    /** requestId：1–64 位（表列宽 64），只允许字母数字下划线短横，禁止冒号等破坏事件键结构的字符。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private SalesRules()
    {
    }

    static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId.trim()).matches())
            throw new IllegalArgumentException("请求缺少有效的 requestId（1–64 位 [A-Za-z0-9_-]）");
        return requestId.trim();
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 及内部闸门口径一致）。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 期间归属 YYYY-MM（UTC+8），与 {@code inv_batch_consumption.period} 口径一致。 */
    static String period(LocalDateTime at)
    {
        return String.format(Locale.ROOT, "%04d-%02d", at.getYear(), at.getMonthValue());
    }

    /** 金额统一 4 位小数（DECIMAL(18,4)，HALF_UP）；null 原样返回，由调用方决定是否合法缺失。 */
    static BigDecimal money(BigDecimal value)
    {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    static int limit(int limit)
    {
        if (limit < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    static void offset(int offset)
    {
        if (offset < 0) throw new IllegalArgumentException("offset 不能为负数");
    }

    static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /** 站点编码规范化：只允许 AE/SA（与 SiteAuthGuard.SUPPORTED_SITES 同源），统一大写。 */
    static String siteCode(String field, String value)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 16) throw new IllegalArgumentException(field + " 长度不能超过 16");
        if (!java.util.List.of("AE", "SA").contains(code))
            throw new IllegalArgumentException(field + " 只允许 AE/SA");
        return code;
    }

    /**
     * 内容指纹（契约 §3）：{@code SHA-256(action|token|token...)} 取 64 位小写十六进制。
     *
     * <p><b>null/空串区分</b>：字符串经 {@link #field(String)} 编码——null → {@code ~}；
     * 非 null → {@code =} 前缀 + 转义（{@code \\ | : , = ~} 前加反斜杠）。故 null ≠ ""，
     * 且含分隔符的值不会与相邻字段串位。数值型（Long/Integer/BigDecimal）统一十进制字面量，
     * 金额先 {@link #money} 再 {@code toPlainString()}，避免 88.5 与 88.5000 产生两个指纹。
     */
    static String fingerprint(String action, Object... tokens)
    {
        StringBuilder canonical = new StringBuilder(action == null ? "~" : action);
        for (Object token : tokens)
        {
            canonical.append('|');
            if (token == null) canonical.append('~');
            else if (token instanceof BigDecimal amount)
                canonical.append('N').append(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString());
            else if (token instanceof Number number)
                canonical.append('N').append(number);
            else canonical.append(field(String.valueOf(token)));
        }
        return sha256Hex(canonical.toString());
    }

    /** 指纹字段编码：null → {@code ~}；非 null → {@code =} + 转义值（空串编码为 {@code =}，与 null 不同）。 */
    static String field(String value)
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
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
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

    /**
     * 数值列健壮读取：真实 schema 的 {@code sign} 为 TINYINT(1)，MySQL JDBC 默认映射 Boolean；
     * H2 映射 Number。两种都必须支持（与 ReversalService 同口径）。
     */
    static int toInt(Object value)
    {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return Integer.parseInt(String.valueOf(value));
    }

    /** SQL IN 占位符：返回 "?,?" 形式。 */
    static String placeholders(int size)
    {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    /** 生成 8 位大写十六进制（批次号尾段）：UUID 去横线取前 8 位，碰撞概率可忽略且与 uk_batch_no 兜底。 */
    static String uuid8()
    {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
