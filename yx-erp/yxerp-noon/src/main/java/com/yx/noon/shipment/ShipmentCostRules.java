package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * M4 第三轮第二批「头程费用登记」通用校验/工具（只在 {@code com.yx.noon.shipment} 内使用）。
 *
 * <p>与 {@code PurchasingRules}/{@code InventoryRules}/{@code StocktakeRules} 同口径：
 * 站点只允许 AE/SA、金额 4 位 HALF_UP、业务时刻 UTC+8、期间 YYYY-MM、分页上限 200、内容指纹 SHA-256。
 * 站点授权本身一律走 {@link SiteAuthGuard}，不在此实现。
 */
final class ShipmentCostRules
{
    static final List<String> FEE_TYPES = List.of("FREIGHT", "DUTY", "CUSTOMS", "PACKAGING", "LABEL");
    static final List<String> ALLOCATION_FLAGS = List.of("NONE", "PARTIAL", "FULL");
    static final String DEFAULT_ALLOCATION_FLAG = "NONE";

    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;
    /** {@code log_shipment_cost.remark VARCHAR(512)}。 */
    static final int REMARK_MAX_LENGTH = 512;
    /** {@code log_shipment_cost.fx_rate DECIMAL(18,8)}：与列宽一致的固定 8 位，用于落库与指纹。 */
    static final int FX_SCALE = 8;

    /** 动作级幂等键（对齐 M3/M4 既有口径）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** 归属期间 {@code YYYY-MM}（UTC+8），与 {@code period CHAR(7)} 同宽。 */
    private static final Pattern PERIOD = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private ShipmentCostRules()
    {
    }

    /**
     * 站点编码：必填、去空白转大写、≤16，且只允许 AE/SA。
     * 非法/缺失 400；授权由调用方随后 {@code guard.requireSite} 判定（越权 403）。
     */
    static String siteCode(String field, String value)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (code.length() > 16) throw new IllegalArgumentException(field + " 长度不能超过 16");
        if (!SiteAuthGuard.SUPPORTED_SITES.contains(code))
            throw new IllegalArgumentException(field + " 只允许 AE/SA");
        return code;
    }

    /** 费用类型：必填、五值之一（契约 §5）。 */
    static String feeType(String value)
    {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("feeType 不能为空");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!FEE_TYPES.contains(normalized))
            throw new IllegalArgumentException("feeType 只允许 FREIGHT/DUTY/CUSTOMS/PACKAGING/LABEL: " + value);
        return normalized;
    }

    /** 列表费用类型过滤：空白不过滤，非空复用 {@link #feeType(String)} 校验。 */
    static String feeTypeFilter(String value)
    {
        if (value == null || value.isBlank()) return null;
        return feeType(value);
    }

    /** 原币金额：非空且 &gt; 0，统一 4 位 HALF_UP（与 DECIMAL(18,4) 一致）。 */
    static BigDecimal amount(BigDecimal value)
    {
        if (value == null || value.signum() <= 0)
            throw new IllegalArgumentException("amount 必须大于 0");
        return money(value);
    }

    /** 币种：必填、3 位大写字母（P1-1 显式币种，不隐含 CNY）。 */
    static String currency(String value)
    {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("currency 不能为空，必须是 3 位大写币种（如 CNY/AED）");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(normalized).matches())
            throw new IllegalArgumentException("currency 必须是 3 位大写字母代码（如 CNY/AED）: " + value);
        return normalized;
    }

    /**
     * 汇率（契约 §5，沿用 {@code PurchasingRules.fxRate} 口径）：CNY 必须<b>显式</b> 1（缺失或非 1 均 400）；
     * 外币必须 &gt; 0；缺失/非法 400，<b>不兜底 1</b>。返回按列宽固定 8 位的值（落库与指纹共用）。
     */
    static BigDecimal fxRate(BigDecimal rate, String currency)
    {
        if ("CNY".equals(currency))
        {
            if (rate == null) throw new IllegalArgumentException("CNY 费用必须显式填写 fxRate=1");
            if (rate.compareTo(BigDecimal.ONE) != 0)
                throw new IllegalArgumentException("CNY 费用 fxRate 只能为 1");
            return BigDecimal.ONE.setScale(FX_SCALE);
        }
        if (rate == null || rate.signum() <= 0)
            throw new IllegalArgumentException("外币费用 fxRate 必须大于 0");
        return rate.setScale(FX_SCALE, RoundingMode.HALF_UP);
    }

    /** 分摊标记：空默认 NONE；非空须 ∈ NONE/PARTIAL/FULL（仅标记，不实际分摊）。 */
    static String allocationFlag(String value)
    {
        if (value == null || value.isBlank()) return DEFAULT_ALLOCATION_FLAG;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOCATION_FLAGS.contains(normalized))
            throw new IllegalArgumentException("allocationFlag 只允许 NONE/PARTIAL/FULL: " + value);
        return normalized;
    }

    /** ASN 关联：空合法（费用不一定挂 ASN）；非空必须为正整数（存在性/同站点随后校验）。 */
    static Long asnId(Long value)
    {
        if (value == null) return null;
        if (value <= 0) throw new IllegalArgumentException("asnId 必须为正整数（留空表示不挂 ASN）");
        return value;
    }

    /** 选填备注：空白视为 null，去首尾空白，长度 ≤ 512。 */
    static String remark(String value)
    {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > REMARK_MAX_LENGTH)
            throw new IllegalArgumentException("remark 长度不能超过 " + REMARK_MAX_LENGTH);
        return trimmed;
    }

    /** 归属期间校验：空返回 null（由服务端取业务时刻当月）；非空必须 YYYY-MM 且月份合法。 */
    static String period(String period)
    {
        if (period == null || period.isBlank()) return null;
        String value = period.trim();
        if (!PERIOD.matcher(value).matches())
            throw new IllegalArgumentException("period 只允许 YYYY-MM 格式（UTC+8 归属期间）: " + period);
        return value;
    }

    /** 列表期间过滤：空白不过滤，非空复用 {@link #period(String)} 校验。 */
    static String periodFilter(String period)
    {
        return period(period);
    }

    /** 业务时刻所属期间 YYYY-MM（UTC+8）。 */
    static String periodOf(LocalDateTime at)
    {
        return String.format(Locale.ROOT, "%04d-%02d", at.getYear(), at.getMonthValue());
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 既有口径一致）。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * CREATE 内容指纹（契约 §4）：
     * {@code SHA-256("CREATE|" + asnId + "|" + siteCode + "|" + period + "|" + feeType + "|" + amount(4位)
     * + "|" + currency + "|" + fxRate(8位) + "|" + allocationFlag + "|" + remark)}。
     *
     * <p>使用<b>归一化后的入参值</b>：period 为空按空串（不按"当前业务月"参与指纹，避免跨月重放被误判内容冲突），
     * asnId/remark 为空按空串；amount/fxRate 按列宽定长。同键换任一业务字段即指纹不同 → 400 内容不一致。
     */
    static String createFingerprint(Long asnId, String siteCode, String period, String feeType, BigDecimal amount,
                                    String currency, BigDecimal fxRate, String allocationFlag, String remark)
    {
        String canonical = "CREATE|" + (asnId == null ? "" : asnId) + '|' + siteCode + '|'
                + (period == null ? "" : period) + '|' + feeType + '|' + amount.toPlainString() + '|'
                + currency + '|' + fxRate.toPlainString() + '|' + allocationFlag + '|'
                + (remark == null ? "" : remark);
        return sha256Hex(canonical);
    }

    /** 金额统一 4 位小数（HALF_UP）。 */
    static BigDecimal money(BigDecimal value)
    {
        if (value == null) throw new IllegalArgumentException("金额不能为空");
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 分页 limit：非法收敛到默认值，上限 200（与 masterdata/inventory/transfer/stocktake 口径一致）。 */
    static int limit(int limit)
    {
        if (limit < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    static void offset(int offset)
    {
        if (offset < 0) throw new IllegalArgumentException("offset 不能为负数");
    }

    /** SQL IN 占位符：返回 "?,?" 形式。 */
    static String placeholders(int size)
    {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    /** 幂等键校验：非空白、长度 8–64、仅 [A-Za-z0-9_-]，否则 400 统一文案。 */
    static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException("费用登记请求缺少有效的 requestId（8–64 位 [A-Za-z0-9_-]）");
        return requestId;
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
