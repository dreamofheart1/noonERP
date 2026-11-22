package com.yx.noon.stocktake;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * M4 第三轮第一批盘点通用校验/工具（只在 {@code com.yx.noon.stocktake} 内使用）。
 *
 * <p>与第一轮 {@code InventoryRules}、第二轮 {@code TransferRules} 同口径（这两个类包私有，无法跨包复用）：
 * 幂等键格式、点数事件号格式、业务时刻 UTC+8、归属期间 YYYY-MM、分页边界、站点编码、金额 4 位 HALF_UP、
 * 内容指纹 SHA-256。站点授权本身一律走 {@link SiteAuthGuard}，不在此实现。
 */
final class StocktakeRules
{
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;
    /** {@code inv_stocktake_item.batch_no} 与 {@code inv_batch.batch_no} 均 VARCHAR(64)。 */
    static final int BATCH_NO_MAX_LENGTH = 64;

    /** 幂等键格式（对齐 M4 第一轮 §2.7 / 第二轮 §2.6）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * 点数事件号格式（契约 §3.1 / §6.2，进入 {@code biz_event_key='STK:'+itemId+':'+countEventId}，
     * 列宽 VARCHAR(160)）：1–64 位，仅字母数字下划线短横；禁止冒号/逗号等会破坏事件键或指纹结构的字符。
     */
    private static final Pattern COUNT_EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 归属期间 {@code YYYY-MM}（UTC+8），与 {@code inv_stocktake.period CHAR(7)} 同宽。 */
    private static final Pattern PERIOD = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private StocktakeRules()
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

    /** SQL IN 占位符：返回 "?,?" 形式。 */
    static String placeholders(int size)
    {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    /** 金额/成本统一 4 位小数（DECIMAL(18,4)，HALF_UP）。 */
    static BigDecimal money(BigDecimal value)
    {
        if (value == null) throw new IllegalArgumentException("金额/成本不能为空");
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 FIFO 口径一致）。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
    }

    /** 业务时刻所属期间 YYYY-MM（UTC+8）。 */
    static String period(LocalDateTime at)
    {
        return String.format(Locale.ROOT, "%04d-%02d", at.getYear(), at.getMonthValue());
    }

    /** 幂等键校验：非空白、长度 8–64、仅 [A-Za-z0-9_-]，否则 400 统一文案。 */
    static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException("盘点请求缺少有效的 requestId");
        return requestId;
    }

    /** 点数事件号校验：必填、1–64 位 [A-Za-z0-9_-]（否则无法生成稳定批次消耗事件键）。 */
    static String requireCountEventId(String countEventId)
    {
        if (countEventId == null || countEventId.isBlank() || !COUNT_EVENT_ID.matcher(countEventId).matches())
            throw new IllegalArgumentException("countEventId 必须为 1–64 位 [A-Za-z0-9_-]");
        return countEventId;
    }

    /** 归属期间校验：空返回 null（由服务端取业务时刻当月）；非空必须 YYYY-MM 且月份合法。 */
    static String requirePeriod(String period)
    {
        if (period == null || period.isBlank()) return null;
        String value = period.trim();
        if (!PERIOD.matcher(value).matches())
            throw new IllegalArgumentException("period 只允许 YYYY-MM 格式（UTC+8 归属期间）: " + value);
        return value;
    }

    /**
     * 建单内容指纹（契约 §4）：
     * {@code SHA-256("CREATE|" + warehouseId + "|" + 按(skuId,batchNo)升序的 "skuId:batchNo:qtyActual:countEventId")}。
     * 动作 CREATE 进指纹，与 APPLY 指纹天然隔离；{@code batchNo} 为空按空串参与规范化，保证同内容同指纹。
     * 与行提交顺序无关，同键换数量/换行/换事件号即指纹不同（400 内容不一致）。
     */
    static String createFingerprint(long warehouseId, List<StocktakeLineInput> lines)
    {
        List<StocktakeLineInput> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(StocktakeLineInput::skuId)
                .thenComparing(line -> batchKey(line.batchNo())));
        StringBuilder canonical = new StringBuilder("CREATE|").append(warehouseId).append('|');
        for (int i = 0; i < sorted.size(); i++)
        {
            if (i > 0) canonical.append(',');
            StocktakeLineInput line = sorted.get(i);
            canonical.append(line.skuId()).append(':').append(batchKey(line.batchNo())).append(':')
                    .append(line.qtyActual()).append(':').append(line.countEventId());
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * 应用内容指纹（契约 §4）：{@code SHA-256("APPLY|" + stocktakeId)}。apply 只认单，
     * 行内容已在 create 固定并进 CREATE 指纹；动作 APPLY 进指纹，故 CREATE 的 requestId 用于 APPLY
     * （或反之）必然内容冲突 400。
     */
    static String applyFingerprint(long stocktakeId)
    {
        return sha256Hex("APPLY|" + stocktakeId);
    }

    /** 指纹规范化用的批次标签：null/空白统一为空串（不参与差异计算，只做顺序与去重标识）。 */
    static String batchKey(String batchNo)
    {
        return batchNo == null || batchNo.isBlank() ? "" : batchNo.trim();
    }

    /**
     * 建单行校验（契约 §3.1 / §6.1）：至少 1 行、skuId 正整数、qtyActual ≥ 0、countEventId 合法、
     * batchNo 规范化（空→null、≤64）、<b>同一 SKU 不得重复</b>。
     *
     * <p>重复行裁决（本报告裁决点 A）：因基线是 SKU 级台账 {@code qty_available}，同一 SKU 两行会
     * 各自以全量账面为基线重复计数同一批实物，故在 create 直接 400 拒绝（比契约「apply 时合并或拒绝」更早更严），
     * apply 侧再兜一层防御性拒绝。
     */
    static List<StocktakeLineInput> requireCreateLines(List<StocktakeLineInput> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行盘点明细");
        Set<Long> seenSku = new LinkedHashSet<>();
        List<StocktakeLineInput> normalized = new ArrayList<>(lines.size());
        for (StocktakeLineInput line : lines)
        {
            if (line == null) throw new IllegalArgumentException("盘点明细行不能为空");
            requiredId("skuId", line.skuId());
            if (line.qtyActual() < 0) throw new IllegalArgumentException("qtyActual 不能为负数");
            String countEventId = requireCountEventId(line.countEventId());
            String batchNo = normalizeBatchNo(line.batchNo());
            if (!seenSku.add(line.skuId()))
                throw new IllegalArgumentException("同一 SKU 不能在一张盘点单重复提交（基线为 SKU 级台账）: skuId="
                        + line.skuId());
            normalized.add(new StocktakeLineInput(line.skuId(), batchNo, line.qtyActual(), countEventId));
        }
        return List.copyOf(normalized);
    }

    /** 批次标签规范化：空白→null、去首尾空白、长度 ≤64（不参与差异计算）。 */
    private static String normalizeBatchNo(String batchNo)
    {
        if (batchNo == null || batchNo.isBlank()) return null;
        String value = batchNo.trim();
        if (value.length() > BATCH_NO_MAX_LENGTH)
            throw new IllegalArgumentException("batchNo 长度不能超过 " + BATCH_NO_MAX_LENGTH);
        return value;
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
