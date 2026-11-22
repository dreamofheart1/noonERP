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
import com.yx.noon.asn.AsnCreateInput;
import com.yx.noon.asn.AsnDetail;
import com.yx.noon.asn.AsnReceiveInput;
import com.yx.noon.asn.AsnResult;
import com.yx.noon.asn.AsnRow;
import com.yx.noon.asn.AsnSealInput;
import com.yx.noon.asn.AsnService;
import com.yx.noon.masterdata.PageResult;

/**
 * M4 第三轮第三批入口：FBN 入仓 ASN 收货闭环（create→seal→receive）。
 * 站点授权、动作级幂等、四态台账守恒、短少 ASN_SHORT 在 AsnService 内执行；Controller 仅权限注解 + 参数绑定。
 */
@RestController
@RequestMapping("/noon/inventory/asn")
public class AsnController
{
    private final AsnService service;
    public AsnController(AsnService service) { this.service = service; }

    /** 新建 ASN（DRAFT，只登记不动库存）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:create')")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody AsnCreateInput in) { return AjaxResult.success(service.create(in)); }

    /** 封单发运（DRAFT→SEALED，扣源仓+拆在途子批次）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:seal')")
    @PostMapping("/seal")
    public AjaxResult seal(@RequestBody AsnSealInput in) { return AjaxResult.success(service.seal(in)); }

    /** 收货登记（SEALED→RECEIVED，按行实收+短少 ASN_SHORT）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:receive')")
    @PostMapping("/receive")
    public AjaxResult receive(@RequestBody AsnReceiveInput in) { return AjaxResult.success(service.receive(in)); }

    /** 幂等结果查询（前端重开弹窗先查上次 requestId）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:list')")
    @GetMapping("/result")
    public AjaxResult result(@RequestParam String requestId) { return AjaxResult.success(service.resultOf(requestId)); }

    /** ASN 列表（按站点授权过滤，真实 total）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit)
    {
        PageResult<AsnRow> page = service.list(siteCode, offset, Math.min(Math.max(limit, 1), 200));
        return AjaxResult.success(page);
    }

    /** ASN 详情（头+行+子批次血缘，带授权）。 */
    @PreAuthorize("@ss.hasPermi('noon:inventory:asn:list')")
    @GetMapping("/get")
    public AjaxResult get(@RequestParam Long asnId) { return AjaxResult.success(service.get(asnId)); }

    @ExceptionHandler(IllegalArgumentException.class)
    public AjaxResult invalidInput(IllegalArgumentException e) { return AjaxResult.error(400, e.getMessage()); }

    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult denied(AccessDeniedException e) { return AjaxResult.error(403, e.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult badBody(HttpMessageNotReadableException e) { return AjaxResult.error(400, "请求体格式无效"); }
}
