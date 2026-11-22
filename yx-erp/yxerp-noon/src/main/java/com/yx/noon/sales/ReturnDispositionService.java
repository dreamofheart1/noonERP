package com.yx.noon.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.inventory.InventoryAudit;

/**
 * M5 核心新逻辑：退货 A/B/C 分级处置（契约 §6，C-2/C-2b）。
 *
 * <p><b>前置</b>：该 {@code sal_return} 已由 {@code ReversalService} 红冲原 SALE 消耗层
 * （存在 {@code sign=-1 && move_type='RETURN_REVERSAL'} 且 {@code sal_return_id=本单} 的流水）。
 * 未红冲先处置 → 400 拒绝（防 COGS 双计）。
 *
 * <p><b>分级</b>：
 * <ul>
 *   <li><b>A（可二次上架）</b>：新建 FIFO 批次（{@code source_type='RETURN_RESTOCK'}、
 *       {@code batch_no='B:RTN:<SITE>:<UUID8>'}、{@code status='OPEN'}、{@code received_at=业务时刻}），
 *       台账 {@code qty_available += qty}（实物回可用；A 级不进不合格库存），回写
 *       {@code sal_return.restock_batch_no}；不写 RETURN_LOSS、不增 {@code qty_unsellable}。</li>
 *   <li><b>B/C（降级/报废）</b>：{@code inv_stock_ledger.qty_unsellable += qty}（锁行 + CHECK≥0）；
 *       按<b>原消耗层</b>写 {@code inv_batch_consumption(move_type='RETURN_LOSS', sign=+1, qty,
 *       unit_cost=原层成本, amount=qty×unit_cost, biz_event_key='RTNDISP:<id>:<grade>',
 *       sal_return_id, reversal_of_id=原 SALE 消耗 id, period=退货期, cross_period)}；
 *       回写 {@code sal_return.return_loss_amount}=全额成本（禁止扣赔偿）。</li>
 * </ul>
 *
 * <p><b>赔偿 C-2b 红线</b>：{@code compensation_amount}/{@code refund_amount} 只登记到
 * {@code sal_return}，<b>不写任何 COMPENSATION 平台费流水、不从 RETURN_LOSS 扣减</b>；
 * 同退货单赔偿只允许确认一次（已登记且金额不同 → 400）。
 *
 * <p><b>与 M4 联调</b>：{@code qty_unsellable} 是四态台账最后一块写入点（M4 遗留），本服务补齐；
 * 库存与损益同事务（D6），失败整体回滚，无部分写入。
 *
 * <p><b>幂等</b>：动作级 {@code noon_m5_sales_request} PK(request_id,'RETURN_DISPOSE') 管重放；
 * 语义级同 returnId+grade 重复处置（不同 requestId）按 ACK，不重复建批次/计损/隔离；
 * 同 returnId 换 grade → 400（防重复损益，作废流程未实现，契约 §9.5）。损益幂等键
 * {@code RTNDISP:<returnId>:<grade>}（A 级为 {@code RTNRESTOCK:<returnId>} 的 {@code restock_batch_no} 回写）。
 */
@Service
public class ReturnDispositionService
{
    static final String ACTION = SalesFlowService.ACTION_RETURN_DISPOSE;

    private static final String GRADE_A = "A";
    private static final String GRADE_B = "B";
    private static final String GRADE_C = "C";
    private static final List<String> GRADES = List.of(GRADE_A, GRADE_B, GRADE_C);

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RETURNED = "RETURNED";
    private static final String STATUS_DISPOSED = "DISPOSED";

    private static final String SOURCE_TYPE_RESTOCK = "RETURN_RESTOCK";
    private static final String MOVE_TYPE_SALE = "SALE";
    private static final String MOVE_TYPE_REVERSAL = "RETURN_REVERSAL";
    private static final String MOVE_TYPE_LOSS = "RETURN_LOSS";

    private static final String BIZ_TYPE = "RETURN_DISPOSE";
    private static final String BATCH_PREFIX = "B:RTN:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;

    public ReturnDispositionService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /**
     * 分级处置主入口。顺序：授权 → 指纹 → DONE 重放/占位 → 锁退货单 → 状态/grade 闸门 →
     * 前置红冲校验 → 数量口径 → 分级写入（库存 + 损益同事务）→ 回写退货单 → DONE → 审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public SalesResult dispose(DisposeInput in)
    {
        if (in == null) throw new IllegalArgumentException("分级处置请求不能为空");
        String requestId = SalesRules.requireRequestId(in.requestId());
        Long actor = guard.requireCurrentUserId();
        String siteCode = SalesRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        if (in.salReturnId() == null || in.salReturnId() <= 0)
            throw invalid(in.salReturnId(), null, "INVALID_INPUT", "salReturnId 必须为正整数");
        String grade = grade(in.grade(), in.salReturnId());
        if (in.qty() != null && in.qty() <= 0)
            throw invalid(in.salReturnId(), grade, "INVALID_INPUT", "qty 必须为正整数（或留空取退货单 qty）");
        // Codex M5 复核：restockCost 非正数一律拒绝（0/负值会使二次上架资产与原红冲成本不一致导致利润失真）。
        // 正数也不得任意覆盖——真正的 ORIGINAL 一致性校验在 restock() 内按原 SALE 层加权成本比对（§13.2）。
        if (in.restockCost() != null && in.restockCost().signum() <= 0)
            throw invalid(in.salReturnId(), grade, "INVALID_RESTOCK_COST",
                    "restockCost 必须为正数：0 或负值会使二次上架资产与原红冲成本不一致导致利润失真；"
                            + "留空取原 SALE 消耗层成本（ORIGINAL），传入值必须与原价一致");
        if (in.compensationAmount() != null && in.compensationAmount().signum() < 0)
            throw invalid(in.salReturnId(), grade, "INVALID_INPUT", "compensationAmount 不得为负");
        if (in.refundAmount() != null && in.refundAmount().signum() < 0)
            throw invalid(in.salReturnId(), grade, "INVALID_INPUT", "refundAmount 不得为负");

        BigDecimal restockCost = SalesRules.money(in.restockCost());
        BigDecimal compensation = SalesRules.money(in.compensationAmount());
        BigDecimal refund = SalesRules.money(in.refundAmount());
        String fingerprint = SalesRules.fingerprint(ACTION, siteCode, in.salReturnId(), grade,
                in.qty(), restockCost, compensation, refund);

        SalesResult replayed = SalesIdempotency.replayDone(jdbc, requestId, ACTION, fingerprint);
        if (replayed != null) return replayed;
        SalesResult claimed = SalesIdempotency.claim(jdbc, requestId, ACTION, siteCode, actor, fingerprint);
        if (claimed != null) return claimed;

        // 锁 sal_return 行：串行化同退货单全部处置动作，读到最新状态与已登记金额。
        ReturnHead head = lockReturn(in.salReturnId());
        if (head == null)
            throw invalid(in.salReturnId(), grade, "RETURN_NOT_FOUND", "退货单不存在");
        if (!siteCode.equals(head.siteCode()))
            throw new AccessDeniedException("跨站处置被拒绝：请求站点 " + siteCode + "，退货单站点 " + head.siteCode());

        // 赔偿/退款单次登记（C-2b）：已登记且金额不同 → 400；相同 → 幂等允许。先于状态 ACK 校验，
        // 避免"已处置后换一个赔偿金额再来一次"被静默 ACK 而掩盖双计风险。
        requireSingleRegistration("补偿", head.compensationAmount(), compensation, in.salReturnId(), grade,
                "COMPENSATION_ALREADY_CONFIRMED");
        requireSingleRegistration("退款", head.refundAmount(), refund, in.salReturnId(), grade,
                "REFUND_ALREADY_REGISTERED");

        if (STATUS_DISPOSED.equals(head.status()))
        {
            if (!grade.equals(head.grade()))
                throw invalid(in.salReturnId(), grade, "ALREADY_DISPOSED_OTHER_GRADE",
                        "该退货单已按 " + head.grade() + " 级处置，禁止改 grade（需作废流程，未实现）");
            SalesResult ack = resultOfDisposed(head, requestId, true);
            SalesIdempotency.markDone(jdbc, requestId, ACTION, head.id(), ack);
            return ack;
        }
        if (!STATUS_OPEN.equals(head.status()) && !STATUS_RETURNED.equals(head.status()))
            throw invalid(in.salReturnId(), grade, "STATUS_NOT_DISPOSABLE",
                    "退货单状态不可处置：" + head.status());

        // 前置红冲校验：必须存在本单的 sign=-1 RETURN_REVERSAL 冲回流水，且 reversal_of_id 指向 sign=+1 SALE 原行。
        List<ReversalLayer> layers = lockReversalLayers(head);
        if (layers.isEmpty())
            throw invalid(in.salReturnId(), grade, "RETURN_NOT_REVERSED",
                    "该退货单尚未红冲原 SALE 消耗层，禁止先处置（防 COGS 双计），请先执行退货红冲");
        long reversedQty = layers.stream().mapToLong(ReversalLayer::qty).sum();
        BigDecimal reversedAmount = layers.stream().map(ReversalLayer::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int qty = in.qty() == null ? head.qty() : in.qty();
        // D3/D7：不允许"部分处置"落地为终态（契约 §9.5 未实现作废/再处置流程）。
        // 口径：处置数量必须等于退货单 qty，且红冲必须已完成到退货单全部数量；
        // 未完成红冲时拒绝（不按"剩余未红冲"比例分摊，避免 COGS 双计与半边状态）。
        if (qty != head.qty())
            throw invalid(in.salReturnId(), grade, "QTY_PARTIAL_NOT_SUPPORTED",
                    "不支持分批处置：本次 qty " + qty + " 与退货单 qty " + head.qty()
                            + " 不一致（请按整单一次性处置）");
        if (reversedQty != head.qty())
            throw invalid(in.salReturnId(), grade, "REVERSAL_INCOMPLETE",
                    "红冲未完成：已红冲 " + reversedQty + " / 退货 qty " + head.qty()
                            + "，请先完成全部红冲再处置（防 COGS 双计）");

        LocalDateTime now = SalesRules.now();
        // 退货期归属（D4）：优先退货单自身期间（入账时已确定，支持补录/回退场景），
        // 未写时才落到处置时刻所在期；绝不改成原消耗期（红冲已归原期，此处计损归退货期）。
        String returnPeriod = head.period() != null ? head.period() : SalesRules.period(now);
        String originalPeriod = head.originalPeriod() != null ? head.originalPeriod() : firstPeriod(layers);
        boolean crossPeriod = layers.stream().anyMatch(layer -> layer.period() == null
                || !returnPeriod.equals(layer.period()));

        SalesResult result;
        if (GRADE_A.equals(grade))
            result = restock(head, layers, reversedAmount, qty, restockCost, returnPeriod, originalPeriod,
                    compensation, refund, requestId, siteCode, actor, now);
        else
            result = writeOff(head, layers, qty, grade, returnPeriod, crossPeriod, originalPeriod,
                    compensation, refund, requestId, siteCode, actor, now);

        jdbc.update("UPDATE sal_return SET grade=?,status='DISPOSED',return_loss_amount=?,restock_batch_no=?,"
                        + "period=?,original_period=? WHERE id=? AND status=?",
                grade, result.returnLossAmount(), result.restockBatchNo(), returnPeriod, originalPeriod,
                head.id(), head.status());
        if (compensation != null)
            jdbc.update("UPDATE sal_return SET compensation_amount=? WHERE id=?", compensation, head.id());
        if (refund != null)
            jdbc.update("UPDATE sal_return SET refund_amount=? WHERE id=?", refund, head.id());

        SalesIdempotency.markDone(jdbc, requestId, ACTION, head.id(), result);
        audit.record(BIZ_TYPE, String.valueOf(head.id()), "DISPOSE_" + grade,
                "returnId=" + head.id() + ",grade=" + grade + ",qty=" + qty
                        + ",loss=" + result.returnLossAmount() + ",restockBatch=" + result.restockBatchNo()
                        + ",compensation=" + (compensation == null ? head.compensationAmount() : compensation)
                        // 成本来源可追溯：区分缺省原层成本与客户端质检重估覆盖（Codex M5 初审要求核实来源）
                        + ",restockCost=" + restockCost
                        + ",restockCostSource=" + (in.restockCost() == null ? "ORIGINAL_LAYER" : "CLIENT_OVERRIDE"),
                actor);
        return result;
    }

    // =================================================================== A 级：二次上架

    private SalesResult restock(ReturnHead head, List<ReversalLayer> layers, BigDecimal reversedAmount, int qty,
                                BigDecimal restockCost, String returnPeriod, String originalPeriod,
                                BigDecimal compensation, BigDecimal refund, String requestId, String siteCode,
                                Long actor, LocalDateTime now)
    {
        if (head.restockBatchNo() != null)
            throw invalid(head.id(), GRADE_A, "RESTOCK_KEY_CONFLICT",
                    "检测到已存在二次上架批次 " + head.restockBatchNo() + " 但退货单状态非 DISPOSED，需人工核查");

        // D2 + Codex M5 复核：PRD return_restock_cost=ORIGINAL，A 级为资产转移、不产生损益，
        // 二次上架成本恒取被红冲原 SALE 消耗层加权成本。注意（Codex 复核任务4）：
        // unit_cost = reversedAmount/qty 按 4 位 HALF_UP，若多层金额之和不能被 qty 整除，
        // 建批资产 = unit_cost×qty 与红冲 COGS 之间可能有"有界舍入差"（紧界 |差|≤0.00005×qty，
        // 例 620/6→103.3333×6=619.9998，差 −0.0002），**不声称数学上恒零**；差额由 M7 利润/快照
        // 按实际入账金额口径承接（契约 §14）。客户端覆盖仅在"与系统计算的原层成本完全一致"时
        // 放行（幂等/兼容），任何偏离一律拒绝：质检重估价需明确业务授权 + 完整资产差额处理，
        // 属业务待决，本批不臆造阈值或审批流（契约 §9.2/§13.2）。
        BigDecimal originalUnitCost = SalesRules.money(reversedAmount.divide(BigDecimal.valueOf(qty),
                SalesRules.MONEY_SCALE, RoundingMode.HALF_UP));
        if (restockCost != null && restockCost.compareTo(originalUnitCost) != 0)
            throw invalid(head.id(), GRADE_A, "RESTOCK_COST_OVERRIDE_FORBIDDEN",
                    "A 级上架成本必须为原 SALE 层成本 " + originalUnitCost.toPlainString()
                            + "（PRD return_restock_cost=ORIGINAL，资产转移不产生损益）；传入 "
                            + restockCost.toPlainString() + " 与原价不一致，重估需业务授权与资产差额处理（待决）");
        BigDecimal unitCost = originalUnitCost;
        ReversalLayer base = layers.get(0);
        String batchNo = BATCH_PREFIX + siteCode + ":" + SalesRules.uuid8();

        KeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                            + "unit_cost,currency,fx_rate,source_type,status,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,'OPEN',?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setLong(2, head.skuId());
            ps.setLong(3, base.warehouseId());
            ps.setString(4, siteCode);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            ps.setInt(6, qty);
            ps.setInt(7, qty);
            ps.setBigDecimal(8, unitCost);
            ps.setString(9, base.currency());
            ps.setBigDecimal(10, base.fxRate());
            ps.setString(11, SOURCE_TYPE_RESTOCK);
            ps.setTimestamp(12, Timestamp.valueOf(now));
            return ps;
        }, generated);
        Number key = generated.getKey();
        if (key == null) throw new IllegalStateException("未取得二次上架批次 ID");

        // 实物回可用（A 级不进不合格库存）。台账与批次同事务，保持 available + 批次剩余守恒。
        addAvailable(siteCode, head.skuId(), base.warehouseId(), qty, now);

        return new SalesResult(requestId, ACTION, siteCode, false, head.id(), null, head.salOrderItemId(),
                head.id(), null, List.of(), GRADE_A, batchNo, BigDecimal.ZERO.setScale(SalesRules.MONEY_SCALE),
                compensation, "DISPOSED");
    }

    // =================================================================== B/C 级：全额计损 + 不合格隔离

    private SalesResult writeOff(ReturnHead head, List<ReversalLayer> layers, int qty, String grade,
                                 String returnPeriod, boolean crossPeriod, String originalPeriod,
                                 BigDecimal compensation, BigDecimal refund, String requestId, String siteCode,
                                 Long actor, LocalDateTime now)
    {
        List<LossAggregate> aggregates = allocate(layers, qty);
        if (aggregates.isEmpty()) throw new IllegalStateException("RETURN_LOSS 分配为空，事务应回滚");

        BigDecimal totalLoss = BigDecimal.ZERO;
        for (LossAggregate loss : aggregates)
        {
            // 语义幂等：同键损益流水已存在但状态未推进 → 保守拒绝，交人工核查（不静默覆盖）。
            int existing = countLossRows(head.id(), grade, loss.batchId());
            if (existing > 0)
                throw invalid(head.id(), grade, "LOSS_KEY_CONFLICT",
                        "已存在同键 RETURN_LOSS 流水（batchId=" + loss.batchId() + "），状态不一致，需人工核查");

            BigDecimal amount = SalesRules.money(loss.unitCost().multiply(BigDecimal.valueOf(loss.qty())));
            totalLoss = totalLoss.add(amount);
            jdbc.update("INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,"
                            + "sal_order_item_id,qty,unit_cost,amount,consumed_at,move_type,sign,reversal_of_id,"
                            + "sal_return_id,period,cross_period,raw_payload,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?)",
                    "RL:" + java.util.UUID.randomUUID(), lossKey(head.id(), grade), loss.batchId(),
                    head.skuId(), head.salOrderItemId(), loss.qty(), loss.unitCost(), amount,
                    Timestamp.valueOf(now), MOVE_TYPE_LOSS, loss.originalId(), head.id(), returnPeriod,
                    crossPeriod ? 1 : 0, lossPayload(head.id(), grade, loss.qty()), Timestamp.valueOf(now));

            // 不合格库存隔离：按原批次仓库锁行累加，CHECK(qty_unsellable>=0) 由真库保证。
            addUnsellable(siteCode, head.skuId(), loss.warehouseId(), loss.qty(), now);
        }

        return new SalesResult(requestId, ACTION, siteCode, false, head.id(), null, head.salOrderItemId(),
                head.id(), null, List.of(), grade, null, SalesRules.money(totalLoss), compensation, "DISPOSED");
    }

    /**
     * 处置数量在"原消耗层"间贪心分配（按红冲行 id 升序）。
     *
     * <p>合并键取 {@code batchId}（不是 originalId）：唯一键是 {@code (biz_event_key, batch_id)}，
     * 同一批次若被同订单行的多次出库事件分别消耗（不同 originalId），按 originalId 分组合并会产生
     * 两行同键流水撞唯一键。同批次必然同单位成本，按批次合并既不丢成本口径也不撞键。
     */
    private static List<LossAggregate> allocate(List<ReversalLayer> layers, int qty)
    {
        Map<Long, LossAggregate> merged = new LinkedHashMap<>();
        int remaining = qty;
        for (ReversalLayer layer : layers)
        {
            if (remaining <= 0) break;
            int take = Math.min(remaining, layer.qty());
            if (take <= 0) continue;
            LossAggregate existing = merged.get(layer.batchId());
            if (existing == null)
                merged.put(layer.batchId(), new LossAggregate(layer.originalId(), layer.batchId(),
                        layer.warehouseId(), layer.unitCost(), take));
            else
                merged.put(layer.batchId(), new LossAggregate(existing.originalId(), existing.batchId(),
                        existing.warehouseId(), existing.unitCost(), existing.qty() + take));
            remaining -= take;
        }
        if (remaining > 0) throw new IllegalStateException("处置数量未能分配到原消耗层，事务应回滚");
        return new ArrayList<>(merged.values());
    }

    // =================================================================== 读取

    /** 已处置退货单的 ACK 结果：从数据库真实字段重建（不缓存失败、不重复写入）。 */
    private static SalesResult resultOfDisposed(ReturnHead head, String requestId, boolean replayed)
    {
        BigDecimal loss = head.returnLossAmount() == null
                ? BigDecimal.ZERO.setScale(SalesRules.MONEY_SCALE) : head.returnLossAmount();
        return new SalesResult(requestId, ACTION, head.siteCode(), replayed, head.id(), null,
                head.salOrderItemId(), head.id(), null, List.of(), head.grade(), head.restockBatchNo(),
                loss, head.compensationAmount(), "DISPOSED");
    }

    private record ReturnHead(Long id, String siteCode, Long skuId, Long salOrderItemId, int qty, String grade,
                              String status, BigDecimal compensationAmount, BigDecimal refundAmount,
                              BigDecimal returnLossAmount, String restockBatchNo, String period,
                              String originalPeriod) { }

    private ReturnHead lockReturn(Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,site_code,sku_id,sal_order_item_id,qty,grade,status,compensation_amount,refund_amount,"
                        + "return_loss_amount,restock_batch_no,period,original_period FROM sal_return "
                        + "WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new ReturnHead(((Number) row.get("id")).longValue(),
                (String) row.get("site_code"),
                ((Number) row.get("sku_id")).longValue(),
                row.get("sal_order_item_id") == null ? null : ((Number) row.get("sal_order_item_id")).longValue(),
                ((Number) row.get("qty")).intValue(),
                (String) row.get("grade"),
                (String) row.get("status"),
                (BigDecimal) row.get("compensation_amount"),
                (BigDecimal) row.get("refund_amount"),
                (BigDecimal) row.get("return_loss_amount"),
                (String) row.get("restock_batch_no"),
                (String) row.get("period"),
                (String) row.get("original_period"));
    }

    /** 一条红冲层：红冲行 → 原 SALE 消耗行 → 原批次（仓库/币种/汇率）。 */
    private record ReversalLayer(Long reversalId, Long originalId, Long batchId, Long warehouseId, Long skuId,
                                 Long itemId, int qty, BigDecimal unitCost, BigDecimal amount, String period,
                                 String currency, BigDecimal fxRate) { }

    /**
     * 锁本退货单全部红冲行（sign=-1 / RETURN_REVERSAL，按 id 升序固定锁序），逐个锁原 SALE 行并校验
     * {@code sign=+1 && move_type='SALE'}、SKU/订单行一致，再读原批次的仓库/币种/汇率。
     */
    private List<ReversalLayer> lockReversalLayers(ReturnHead head)
    {
        List<Map<String, Object>> reversals = jdbc.queryForList(
                "SELECT id,reversal_of_id,batch_id,sku_id,sal_order_item_id,qty,unit_cost,amount "
                        + "FROM inv_batch_consumption "
                        + "WHERE sal_return_id=? AND sign=-1 AND move_type=? ORDER BY id FOR UPDATE",
                head.id(), MOVE_TYPE_REVERSAL);
        List<ReversalLayer> layers = new ArrayList<>();
        for (Map<String, Object> reversal : reversals)
        {
            Long originalId = toLong(reversal.get("reversal_of_id"));
            if (originalId == null)
                throw invalid(head.id(), head.grade(), "REVERSAL_NOT_LINKED",
                        "红冲行 " + reversal.get("id") + " 未指向原 SALE 消耗行，无法判定成本层");
            List<Map<String, Object>> originals = jdbc.queryForList(
                    "SELECT id,batch_id,sku_id,sal_order_item_id,qty,unit_cost,amount,period,move_type,"
                            + "(sign + 0) AS sign_value FROM inv_batch_consumption WHERE id=? FOR UPDATE",
                    originalId);
            if (originals.size() != 1)
                throw invalid(head.id(), head.grade(), "ORIGINAL_CONSUMPTION_MISSING",
                        "原 SALE 消耗行 " + originalId + " 不存在，禁止臆造成本层");
            Map<String, Object> original = originals.get(0);
            if (SalesRules.toInt(original.get("sign_value")) != 1 || !MOVE_TYPE_SALE.equals(original.get("move_type")))
                throw invalid(head.id(), head.grade(), "ORIGINAL_NOT_SALE",
                        "原消耗行 " + originalId + " 不是 sign=+1 的 SALE 正常扣减");
            Long originalSku = toLong(original.get("sku_id"));
            if (!Objects.equals(originalSku, head.skuId()))
                throw invalid(head.id(), head.grade(), "ITEM_MISMATCH",
                        "原消耗行 SKU " + originalSku + " 与退货单 SKU " + head.skuId() + " 不一致");
            Long originalItem = toLong(original.get("sal_order_item_id"));
            if (head.salOrderItemId() != null && !Objects.equals(originalItem, head.salOrderItemId()))
                throw invalid(head.id(), head.grade(), "ITEM_MISMATCH",
                        "原消耗行订单行 " + originalItem + " 与退货单订单行 " + head.salOrderItemId() + " 不一致");

            Long batchId = toLong(original.get("batch_id"));
            List<Map<String, Object>> batches = jdbc.queryForList(
                    "SELECT warehouse_id,currency,fx_rate FROM inv_batch WHERE id=?", batchId);
            if (batches.size() != 1)
                throw invalid(head.id(), head.grade(), "SOURCE_BATCH_MISSING",
                        "原批次 " + batchId + " 不存在，无法判定仓库/币种口径");
            Map<String, Object> batch = batches.get(0);
            layers.add(new ReversalLayer(toLong(reversal.get("id")), originalId, batchId,
                    toLong(batch.get("warehouse_id")), originalSku, originalItem,
                    ((Number) reversal.get("qty")).intValue(),
                    (BigDecimal) reversal.get("unit_cost"),
                    (BigDecimal) reversal.get("amount"),
                    (String) original.get("period"),
                    (String) batch.get("currency"),
                    (BigDecimal) batch.get("fx_rate")));
        }
        return layers;
    }

    // =================================================================== 台账

    private void addAvailable(String siteCode, Long skuId, Long warehouseId, int qty, LocalDateTime now)
    {
        int updated = jdbc.update("UPDATE inv_stock_ledger SET qty_available=qty_available+?, updated_at=? "
                        + "WHERE sku_id=? AND warehouse_id=? AND site_code=?",
                qty, Timestamp.valueOf(now), skuId, warehouseId, siteCode);
        if (updated == 0)
            jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,qty_in_transit,"
                            + "qty_reserved,qty_unsellable,updated_at) VALUES(?,?,?,?,0,0,0,?)",
                    skuId, warehouseId, siteCode, qty, Timestamp.valueOf(now));
    }

    private void addUnsellable(String siteCode, Long skuId, Long warehouseId, int qty, LocalDateTime now)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,qty_unsellable FROM inv_stock_ledger WHERE sku_id=? AND warehouse_id=? AND site_code=? "
                        + "FOR UPDATE", skuId, warehouseId, siteCode);
        if (rows.isEmpty())
        {
            jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,qty_in_transit,"
                            + "qty_reserved,qty_unsellable,updated_at) VALUES(?,?,?,0,0,0,?,?)",
                    skuId, warehouseId, siteCode, qty, Timestamp.valueOf(now));
            return;
        }
        long ledgerId = ((Number) rows.get(0).get("id")).longValue();
        int updated = jdbc.update("UPDATE inv_stock_ledger SET qty_unsellable=qty_unsellable+?, updated_at=? "
                + "WHERE id=? AND qty_unsellable+?>=0", qty, Timestamp.valueOf(now), ledgerId, qty);
        if (updated != 1)
            throw new IllegalStateException("不合格库存隔离写入失败（ledger=" + ledgerId + "），事务整体回滚");
    }

    // =================================================================== 幂等键 / 小工具

    private static String lossKey(Long salReturnId, String grade)
    {
        return "RTNDISP:" + salReturnId + ":" + grade;
    }

    private int countLossRows(Long salReturnId, String grade, Long batchId)
    {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM inv_batch_consumption "
                + "WHERE biz_event_key=? AND batch_id=? AND sign=1", Integer.class, lossKey(salReturnId, grade), batchId);
        return count == null ? 0 : count;
    }

    private static String lossPayload(Long salReturnId, String grade, int qty)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("sal_return_id", salReturnId);
        node.put("grade", grade);
        node.put("qty", qty);
        node.put("reason", "RETURN_LOSS_FULL_COST");
        return node.toString();
    }

    private static String grade(String value, Long salReturnId)
    {
        if (value == null || value.isBlank())
            throw invalid(salReturnId, null, "GRADE_REQUIRED", "grade 必填（A/B/C，人工质检结果）");
        String grade = value.trim().toUpperCase(Locale.ROOT);
        if (!GRADES.contains(grade))
            throw invalid(salReturnId, grade, "GRADE_INVALID", "grade 只允许 A/B/C");
        return grade;
    }

    private static ReturnDispositionException invalid(Long salReturnId, String grade, String reasonCode, String message)
    {
        return new ReturnDispositionException(salReturnId, grade, reasonCode, message);
    }

    /** 金额单次登记：已登记（非 0）且本次不同 → 400；同值允许（幂等）。 */
    private static void requireSingleRegistration(String label, BigDecimal stored, BigDecimal incoming,
                                                  Long salReturnId, String grade, String reasonCode)
    {
        if (incoming == null) return;
        if (stored != null && stored.signum() != 0 && stored.compareTo(incoming) != 0)
            throw invalid(salReturnId, grade, reasonCode,
                    label + "已登记 " + stored.toPlainString() + "，本次 " + incoming.toPlainString()
                            + " 不同，禁止重复确认（C-2b 防双计）");
    }

    private static String firstPeriod(List<ReversalLayer> layers)
    {
        return layers.isEmpty() ? null : layers.get(0).period();
    }

    private static Long toLong(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 一条 RETURN_LOSS 聚合：原消耗行身份 + 原批次/仓库 + 原层成本 + 本次数量。 */
    private record LossAggregate(Long originalId, Long batchId, Long warehouseId, BigDecimal unitCost, int qty) { }
}
