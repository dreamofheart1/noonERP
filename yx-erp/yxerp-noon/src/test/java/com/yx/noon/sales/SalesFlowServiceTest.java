package com.yx.noon.sales;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import com.yx.noon.fifo.FifoConsumptionService;
import com.yx.noon.intake.IntakeService;
import com.yx.noon.masterdata.PageResult;
import com.yx.noon.reversal.ReversalService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 编排闭环 H2 验证：真实 Spring 事务 + H2(MySQL 模式) + 真站点 Mapper/Guard + 真实内部闸门
 * （{@code IntakeService} / {@code FifoConsumptionService} / {@code ReversalService}）。
 *
 * <p>覆盖：订单导入成功 + 动作幂等 + 跨站 403 + 隔离 409（事件与待核对留痕、幂等占位释放）、
 * 妥投 FIFO 拆批 + 累计守恒、退货入账 + 红冲累计不超原消耗/父退货、只读站点隔离、结果回读。
 */
class SalesFlowServiceTest
{
    private static final long WAREHOUSE = 21L;
    private static final long SKU = 11L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SalesFlowService flow;
    private IntakeService intake;
    private FifoConsumptionService fifo;
    private ReversalService reversal;

    @BeforeEach
    void setUp()
    {
        context = new AnnotationConfigApplicationContext(SalesTestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        SalesTestSchema.init(jdbc);
        SalesTestSchema.seedMasterData(jdbc);
        SalesTestSchema.grant(jdbc, 1L, "AE", "SA");
        SalesTestSchema.grant(jdbc, 2L, "AE");
        flow = context.getBean(SalesFlowService.class);
        intake = context.getBean(IntakeService.class);
        fifo = context.getBean(FifoConsumptionService.class);
        reversal = context.getBean(ReversalService.class);
        SalesTestSchema.login(1L);
    }

    @AfterEach
    void tearDown()
    {
        SalesTestSchema.clearAuth();
        if (context != null) context.close();
    }

    // ---------------------------------------------------------------- helpers

    private static OrderImportInput order(String requestId, String site, String orderNo, int qty,
                                          String amount, String currency)
    {
        return new OrderImportInput(requestId, site, orderNo, 7L, SKU, "SKU",
                new BigDecimal(amount), qty, currency, "WEBHOOK");
    }

    private long seedBatch(String site, long warehouse, int qty, String cost, String receivedAt)
    {
        String batchNo = "B:T-" + java.util.UUID.randomUUID();
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                        + "unit_cost,currency,fx_rate,source_type,status,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?, 'OPEN', current_timestamp)",
                batchNo, SKU, warehouse, site, Timestamp.valueOf(receivedAt), qty, qty, new BigDecimal(cost),
                site.equals("AE") ? "AED" : "SAR", BigDecimal.ONE, "PURCHASE");
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(String site, long warehouse, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (?,?,?,?)",
                SKU, warehouse, site, available);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    private long orderItemId(long orderId)
    {
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=?", Long.class, orderId);
    }

    // ---------------------------------------------------------------- ① 订单导入

    @Test
    void importOrderPostsOrderAndCreatesItemAndEvent()
    {
        SalesResult result = flow.importOrder(order("REQ-0001", "AE", "O-1", 10, "1500.0000", "AED"));

        assertFalse(result.replayed());
        assertEquals("POSTED", result.reasonCode());
        assertEquals(1, count("sal_order"));
        assertNotNull(result.orderId());
        assertNotNull(result.orderItemId());
        // 订单行为 FIFO/退货的父记录：真实 SKU、qty、分摊单价（1500/10=150，不臆造平台行号）
        assertEquals(10, jdbc.queryForObject("select qty from sal_order_item where id=?", Integer.class,
                result.orderItemId()));
        assertEquals(0, new BigDecimal("150.0000").compareTo(
                jdbc.queryForObject("select unit_price from sal_order_item where id=?", BigDecimal.class,
                        result.orderItemId())));
        assertNull(jdbc.queryForObject("select platform_item_no from sal_order_item where id=?", Object.class,
                result.orderItemId()));
        // 事件留痕：ORDER + RECEIVED + processed_at（本服务受控登记，非平台事实）
        Map<String, Object> event = jdbc.queryForMap(
                "select event_type,site_code,status,processed_at from int_webhook_event where event_id=?",
                "ORDER:REQ-0001");
        assertEquals("ORDER", event.get("event_type"));
        assertEquals("RECEIVED", event.get("status"));
        assertNotNull(event.get("processed_at"));
        assertEquals(1, count("noon_m4_inventory_audit"));
    }

    @Test
    void importOrderSameRequestIdReplaysWithoutSideEffects()
    {
        OrderImportInput in = order("REQ-0002", "AE", "O-2", 2, "300.0000", "AED");
        SalesResult first = flow.importOrder(in);
        SalesResult again = flow.importOrder(in);

        assertTrue(again.replayed());
        assertEquals(first.orderId(), again.orderId());
        assertEquals(1, count("sal_order"));
        assertEquals(1, count("sal_order_item"));
        assertEquals(1, count("int_webhook_event"));
        assertEquals(1, count("noon_m5_sales_request"));
    }

    @Test
    void importOrderCrossSiteIsDeniedNotQuarantined()
    {
        SalesTestSchema.login(2L); // 只授权 AE
        assertThrows(AccessDeniedException.class,
                () -> flow.importOrder(order("REQ-0003", "SA", "O-3", 1, "150.0000", "SAR")));
        assertEquals(0, count("sal_order"));
        assertEquals(0, count("int_webhook_event"));
    }

    @Test
    void importOrderQuarantineIsNotSuccessButKeepsEvidence()
    {
        flow.importOrder(order("REQ-0004", "AE", "O-4", 1, "150.0000", "AED"));
        // 同站同订单号但可信业务内容不同（数量 1→2）→ 内部闸门 SOURCE_CONFLICT 隔离
        SalesFlowException conflict = assertThrows(SalesFlowException.class,
                () -> flow.importOrder(order("REQ-0005", "AE", "O-4", 2, "300.0000", "AED")));

        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertNotNull(conflict.reviewNo());
        assertTrue(conflict.reviewNo().startsWith("RV:"));
        assertEquals(1, count("sal_order"), "冲突不得覆盖已入账订单");
        assertEquals(1, count("noon_intake_review"), "隔离记录必须保留");
        // D8：事件留痕保留且标 processed_at；幂等占位释放（失败不缓存）
        Map<String, Object> event = jdbc.queryForMap(
                "select status,processed_at from int_webhook_event where event_id=?", "ORDER:REQ-0005");
        assertNotNull(event.get("processed_at"));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from noon_m5_sales_request where request_id=?", Integer.class, "REQ-0005"));
    }

    // ---------------------------------------------------------------- ② 妥投出库

    @Test
    void deliverSplitsFifoLayersAndKeepsCumulativeConservation()
    {
        seedBatch("AE", WAREHOUSE, 5, "100.0000", "2026-11-01 10:00:00");
        seedBatch("AE", WAREHOUSE, 6, "120.0000", "2026-11-02 10:00:00");
        seedLedger("AE", WAREHOUSE, 11);

        SalesResult imported = flow.importOrder(order("REQ-0101", "AE", "O-101", 10, "1500.0000", "AED"));
        long itemId = imported.orderItemId();

        SalesResult delivered = flow.deliverOrder(new DeliverInput("REQ-0102", "AE", itemId, 6));
        assertEquals(2, delivered.consumptionIds().size(), "FIFO 应拆 5+1 两批");
        assertEquals(6, jdbc.queryForObject("select sum(qty) from inv_batch_consumption where biz_event_key=?",
                Integer.class, "SALE:SALE_DELIVERY:REQ-0102"));
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where unit_cost=100.0000",
                Integer.class));
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch where unit_cost=120.0000",
                Integer.class));
        assertEquals(5, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(6, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, itemId));

        SalesResult second = flow.deliverOrder(new DeliverInput("REQ-0103", "AE", itemId, 4));
        assertEquals(1, second.consumptionIds().size());
        assertEquals(1, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(10, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, itemId));

        // 全局守恒：批次入库合计 11 = 已出库 10 + 批剩余 1
        assertEquals(11, jdbc.queryForObject("select sum(qty_in) from inv_batch", Integer.class));
        assertEquals(11, jdbc.queryForObject("select sum(qty) from inv_batch_consumption where sign=1", Integer.class)
                + jdbc.queryForObject("select sum(qty_remaining) from inv_batch", Integer.class));

        // 再投 1 超过可投量 → 内部闸门隔离，不静默成功
        SalesFlowException exceeded = assertThrows(SalesFlowException.class,
                () -> flow.deliverOrder(new DeliverInput("REQ-0104", "AE", itemId, 1)));
        assertEquals("DELIVERY_EXCEEDS_ORDER", exceeded.reasonCode());
        assertEquals(1, count("noon_fifo_review"));
        assertEquals(10, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, itemId));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from noon_m5_sales_request where request_id=?", Integer.class, "REQ-0104"));
    }

    @Test
    void deliverCrossSiteItemIsDenied()
    {
        seedBatch("SA", 22L, 5, "100.0000", "2026-11-01 10:00:00");
        seedLedger("SA", 22L, 5);
        SalesResult imported = flow.importOrder(order("REQ-0201", "SA", "O-201", 5, "750.0000", "SAR"));

        SalesTestSchema.login(1L); // 同时有 AE/SA 权限，仍不允许跨站引用
        assertThrows(AccessDeniedException.class,
                () -> flow.deliverOrder(new DeliverInput("REQ-0202", "AE", imported.orderItemId(), 1)));
        assertEquals(0, count("inv_batch_consumption"));
    }

    // ---------------------------------------------------------------- ③ 退货 + 红冲

    @Test
    void returnAndReversalAccumulateWithoutExceedingOriginalOrReturn()
    {
        long batch1 = seedBatch("AE", WAREHOUSE, 2, "100.0000", "2026-11-01 10:00:00");
        seedBatch("AE", WAREHOUSE, 8, "100.0000", "2026-11-02 10:00:00");
        seedLedger("AE", WAREHOUSE, 10);

        SalesResult imported = flow.importOrder(order("REQ-0301", "AE", "O-301", 10, "1500.0000", "AED"));
        long itemId = imported.orderItemId();
        SalesResult delivered = flow.deliverOrder(new DeliverInput("REQ-0302", "AE", itemId, 10));
        assertEquals(2, delivered.consumptionIds().size());
        long originalOnBatch1 = jdbc.queryForObject(
                "select id from inv_batch_consumption where batch_id=? and sign=1", Long.class, batch1);
        long originalOnBatch2 = jdbc.queryForObject(
                "select id from inv_batch_consumption where batch_id<>? and sign=1", Long.class, batch1);

        SalesResult returned = flow.importReturn(new ReturnImportInput("REQ-0303", "AE", "O-301", "SR-301", itemId, 4));
        long returnId = returned.returnId();
        assertEquals(4, jdbc.queryForObject("select qty_returned from sal_order_item where id=?", Integer.class, itemId));

        SalesResult reverse1 = flow.reverseReturn(new ReturnReverseInput("REQ-0304", "AE", returnId, originalOnBatch1, 2));
        assertNotNull(reverse1.reversalId());
        assertEquals(-1, jdbc.queryForObject("select sign from inv_batch_consumption where id=?", Integer.class,
                reverse1.reversalId()));
        assertEquals(originalOnBatch1, jdbc.queryForObject("select reversal_of_id from inv_batch_consumption where id=?",
                Long.class, reverse1.reversalId()));

        flow.reverseReturn(new ReturnReverseInput("REQ-0305", "AE", returnId, originalOnBatch2, 2));
        assertEquals(4, jdbc.queryForObject("select sum(qty) from inv_batch_consumption where sal_return_id=? and sign=-1",
                Integer.class, returnId));

        // 已冲满原层 2 件，再冲 1 件 → REVERSAL_EXCEEDS_ORIGINAL 隔离（不写红冲行）
        SalesFlowException exceededOriginal = assertThrows(SalesFlowException.class,
                () -> flow.reverseReturn(new ReturnReverseInput("REQ-0306", "AE", returnId, originalOnBatch1, 1)));
        assertEquals("REVERSAL_EXCEEDS_ORIGINAL", exceededOriginal.reasonCode());
        assertEquals(4, count("inv_batch_consumption"), "2 条 SALE + 2 条红冲，隔离不得多写红冲行");

        // 退货累计超过已妥投：入账被整体回滚，不产生孤儿退货单
        assertThrows(IllegalArgumentException.class,
                () -> flow.importReturn(new ReturnImportInput("REQ-0307", "AE", "O-301", "SR-307", itemId, 7)));
        assertEquals(1, count("sal_return"));
        assertEquals(4, jdbc.queryForObject("select qty_returned from sal_order_item where id=?", Integer.class, itemId));
    }

    @Test
    void returnReplayWithNewRequestIdDoesNotDoubleCountQtyReturned()
    {
        seedBatch("AE", WAREHOUSE, 10, "100.0000", "2026-11-01 10:00:00");
        seedLedger("AE", WAREHOUSE, 10);
        SalesResult imported = flow.importOrder(order("REQ-0401", "AE", "O-401", 10, "1500.0000", "AED"));
        long itemId = imported.orderItemId();
        flow.deliverOrder(new DeliverInput("REQ-0402", "AE", itemId, 10));

        flow.importReturn(new ReturnImportInput("REQ-0403", "AE", "O-401", "SR-401", itemId, 2));
        SalesResult replay = flow.importReturn(new ReturnImportInput("REQ-0404", "AE", "O-401", "SR-401", itemId, 2));

        assertEquals("ALREADY_POSTED", replay.reasonCode());
        assertEquals(1, count("sal_return"));
        assertEquals(2, jdbc.queryForObject("select qty_returned from sal_order_item where id=?", Integer.class, itemId),
                "同退货号重传不得二次累计");
        assertEquals(4, count("noon_m5_sales_request"), "订单导入 + 妥投 + 两次退货（各 requestId 各一行）");
    }

    // ---------------------------------------------------------------- 只读 + 回读

    @Test
    void listsAndDetailsAreSiteIsolated()
    {
        seedBatch("SA", 22L, 5, "100.0000", "2026-11-01 10:00:00");
        seedLedger("SA", 22L, 5);
        SalesResult ae = flow.importOrder(order("REQ-0501", "AE", "O-501", 1, "150.0000", "AED"));
        SalesResult sa = flow.importOrder(order("REQ-0502", "SA", "O-502", 1, "150.0000", "SAR"));

        PageResult<SalesOrderRow> all = flow.listOrders(null, 0, 50, null, null);
        assertEquals(2, all.total());

        SalesTestSchema.login(2L); // 只授权 AE
        PageResult<SalesOrderRow> aeOnly = flow.listOrders(null, 0, 50, null, null);
        assertEquals(1, aeOnly.total());
        assertEquals("AE", aeOnly.rows().get(0).siteCode());
        assertThrows(AccessDeniedException.class, () -> flow.getOrder(sa.orderId()));
        assertThrows(AccessDeniedException.class, () -> flow.listReturns("SA", 0, 50, null, null));

        SalesTestSchema.login(1L);
        SalesOrderDetail detail = flow.getOrder(ae.orderId());
        assertEquals(1, detail.items().size());
        assertEquals(new BigDecimal("150.0000"), detail.items().get(0).unitPrice());
    }

    @Test
    void resultOfReadsBackStoredResultAndRejectsUnknown()
    {
        SalesResult imported = flow.importOrder(order("REQ-0601", "AE", "O-601", 1, "150.0000", "AED"));
        SalesResult read = flow.resultOf("REQ-0601", "ORDER_IMPORT");
        assertEquals(imported.orderId(), read.orderId());
        assertFalse(read.replayed());
        assertThrows(IllegalArgumentException.class, () -> flow.resultOf("REQ-0601", "RETURN_IMPORT"));
        assertThrows(IllegalArgumentException.class, () -> flow.resultOf("REQ-9999", "ORDER_IMPORT"));
    }
}
