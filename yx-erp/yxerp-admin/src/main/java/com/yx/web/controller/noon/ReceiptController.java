package com.yx.web.controller.noon;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.yx.common.core.domain.AjaxResult;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.purchasing.ReceiptInput;
import com.yx.noon.purchasing.ReceiptResult;
import com.yx.noon.purchasing.ReceiptRow;
import com.yx.noon.purchasing.ReceiptService;

/** M3 部分收货入口。数量守恒、状态推进与成本口径在 ReceiptService 内执行。 */
@RestController
@RequestMapping("/noon/purchasing/receipt")
public class ReceiptController
{
    private final ReceiptService service;
    public ReceiptController(ReceiptService service) { this.service = service; }

    /** 部分收货（写）：站点授权 + 数量守恒 + 状态推进 + 审计。 */
    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:receive')")
    @PostMapping("/receive")
    public AjaxResult receive(@RequestBody ReceiptInput in) { return AjaxResult.success(service.receive(in)); }

    /** 收货明细只读台账：按订单站点授权过滤。 */
    @PreAuthorize("@ss.hasPermi('noon:purchasing:receipt:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long orderId)
    {
        PageResult<ReceiptRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), orderId);
        return AjaxResult.success(page);
    }

    /**
     * 查询收货请求结果（契约 v2.1 §9.6）：前端重开弹窗时先查上次 requestId 是否已入账，
     * 避免响应不明时重复提交。授权在 Service 内按订单真实站点执行（无权 403，不存在返回 null）。
     */
    @PreAuthorize("@ss.hasPermi('noon:purchasing:order:receive')")
    @GetMapping("/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
