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
import com.yx.noon.shipment.CostQuery;
import com.yx.noon.shipment.ShipmentCostDetail;
import com.yx.noon.shipment.ShipmentCostInput;
import com.yx.noon.shipment.ShipmentCostResult;
import com.yx.noon.shipment.ShipmentCostRow;
import com.yx.noon.shipment.ShipmentCostService;

/**
 * M4 第三轮第二批入口：头程/关税/包材费用登记（纯登记，不动库存、不摊 FIFO）。
 * 站点授权、动作级幂等、审计在 ShipmentCostService 内执行；Controller 仅权限注解 + 参数绑定。
 */
@RestController
@RequestMapping("/noon/inventory/shipment-cost")
public class ShipmentCostController
{
    private final ShipmentCostService service;
    public ShipmentCostController(ShipmentCostService service) { this.service = service; }

    /** 登记一笔头程费用。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:shipment:create')")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody ShipmentCostInput in) { return AjaxResult.success(service.create(in)); }

    /** 查询登记请求结果：前端重开弹窗先查上次 requestId 是否已登记。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:shipment:list')")
    @GetMapping("/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    /** 费用列表（按站点授权过滤，真实 total）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:shipment:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String feeType,
            @RequestParam(required = false) Long asnId,
            @RequestParam(required = false) String period)
    {
        CostQuery q = new CostQuery(feeType, asnId, period);
        PageResult<ShipmentCostRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200), q);
        return AjaxResult.success(page);
    }

    /** 费用详情（带授权）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:shipment:list')")
    @GetMapping("/get")
    public AjaxResult get(@RequestParam Long id) { return AjaxResult.success(service.get(id)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
