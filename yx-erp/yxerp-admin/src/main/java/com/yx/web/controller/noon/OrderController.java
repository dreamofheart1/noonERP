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
import com.yx.noon.purchasing.OrderInput;
import com.yx.noon.purchasing.OrderQuery;
import com.yx.noon.purchasing.OrderRow;
import com.yx.noon.purchasing.OrderService;

/** M3 采购单入口。状态机（confirm/close）与站点授权在 OrderService 内执行。 */
@RestController
@RequestMapping("/noon/purchasing/order")
public class OrderController
{
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source)
    {
        OrderQuery q = new OrderQuery(orderNo, supplierId, siteCode, status, source);
        PageResult<OrderRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), q);
        return AjaxResult.success(page);
    }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:list')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable long id) { return AjaxResult.success(service.get(id)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:add')")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody OrderInput in) { return AjaxResult.success(service.save(in)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:edit')")
    @PostMapping("/{id}/update")
    public AjaxResult update(@PathVariable long id, @RequestBody OrderInput in) { return AjaxResult.success(service.update(id, in)); }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:confirm')")
    @PostMapping("/{id}/confirm")
    public AjaxResult confirm(@PathVariable long id)
    {
        service.confirm(id);
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:close')")
    @PostMapping("/{id}/close")
    public AjaxResult close(@PathVariable long id)
    {
        service.close(id);
        return AjaxResult.success();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
