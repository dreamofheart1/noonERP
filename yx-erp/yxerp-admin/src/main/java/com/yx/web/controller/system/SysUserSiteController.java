package com.yx.web.controller.system;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.yx.common.annotation.Log;
import com.yx.common.core.domain.AjaxResult;
import com.yx.common.enums.BusinessType;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.framework.web.service.SysUserSiteService;

/** 复用若依 JWT 登录链，授权校验同时放在服务层。 */
@RestController
@RequestMapping("/noon")
public class SysUserSiteController
{
    private final SiteAuthGuard guard;
    private final SysUserSiteService service;

    public SysUserSiteController(SiteAuthGuard guard, SysUserSiteService service)
    {
        this.guard = guard;
        this.service = service;
    }

    @GetMapping("/sites")
    public AjaxResult currentSites()
    {
        return AjaxResult.success(guard.allowedSiteCodes());
    }

    @GetMapping("/site-grants/{userId}")
    public AjaxResult userSites(@PathVariable Long userId)
    {
        return AjaxResult.success(service.getUserSites(userId));
    }

    @Log(title = "Noon站点授权", businessType = BusinessType.GRANT)
    @PutMapping("/site-grants/{userId}")
    public AjaxResult replaceSites(@PathVariable Long userId, @RequestBody List<String> siteCodes)
    {
        service.replaceUserSites(userId, siteCodes);
        return AjaxResult.success();
    }
}
