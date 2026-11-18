package com.yx.framework.configversion;

import java.util.Collection;
import org.springframework.stereotype.Repository;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.system.mapper.RuleConfigHistoryMapper;

/** 仅服务内部使用；由 VersionedConfigReader 在站点授权之后调用。 */
@Repository
public class MyBatisRuleConfigRepository implements VersionedConfigReader.Repository
{
    private final RuleConfigHistoryMapper mapper;

    public MyBatisRuleConfigRepository(RuleConfigHistoryMapper mapper) { this.mapper = mapper; }

    @Override
    public Collection<VersionedConfigReader.Row> findHistory(String key, String site)
    {
        return mapper.findHistory(key, site).stream().map(row -> new VersionedConfigReader.Row(
                row.getId(), new Version(row.getConfigKey(), row.getScope(), row.getConfigValue(),
                        row.getEffectiveFrom(), row.getEffectiveTo()), row.getRevokedAt())).toList();
    }
}
