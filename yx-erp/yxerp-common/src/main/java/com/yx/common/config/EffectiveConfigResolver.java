package com.yx.common.config;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 按北京时间业务时刻选择版本；数据读取与权限检查由调用方负责。 */
public final class EffectiveConfigResolver
{
    private EffectiveConfigResolver() { }

    /** 生效区间为 [from, to)，null to 表示无结束时间。value 为不可变快照文本。 */
    public record Version(String key, String scope, String value, LocalDateTime from, LocalDateTime to)
    {
        public Version
        {
            Objects.requireNonNull(key);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(value);
            Objects.requireNonNull(from);
            if (to != null && !to.isAfter(from)) throw new IllegalArgumentException("无效生效区间");
        }
    }

    public static Version resolve(Collection<Version> versions, String key, String site, LocalDateTime at)
    {
        Objects.requireNonNull(versions);
        if (key == null || key.isBlank() || site == null || site.isBlank() || "GLOBAL".equals(site) || at == null)
            throw new IllegalArgumentException("必须提供参数键、明确站点和业务时刻");
        List<Version> matching = versions.stream().filter(v -> v.key().equals(key))
                .filter(v -> v.scope().equals(site) || v.scope().equals("GLOBAL"))
                .filter(v -> !v.from().isAfter(at) && (v.to() == null || at.isBefore(v.to())))
                .toList();
        List<Version> siteValues = matching.stream().filter(v -> v.scope().equals(site)).toList();
        List<Version> globalValues = matching.stream().filter(v -> v.scope().equals("GLOBAL")).toList();
        if (siteValues.size() > 1 || globalValues.size() > 1)
            throw new IllegalStateException("参数有效区间重叠，无法确定版本");
        if (!siteValues.isEmpty()) return siteValues.get(0);
        if (!globalValues.isEmpty()) return globalValues.get(0);
        throw new IllegalStateException("业务时刻没有有效参数");
    }
}
