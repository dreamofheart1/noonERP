package com.yx.framework.security.site;

import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.common.utils.SecurityUtils;
import com.yx.system.mapper.SysUserSiteMapper;

/**
 * 独立于菜单和部门权限的站点闸门。每次校验读取数据库；不接受调用方传入 userId。
 * 未来业务服务必须先 requireSite，再用返回的站点作为 SQL 绑定参数。
 */
@Component
public class SiteAuthGuard
{
    // 当前已确认站点。扩站须同步主数据、此清单及权限测试；不支持通配符。
    public static final List<String> SUPPORTED_SITES = List.of("AE", "SA");

    private final SysUserSiteMapper mapper;

    public SiteAuthGuard(SysUserSiteMapper mapper)
    {
        this.mapper = mapper;
    }

    public Long requireCurrentUserId()
    {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getUserId() == null || loginUser.getUserId() <= 0
                || loginUser.getUser() == null
                || !Objects.equals(loginUser.getUserId(), loginUser.getUser().getUserId()))
        {
            throw new AccessDeniedException("需要有效的若依登录身份");
        }
        Long userId = loginUser.getUserId();
        if (mapper.selectActiveUserId(userId) == null)
        {
            throw new AccessDeniedException("用户已停用或删除");
        }
        return userId;
    }

    public Long requireAdministrator()
    {
        Long userId = requireCurrentUserId();
        if (!SecurityUtils.isAdmin(userId))
        {
            throw new AccessDeniedException("仅超管可管理站点授权");
        }
        return userId;
    }

    /** 列表查询使用；空集合必须产生零行，不能省略 WHERE 条件。 */
    public List<String> allowedSiteCodes()
    {
        Long userId = requireCurrentUserId();
        if (SecurityUtils.isAdmin(userId))
        {
            return SUPPORTED_SITES;
        }
        return mapper.selectSiteCodes(userId).stream().filter(SUPPORTED_SITES::contains).distinct().toList();
    }

    /** 详情/写入先取记录真实站点再校验，不能只信任请求体站点。 */
    public String requireSite(String siteCode)
    {
        if (siteCode == null || !SUPPORTED_SITES.contains(siteCode)
                || !allowedSiteCodes().contains(siteCode))
        {
            throw new AccessDeniedException("没有该站点的访问权限");
        }
        return siteCode;
    }
}
