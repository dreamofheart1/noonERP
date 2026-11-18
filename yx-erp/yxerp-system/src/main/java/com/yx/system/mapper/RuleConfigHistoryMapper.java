package com.yx.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.yx.system.domain.configversion.RuleConfigRecord;

/** 内部只读 Mapper，不是鉴权入口；业务调用必须经过站点守卫。 */
public interface RuleConfigHistoryMapper
{
    List<RuleConfigRecord> findHistory(@Param("key") String key, @Param("site") String site);
}
