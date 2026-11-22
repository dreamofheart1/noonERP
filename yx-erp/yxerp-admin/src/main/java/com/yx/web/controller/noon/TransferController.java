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
import com.yx.noon.transfer.TransferCreateInput;
import com.yx.noon.transfer.TransferDetail;
import com.yx.noon.transfer.TransferReceiveInput;
import com.yx.noon.transfer.TransferResult;
import com.yx.noon.transfer.TransferRow;
import com.yx.noon.transfer.TransferService;
import com.yx.noon.transfer.TransferShipInput;

/**
 * M4 库存第二轮入口：调拨发运（在途拆批）/收货（转 OPEN + 短少冲销）。
 * 站点授权、C-5 拆批守恒、四态台账双边守恒、幂等在 TransferService 内执行；Controller 仅权限注解与参数绑定。
 */
@RestController
@RequestMapping("/noon/inventory")
public class TransferController
{
    private final TransferService service;
    public TransferController(TransferService service) { this.service = service; }

    /** 新建调拨单（写）：从授权仓库与可用批次创建 DRAFT 单+计划行，不扣库存；菜单 3301 add 权限。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:add')")
    @PostMapping("/transfer/create")
    public AjaxResult create(@RequestBody TransferCreateInput in) { return AjaxResult.success(service.create(in)); }

    /** 调拨发运（写）：源仓可用减 + 在途子批次 + 目标仓在途增 + TRANSFER_OUT 流水。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:ship')")
    @PostMapping("/transfer/ship")
    public AjaxResult ship(@RequestBody TransferShipInput in) { return AjaxResult.success(service.ship(in)); }

    /** 调拨收货（写）：在途转 OPEN + 目标仓在途减可用增 + 短少 TRANSFER_SHORT + TRANSFER_IN 流水。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:receive')")
    @PostMapping("/transfer/receive")
    public AjaxResult receive(@RequestBody TransferReceiveInput in) { return AjaxResult.success(service.receive(in)); }

    /** 查询调拨请求结果：前端重开弹窗先查上次 requestId 是否已处理，避免响应不明重复提交。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:ship')")
    @GetMapping("/transfer/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    /** 调拨单列表（按站点授权过滤，真实 total）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:list')")
    @GetMapping("/transfer/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit)
    {
        PageResult<TransferRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200));
        return AjaxResult.success(page);
    }

    /** 调拨单详情（带授权）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:transfer:list')")
    @GetMapping("/transfer/get")
    public AjaxResult get(@RequestParam Long id) { return AjaxResult.success(service.get(id)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
