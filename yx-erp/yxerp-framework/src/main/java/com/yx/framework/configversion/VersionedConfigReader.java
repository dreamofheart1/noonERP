package com.yx.framework.configversion;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import com.yx.common.config.EffectiveConfigResolver;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.stereotype.Service;

/** 业务参数读取边界；持久化适配器须实现 Repository 后由业务层显式接入。 */
@Service
public class VersionedConfigReader
{
    /** 历史行不得因当前撤销状态被排除；revokedAt 为撤销生效的北京时间。 */
    public record Row(long id, Version version, LocalDateTime revokedAt)
    {
        public Row
        {
            if (id <= 0) throw new IllegalArgumentException("版本 ID 无效");
            Objects.requireNonNull(version);
        }
    }

    /** 返回该键的真实站点和 GLOBAL 的全部相关历史行，不按当前状态过滤。 */
    public interface Repository
    {
        Collection<Row> findHistory(String key, String site);
    }

    /** 必须存入受信任的业务记录，不能接收客户端提交的快照作为重算输入。 */
    public record Snapshot(long versionId, String key, String requestedSite, String resolvedScope,
                           String value, LocalDateTime businessTime)
    {
        public Snapshot
        {
            if (versionId <= 0 || key == null || key.isBlank() || requestedSite == null || requestedSite.isBlank()
                    || resolvedScope == null || value == null || businessTime == null
                    || (!resolvedScope.equals("GLOBAL") && !resolvedScope.equals(requestedSite)))
                throw new IllegalArgumentException("参数快照无效");
        }
    }

    private final SiteAuthGuard guard;

    public VersionedConfigReader(SiteAuthGuard guard) { this.guard = guard; }

    /** 接口由调用业务注入，当前不绑定任何表，避免假定未定稿的 schema。 */
    public Snapshot resolve(Repository repository, String key, String site, LocalDateTime businessTime)
    {
        guard.requireSite(site);
        Objects.requireNonNull(repository);
        if (key == null || key.isBlank() || businessTime == null)
            throw new IllegalArgumentException("必须提供参数键和业务时刻");
        Collection<Row> rows = Objects.requireNonNull(repository.findHistory(key, site), "参数历史不可用");
        if (rows.stream().anyMatch(row -> row == null || !row.version().key().equals(key)
                || !(row.version().scope().equals(site) || row.version().scope().equals("GLOBAL"))))
            throw new IllegalStateException("参数历史包含不匹配的键或站点");
        if (rows.stream().map(Row::id).distinct().count() != rows.size())
            throw new IllegalStateException("参数版本 ID 重复");
        List<Row> eligible = rows.stream()
                .filter(row -> row.revokedAt() == null || businessTime.isBefore(row.revokedAt())).toList();
        Version selected = EffectiveConfigResolver.resolve(eligible.stream().map(Row::version).toList(), key, site, businessTime);
        Row row = eligible.stream().filter(item -> item.version() == selected).findFirst().orElseThrow();
        return new Snapshot(row.id(), key, site, selected.scope(), selected.value(), businessTime);
    }

    /** 使用受信任存储的原快照重算，绝不重新查询当前配置；仍检查当前访问授权。 */
    public String valueFromSnapshot(Snapshot snapshot)
    {
        Objects.requireNonNull(snapshot);
        guard.requireSite(snapshot.requestedSite());
        return snapshot.value();
    }
}
