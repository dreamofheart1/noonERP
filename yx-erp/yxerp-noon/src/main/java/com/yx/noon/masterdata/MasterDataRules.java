package com.yx.noon.masterdata;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.dao.DuplicateKeyException;

/**
 * M2 主数据通用校验/工具。只在本包内使用，不对外暴露。
 *
 * <p>职责：字段必填与长度、状态枚举、分页边界、站点过滤、唯一键冲突转中文业务异常、
 * spec_json 合法性。站点授权本身不在此实现，一律走 {@link SiteAuthGuard}。
 */
final class MasterDataRules
{
    static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE");
    static final List<String> WAREHOUSE_TYPES = List.of("LOCAL", "TRANSIT", "FBN", "3PL");
    static final List<String> FULFILLMENT_MODES = List.of("FBN", "GLOBAL", "FBP");
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    private MasterDataRules()
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

    /** 状态只允许 ACTIVE/INACTIVE；空值默认 ACTIVE（不物理删除，仅置 INACTIVE）。 */
    static String status(String value)
    {
        if (value == null || value.isBlank()) return "ACTIVE";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized))
            throw new IllegalArgumentException("status 只允许 ACTIVE/INACTIVE");
        return normalized;
    }

    static void requiredId(String field, Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
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

    /**
     * 站点过滤：指定站点时先 requireSite 再单站过滤；未指定时返回全部授权站点。
     * 返回空集合表示"零行"，调用方不得省略 WHERE 条件（否则会越权读到全部）。
     */
    static List<String> sites(String requestedSite, SiteAuthGuard guard)
    {
        if (requestedSite != null && !requestedSite.isBlank())
            return List.of(guard.requireSite(requestedSite.trim().toUpperCase(Locale.ROOT)));
        return guard.allowedSiteCodes();
    }

    /**
     * 模糊匹配绑定值：配合 {@code LOWER(col) LIKE ?} 使用，返回 {@code %trim(小写值)%}。
     * 空白返回 null 表示该条件不过滤。
     */
    static String like(String value)
    {
        if (value == null || value.isBlank()) return null;
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /** 列表状态过滤：空白表示不过滤；非空复用 {@link #status(String)} 校验并规范化。 */
    static String statusFilter(String value)
    {
        if (value == null || value.isBlank()) return null;
        return status(value);
    }

    /**
     * 站点条件与授权闸门取交集：闸门由 {@link #sites(String, SiteAuthGuard)} 计算，
     * 条件站点为空时用闸门结果，否则只保留条件站点（不在授权内即零行，不抛越权）。
     * 同一站点在入参与 Query 中重复出现时只出现一次，避免重复占位符。
     */
    static List<String> intersectSites(List<String> gate, String conditionSite)
    {
        if (conditionSite == null || conditionSite.isBlank()) return gate;
        String code = siteCode("siteCode", conditionSite);
        return gate.contains(code) ? List.of(code) : List.of();
    }

    /** 站点编码只允许 AE/SA，与 SiteAuthGuard.SUPPORTED_SITES 同源。 */
    static String siteCode(String field, String value)
    {
        String code = required(field, value, 16).toUpperCase(Locale.ROOT);
        if (!SiteAuthGuard.SUPPORTED_SITES.contains(code))
            throw new IllegalArgumentException(field + " 只允许 AE/SA");
        return code;
    }

    /** SQL IN 占位符：返回 "?,?" 形式，参数由调用方按站点顺序追加。 */
    static String placeholders(int size)
    {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    /** DB 唯一键冲突 → 明确中文字段提示。 */
    static IllegalArgumentException duplicate(DuplicateKeyException e, String table, String ukName, String message)
    {
        String raw = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (raw.contains(ukName.toLowerCase(Locale.ROOT)))
            return new IllegalArgumentException(message);
        return new IllegalArgumentException("唯一约束冲突（" + table + "）：" + message);
    }

    /** spec_json：非空时必须能被 Jackson 解析，避免把非法 JSON 交给 DB 后再报错。 */
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
}
