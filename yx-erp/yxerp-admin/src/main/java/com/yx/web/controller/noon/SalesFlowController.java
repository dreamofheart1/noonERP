package com.yx.web.controller.noon;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yx.common.core.domain.AjaxResult;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.sales.DeliverInput;
import com.yx.noon.sales.DisposeInput;
import com.yx.noon.sales.OrderImportInput;
import com.yx.noon.sales.ReturnDispositionException;
import com.yx.noon.sales.ReturnImportInput;
import com.yx.noon.sales.ReturnReverseInput;
import com.yx.noon.sales.SalesFlowException;
import com.yx.noon.sales.SalesFlowService;
import com.yx.noon.sales.SalesResult;

/**
 * M5 订单与退货编排入口。站点授权、动作级幂等、事件登记、A/B/C 分级处置在 SalesFlowService /
 * ReturnDispositionService 内执行；Controller 仅权限注解 + 参数绑定。
 * 内部闸门（intake/fifo/reversal）返回的 NEEDS_REVIEW 由 service 抛 SalesFlowException 映射 409。
 */
@RestController
@RequestMapping("/noon/sales")
public class SalesFlowController
{
    private final SalesFlowService service;
    public SalesFlowController(SalesFlowService service) { this.service = service; }

    /** 订单导入：登记 ORDER 事件 → IntakeService.postOrder。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:order:import')")
    @PostMapping("/order/import")
    public AjaxResult importOrder(@RequestBody OrderImportInput in) { return AjaxResult.success(service.importOrder(in)); }

    /** 妥投出库：登记 SALE_DELIVERY 事件 → FifoConsumptionService.postDelivery。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:order:deliver')")
    @PostMapping("/order/deliver")
    public AjaxResult deliverOrder(@RequestBody DeliverInput in) { return AjaxResult.success(service.deliverOrder(in)); }

    /** 退货入账：登记 RETURN 事件 → IntakeService.postReturn。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:return:import')")
    @PostMapping("/return/import")
    public AjaxResult importReturn(@RequestBody ReturnImportInput in) { return AjaxResult.success(service.importReturn(in)); }

    /** 退货红冲：登记 RETURN_REVERSAL 事件 → ReversalService.postReversal。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:return:reverse')")
    @PostMapping("/return/reverse")
    public AjaxResult reverseReturn(@RequestBody ReturnReverseInput in) { return AjaxResult.success(service.reverseReturn(in)); }

    /** A/B/C 分级处置：核心新逻辑（A 建新批次 / B/C RETURN_LOSS+qty_unsellable / 赔偿只登记）。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:dispose:edit')")
    @PostMapping("/return/dispose")
    public AjaxResult disposeReturn(@RequestBody DisposeInput in) { return AjaxResult.success(service.disposeReturn(in)); }

    /** 幂等结果回读。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:order:list')")
    @GetMapping("/result")
    public AjaxResult result(@RequestParam String requestId, @RequestParam String action)
    {
        return AjaxResult.success(service.resultOf(requestId, action));
    }

    /** 订单列表（站点授权过滤）。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:order:list')")
    @GetMapping("/order/list")
    public AjaxResult listOrders(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status)
    {
        PageResult<?> page = service.listOrders(siteCode, offset, Math.min(Math.max(limit, 1), 200), orderNo, status);
        return AjaxResult.success(page);
    }

    /** 退货列表（站点授权过滤）。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:return:list')")
    @GetMapping("/return/list")
    public AjaxResult listReturns(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String grade)
    {
        PageResult<?> page = service.listReturns(siteCode, offset, Math.min(Math.max(limit, 1), 200), orderNo, grade);
        return AjaxResult.success(page);
    }

    /** 订单详情。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:order:list')")
    @GetMapping("/order/get")
    public AjaxResult getOrder(@RequestParam Long id) { return AjaxResult.success(service.getOrder(id)); }

    /** 退货详情。 */
    @PreAuthorize("@ss.hasPermi('noon:sales:return:list')")
    @GetMapping("/return/get")
    public AjaxResult getReturn(@RequestParam Long id) { return AjaxResult.success(service.getReturn(id)); }

    /** 内部闸门隔离（NEEDS_REVIEW）→ 409，携带 reasonCode/reviewNo，不当成功、不静默吞掉。 */
    @ExceptionHandler(SalesFlowException.class)
    public AjaxResult needsReview(SalesFlowException e)
    {
        AjaxResult r = AjaxResult.error(409, e.getMessage());
        r.put("reasonCode", e.reasonCode());
        r.put("reviewNo", e.reviewNo() == null ? "" : e.reviewNo());
        r.put("requestId", e.requestId());
        r.put("action", e.action());
        return r;
    }

    /** 分级处置业务拒绝（未红冲/部分处置/重复损益）→ 409。 */
    @ExceptionHandler(ReturnDispositionException.class)
    public AjaxResult dispositionRejected(ReturnDispositionException e) { return AjaxResult.error(409, e.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
