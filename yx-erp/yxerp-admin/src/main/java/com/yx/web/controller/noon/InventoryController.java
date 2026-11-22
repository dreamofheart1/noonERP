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
import com.yx.noon.inventory.BatchRow;
import com.yx.noon.inventory.InboundDetail;
import com.yx.noon.inventory.InboundInput;
import com.yx.noon.inventory.InboundResult;
import com.yx.noon.inventory.InboundRow;
import com.yx.noon.inventory.InboundService;
import com.yx.noon.inventory.LedgerRow;
import com.yx.noon.masterdata.PageResult;

/**
 * M4 库存第一轮入口：采购收货→入库确认→FIFO 批次/四态台账/流水。
 * 站点授权、数量与成本守恒、幂等在 InboundService 内执行；Controller 仅做权限注解与参数绑定。
 */
@RestController
@RequestMapping("/noon/inventory")
public class InventoryController
{
    private final InboundService service;
    public InventoryController(InboundService service) { this.service = service; }

    /** 入库确认（写）：授权经仓库归属 + 防伪造 + 幂等 + 批次/台账/流水/审计。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:inbound:confirm')")
    @PostMapping("/inbound/confirm")
    public AjaxResult confirm(@RequestBody InboundInput in) { return AjaxResult.success(service.confirm(in)); }

    /** 查询入库请求结果：前端重开弹窗先查上次 requestId 是否已入账，避免响应不明重复提交。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:inbound:confirm')")
    @GetMapping("/inbound/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    /** 入库单列表（按站点授权过滤，真实 total）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:inbound:list')")
    @GetMapping("/inbound/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit)
    {
        PageResult<InboundRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200));
        return AjaxResult.success(page);
    }

    /** 入库单详情（带授权）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:inbound:list')")
    @GetMapping("/inbound/get")
    public AjaxResult get(@RequestParam Long id) { return AjaxResult.success(service.get(id)); }

    /** FIFO 批次只读台账。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:batch:list')")
    @GetMapping("/batch/list")
    public AjaxResult batches(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long skuId)
    {
        PageResult<BatchRow> page = service.listBatches(siteCode, offset, Math.min(Math.max(limit, 1), 200), skuId);
        return AjaxResult.success(page);
    }

    /** 四态台账只读。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:ledger:list')")
    @GetMapping("/ledger/list")
    public AjaxResult ledger(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit)
    {
        PageResult<LedgerRow> page = service.listLedger(siteCode, offset, Math.min(Math.max(limit, 1), 200));
        return AjaxResult.success(page);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
