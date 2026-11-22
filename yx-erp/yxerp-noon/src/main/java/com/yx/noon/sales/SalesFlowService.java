package com.yx.noon.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.fifo.FifoConsumptionService;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryInput;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryLine;
import com.yx.noon.intake.IntakeService;
import com.yx.noon.inventory.InventoryAudit;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.reversal.ReversalService;
import com.yx.noon.reversal.ReversalService.ReversalInput;

/**
 * M5 订单与退货编排闭环（契约 §2–§5，owner：豆包）。把已复核的内部闸门串成 HTTP 可调用闭环：
 *
 * <pre>
 * importOrder   → 登记 ORDER 事件         → IntakeService.postOrder   → 建订单行 → DONE
 * deliverOrder  → 登记 SALE_DELIVERY 事件 → FifoConsumptionService.postDelivery
 * importReturn  → 登记 RETURN 事件        → IntakeService.postReturn  → qty_returned 累计
 * reverseReturn → 登记 RETURN_REVERSAL   → ReversalService.postReversal
 * disposeReturn → ReturnDispositionService（A/B/C 分级处置）
 * </pre>
 *
 * <p><b>编排层只做四件事</b>：① 把 HTTP 入参翻译成内部服务入参；② 为每个动作写系统侧事件留痕
 * {@code int_webhook_event}（受控事件登记，模拟 M9 接收；真实平台事件类型适配属 M9 未确认范围，
 * 本批不臆造）；③ 动作级幂等（{@code noon_m5_sales_request}）；④ 事务边界 + 错误映射。
 * 它<b>不</b>绕过内部闸门、不伪造平台身份、不自行判定来源可核验、不吞隔离结果。
 *
 * <p><b>事件 payload 与 rawPayload 逐字一致</b>：payload 由本服务按内部服务 verify 字段生成，
 * 同一字符串既写 {@code int_webhook_event.payload_raw} 又作为内部服务 {@code rawPayload}，
 * 内部服务的"报文内容一致"校验必然通过，字段比对仍由内部服务独立完成（本服务不预判可信性）。
 *
 * <p><b>隔离（NEEDS_REVIEW）语义</b>：内部闸门返回隔离时，本服务<b>不</b>当成功、不缓存结果：
 * 释放 PENDING 占位（同 requestId 可重试）→ 置事件 {@code processed_at}（D8 留痕）→ 抛
 * {@link SalesFlowException} 携带 {@code reasonCode}/{@code reviewNo}（映射 409）。事务因
 * {@code noRollbackFor} 提交，隔离记录（{@code noon_*_review}）与事件留痕保留，可人工核对。
 *
 * <p><b>订单行</b>：内部 {@code IntakeService.postOrder} 只写 {@code sal_order}（订单头，口径为
 * "一单一 SKU"）。FIFO 出库与退货入账都要求 {@code sal_order_item} 父行，故本服务在同一事务内按
 * 订单头真实值建一行订单行（{@code unit_price = gross_amount/qty}，4 位 HALF_UP），不臆造平台行号
 * （{@code platform_item_no=NULL}，不声称平台事实）。
 */
@Service
public class SalesFlowService
{
    static final String ACTION_ORDER_IMPORT = "ORDER_IMPORT";
    static final String ACTION_ORDER_DELIVER = "ORDER_DELIVER";
    static final String ACTION_RETURN_IMPORT = "RETURN_IMPORT";
    static final String ACTION_RETURN_REVERSE = "RETURN_REVERSE";
    static final String ACTION_RETURN_DISPOSE = "RETURN_DISPOSE";

    /** 系统侧内部事件类型（非 Noon 平台原生类型；与内部服务契约一致）。 */
    static final String EVENT_ORDER = "ORDER";
    static final String EVENT_SALE_DELIVERY = "SALE_DELIVERY";
    static final String EVENT_RETURN = "RETURN";
    static final String EVENT_RETURN_REVERSAL = "RETURN_REVERSAL";

    /** 事件初始状态（契约 §4）：ORDER/SALE_DELIVERY = RECEIVED；RETURN/RETURN_REVERSAL = PROCESSED。 */
    private static final String STATUS_RECEIVED = "RECEIVED";
    private static final String STATUS_PROCESSED = "PROCESSED";

    private static final String BIZ_TYPE = "SALES_FLOW";
    private static final String DEFAULT_CHANNEL = "WEBHOOK";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;
    private final IntakeService intake;
    private final FifoConsumptionService fifo;
    private final ReversalService reversal;
    private final ReturnDispositionService disposition;

    public SalesFlowService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit,
                           IntakeService intake, FifoConsumptionService fifo, ReversalService reversal,
                           ReturnDispositionService disposition)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.intake = intake;
        this.fifo = fifo;
        this.reversal = reversal;
        this.disposition = disposition;
    }

    // =================================================================== 订单导入

    /**
     * 订单导入：授权 → 指纹 → 幂等前置 → 登记 ORDER 事件 → {@code postOrder} → 建订单行 → DONE。
     * 隔离（NEEDS_REVIEW）释放幂等占位、保留事件与隔离记录，并抛 {@link SalesFlowException}（409）。
     */
    @Transactional(noRollbackFor = SalesFlowException.class)
    public SalesResult importOrder(OrderImportInput in)
    {
        if (in == null) throw new IllegalArgumentException("订单导入请求不能为空");
        String requestId = SalesRules.requireRequestId(in.requestId());
        Long actor = guard.requireCurrentUserId();
        String siteCode = SalesRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        requireOrderStructure(in);

        BigDecimal grossAmount = SalesRules.money(in.grossAmount());
        String fingerprint = SalesRules.fingerprint(ACTION_ORDER_IMPORT, siteCode, in.orderNo(),
                in.storeId(), in.skuId(), in.partnerSku(), grossAmount, in.qty(), in.currency());

        SalesResult replayed = SalesIdempotency.replayDone(jdbc, requestId, ACTION_ORDER_IMPORT, fingerprint);
        if (replayed != null) return replayed;
        SalesResult claimed = SalesIdempotency.claim(jdbc, requestId, ACTION_ORDER_IMPORT, siteCode,
                actor, fingerprint);
        if (claimed != null) return claimed;

        String payload = orderPayload(in, grossAmount);
        String eventId = registerEvent(EVENT_ORDER, requestId, siteCode, payload, STATUS_RECEIVED);
        IntakeService.Result result = intake.postOrder(new IntakeService.OrderInput(
                siteCode, in.orderNo(), SalesRules.hasText(in.sourceChannel()) ? in.sourceChannel().trim() : DEFAULT_CHANNEL,
                eventId, null, in.partnerSku(), in.storeId(), in.skuId(), grossAmount, in.qty(), in.currency(),
                payload));
        markEventProcessed(eventId);
        if (!result.posted())
        {
            SalesIdempotency.release(jdbc, requestId, ACTION_ORDER_IMPORT);
            throw new SalesFlowException(ACTION_ORDER_IMPORT, requestId, result.reasonCode(), result.reviewNo(),
                    "订单导入未通过内部闸门（reasonCode=" + result.reasonCode()
                            + "），已进入待核对层，reviewNo=" + result.reviewNo());
        }

        long orderId = result.entityId();
        long orderItemId = ensureOrderItem(orderId, in, grossAmount, actor);
        SalesResult salesResult = new SalesResult(requestId, ACTION_ORDER_IMPORT, siteCode, false,
                orderId, orderId, orderItemId, null, null, List.of(), null, null, null, null,
                result.reasonCode());
        SalesIdempotency.markDone(jdbc, requestId, ACTION_ORDER_IMPORT, orderId, salesResult);
        audit.record(BIZ_TYPE, String.valueOf(orderId), ACTION_ORDER_IMPORT,
                "orderNo=" + in.orderNo() + ",itemId=" + orderItemId + ",event=" + eventId
                        + ",reason=" + result.reasonCode(), actor);
        return salesResult;
    }

    // =================================================================== 妥投出库

    /**
     * 妥投出库：锁订单行取真实 SKU → 登记 SALE_DELIVERY 事件 → {@code postDelivery}（FIFO 可拆多批）
     * → 按事件键回读全部 consumptionIds（内部服务只返回首个 id）。
     */
    @Transactional(noRollbackFor = SalesFlowException.class)
    public SalesResult deliverOrder(DeliverInput in)
    {
        if (in == null) throw new IllegalArgumentException("妥投出库请求不能为空");
        String requestId = SalesRules.requireRequestId(in.requestId());
        Long actor = guard.requireCurrentUserId();
        String siteCode = SalesRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        if (in.salOrderItemId() == null || in.salOrderItemId() <= 0)
            throw new IllegalArgumentException("salOrderItemId 必须为正整数");
        if (in.qty() == null || in.qty() <= 0)
            throw new IllegalArgumentException("妥投数量必须为正整数");

        String fingerprint = SalesRules.fingerprint(ACTION_ORDER_DELIVER, siteCode, in.salOrderItemId(), in.qty());
        SalesResult replayed = SalesIdempotency.replayDone(jdbc, requestId, ACTION_ORDER_DELIVER, fingerprint);
        if (replayed != null) return replayed;
        SalesResult claimed = SalesIdempotency.claim(jdbc, requestId, ACTION_ORDER_DELIVER, siteCode,
                actor, fingerprint);
        if (claimed != null) return claimed;

        OrderItemRef item = lockOrderItem(in.salOrderItemId());
        if (item == null) throw new IllegalArgumentException("订单行不存在: id=" + in.salOrderItemId());
        requireSameSite(siteCode, item.siteCode());

        String payload = deliveryPayload(in.salOrderItemId(), item.skuId(), in.qty());
        String eventId = registerEvent(EVENT_SALE_DELIVERY, requestId, siteCode, payload, STATUS_RECEIVED);
        FifoConsumptionService.Result result = fifo.postDelivery(new DeliveryInput(siteCode, eventId,
                in.salOrderItemId(), List.of(new DeliveryLine(item.skuId(), in.qty())), payload));
        markEventProcessed(eventId);
        if (!result.posted())
        {
            SalesIdempotency.release(jdbc, requestId, ACTION_ORDER_DELIVER);
            throw new SalesFlowException(ACTION_ORDER_DELIVER, requestId, result.reasonCode(), result.reviewNo(),
                    "妥投出库未通过内部闸门（reasonCode=" + result.reasonCode()
                            + "），已进入待核对层，reviewNo=" + result.reviewNo());
        }

        List<Long> consumptionIds = jdbc.queryForList(
                "SELECT id FROM inv_batch_consumption WHERE biz_event_key=? ORDER BY id",
                Long.class, "SALE:" + eventId);
        SalesResult salesResult = new SalesResult(requestId, ACTION_ORDER_DELIVER, siteCode, false,
                result.entityId(), item.salOrderId(), in.salOrderItemId(), null, null, consumptionIds,
                null, null, null, null, result.reasonCode());
        SalesIdempotency.markDone(jdbc, requestId, ACTION_ORDER_DELIVER, result.entityId(), salesResult);
        audit.record(BIZ_TYPE, String.valueOf(item.salOrderId()), ACTION_ORDER_DELIVER,
                "itemId=" + in.salOrderItemId() + ",qty=" + in.qty() + ",consumptions=" + consumptionIds
                        + ",event=" + eventId, actor);
        return salesResult;
    }

    // =================================================================== 退货入账

    /**
     * 退货入账：父行真实 站点/订单号/SKU → 登记 RETURN 事件 → {@code postReturn}
     * → 非 ACK 时累计 {@code sal_order_item.qty_returned}（不得超过已妥投量）。
     */
    @Transactional(noRollbackFor = SalesFlowException.class)
    public SalesResult importReturn(ReturnImportInput in)
    {
        if (in == null) throw new IllegalArgumentException("退货入账请求不能为空");
        String requestId = SalesRules.requireRequestId(in.requestId());
        Long actor = guard.requireCurrentUserId();
        String siteCode = SalesRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        if (in.salOrderItemId() == null || in.salOrderItemId() <= 0)
            throw new IllegalArgumentException("salOrderItemId 必须为正整数");
        if (in.qty() == null || in.qty() <= 0) throw new IllegalArgumentException("退货数量必须为正整数");
        if (!SalesRules.hasText(in.sourceReturnNo()))
            throw new IllegalArgumentException("sourceReturnNo（平台退货单号）不能为空，禁止臆造业务幂等键");
        if (!SalesRules.hasText(in.orderNo())) throw new IllegalArgumentException("orderNo 不能为空");

        String fingerprint = SalesRules.fingerprint(ACTION_RETURN_IMPORT, siteCode, in.orderNo(),
                in.sourceReturnNo(), in.salOrderItemId(), in.qty());
        SalesResult replayed = SalesIdempotency.replayDone(jdbc, requestId, ACTION_RETURN_IMPORT, fingerprint);
        if (replayed != null) return replayed;
        SalesResult claimed = SalesIdempotency.claim(jdbc, requestId, ACTION_RETURN_IMPORT, siteCode,
                actor, fingerprint);
        if (claimed != null) return claimed;

        OrderItemRef item = lockOrderItem(in.salOrderItemId());
        if (item == null) throw new IllegalArgumentException("订单行不存在: id=" + in.salOrderItemId());
        requireSameSite(siteCode, item.siteCode());
        if (!in.orderNo().equals(item.orderNo()))
            throw new IllegalArgumentException("退货订单号与订单行父订单不一致: " + in.orderNo()
                    + " vs " + item.orderNo());

        String payload = returnPayload(item.orderNo(), in.sourceReturnNo(), in.qty(), in.salOrderItemId());
        String eventId = registerEvent(EVENT_RETURN, requestId, siteCode, payload, STATUS_PROCESSED);
        IntakeService.Result result = intake.postReturn(new IntakeService.ReturnInput(siteCode, item.orderNo(),
                eventId, in.sourceReturnNo(), in.salOrderItemId(), in.qty(), payload));
        markEventProcessed(eventId);
        if (!result.posted())
        {
            SalesIdempotency.release(jdbc, requestId, ACTION_RETURN_IMPORT);
            throw new SalesFlowException(ACTION_RETURN_IMPORT, requestId, result.reasonCode(), result.reviewNo(),
                    "退货入账未通过内部闸门（reasonCode=" + result.reasonCode()
                            + "），已进入待核对层，reviewNo=" + result.reviewNo());
        }

        // 只有"确属新增"才累计 qty_returned：同退货号重传（ALREADY_POSTED）不得二次累计。
        if (!"ALREADY_POSTED".equals(result.reasonCode()))
        {
            int updated = jdbc.update("UPDATE sal_order_item SET qty_returned=qty_returned+? "
                    + "WHERE id=? AND qty_returned+?<=qty_delivered", in.qty(), in.salOrderItemId(), in.qty());
            if (updated != 1)
                throw new IllegalArgumentException("退货数量超过已妥投数量（订单行 " + in.salOrderItemId()
                        + "，本次 " + in.qty() + "），拒绝入账");
        }

        SalesResult salesResult = new SalesResult(requestId, ACTION_RETURN_IMPORT, siteCode, false,
                result.entityId(), item.salOrderId(), in.salOrderItemId(), result.entityId(), null,
                List.of(), null, null, null, null, result.reasonCode());
        SalesIdempotency.markDone(jdbc, requestId, ACTION_RETURN_IMPORT, result.entityId(), salesResult);
        audit.record(BIZ_TYPE, String.valueOf(item.salOrderId()), ACTION_RETURN_IMPORT,
                "returnId=" + result.entityId() + ",returnNo=" + in.sourceReturnNo() + ",qty=" + in.qty()
                        + ",event=" + eventId, actor);
        return salesResult;
    }

    // =================================================================== 退货红冲

    /**
     * 退货红冲：父退货真实 SKU → 登记 RETURN_REVERSAL 事件 → {@code postReversal}
     * （内部服务校验原行为 sign=+1 && move_type='SALE'、累计不超原消耗）。
     */
    @Transactional(noRollbackFor = SalesFlowException.class)
    public SalesResult reverseReturn(ReturnReverseInput in)
    {
        if (in == null) throw new IllegalArgumentException("退货红冲请求不能为空");
        String requestId = SalesRules.requireRequestId(in.requestId());
        Long actor = guard.requireCurrentUserId();
        String siteCode = SalesRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        if (in.salReturnId() == null || in.salReturnId() <= 0)
            throw new IllegalArgumentException("salReturnId 必须为正整数");
        if (in.originalConsumptionId() == null || in.originalConsumptionId() <= 0)
            throw new IllegalArgumentException("originalConsumptionId 必须为正整数");
        if (in.qty() == null || in.qty() <= 0) throw new IllegalArgumentException("红冲数量必须为正整数");

        String fingerprint = SalesRules.fingerprint(ACTION_RETURN_REVERSE, siteCode, in.salReturnId(),
                in.originalConsumptionId(), in.qty());
        SalesResult replayed = SalesIdempotency.replayDone(jdbc, requestId, ACTION_RETURN_REVERSE, fingerprint);
        if (replayed != null) return replayed;
        SalesResult claimed = SalesIdempotency.claim(jdbc, requestId, ACTION_RETURN_REVERSE, siteCode,
                actor, fingerprint);
        if (claimed != null) return claimed;

        ReturnRef ret = lockReturnForReverse(in.salReturnId());
        if (ret == null) throw new IllegalArgumentException("退货单不存在: id=" + in.salReturnId());
        requireSameSite(siteCode, ret.siteCode());

        String payload = reversalPayload(in.salReturnId(), in.originalConsumptionId(), in.qty(), ret.skuId());
        String eventId = registerEvent(EVENT_RETURN_REVERSAL, requestId, siteCode, payload, STATUS_PROCESSED);
        ReversalService.Result result = reversal.postReversal(new ReversalInput(siteCode, eventId,
                in.salReturnId(), in.originalConsumptionId(), in.qty(), payload));
        markEventProcessed(eventId);
        if (!result.posted())
        {
            SalesIdempotency.release(jdbc, requestId, ACTION_RETURN_REVERSE);
            throw new SalesFlowException(ACTION_RETURN_REVERSE, requestId, result.reasonCode(), result.reviewNo(),
                    "退货红冲未通过内部闸门（reasonCode=" + result.reasonCode()
                            + "），已进入待核对层，reviewNo=" + result.reviewNo());
        }

        SalesResult salesResult = new SalesResult(requestId, ACTION_RETURN_REVERSE, siteCode, false,
                result.entityId(), null, ret.salOrderItemId(), in.salReturnId(), result.entityId(),
                List.of(), null, null, null, null, result.reasonCode());
        SalesIdempotency.markDone(jdbc, requestId, ACTION_RETURN_REVERSE, result.entityId(), salesResult);
        audit.record(BIZ_TYPE, String.valueOf(in.salReturnId()), ACTION_RETURN_REVERSE,
                "reversalId=" + result.entityId() + ",original=" + in.originalConsumptionId()
                        + ",qty=" + in.qty() + ",event=" + eventId, actor);
        return salesResult;
    }

    // =================================================================== 分级处置

    /** A/B/C 分级处置：委托 {@link ReturnDispositionService}（授权/幂等/锁序/事务均在该服务内）。 */
    public SalesResult disposeReturn(DisposeInput in)
    {
        if (in == null) throw new IllegalArgumentException("分级处置请求不能为空");
        return disposition.dispose(in);
    }

    // =================================================================== 幂等结果回读

    /** 结果回读（先授权：读到行后按行内站点 {@code requireSite}；PENDING 视为处理中）。 */
    public SalesResult resultOf(String requestId, String action)
    {
        guard.requireCurrentUserId();
        String id = SalesRules.requireRequestId(requestId);
        if (!SalesRules.hasText(action)) throw new IllegalArgumentException("action 不能为空");
        String normalized = action.trim().toUpperCase(Locale.ROOT);
        SalesIdempotency.RequestRow row = SalesIdempotency.load(jdbc, id, normalized);
        guard.requireSite(row.siteCode());
        if (!SalesIdempotency.DONE.equals(row.status()))
            throw new IllegalArgumentException("请求正在处理中: requestId=" + id + "，action=" + normalized);
        return SalesIdempotency.readResult(row.resultJson());
    }

    // =================================================================== 只读清单

    /** 订单列表：站点授权过滤（未指定 siteCode 时取当前用户全部授权站点），空集合必产生零行。 */
    public PageResult<SalesOrderRow> listOrders(String siteCode, int offset, int limit, String orderNo, String status)
    {
        guard.requireCurrentUserId();
        List<String> sites = resolveSites(siteCode);
        SalesRules.offset(offset);
        int lim = SalesRules.limit(limit);
        if (sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE site_code IN (" + SalesRules.placeholders(sites.size()) + ")");
        List<Object> args = new ArrayList<>(sites);
        if (SalesRules.hasText(orderNo))
        {
            where.append(" AND order_no=?");
            args.add(orderNo.trim());
        }
        if (SalesRules.hasText(status))
        {
            where.append(" AND status=?");
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sal_order" + where, Long.class, args.toArray());
        args.add(lim);
        args.add(offset);
        List<SalesOrderRow> rows = jdbc.query("SELECT id,order_no,site_code,store_id,partner_sku,sku_id,qty,"
                + "gross_amount,currency,source_channel,status,created_at FROM sal_order" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?", ORDER_ROW, args.toArray());
        return PageResult.of(rows, total);
    }

    /** 订单详情：订单头 + 明细行；不存在返回 null；站点不属于当前用户 → 403。 */
    public SalesOrderDetail getOrder(Long id)
    {
        guard.requireCurrentUserId();
        if (id == null || id <= 0) throw new IllegalArgumentException("id 必须为正整数");
        List<SalesOrderRow> rows = jdbc.query("SELECT id,order_no,site_code,store_id,partner_sku,sku_id,qty,"
                + "gross_amount,currency,source_channel,status,created_at FROM sal_order WHERE id=?",
                ORDER_ROW, id);
        if (rows.isEmpty()) return null;
        SalesOrderRow row = rows.get(0);
        guard.requireSite(row.siteCode());
        List<SalesOrderItemRow> items = jdbc.query("SELECT id,sal_order_id,order_no,sku_id,qty,qty_delivered,"
                + "qty_returned,qty_cancelled,unit_price FROM sal_order_item WHERE sal_order_id=? ORDER BY id",
                ITEM_ROW, id);
        return new SalesOrderDetail(row, items);
    }

    /** 退货列表：站点授权过滤 + 可选 orderNo / grade 筛选。 */
    public PageResult<SalesReturnRow> listReturns(String siteCode, int offset, int limit, String orderNo, String grade)
    {
        guard.requireCurrentUserId();
        List<String> sites = resolveSites(siteCode);
        SalesRules.offset(offset);
        int lim = SalesRules.limit(limit);
        if (sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE site_code IN (" + SalesRules.placeholders(sites.size()) + ")");
        List<Object> args = new ArrayList<>(sites);
        if (SalesRules.hasText(orderNo))
        {
            where.append(" AND order_no=?");
            args.add(orderNo.trim());
        }
        if (SalesRules.hasText(grade))
        {
            where.append(" AND grade=?");
            args.add(grade.trim().toUpperCase(Locale.ROOT));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM sal_return" + where, Long.class, args.toArray());
        args.add(lim);
        args.add(offset);
        List<SalesReturnRow> rows = jdbc.query(RETURN_SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                RETURN_ROW, args.toArray());
        return PageResult.of(rows, total);
    }

    /** 退货详情：退货头 + 处置链路流水（红冲 sign=-1 与 B/C RETURN_LOSS）。 */
    public SalesReturnDetail getReturn(Long id)
    {
        guard.requireCurrentUserId();
        if (id == null || id <= 0) throw new IllegalArgumentException("id 必须为正整数");
        List<SalesReturnRow> rows = jdbc.query(RETURN_SELECT + " WHERE id=?", RETURN_ROW, id);
        if (rows.isEmpty()) return null;
        SalesReturnRow row = rows.get(0);
        guard.requireSite(row.siteCode());
        List<SalesReturnFlowRow> flows = jdbc.query("SELECT id,source_move_type,source_sign,qty,unit_cost,amount,"
                + "biz_event_key,reversal_of_id,batch_id,period,cross_period,consumed_at FROM ("
                + " SELECT id,move_type AS source_move_type,(sign+0) AS source_sign,qty,unit_cost,amount,"
                + "biz_event_key,reversal_of_id,batch_id,period,(cross_period+0) AS cross_period,consumed_at"
                + " FROM inv_batch_consumption WHERE sal_return_id=?"
                + ") flows ORDER BY id", FLOW_ROW, id);
        return new SalesReturnDetail(row, flows);
    }

    // =================================================================== 内部：事件登记

    /**
     * 受控事件登记（契约 §4）：{@code eventId=<EVENT_TYPE>:<requestId>}，requestId 派生保证同动作重放
     * 命中同一事件（D1）；重复登记按唯一键 {@code uk_event_id} 兜底，并校验类型/站点/报文一致，
     * 不一致即 400（同 requestId 换事件内容）。
     */
    private String registerEvent(String eventType, String requestId, String siteCode, String payload, String status)
    {
        String eventId = eventType + ":" + requestId;
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT event_type,site_code,payload_raw FROM int_webhook_event WHERE event_id=?", eventId);
        if (!existing.isEmpty())
        {
            requireSameEvent(existing.get(0), eventType, siteCode, payload, eventId);
            return eventId;
        }
        try
        {
            jdbc.update("INSERT INTO int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                            + "VALUES(?,?,?,?,?,?)",
                    eventId, eventType, siteCode, payload, Timestamp.valueOf(SalesRules.now()), status);
        }
        catch (DuplicateKeyException concurrent)
        {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT event_type,site_code,payload_raw FROM int_webhook_event WHERE event_id=?", eventId);
            if (rows.isEmpty()) throw new IllegalStateException("事件登记并发冲突: " + eventId);
            requireSameEvent(rows.get(0), eventType, siteCode, payload, eventId);
        }
        return eventId;
    }

    private static void requireSameEvent(Map<String, Object> row, String eventType, String siteCode,
                                         String payload, String eventId)
    {
        if (!eventType.equals(row.get("event_type")) || !siteCode.equals(row.get("site_code"))
                || !sameJson(textOf(row.get("payload_raw")), payload))
            throw new IllegalArgumentException("eventId 已用于不同内容的事件: " + eventId);
    }

    /** 置 processed_at（D8）：隔离路径也留痕，便于人工核对"事件已处理但被隔离"。 */
    private void markEventProcessed(String eventId)
    {
        jdbc.update("UPDATE int_webhook_event SET processed_at=? WHERE event_id=?",
                Timestamp.valueOf(SalesRules.now()), eventId);
    }

    // =================================================================== 内部：payload

    private static String orderPayload(OrderImportInput in, BigDecimal grossAmount)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("order_no", in.orderNo());
        node.put("store_id", in.storeId());
        node.put("sku_id", in.skuId());
        node.put("partner_sku", in.partnerSku());
        node.put("gross_amount", grossAmount.toPlainString());
        node.put("qty", in.qty());
        node.put("currency", in.currency());
        return node.toString();
    }

    private static String deliveryPayload(Long salOrderItemId, Long skuId, int qty)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("sal_order_item_id", salOrderItemId);
        ArrayNode lines = node.putArray("lines");
        ObjectNode line = lines.addObject();
        line.put("sku_id", skuId);
        line.put("qty", qty);
        return node.toString();
    }

    private static String returnPayload(String orderNo, String returnNo, int qty, Long itemId)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("order_no", orderNo);
        node.put("return_no", returnNo);
        node.put("qty", qty);
        node.put("item_id", itemId);
        return node.toString();
    }

    private static String reversalPayload(Long salReturnId, Long originalConsumptionId, int qty, Long skuId)
    {
        ObjectNode node = JSON.createObjectNode();
        node.put("sal_return_id", salReturnId);
        node.put("reversal_of_id", originalConsumptionId);
        node.put("qty", qty);
        node.put("sku_id", skuId);
        return node.toString();
    }

    // =================================================================== 内部：订单行

    private record OrderItemRef(Long id, Long salOrderId, String siteCode, String orderNo, Long skuId) { }

    private record ReturnRef(Long id, String siteCode, Long skuId, Long salOrderItemId) { }

    /** 订单行 FOR UPDATE 当前读；不存在返回 null。 */
    private OrderItemRef lockOrderItem(Long id)
    {
        if (id == null || id <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.id,i.sal_order_id,i.order_no,i.sku_id,o.site_code FROM sal_order_item i "
                        + "JOIN sal_order o ON o.id=i.sal_order_id WHERE i.id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new OrderItemRef(((Number) row.get("id")).longValue(),
                ((Number) row.get("sal_order_id")).longValue(),
                (String) row.get("site_code"),
                (String) row.get("order_no"),
                ((Number) row.get("sku_id")).longValue());
    }

    /** 退货单 FOR UPDATE 当前读（红冲 payload 需父退货真实 sku）。 */
    private ReturnRef lockReturnForReverse(Long id)
    {
        if (id == null || id <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,site_code,sku_id,sal_order_item_id FROM sal_return WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new ReturnRef(((Number) row.get("id")).longValue(),
                (String) row.get("site_code"),
                ((Number) row.get("sku_id")).longValue(),
                row.get("sal_order_item_id") == null ? null : ((Number) row.get("sal_order_item_id")).longValue());
    }

    /** 跨站引用一律 403（即使调用方同时拥有两个站点权限，也不允许 A 站动作引用 B 站记录）。 */
    private static void requireSameSite(String requestedSite, String actualSite)
    {
        if (!requestedSite.equals(actualSite))
            throw new AccessDeniedException("跨站引用被拒绝：请求站点 " + requestedSite + "，记录站点 " + actualSite);
    }

    /**
     * 建订单行（内部 {@code postOrder} 只写订单头）：同订单已有行则复用，不重复建。
     * {@code unit_price = gross_amount / qty}（4 位 HALF_UP，仅作分摊展示；COGS 仍以批次层为准）。
     */
    private long ensureOrderItem(long orderId, OrderImportInput in, BigDecimal grossAmount, Long actor)
    {
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM sal_order_item WHERE sal_order_id=? ORDER BY id", Long.class, orderId);
        if (!existing.isEmpty()) return existing.get(0);

        BigDecimal unitPrice = grossAmount.divide(BigDecimal.valueOf(in.qty()), SalesRules.MONEY_SCALE,
                RoundingMode.HALF_UP);
        KeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,"
                            + "qty_delivered,qty_returned,qty_cancelled,unit_price,created_at) "
                            + "VALUES(?,?,?,?,?,0,0,0,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, orderId);
            ps.setString(2, in.orderNo());
            ps.setString(3, null); // 不臆造平台行号，部分妥投/退货按行数量表达
            ps.setLong(4, in.skuId());
            ps.setInt(5, in.qty());
            ps.setBigDecimal(6, unitPrice);
            ps.setTimestamp(7, Timestamp.valueOf(SalesRules.now()));
            return ps;
        }, generated);
        Number key = generated.getKey();
        if (key == null) throw new IllegalStateException("未取得订单行 ID");
        return key.longValue();
    }

    // =================================================================== 内部：其它

    private List<String> resolveSites(String siteCode)
    {
        if (SalesRules.hasText(siteCode))
        {
            String normalized = SalesRules.siteCode("siteCode", siteCode);
            guard.requireSite(normalized);
            return List.of(normalized);
        }
        return guard.allowedSiteCodes();
    }

    private static void requireOrderStructure(OrderImportInput in)
    {
        if (!SalesRules.hasText(in.orderNo())) throw new IllegalArgumentException("orderNo 不能为空");
        if (in.storeId() == null || in.storeId() <= 0) throw new IllegalArgumentException("storeId 必须为正整数");
        if (in.skuId() == null || in.skuId() <= 0) throw new IllegalArgumentException("skuId 必须为正整数");
        if (!SalesRules.hasText(in.partnerSku())) throw new IllegalArgumentException("partnerSku 不能为空");
        if (in.grossAmount() == null || in.grossAmount().signum() < 0)
            throw new IllegalArgumentException("grossAmount 不能为空且不得为负");
        if (in.qty() == null || in.qty() <= 0) throw new IllegalArgumentException("qty 必须为正整数");
        if (!SalesRules.hasText(in.currency())) throw new IllegalArgumentException("currency 不能为空");
    }

    private static final ObjectMapper JSON_PARSE = new ObjectMapper();

    private static String textOf(Object value)
    {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return value.toString();
    }

    /** 报文同内容判定：JSON 按字段递归排序后压缩再比较（兼容 MySQL JSON 列字段重排）。 */
    private static boolean sameJson(String left, String right)
    {
        if (left == null || right == null) return left == null && right == null;
        return sortedJson(left).equals(sortedJson(right));
    }

    private static String sortedJson(String raw)
    {
        try
        {
            JsonNode node = JSON_PARSE.readTree(raw);
            if (node == null) return raw.replaceAll("\\s+", "");
            return sort(node).toString();
        }
        catch (Exception notJson)
        {
            return raw.replaceAll("\\s+", "");
        }
    }

    private static JsonNode sort(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            java.util.Collections.sort(fields);
            for (String field : fields) sorted.set(field, sort(node.get(field)));
            return sorted;
        }
        if (node.isArray())
        {
            ArrayNode sorted = JSON.createArrayNode();
            node.forEach(element -> sorted.add(sort(element)));
            return sorted;
        }
        return node.deepCopy();
    }

    // =================================================================== 行映射

    private static final RowMapper<SalesOrderRow> ORDER_ROW = (rs, i) -> new SalesOrderRow(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getString("site_code"),
            rs.getLong("store_id"),
            rs.getString("partner_sku"),
            rs.getObject("sku_id") == null ? null : rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getBigDecimal("gross_amount"),
            rs.getString("currency"),
            rs.getString("source_channel"),
            rs.getString("status"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<SalesOrderItemRow> ITEM_ROW = (rs, i) -> new SalesOrderItemRow(
            rs.getLong("id"),
            rs.getLong("sal_order_id"),
            rs.getString("order_no"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getInt("qty_delivered"),
            rs.getInt("qty_returned"),
            rs.getInt("qty_cancelled"),
            rs.getBigDecimal("unit_price"));

    private static final String RETURN_SELECT = "SELECT id,return_no,site_code,order_no,sku_id,qty,grade,status,"
            + "compensation_amount,refund_amount,return_loss_amount,restock_batch_no,period,original_period,"
            + "sal_order_item_id,created_at FROM sal_return";

    private static final RowMapper<SalesReturnRow> RETURN_ROW = (rs, i) -> new SalesReturnRow(
            rs.getLong("id"),
            rs.getString("return_no"),
            rs.getString("site_code"),
            rs.getString("order_no"),
            rs.getLong("sku_id"),
            rs.getInt("qty"),
            rs.getString("grade"),
            rs.getString("status"),
            rs.getBigDecimal("compensation_amount"),
            rs.getBigDecimal("refund_amount"),
            rs.getBigDecimal("return_loss_amount"),
            rs.getString("restock_batch_no"),
            rs.getString("period"),
            rs.getString("original_period"),
            rs.getObject("sal_order_item_id") == null ? null : rs.getLong("sal_order_item_id"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<SalesReturnFlowRow> FLOW_ROW = (rs, i) -> new SalesReturnFlowRow(
            rs.getLong("id"),
            rs.getString("source_move_type"),
            rs.getInt("source_sign"),
            rs.getInt("qty"),
            rs.getBigDecimal("unit_cost"),
            rs.getBigDecimal("amount"),
            rs.getString("biz_event_key"),
            rs.getObject("reversal_of_id") == null ? null : rs.getLong("reversal_of_id"),
            rs.getObject("batch_id") == null ? null : rs.getLong("batch_id"),
            rs.getString("period"),
            rs.getInt("cross_period"),
            rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toLocalDateTime());
}
