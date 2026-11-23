package com.yx.web.controller.noon;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.sync.NoonSkuSyncResult;
import com.yx.noon.sync.NoonSkuSyncService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yx.common.core.domain.AjaxResult;

/** Noon 商品同步手动入口。周期执行请用若依定时任务 noonSkuSyncTask.syncAe()。 */
@RestController
@RequestMapping("/noon/sync")
public class NoonSkuSyncController
{
    private final NoonSkuSyncService service;
    private final SiteAuthGuard guard;

    public NoonSkuSyncController(NoonSkuSyncService service, SiteAuthGuard guard)
    {
        this.service = service;
        this.guard = guard;
    }

    /** 仅 AE；权限复用 SKU 维护权限（SKU 新增本身仅超管）。 */
    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/sku")
    public AjaxResult syncSku(@RequestParam(required = false, defaultValue = "AE") String siteCode)
    {
        guard.requireSite("AE");
        if (siteCode != null && !siteCode.isBlank() && !"AE".equalsIgnoreCase(siteCode.trim()))
            throw new IllegalArgumentException("当前只开放 AE 站点同步");
        NoonSkuSyncResult result = service.sync("AE");
        return AjaxResult.success(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }
}
