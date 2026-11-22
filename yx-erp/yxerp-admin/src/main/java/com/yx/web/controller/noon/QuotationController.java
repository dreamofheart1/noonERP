package com.yx.web.controller.noon;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yx.common.core.domain.AjaxResult;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.purchasing.QuotationInput;
import com.yx.noon.purchasing.QuotationQuery;
import com.yx.noon.purchasing.QuotationRow;
import com.yx.noon.purchasing.QuotationService;

/** M3 SKU 供应商报价（历史价）入口。业务校验与站点授权在 QuotationService 内执行。 */
@RestController
@RequestMapping("/noon/purchasing/quotation")
public class QuotationController
{
    private final QuotationService service;
    public QuotationController(QuotationService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:quotation:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long skuId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String effectiveFrom,
            @RequestParam(required = false) String effectiveTo)
    {
        QuotationQuery q = new QuotationQuery(skuId, supplierId, effectiveFrom, effectiveTo);
        PageResult<QuotationRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), q);
        return AjaxResult.success(page);
    }

    /** 取当前/指定日期有效报价，供采购单带出单价；at 省略用当天。 */
    @PreAuthorize("@ss.hasPermi('noon:purchasing:quotation:list')")
    @GetMapping("/effective")
    public AjaxResult effective(@RequestParam Long skuId, @RequestParam Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at)
    {
        LocalDate day = at == null ? LocalDate.now() : at;
        return AjaxResult.success(service.effectivePrice(skuId, supplierId, day));
    }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:quotation:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable long id) { return AjaxResult.success(service.get(id)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:quotation:add')")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody QuotationInput in) { return AjaxResult.success(service.save(in)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:quotation:edit')")
    @PostMapping("/{id}/update")
    public AjaxResult update(@PathVariable long id, @RequestBody QuotationInput in) { return AjaxResult.success(service.update(id, in)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
