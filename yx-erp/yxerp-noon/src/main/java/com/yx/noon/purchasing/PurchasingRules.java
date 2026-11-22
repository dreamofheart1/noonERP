package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.dao.DuplicateKeyException;

/**
 * M3 供应商与采购通用校验/工具。只在本包内使用，不对外暴露。
 *
 * <p>职责：字段必填与长度、采购单状态枚举、来源枚举、币种/汇率/金额/数量、生效日期区间、
 * 分页边界、站点过滤、唯一键冲突转中文业务异常、JSON 列合法性。
 * 站点授权本身不在此实现，一律走 {@link SiteAuthGuard}。
 */
final class PurchasingRules
{
    static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE");
    static final List<String> ORDER_STATUSES = List.of(
            "DRAFT", "CONFIRMED", "PARTIAL_RECEIVED", "RECEIVED", "CLOSED");
    static final List<String> ORDER_SOURCES = List.of(
            "MANUAL", "REPLENISH_SUGGESTION", "AI_SUGGESTION");
    /** 可收货状态（契约 4.4 第 2 步）。 */
    static final List<String> RECEIVABLE_STATUSES = List.of("CONFIRMED", "PARTIAL_RECEIVED");
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final ObjectMapper JSON = new ObjectMapper();

    private PurchasingRules()
    {
    }

    /** 必填文本：去空白后非空且不超过长度上限。 */
    static String required(String field, String value, int maxLength)
    {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " 不能为空");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength)
            throw new IllegalArgumentException(field + " 长度不能超过 " + maxLength);
        return trimmed;
    }

    /** 选填文本：空白视为 null，否则去空白并校验长度。 */
    static String optional(String field, String value, int maxLength)
    {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength)
            throw new IllegalArgumentException(field + " 长度不能超过 " + maxLength);
        return trimmed;
    }

    static void requiredId(String field, Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
    }

    /** 启停状态只允许 ACTIVE/INACTIVE；空值默认 ACTIVE（不物理删除，仅置 INACTIVE）。 */
    static String status(String value)
    {
        if (value == null || value.isBlank()) return "ACTIVE";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized))
            throw new IllegalArgumentException("status 只允许 ACTIVE/INACTIVE");
        return normalized;
    }

    /** 列表状态过滤：空白表示不过滤；非空复用 {@link #status(String)} 校验并规范化。 */
    static String statusFilter(String value)
    {
        if (value == null || value.isBlank()) return null;
        return status(value);
    }

    /** 分页边界：offset 非法直接拒绝；limit 非法时收敛到默认/上限。 */
    static int limit(int limit)
    {
        if (limit < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    static void offset(int offset)
    {
        if (offset < 0) throw new IllegalArgumentException("offset 不能为负数");
    }

    /** 站点编码只允许 AE/SA，与 SiteAuthGuard.SUPPORTED_SITES 同源。 */
    static String siteCode(String field, String value)
    {
        String code = required(field, value, 16).toUpperCase(Locale.ROOT);
        if (!SiteAuthGuard.SUPPORTED_SITES.contains(code))
            throw new IllegalArgumentException(field + " 只允许 AE/SA");
        return code;
    }

    /**
     * 站点闸门（采购单口径）：指定站点时先 requireSite 再单站过滤；未指定时返回全部授权站点。
     * 返回空集合表示"零行"，调用方不得省略 WHERE 条件（否则会越权读到全部）。
     * 超管的 allowedSiteCodes() 即 SUPPORTED_SITES。
     */
    static List<String> sites(String requestedSite, SiteAuthGuard guard)
    {
        if (requestedSite != null && !requestedSite.isBlank())
            return List.of(guard.requireSite(requestedSite.trim().toUpperCase(Locale.ROOT)));
        return guard.allowedSiteCodes();
    }

    /** 模糊匹配绑定值：配合 {@code LOWER(col) LIKE ?} 使用，返回 {@code %trim(小写值)%}。 */
    static String like(String value)
    {
        if (value == null || value.isBlank()) return null;
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * 闸门与 Query.siteCode 取交集：条件站点不在闸门内即零行（不抛越权）；
     * 闸门为 null（超管不限站点）时，条件站点即唯一过滤站点。
     */
    static List<String> intersectSites(List<String> gate, String conditionSite)
    {
        if (conditionSite == null || conditionSite.isBlank()) return gate;
        String code = siteCode("siteCode", conditionSite);
        if (gate == null) return List.of(code);
        return gate.contains(code) ? List.of(code) : List.of();
    }

    /** SQL IN 占位符：返回 "?,?" 形式，参数由调用方按站点顺序追加。 */
    static String placeholders(int size)
    {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    /** DB 唯一键冲突 → 明确中文字段提示。 */
    static IllegalArgumentException duplicate(DuplicateKeyException e, String table, String ukName, String message)
    {
        String raw = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (raw.contains(ukName.toLowerCase(Locale.ROOT)))
            return new IllegalArgumentException(message);
        return new IllegalArgumentException("唯一约束冲突（" + table + "）：" + message);
    }

    /** JSON 列：非空时必须能被 Jackson 解析（contact_json）。 */
    static String json(String field, String value)
    {
        String text = optional(field, value, 10000);
        if (text == null) return null;
        try
        {
            JsonNode node = JSON.readTree(text);
            if (node == null || node.isMissingNode()) throw new IllegalArgumentException();
            return text;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(field + " 不是合法 JSON");
        }
    }

    /**
     * 币种：去空白转大写，必须三位字母；空白按 DB 默认 CNY。
     */
    static String currency(String value)
    {
        if (value == null || value.isBlank()) return "CNY";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(normalized).matches())
            throw new IllegalArgumentException("currency 必须是 3 位大写字母代码（如 CNY/AED）");
        return normalized;
    }

    /** 金额：非空且 > 0（单价/报价/采购价）。 */
    static BigDecimal positiveMoney(BigDecimal value, String field)
    {
        if (value == null || value.signum() <= 0)
            throw new IllegalArgumentException(field + " 必须大于 0");
        return value;
    }

    /** 数量：严格 > 0（下单量/收货量）。 */
    static int qty(Integer value, String field)
    {
        if (value == null) throw new IllegalArgumentException(field + " 不能为空");
        if (value <= 0) throw new IllegalArgumentException(field + " 必须大于 0");
        return value;
    }

    /**
     * 汇率（契约 2 / 5）：本币 CNY 必须**显式**写 1，传 null 或其他值报错；
     * 外币必须 > 0。返回原值。采购成本口径 = unit_price × fx_rate（不含头程/关税/包材）。
     */
    static BigDecimal fxRate(BigDecimal rate, String currency)
    {
        if ("CNY".equals(currency))
        {
            if (rate == null) throw new IllegalArgumentException("CNY 采购必须显式填写 fxRate=1");
            if (rate.compareTo(BigDecimal.ONE) != 0)
                throw new IllegalArgumentException("CNY 采购 fxRate 只能为 1");
            return BigDecimal.ONE.setScale(4);
        }
        if (rate == null || rate.signum() <= 0)
            throw new IllegalArgumentException("外币采购 fxRate 必须大于 0");
        return rate;
    }

    /** 生效日：ISO yyyy-MM-dd；required 时空值报错。 */
    static LocalDate date(String field, String value, boolean required)
    {
        if (value == null || value.isBlank())
        {
            if (required) throw new IllegalArgumentException(field + " 不能为空，格式 yyyy-MM-dd");
            return null;
        }
        try
        {
            return LocalDate.parse(value.trim(), DATE);
        }
        catch (DateTimeParseException e)
        {
            throw new IllegalArgumentException(field + " 必须是 yyyy-MM-dd 的合法日期");
        }
    }

    /** 生效区间：effective_to 非空时必须 > effective_from（同一天不算新版本）。 */
    static void effectiveRange(LocalDate from, LocalDate to)
    {
        if (to != null && !to.isAfter(from))
            throw new IllegalArgumentException("effectiveTo 必须晚于 effectiveFrom");
    }

    /** 采购单状态：五态之一，非法 400。 */
    static String orderStatus(String value)
    {
        String normalized = required("status", value, 24).toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(normalized))
            throw new IllegalArgumentException("status 只允许 DRAFT/CONFIRMED/PARTIAL_RECEIVED/RECEIVED/CLOSED");
        return normalized;
    }

    /** 列表状态过滤：空白表示不过滤。 */
    static String orderStatusFilter(String value)
    {
        if (value == null || value.isBlank()) return null;
        return orderStatus(value);
    }

    /** 采购单来源：空白默认 MANUAL，只允许三值。 */
    static String source(String value)
    {
        if (value == null || value.isBlank()) return "MANUAL";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ORDER_SOURCES.contains(normalized))
            throw new IllegalArgumentException("source 只允许 MANUAL/REPLENISH_SUGGESTION/AI_SUGGESTION");
        return normalized;
    }

    /** 来源过滤：空白表示不过滤。 */
    static String sourceFilter(String value)
    {
        if (value == null || value.isBlank()) return null;
        return source(value);
    }

    /** 金额列统一 4 位小数（与 DECIMAL(18,4) 一致），避免静默截断产生口径差异。 */
    static BigDecimal money(BigDecimal value)
    {
        return value.setScale(MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }
}
