package com.yx.web.controller.noon;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.sync.NoonSellerLabSyncResult;
import com.yx.noon.sync.NoonSellerLabSyncService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yx.common.core.domain.AjaxResult;

/**
 * Seller Lab 只读查询接入入口（FBN 库存 / Sales；ASN 暂缓）。
 * 平台为非公开内部接口、单进程限速；未同步或失败不等于 0，不覆盖 ERP 账目。
 */
@RestController
@RequestMapping("/noon/sellerlab")
public class NoonSellerLabController
{
    private final NoonSellerLabSyncService service;
    private final SiteAuthGuard guard;

    public NoonSellerLabController(NoonSellerLabSyncService service, SiteAuthGuard guard)
    {
        this.service = service;
        this.guard = guard;
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/fbn-inventory/sync")
    public AjaxResult syncFbn(@RequestParam(defaultValue = "AE") String siteCode)
    {
        guard.requireSite("AE");
        return AjaxResult.success(service.syncFbnInventory(siteCode));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/sales/sync")
    public AjaxResult syncSales(@RequestParam(defaultValue = "AE") String siteCode,
                                @RequestParam String fromDate, @RequestParam String toDate,
                                @RequestParam(defaultValue = "5") int maxPages)
    {
        guard.requireSite("AE");
        return AjaxResult.success(service.syncSales(siteCode, fromDate, toDate, maxPages));
    }

    /** 库存简单数量对比：官方快照 vs ERP 四态台账；口径/映射不一致明确标注，不认定丢货。 */
    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:list')")
    @GetMapping("/fbn-inventory/compare")
    public AjaxResult compare(@RequestParam(defaultValue = "AE") String siteCode,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "50") int limit)
    {
        return AjaxResult.success(service.compare(siteCode, offset, Math.min(Math.max(limit, 1), 200)));
    }

    @PreAuthorize("@ss.hasPermi('noon:sales:order:list')")
    @GetMapping("/sales/list")
    public AjaxResult listSales(@RequestParam(defaultValue = "AE") String siteCode,
                                @RequestParam(defaultValue = "0") int offset,
                                @RequestParam(defaultValue = "50") int limit)
    {
        return AjaxResult.success(service.listSales(siteCode, offset, Math.min(Math.max(limit, 1), 200)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }
}
