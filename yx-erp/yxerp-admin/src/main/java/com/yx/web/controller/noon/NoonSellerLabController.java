package com.yx.web.controller.noon;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.sync.NoonOpeningService;
import com.yx.noon.sync.NoonSalesLinkService;
import com.yx.noon.sync.NoonSellerLabClient;
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
    private final NoonSalesLinkService salesLinkService;
    private final NoonOpeningService openingService;
    private final SiteAuthGuard guard;

    public NoonSellerLabController(NoonSellerLabSyncService service, SiteAuthGuard guard,
                                   NoonSalesLinkService salesLinkService, NoonOpeningService openingService)
    {
        this.service = service;
        this.guard = guard;
        this.salesLinkService = salesLinkService;
        this.openingService = openingService;
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/fbn-inventory/sync")
    public AjaxResult syncFbn(@RequestParam(defaultValue = "AE") String siteCode)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(service.syncFbnInventory(siteCode));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/sales/sync")
    public AjaxResult syncSales(@RequestParam(defaultValue = "AE") String siteCode,
                                @RequestParam String fromDate, @RequestParam String toDate,
                                @RequestParam(defaultValue = "5") int maxPages)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(service.syncSales(siteCode, fromDate, toDate, maxPages));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/asn/sync")
    public AjaxResult syncAsn(@RequestParam(defaultValue = "AE") String siteCode,
                              @RequestParam(defaultValue = "20") int maxPages)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(service.syncAsn(siteCode, Math.min(Math.max(maxPages, 1), 100)));
    }

    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:list')")
    @GetMapping("/asn/list")
    public AjaxResult listAsn(@RequestParam(defaultValue = "AE") String siteCode,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "50") int limit)
    {
        return AjaxResult.success(service.listAsn(siteCode, offset, Math.min(Math.max(limit, 1), 200)));
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
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(required = false) String fromDate,
                                @RequestParam(required = false) String toDate)
    {
        return AjaxResult.success(service.listSales(siteCode, offset, Math.min(Math.max(limit, 1), 200), fromDate, toDate));
    }

    // ===== 期初建议/确认：generate 只读；confirm 才建账，需显式确认人和真实成本 =====
    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/opening/proposal")
    public AjaxResult openingProposal(@RequestParam(defaultValue = "AE") String siteCode,
                                      @RequestParam String proposalNo,
                                      @RequestParam(required = false) String asOfDate)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(openingService.generate(siteCode, proposalNo, asOfDate));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/opening/confirm")
    public AjaxResult openingConfirm(@RequestParam(defaultValue = "AE") String siteCode,
                                     @RequestParam String proposalNo,
                                     @RequestParam java.math.BigDecimal unitCost)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        String operator = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() == null ? "" : org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        return AjaxResult.success(openingService.confirm(siteCode, proposalNo, operator, unitCost));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/sales-link/refresh")
    public AjaxResult refreshSalesLink(@RequestParam(defaultValue = "AE") String siteCode)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(salesLinkService.refresh(siteCode));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:save')")
    @PostMapping("/return-evidence")
    public AjaxResult registerReturn(@RequestParam(defaultValue = "AE") String siteCode,
                                     @RequestParam(required = false) String itemNr,
                                     @RequestParam String sourceReturnNo,
                                     @RequestParam(defaultValue = "false") boolean verifiedRestockSaleable,
                                     @RequestParam(required = false) String evidenceNote)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(salesLinkService.registerReturn(siteCode, itemNr, sourceReturnNo,
                verifiedRestockSaleable, evidenceNote));
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:sku:list')")
    @GetMapping("/return-evidence/list")
    public AjaxResult listReturnEvidence(@RequestParam(defaultValue = "AE") String siteCode)
    {
        guard.requireSite(NoonSellerLabClient.requireSite(siteCode));
        return AjaxResult.success(jdbcReturnEvidence(siteCode));
    }

    private Object jdbcReturnEvidence(String siteCode)
    {
        return service.listReturnEvidence(siteCode);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalid(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }
}
