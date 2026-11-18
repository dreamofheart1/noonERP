package com.yx.framework.web.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yx.common.exception.ServiceException;
import com.yx.common.utils.SecurityUtils;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

/** 首阶段由超管管理授权。机器身份及角色授权另行接入，不隐式继承。 */
@Service
public class SysUserSiteService
{
    private final SysUserSiteMapper mapper;
    private final SiteAuthGuard guard;

    public SysUserSiteService(SysUserSiteMapper mapper, SiteAuthGuard guard)
    {
        this.mapper = mapper;
        this.guard = guard;
    }

    public List<String> getUserSites(Long userId)
    {
        guard.requireAdministrator();
        requireTarget(userId);
        return SecurityUtils.isAdmin(userId) ? SiteAuthGuard.SUPPORTED_SITES : mapper.selectSiteCodes(userId);
    }

    /** 用户行锁串行化同一目标的全量替换；授权与前后值审计同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceUserSites(Long userId, Collection<String> siteCodes)
    {
        Long actorId = guard.requireAdministrator();
        if (siteCodes == null || siteCodes.size() > SiteAuthGuard.SUPPORTED_SITES.size()
                || siteCodes.stream().anyMatch(code -> code == null || !SiteAuthGuard.SUPPORTED_SITES.contains(code)))
        {
            throw new ServiceException("站点列表必须显式提供且仅允许 AE、SA（空列表表示撤销全部授权）", 400);
        }
        if (userId == null || userId <= 0 || mapper.lockActiveUserId(userId) == null)
        {
            throw new ServiceException("目标用户不存在、已停用或已删除", 400);
        }
        if (SecurityUtils.isAdmin(userId))
        {
            throw new ServiceException("超管固定拥有全部站点，不能覆盖", 400);
        }
        List<String> after = siteCodes.stream().distinct().sorted().toList();
        List<String> before = mapper.lockSiteCodes(userId);
        if (before.equals(after))
        {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        mapper.deleteByUserId(userId);
        for (String siteCode : after)
        {
            mapper.insertGrant(userId, siteCode, actorId, now);
        }
        mapper.insertAudit(userId, actorId, String.join(",", before), String.join(",", after), now);
    }

    private void requireTarget(Long userId)
    {
        if (userId == null || userId <= 0 || mapper.selectActiveUserId(userId) == null)
        {
            throw new ServiceException("目标用户不存在、已停用或已删除", 400);
        }
    }
}
