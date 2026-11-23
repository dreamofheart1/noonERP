package com.yx.web.controller.noon;

import com.yx.noon.masterdata.PartnerSkuQuantityBreakdown;
import com.yx.noon.masterdata.PartnerSkuQuantityService;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.masterdata.PartnerSkuQuery;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.security.access.prepost.PreAuthorize;
import com.yx.common.core.domain.AjaxResult;
import com.yx.noon.masterdata.PartnerSkuInput;
import com.yx.noon.masterdata.PartnerSkuRow;
import com.yx.noon.masterdata.PartnerSkuService;

/** M2 Partner SKU 映射主数据管理入口。业务校验与站点授权在 PartnerSkuService 内执行。 */
@RestController
@RequestMapping("/noon/masterdata/partner-sku")
public class PartnerSkuController
{
    private final PartnerSkuService service;
    private final PartnerSkuQuantityService quantityService;

    public PartnerSkuController(PartnerSkuService service, PartnerSkuQuantityService quantityService)
    {
        this.service = service;
        this.quantityService = quantityService;
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String partnerSku,
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) String status)
    {
        PartnerSkuQuery q = new PartnerSkuQuery(partnerSku, skuId, siteCode, status);
        PageResult<PartnerSkuRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), q);
        return AjaxResult.success(page);
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable long id) { return AjaxResult.success(service.get(id)); }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:list')")
    @GetMapping("/{id}/quantity-breakdown")
    public AjaxResult quantityBreakdown(@PathVariable long id)
    {
        PartnerSkuQuantityBreakdown breakdown = quantityService.breakdown(id);
        return AjaxResult.success(breakdown);
    }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:save')")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody PartnerSkuInput in) { return AjaxResult.success(service.save(in)); }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:save')")
    @PostMapping("/{id}/update")
    public AjaxResult update(@PathVariable long id, @RequestBody PartnerSkuInput in) { return AjaxResult.success(service.update(id, in)); }

    @PreAuthorize("@ss.hasPermi('noon:masterdata:partnerSku:status')")
    @PostMapping("/{id}/status")
    public AjaxResult setStatus(@PathVariable long id, @RequestParam String status)
    {
        service.setStatus(id, status);
        return AjaxResult.success();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
