package com.yx.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 平台时刻的无损来源与中国业务时间。只换算同一瞬间，不替平台重定义日切或截止。
 * 解析严格使用 ISO 时间；无偏移的文本必须同时提供平台明确的时区。
 * 持久化方须分别保存 sourceValue/sourceZone/instant，不能只保存 chinaTime。
 */
public final class BusinessTimestamp
{
    public static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final String sourceValue;
    private final String sourceZone;
    private final Instant instant;

    private BusinessTimestamp(String sourceValue, String sourceZone, Instant instant)
    {
        this.sourceValue = sourceValue;
        this.sourceZone = sourceZone;
        this.instant = instant;
    }

    /** 例如 Noon 原始文本 2026-11-18T23:59:59.123+04:00；必须显式带偏移。 */
    public static BusinessTimestamp fromOffset(String sourceValue)
    {
        OffsetDateTime parsed = OffsetDateTime.parse(Objects.requireNonNull(sourceValue, "平台时间不能为空"));
        return new BusinessTimestamp(sourceValue, parsed.getOffset().getId(), parsed.toInstant());
    }

    /**
     * 平台只给本地时间时必须附其明确时区；DST 缺口或重叠均拒绝猜测，
     * 重叠时应改用平台给定的显式偏移调用 fromOffset。
     */
    public static BusinessTimestamp fromLocal(String sourceValue, ZoneId sourceZone)
    {
        Objects.requireNonNull(sourceZone, "必须提供平台原始时区");
        LocalDateTime parsed = LocalDateTime.parse(Objects.requireNonNull(sourceValue, "平台时间不能为空"));
        List<ZoneOffset> offsets = sourceZone.getRules().getValidOffsets(parsed);
        if (offsets.size() != 1)
        {
            throw new IllegalArgumentException("平台本地时间不存在或有歧义，需要明确偏移");
        }
        return new BusinessTimestamp(sourceValue, sourceZone.getId(), parsed.toInstant(offsets.get(0)));
    }

    public String sourceValue() { return sourceValue; }

    public String sourceZone() { return sourceZone; }

    public Instant instant() { return instant; }

    /** 派生展示/业务比较值，不依赖系统默认时区，不截断原始纳秒精度。 */
    public LocalDateTime chinaTime() { return LocalDateTime.ofInstant(instant, CHINA_ZONE); }
}
