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
import com.yx.noon.stocktake.StocktakeApplyInput;
import com.yx.noon.stocktake.StocktakeDetail;
import com.yx.noon.stocktake.StocktakeInput;
import com.yx.noon.stocktake.StocktakeResult;
import com.yx.noon.stocktake.StocktakeRow;
import com.yx.noon.stocktake.StocktakeService;

/**
 * M4 库存第三轮第一批入口：盘点单创建 → 应用过账（账实差异回写四态台账）。
 * 站点授权、动作级幂等、盘盈盘亏守恒、审计在 StocktakeService 内执行；Controller 仅权限注解 + 参数绑定。
 */
@RestController
@RequestMapping("/noon/inventory/stocktake")
public class StocktakeController
{
    private final StocktakeService service;
    public StocktakeController(StocktakeService service) { this.service = service; }

    /** 创建盘点单（不改库存）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:stocktake:create')")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody StocktakeInput in) { return AjaxResult.success(service.create(in)); }

    /** 应用盘点（过账：盘盈/盘亏回写台账 + 流水 + 批次消耗）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:stocktake:apply')")
    @PostMapping("/apply")
    public AjaxResult apply(@RequestBody StocktakeApplyInput in) { return AjaxResult.success(service.apply(in)); }

    /** 查询盘点请求结果：前端重开弹窗先查上次 requestId 是否已过账。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:stocktake:apply')")
    @GetMapping("/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    /** 盘点单列表（按站点授权过滤，真实 total）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:stocktake:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit)
    {
        PageResult<StocktakeRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200));
        return AjaxResult.success(page);
    }

    /** 盘点单详情（带授权）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:stocktake:list')")
    @GetMapping("/get")
    public AjaxResult get(@RequestParam Long id) { return AjaxResult.success(service.get(id)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
