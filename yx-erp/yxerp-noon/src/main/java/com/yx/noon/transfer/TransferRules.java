package com.yx.noon.transfer;

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
 * M4 第二轮调拨通用校验/工具（只在 {@code com.yx.noon.transfer} 内使用）。
 *
 * <p>与第一轮 {@code InventoryRules} 同口径（该类包私有，无法跨包复用，故此处按契约 §4「同口径实现」）：
 * 幂等键格式、业务时刻 UTC+8、分页边界、站点编码、金额 4 位 HALF_UP、内容指纹 SHA-256。
 * 站点授权本身一律走 {@link SiteAuthGuard}，不在此实现。
 */
final class TransferRules
{
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MONEY_SCALE = 4;

    /** 幂等键格式（对齐 M4 第一轮 §2.7）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    /**
     * 短少事件号格式（进入 {@code biz_event_key='TSHORT:'+itemId+':'+shortEventId}，列宽 VARCHAR(160)）：
     * 1–64 位，仅字母数字下划线短横；禁止含冒号等会破坏事件键结构的字符。
     */
    private static final Pattern SHORT_EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private TransferRules()
    {
    }

    /** 必填正整数 id。 */
    static void requiredId(String field, Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException(field + " 必须为正整数");
    }

    /** 分页 limit：非法收敛到默认值，上限 200（与 masterdata/inventory 口径一致）。 */
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
        if (value == null) throw new IllegalArgumentException("金额/成本不能为空");
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** 业务时刻：UTC+8，毫秒截断（与 M3/M4 FIFO 口径一致）。 */
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
            throw new IllegalArgumentException("调拨请求缺少有效的 requestId");
        return requestId;
    }

    /** 短少事件号校验：短少（diff &gt; 0）时必须提供合法事件号，否则无法生成稳定的事件键。 */
    static String requireShortEventId(String shortEventId)
    {
        if (shortEventId == null || shortEventId.isBlank()
                || !SHORT_EVENT_ID.matcher(shortEventId).matches())
            throw new IllegalArgumentException("短少必须提供有效的 shortEventId（1–64 位 [A-Za-z0-9_-]）");
        return shortEventId;
    }

    /** 请求体 siteCode 仅展示用；非空时必须与仓库站点一致（否则 400，不参与授权）。 */
    static void requireSuppliedSiteMatches(String suppliedSite, String warehouseSite)
    {
        if (suppliedSite == null || suppliedSite.isBlank()) return;
        String normalized = suppliedSite.trim().toUpperCase(Locale.ROOT);
        if (!warehouseSite.equals(normalized))
            throw new IllegalArgumentException("请求站点 " + normalized + " 与源仓归属站点 "
                    + warehouseSite + " 不一致");
    }

    /**
     * 发货内容指纹（契约 §2.6）：SHA-256({@code transferId|SHIP|按 itemId 升序的 itemId:qtyShipped})。
     * 与行提交顺序无关；同键换数量/换行/换动作即指纹不同（400 内容不一致）。
     */
    static String shipFingerprint(long transferId, List<TransferShipLine> lines)
    {
        List<long[]> pairs = new ArrayList<>(lines.size());
        for (TransferShipLine line : lines)
            pairs.add(new long[]{line.itemId(), line.qtyShipped()});
        return fingerprint(transferId, "SHIP", pairs);
    }

    /**
     * 收货内容指纹（契约 §2.6）：SHA-256({@code transferId|RECEIVE|按 itemId 升序的 itemId:qtyReceived})。
     * 动作进入指纹，故 ship 的 requestId 用于 receive 必然内容冲突 400。
     */
    static String receiveFingerprint(long transferId, List<TransferReceiveLine> lines)
    {
        List<long[]> pairs = new ArrayList<>(lines.size());
        for (TransferReceiveLine line : lines)
            pairs.add(new long[]{line.itemId(), line.qtyReceived()});
        return fingerprint(transferId, "RECEIVE", pairs);
    }

    /**
     * 建单内容指纹（契约 v1.2 §9.2.6）：
     * {@code SHA-256("CREATE|" + fromWarehouseId + "|" + toWarehouseId + "|" + 按(skuId,batchNo)升序的
     * skuId:batchNo:qty, ...)}。动作 CREATE 进入指纹，与 ship/receive 的动作指纹天然隔离；
     * create 时尚无 transferId，故指纹不含 transferId。与行提交顺序无关。
     */
    static String createFingerprint(long fromWarehouseId, long toWarehouseId, List<TransferCreateLine> lines)
    {
        List<TransferCreateLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(TransferCreateLine::skuId)
                .thenComparing(TransferCreateLine::batchNo));
        StringBuilder canonical = new StringBuilder("CREATE|")
                .append(fromWarehouseId).append('|').append(toWarehouseId).append('|');
        for (int i = 0; i < sorted.size(); i++)
        {
            if (i > 0) canonical.append(',');
            TransferCreateLine line = sorted.get(i);
            canonical.append(line.skuId()).append(':').append(line.batchNo()).append(':').append(line.qty());
        }
        return sha256Hex(canonical.toString());
    }

    private static String fingerprint(long transferId, String action, List<long[]> pairs)
    {
        pairs.sort(Comparator.comparingLong(pair -> pair[0]));
        StringBuilder canonical = new StringBuilder()
                .append(transferId).append('|').append(action).append('|');
        for (int i = 0; i < pairs.size(); i++)
        {
            if (i > 0) canonical.append(',');
            canonical.append(pairs.get(i)[0]).append(':').append(pairs.get(i)[1]);
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
