package com.yx.framework.configversion;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Objects;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.framework.configversion.VersionedConfigReader.Row;

/** 事务内、同一键和范围的写锁已取得后调用；本类不提供数据库并发控制。 */
public final class ConfigIntervalPolicy
{
    private ConfigIntervalPolicy() { }

    /** 现有版本以较早的有效期结束或撤销生效时间为实际结束；半开区间允许边界相接。 */
    public static void requireNoOverlap(Version proposed, Collection<Row> existing)
    {
        Objects.requireNonNull(proposed);
        Objects.requireNonNull(existing);
        for (Row row : existing)
        {
            Objects.requireNonNull(row);
            Version old = row.version();
            if (!old.key().equals(proposed.key()) || !old.scope().equals(proposed.scope()))
                throw new IllegalArgumentException("候选不属于同一参数和范围");
            LocalDateTime end = earlier(old.to(), row.revokedAt());
            // 生效前已撤销的版本没有有效区间。
            if (end != null && !end.isAfter(old.from())) continue;
            if ((proposed.to() == null || old.from().isBefore(proposed.to()))
                    && (end == null || proposed.from().isBefore(end)))
                throw new IllegalStateException("参数有效区间重叠");
        }
    }

    private static LocalDateTime earlier(LocalDateTime first, LocalDateTime second)
    {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }
}
