package com.yx.web.controller.noon;

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
import com.yx.noon.purchasing.SupplierInput;
import com.yx.noon.purchasing.SupplierQuery;
import com.yx.noon.purchasing.SupplierRow;
import com.yx.noon.purchasing.SupplierService;

/** M3 供应商档案入口。业务校验与站点授权在 SupplierService 内执行。 */
@RestController
@RequestMapping("/noon/purchasing/supplier")
public class SupplierController
{
    private final SupplierService service;
    public SupplierController(SupplierService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:supplier:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String supplierNo,
            @RequestParam(required = false) String supplierName,
            @RequestParam(required = false) String status)
    {
        SupplierQuery q = new SupplierQuery(supplierNo, supplierName, status);
        PageResult<SupplierRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), q);
        return AjaxResult.success(page);
    }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:supplier:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable long id) { return AjaxResult.success(service.get(id)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:supplier:add')")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody SupplierInput in) { return AjaxResult.success(service.save(in)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:supplier:edit')")
    @PostMapping("/{id}/update")
    public AjaxResult update(@PathVariable long id, @RequestBody SupplierInput in) { return AjaxResult.success(service.update(id, in)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:supplier:status')")
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
