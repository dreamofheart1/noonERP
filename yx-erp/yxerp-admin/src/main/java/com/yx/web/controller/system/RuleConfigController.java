package com.yx.web.controller.system;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.yx.common.core.domain.AjaxResult;
import com.yx.framework.configversion.RuleConfigWriteService;

/** 业务参数管理；授权、锁与审计均在服务层执行。时间文本为北京时间 ISO 本地时间。 */
@RestController
@RequestMapping("/noon/rule-config")
public class RuleConfigController
{
    public record CreateRequest(String key, String scope, String value, String effectiveFrom, String effectiveTo) { }
    private static final Logger LOG = LoggerFactory.getLogger(RuleConfigController.class);
    private static final Set<String> ALLOWED_KEYS = Set.of("return_window_days", "appeal_window_days",
            "statement_dispute_days", "api_max_destinations", "payout_threshold", "allocation_policy",
            "sku_allocation_enabled", "cost_method", "vat_mode", "return_restock_cost", "stock_update_mode",
            "profit_allocation_basis", "order_idempotent_mode");
    private final RuleConfigWriteService service;
    public RuleConfigController(RuleConfigWriteService service) { this.service = service; }

    @GetMapping("/history")
    public AjaxResult history(@RequestParam String key, @RequestParam String scope,
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "50") int limit)
    {
        return AjaxResult.success(service.history(key, scope, afterId, limit));
    }

    @PostMapping
    public AjaxResult create(@RequestBody CreateRequest request)
    {
        if (request == null || request.effectiveFrom() == null) throw new IllegalArgumentException("必须提供生效时间");
        validateValue(request.key(), request.value());
        LocalDateTime from = LocalDateTime.parse(request.effectiveFrom());
        LocalDateTime to = request.effectiveTo() == null ? null : LocalDateTime.parse(request.effectiveTo());
        return AjaxResult.success("创建成功", service.create(request.key(), request.scope(), request.value(), from, to));
    }

    @PostMapping("/{id}/revoke")
    public AjaxResult revoke(@PathVariable long id)
    {
        service.revokeManaged(id, ALLOWED_KEYS);
        return AjaxResult.success();
    }

    /** 仅开放已定义语义的参数；AI 自动审核尚无可信规则执行器，保持人工模式。 */
    private static void validateValue(String key, String value)
    {
        if (key == null || value == null || value.length() > 1024) throw new IllegalArgumentException("参数值无效");
        switch (key)
        {
            case "return_window_days", "appeal_window_days", "statement_dispute_days", "api_max_destinations" -> {
                if (!value.matches("0|[1-9][0-9]{0,8}")) throw new IllegalArgumentException("参数值须为非负整数");
            }
            case "payout_threshold" -> {
                if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,4})?") || new BigDecimal(value).signum() < 0)
                    throw new IllegalArgumentException("金额格式无效");
            }
            case "allocation_policy" -> {
                if (!Set.of("NONE", "PARTIAL", "FULL").contains(value)) throw new IllegalArgumentException("合并口径无效");
            }
            case "sku_allocation_enabled" -> {
                if (!Set.of("0", "1").contains(value)) throw new IllegalArgumentException("开关无效");
            }
            case "cost_method" -> { if (!"FIFO".equals(value)) throw new IllegalArgumentException("成本方法无效"); }
            case "vat_mode" -> { if (!"IGNORE".equals(value)) throw new IllegalArgumentException("税务口径无效"); }
            case "return_restock_cost" -> { if (!"ORIGINAL".equals(value)) throw new IllegalArgumentException("退货成本无效"); }
            case "stock_update_mode" -> { if (!"ABSOLUTE".equals(value)) throw new IllegalArgumentException("库存更新口径无效"); }
            case "profit_allocation_basis" -> { if (!"REVENUE_SHARE".equals(value)) throw new IllegalArgumentException("分摊方法无效"); }
            case "order_idempotent_mode" -> { if (!"FINGERPRINT_ACK".equals(value)) throw new IllegalArgumentException("幂等方法无效"); }
            default -> throw new IllegalArgumentException("未开放的参数键");
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class, HttpMessageNotReadableException.class})
    public AjaxResult invalidInput(Exception e) { return AjaxResult.error(400, "参数或时间格式无效"); }

    @ExceptionHandler(IllegalStateException.class)
    public AjaxResult conflict(IllegalStateException e) { return AjaxResult.error(409, "版本冲突或状态已改变"); }

    @ExceptionHandler(DataAccessException.class)
    public AjaxResult databaseFailure(DataAccessException e)
    {
        LOG.error("参数数据库操作失败：{}", e.getClass().getSimpleName());
        return AjaxResult.error(500, "参数操作失败，请联系管理员");
    }
}
