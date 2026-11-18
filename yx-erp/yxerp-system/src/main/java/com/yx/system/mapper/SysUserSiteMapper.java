package com.yx.system.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Noon 站点授权。只由框架授权服务调用，禁止由客户端指定授权主体。 */
public interface SysUserSiteMapper
{
    Long selectActiveUserId(@Param("userId") Long userId);

    Long lockActiveUserId(@Param("userId") Long userId);

    List<String> selectSiteCodes(@Param("userId") Long userId);

    List<String> lockSiteCodes(@Param("userId") Long userId);

    int deleteByUserId(@Param("userId") Long userId);

    int insertGrant(@Param("userId") Long userId, @Param("siteCode") String siteCode,
            @Param("actorId") Long actorId, @Param("now") LocalDateTime now);

    int insertAudit(@Param("userId") Long userId, @Param("actorId") Long actorId,
            @Param("beforeCodes") String beforeCodes, @Param("afterCodes") String afterCodes,
            @Param("now") LocalDateTime now);
}
