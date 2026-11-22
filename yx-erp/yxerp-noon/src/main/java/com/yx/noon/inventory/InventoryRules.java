package com.yx.noon.inventory;

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
 * M4 库存第一轮通用校验/工具（只在 {@code com.yx.noon.inventory} 内使用）。
 *
 * <p>职责：入参必填与幂等键格式、分页边界、站点编码、SQL IN 占位符、业务时刻（UTC+8）、
 * 金额 4 位 HALF_UP、入库内容指纹。站点授权本身一律走 {@link SiteAuthGuard}，不在此实现。
 */
final class InventoryRules
{
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;

    /**
     * 可入库的采购单状态（契约 §9.1：来源必须是已有收货的采购单）。PARTIAL_RECEIVED 表示分批到货，
     * 首批已收但整单未收齐时允许先入库；安全性由逐行守恒（累计收货 − 累计已入库）保证，不依赖整单收齐。
     * DRAFT/CONFIRMED（尚无收货）仍拒绝。
     */
    static final List<String> INBOUNDABLE_ORDER_STATUSES = List.of("PARTIAL_RECEIVED", "RECEIVED", "CLOSED");

    /** 已生成库存的入库单状态（累计已入库量统计口径）。 */
    static final List<String> BOOKED_INBOUND_STATUSES = List.of("CONFIRMED", "CLOSED");

    /** 幂等键格式（对齐 M3 v2 §9.1）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private InventoryRules()
    {
    }

    /** 必填正整数 id。 */
    static void requiredId(String field, Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
    }

    /** 分页 limit：非法收敛到默认值，上限 200（与 masterdata/purchasing 口径一致）。 */
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

    /** SQL IN 占位符：返回 "?,?" 形式。 */
    static String placeholders(int size)
    {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    /** 金额统一 4 位小数（DECIMAL(18,4)，HALF_UP）。 */
    static BigDecimal money(BigDecimal value)
    {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 FIFO 口径一致）。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 幂等键校验：非空白、长度 8–64、仅 [A-Za-z0-9_-]，否则 400 统一文案。 */
    static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException("入库请求缺少有效的 requestId");
        return requestId;
    }

    /**
     * 入库内容指纹（契约 §2.7）：规范化串 = {@code purOrderId|warehouseId|按 purOrderItemId 数值升序的
     * "purOrderItemId:qtyQualified"}，逗号连接（如 {@code 12|7|3:60,5:40}），UTF-8 后 SHA-256 取 64 位小写十六进制。
     * 与行提交顺序无关，同键换数量/换行即指纹不同（400 内容不一致）。
     */
    static String contentFingerprint(long purOrderId, long warehouseId, List<InboundLineInput> lines)
    {
        StringBuilder canonical = new StringBuilder()
                .append(purOrderId).append('|').append(warehouseId).append('|');
        List<InboundLineInput> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(InboundLineInput::purOrderItemId));
        for (int i = 0; i < sorted.size(); i++)
        {
            InboundLineInput line = sorted.get(i);
            if (i > 0) canonical.append(',');
            canonical.append(line.purOrderItemId()).append(':').append(line.qtyQualified());
        }
        return sha256Hex(canonical.toString());
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
