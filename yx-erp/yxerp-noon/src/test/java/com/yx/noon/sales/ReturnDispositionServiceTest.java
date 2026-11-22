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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 A/B/C 分级处置 H2 验证（核心新逻辑）：真实 Spring 事务 + 真实内部闸门（入账/妥投/红冲）。
 *
 * <p>覆盖：A 级建新批次 + qty_unsellable 不变 + 台账回可用；B/C 级全额 100（非 70）+ qty_unsellable
 * 守恒；赔偿只登记不写流水、不从 RETURN_LOSS 扣减；重复处置 ACK / 换 grade 400 / 未红冲 400 /
 * 跨站 403；跨期 period 归属；多成本层按原层拆分；C-2b L1=−70 流水级验算。
 */
class ReturnDispositionServiceTest
{
    private static final long WAREHOUSE = 21L;
    private static final long SKU = 11L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SalesFlowService flow;
    private ReturnDispositionService disposition;

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
        disposition = context.getBean(ReturnDispositionService.class);
        SalesTestSchema.login(1L);
    }

    @AfterEach
    void tearDown()
    {
        SalesTestSchema.clearAuth();
        if (context != null) context.close();
    }

    // ---------------------------------------------------------------- 夹具

    private record Ctx(long orderId, long itemId, long returnId, long originalId, long batchId) { }

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

    /** 标准链路：批次 → 订单 → 妥投 → 退货 → 红冲。返回各实体 id。 */
    private Ctx prepare(int batchQty, String cost, int deliveredQty, int returnQty, int reverseQty, String gross)
    {
        long batchId = seedBatch("AE", WAREHOUSE, batchQty, cost, "2026-11-01 10:00:00");
        seedLedger("AE", WAREHOUSE, batchQty);
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SalesResult imported = flow.importOrder(new OrderImportInput("IMP-" + id, "AE", "O-" + id, 7L, SKU, "SKU",
                new BigDecimal(gross), batchQty, "AED", "WEBHOOK"));
        if (deliveredQty > 0)
            flow.deliverOrder(new DeliverInput("DEL-" + id, "AE", imported.orderItemId(), deliveredQty));
        SalesResult returned = flow.importReturn(new ReturnImportInput("RET-" + id, "AE", "O-" + id,
                "SR-" + id, imported.orderItemId(), returnQty));
        long returnId = returned.returnId();
        long originalId = jdbc.queryForObject("select id from inv_batch_consumption where sal_order_item_id=? "
                + "and sign=1 order by id limit 1", Long.class, imported.orderItemId());
        if (reverseQty > 0)
            flow.reverseReturn(new ReturnReverseInput("REV-" + id, "AE", returnId, originalId, reverseQty));
        return new Ctx(imported.orderId(), imported.orderItemId(), returnId, originalId, batchId);
    }

    private Ctx prepareDefault() { return prepare(5, "100.0000", 1, 1, 1, "150.0000"); }

    private DisposeInput dispose(long returnId, String grade, String req)
    {
        return new DisposeInput(req, "AE", returnId, grade, null, null, null, null);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    private BigDecimal lossAmount(long returnId)
    {
        return jdbc.queryForObject("select amount from inv_batch_consumption where sal_return_id=? "
                + "and move_type='RETURN_LOSS'", BigDecimal.class, returnId);
    }

    private int available() { return jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class); }

    private int unsellable() { return jdbc.queryForObject("select qty_unsellable from inv_stock_ledger", Integer.class); }

    // ---------------------------------------------------------------- A 级

    @Test
    void disposeGradeACreatesRestockBatchAndKeepsUnsellableZero()
    {
        Ctx ctx = prepareDefault();
        assertEquals(4, available());

        // Codex M5 复核：传入值必须等于原 SALE 层成本（prepareDefault=100），偏离一律拒绝。
        // 此处传 100（与原价一致）验证"一致值兼容放行"；偏离场景见下方独立用例。
        SalesResult result = disposition.dispose(new DisposeInput("DSP-0001", "AE", ctx.returnId(), "A",
                new BigDecimal("100.0000"), null, null, 1));

        String batchNo = result.restockBatchNo();
        assertNotNull(batchNo);
        assertTrue(batchNo.startsWith("B:RTN:AE:"), "批次号口径 B:RTN:<SITE>:<UUID8>");
        Map<String, Object> batch = jdbc.queryForMap("select sku_id,warehouse_id,qty_in,qty_remaining,unit_cost,"
                + "source_type,status from inv_batch where batch_no=?", batchNo);
        assertEquals(SKU, ((Number) batch.get("sku_id")).longValue());
        assertEquals(WAREHOUSE, ((Number) batch.get("warehouse_id")).longValue());
        assertEquals(1, ((Number) batch.get("qty_in")).intValue());
        assertEquals(1, ((Number) batch.get("qty_remaining")).intValue());
        assertEquals(0, new BigDecimal("100.0000").compareTo((BigDecimal) batch.get("unit_cost")),
                "ORIGINAL：即使客户端传值，也必须与原价一致");
        assertEquals("RETURN_RESTOCK", batch.get("source_type"));
        assertEquals("OPEN", batch.get("status"));

        // A 级：实物回可用，不进不合格库存；无 RETURN_LOSS
        assertEquals(5, available(), "台账可用量回补（4 + 1）");
        assertEquals(0, unsellable());
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where sal_return_id=?"
                + " and move_type='RETURN_LOSS'", Integer.class, ctx.returnId()));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.returnLossAmount()));
        assertEquals("DISPOSED", jdbc.queryForObject("select status from sal_return where id=?", String.class,
                ctx.returnId()));
        assertEquals(batchNo, jdbc.queryForObject("select restock_batch_no from sal_return where id=?", String.class,
                ctx.returnId()));
    }

    @Test
    void disposeGradeADefaultsCostToOriginalConsumedLayer()
    {
        Ctx ctx = prepareDefault();
        SalesResult result = disposition.dispose(dispose(ctx.returnId(), "A", "DSP-0002"));
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                jdbc.queryForObject("select unit_cost from inv_batch where batch_no=?", BigDecimal.class,
                        result.restockBatchNo())), "D2：缺省取原 SALE 消耗层 unit_cost");
    }

    @Test
    void restockCostDeviatingFromOriginalIsRejectedToAvoidProfitDistortion()
    {
        // Codex M5 复核：PRD return_restock_cost=ORIGINAL，A 级资产转移不产生损益。
        // 0/负数、正数偏离原价、正数恶意放大都不得放行，且不得产生任何写入副作用。
        Ctx ctx = prepareDefault();  // 原 SALE 层成本 100
        ReturnDispositionException zero = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(new DisposeInput("DSP-ZERO", "AE", ctx.returnId(), "A",
                        BigDecimal.ZERO.setScale(4), null, null, null)));
        assertEquals("INVALID_RESTOCK_COST", zero.reasonCode());

        ReturnDispositionException negative = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(new DisposeInput("DSP-NEG", "AE", ctx.returnId(), "A",
                        new BigDecimal("-1.0000"), null, null, null)));
        assertEquals("INVALID_RESTOCK_COST", negative.reasonCode());

        ReturnDispositionException override = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(new DisposeInput("DSP-OVR", "AE", ctx.returnId(), "A",
                        new BigDecimal("120.0000"), null, null, null)));
        assertEquals("RESTOCK_COST_OVERRIDE_FORBIDDEN", override.reasonCode(),
                "正数但偏离原价 100 的覆盖必须拒绝");

        assertEquals("OPEN", jdbc.queryForObject("select status from sal_return where id=?", String.class,
                ctx.returnId()), "被拒后退货单仍 OPEN");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='RETURN_RESTOCK'",
                Integer.class), "被拒后不得建二次上架批次");
        assertEquals(4, available(), "台账可用量未被改动");
    }

    // ---------------------------------------------------------------- B / C 级

    @Test
    void disposeGradeBWritesFullCostLossAndUnsellableConservation()
    {
        Ctx ctx = prepareDefault();
        assertEquals(4, available());

        SalesResult result = disposition.dispose(new DisposeInput("DSP-0003", "AE", ctx.returnId(), "B",
                null, new BigDecimal("30.0000"), new BigDecimal("150.0000"), null));

        assertEquals(0, new BigDecimal("100.0000").compareTo(lossAmount(ctx.returnId())),
                "RETURN_LOSS 全额 100（不得因赔偿扣成 70）");
        Map<String, Object> loss = jdbc.queryForMap("select qty,unit_cost,sign,move_type,biz_event_key,"
                        + "reversal_of_id,period,cross_period from inv_batch_consumption "
                        + "where sal_return_id=? and move_type='RETURN_LOSS'", ctx.returnId());
        assertEquals(1, ((Number) loss.get("qty")).intValue());
        assertEquals(0, new BigDecimal("100.0000").compareTo((BigDecimal) loss.get("unit_cost")));
        assertEquals(1, ((Number) loss.get("sign")).intValue());
        assertEquals("RETURN_LOSS", loss.get("move_type"));
        assertEquals("RTNDISP:" + ctx.returnId() + ":B", loss.get("biz_event_key"));
        assertEquals(ctx.originalId(), ((Number) loss.get("reversal_of_id")).longValue());

        // 库存守恒：可用量不变 4，不合格 +1 → 实物 4+1 = 进货 5 - 妥投 1 + 退货 1 = 5
        assertEquals(4, available());
        assertEquals(1, unsellable());
        assertEquals(5, available() + unsellable());

        // C-2b：赔偿只登记，不写 COMPENSATION 流水、不从 RETURN_LOSS 扣减
        assertEquals(0, new BigDecimal("30.0000").compareTo(
                jdbc.queryForObject("select compensation_amount from sal_return where id=?", BigDecimal.class,
                        ctx.returnId())));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='COMPENSATION'",
                Integer.class));
        assertEquals(0, new BigDecimal("100.0000").compareTo(
                jdbc.queryForObject("select return_loss_amount from sal_return where id=?", BigDecimal.class,
                        ctx.returnId())));
        // 退款只登记字段，不写收入流水
        assertEquals(0, new BigDecimal("150.0000").compareTo(
                jdbc.queryForObject("select refund_amount from sal_return where id=?", BigDecimal.class,
                        ctx.returnId())));
        assertEquals("B", jdbc.queryForObject("select grade from sal_return where id=?", String.class, ctx.returnId()));
        assertNull(jdbc.queryForObject("select restock_batch_no from sal_return where id=?", Object.class,
                ctx.returnId()));

        // 只读视图走真实派生表 (sign+0)：红冲行 sign=-1、处置行未写，映射不因 TINYINT 口径失真
        SalesReturnDetail detail = flow.getReturn(ctx.returnId());
        assertEquals("DISPOSED", detail.row().status());
        assertEquals(2, detail.flows().size());
        SalesReturnFlowRow reversalRow = detail.flows().stream()
                .filter(f -> "RETURN_REVERSAL".equals(f.moveType())).findFirst().orElseThrow();
        assertEquals(-1, reversalRow.sign());
        assertEquals(ctx.originalId(), reversalRow.reversalOfId());
        assertEquals(1, flow.listReturns("AE", 0, 10, null, null).total());
        assertEquals(0, flow.listReturns("SA", 0, 10, null, null).total(), "站点隔离：SA 看不到 AE 退货");
    }

    @Test
    void disposeGradeCUsesItsOwnLossKey()
    {
        Ctx ctx = prepareDefault();
        SalesResult result = disposition.dispose(dispose(ctx.returnId(), "C", "DSP-0004"));
        assertEquals("C", result.grade());
        assertEquals("RTNDISP:" + ctx.returnId() + ":C", jdbc.queryForObject(
                "select biz_event_key from inv_batch_consumption where sal_return_id=? and move_type='RETURN_LOSS'",
                String.class, ctx.returnId()));
        assertEquals(0, new BigDecimal("100.0000").compareTo(lossAmount(ctx.returnId())));
        assertEquals(1, unsellable());
    }

    @Test
    void c2bBaselineRecomputesL1MinusSeventy()
    {
        // PRD C-2/C-2b 基准：单件成本 100、售价 150、全额退款 150、赔偿 30
        Ctx ctx = prepare(5, "100.0000", 1, 1, 1, "150.0000");
        disposition.dispose(new DisposeInput("DSP-0005", "AE", ctx.returnId(), "B", null,
                new BigDecimal("30.0000"), new BigDecimal("150.0000"), 1));

        BigDecimal sale = jdbc.queryForObject("select amount from inv_batch_consumption where sal_return_id is null "
                + "and sign=1", BigDecimal.class);
        BigDecimal reversal = jdbc.queryForObject("select amount from inv_batch_consumption where sal_return_id=? "
                + "and sign=-1", BigDecimal.class, ctx.returnId());
        BigDecimal loss = lossAmount(ctx.returnId());
        BigDecimal refund = jdbc.queryForObject("select refund_amount from sal_return where id=?", BigDecimal.class,
                ctx.returnId());
        BigDecimal compensation = jdbc.queryForObject("select compensation_amount from sal_return where id=?",
                BigDecimal.class, ctx.returnId());
        BigDecimal gross = jdbc.queryForObject("select gross_amount from sal_order", BigDecimal.class);

        assertEquals(0, new BigDecimal("100.0000").compareTo(sale));
        assertEquals(0, new BigDecimal("100.0000").compareTo(reversal));
        assertEquals(0, new BigDecimal("100.0000").compareTo(loss), "双计错会写成 70");
        assertEquals(0, new BigDecimal("150.0000").compareTo(refund));
        assertEquals(0, new BigDecimal("30.0000").compareTo(compensation));

        // cogs = SALE − 红冲 + RETURN_LOSS = 100 − 100 + 100 = 100；netRevenue = 150 − 150 = 0；
        // L1 = netRevenue − cogs + compensation(30 单次确认) = 0 − 100 + 30 = −70
        BigDecimal netRevenue = gross.subtract(refund);
        BigDecimal cogs = sale.subtract(reversal).add(loss);
        BigDecimal l1 = netRevenue.subtract(cogs).add(compensation);
        assertEquals(0, new BigDecimal("-70.0000").compareTo(l1), "C-2b 验收基准 L1=−70");
        assertFalse(cogs.compareTo(new BigDecimal("200")) == 0, "未红冲会得 cogs=200");
    }

    // ---------------------------------------------------------------- 幂等 / grade / 前置

    @Test
    void repeatDisposeSameGradeIsAckWithoutSecondLoss()
    {
        Ctx ctx = prepareDefault();
        SalesResult first = disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0010"));
        SalesResult again = disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0011"));

        assertTrue(again.replayed());
        assertEquals(first.entityId(), again.entityId());
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption where sal_return_id=? "
                + "and move_type='RETURN_LOSS'", Integer.class, ctx.returnId()));
        assertEquals(1, unsellable(), "重复处置不得二次隔离");
    }

    @Test
    void sameRequestIdReplaysStoredResult()
    {
        Ctx ctx = prepareDefault();
        SalesResult first = disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0012"));
        SalesResult replay = disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0012"));
        assertTrue(replay.replayed());
        assertEquals(first.entityId(), replay.entityId());
        assertEquals(5, count("noon_m5_sales_request"),
                "导入 + 妥投 + 退货 + 红冲 + 处置各一行（同 requestId 重放不再新增）");
    }

    @Test
    void differentGradeAfterDisposeIsRejected()
    {
        Ctx ctx = prepareDefault();
        disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0013"));
        ReturnDispositionException conflict = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(dispose(ctx.returnId(), "A", "DSP-0014")));
        assertEquals("ALREADY_DISPOSED_OTHER_GRADE", conflict.reasonCode());
        assertEquals("B", jdbc.queryForObject("select grade from sal_return where id=?", String.class, ctx.returnId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='RETURN_RESTOCK'",
                Integer.class));
    }

    @Test
    void disposeBeforeReversalIsRejected()
    {
        Ctx ctx = prepare(5, "100.0000", 1, 1, 0, "150.0000");
        ReturnDispositionException notReversed = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0020")));
        assertEquals("RETURN_NOT_REVERSED", notReversed.reasonCode());
        assertEquals("OPEN", jdbc.queryForObject("select status from sal_return where id=?", String.class,
                ctx.returnId()));
        assertEquals(1, count("inv_batch_consumption"), "只有 1 条 SALE，无 RETURN_LOSS");
        assertEquals(0, unsellable());    }

    @Test
    void partialReversalOrPartialQtyCannotBeDisposed()
    {
        Ctx ctx = prepare(5, "100.0000", 3, 3, 1, "450.0000");
        ReturnDispositionException incomplete = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0021")));
        assertEquals("REVERSAL_INCOMPLETE", incomplete.reasonCode());

        ReturnDispositionException partial = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(new DisposeInput("DSP-0022", "AE", ctx.returnId(), "B", null, null, null, 2)));
        assertEquals("QTY_PARTIAL_NOT_SUPPORTED", partial.reasonCode());
        assertEquals(0, unsellable());
        assertEquals("OPEN", jdbc.queryForObject("select status from sal_return where id=?", String.class,
                ctx.returnId()));
    }

    @Test
    void compensationChangeAfterDisposeIsRejected()
    {
        Ctx ctx = prepareDefault();
        disposition.dispose(new DisposeInput("DSP-0030", "AE", ctx.returnId(), "B", null,
                new BigDecimal("30.0000"), null, null));
        ReturnDispositionException changed = assertThrows(ReturnDispositionException.class,
                () -> disposition.dispose(new DisposeInput("DSP-0031", "AE", ctx.returnId(), "B", null,
                        new BigDecimal("40.0000"), null, null)));
        assertEquals("COMPENSATION_ALREADY_CONFIRMED", changed.reasonCode());
        assertEquals(0, new BigDecimal("30.0000").compareTo(
                jdbc.queryForObject("select compensation_amount from sal_return where id=?", BigDecimal.class,
                        ctx.returnId())));
    }

    // ---------------------------------------------------------------- 站点 / 跨期 / 多层

    @Test
    void disposeCrossSiteIsDenied()
    {
        Ctx ctx = prepareDefault();
        // 调用方同时有 AE/SA 权限，但用 AE 站点处置（不会走到站点不一致，先把记录改成 SA 模拟跨站引用）
        jdbc.update("update sal_return set site_code='SA' where id=?", ctx.returnId());
        assertThrows(AccessDeniedException.class,
                () -> disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0040")));
        assertEquals("OPEN", jdbc.queryForObject("select status from sal_return where id=?", String.class,
                ctx.returnId()));

        SalesTestSchema.login(2L); // 只授权 AE
        assertThrows(AccessDeniedException.class,
                () -> disposition.dispose(new DisposeInput("DSP-0041", "SA", ctx.returnId(), "B", null, null, null, null)));
    }

    @Test
    void crossPeriodLossBelongsToReturnPeriod()
    {
        Ctx ctx = prepareDefault();
        jdbc.update("update inv_batch_consumption set period='2026-10' where sal_return_id is null");
        SalesResult result = disposition.dispose(dispose(ctx.returnId(), "B", "DSP-0050"));

        Map<String, Object> loss = jdbc.queryForMap("select period,cross_period from inv_batch_consumption "
                + "where sal_return_id=? and move_type='RETURN_LOSS'", ctx.returnId());
        String expectedPeriod = SalesRules.period(SalesRules.now());
        assertEquals(expectedPeriod, loss.get("period"), "D4：RETURN_LOSS 归退货期（红冲已归原期）");
        assertEquals(1, ((Number) loss.get("cross_period")).intValue());
        assertEquals("2026-10", jdbc.queryForObject("select original_period from sal_return where id=?", String.class,
                ctx.returnId()));
        assertEquals(expectedPeriod, jdbc.queryForObject("select period from sal_return where id=?", String.class,
                ctx.returnId()));
    }

    @Test
    void aLevelRestockFourDigitRoundingDifferenceIsBoundedAndExplicit()
    {
        // Codex M5 复核任务4：多原成本层 + 4 位精度下，建批资产与红冲 COGS 可能有"有界舍入差"，
        // 不声称数学上恒零。本用例钉死差额的数值、方向和边界，供 M7 利润/快照按实际入账金额口径承接。
        // 场景：FIFO 妥投 6 件 = 5@100 + 1@120 → 红冲 COGS=620.0000；建批 unit=620/6=103.3333，
        //       建批资产=103.3333×6=619.9998，差额=−0.0002（非零、紧界 |差|≤0.00005×qty=0.0003）。
        long b1 = seedBatch("AE", WAREHOUSE, 5, "100.0000", "2026-11-01 10:00:00");
        seedBatch("AE", WAREHOUSE, 5, "120.0000", "2026-11-02 10:00:00");
        seedLedger("AE", WAREHOUSE, 10);
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SalesResult imported = flow.importOrder(new OrderImportInput("IMP-" + id, "AE", "O-" + id, 7L, SKU, "SKU",
                new BigDecimal("1500.0000"), 10, "AED", "WEBHOOK"));
        flow.deliverOrder(new DeliverInput("DEL-" + id, "AE", imported.orderItemId(), 6));
        SalesResult returned = flow.importReturn(new ReturnImportInput("RET-" + id, "AE", "O-" + id,
                "SR-" + id, imported.orderItemId(), 6));
        long original1 = jdbc.queryForObject("select id from inv_batch_consumption where batch_id=? and sign=1",
                Long.class, b1);
        long original2 = jdbc.queryForObject("select id from inv_batch_consumption where batch_id<>? and sign=1",
                Long.class, b1);
        flow.reverseReturn(new ReturnReverseInput("REV1-" + id, "AE", returned.returnId(), original1, 5));
        flow.reverseReturn(new ReturnReverseInput("REV2-" + id, "AE", returned.returnId(), original2, 1));

        SalesResult result = disposition.dispose(dispose(returned.returnId(), "A", "DSP-RND-" + id));

        BigDecimal reversed = jdbc.queryForObject("select sum(amount) from inv_batch_consumption "
                + "where sal_return_id=? and sign=-1 and move_type='RETURN_REVERSAL'", BigDecimal.class,
                returned.returnId());
        assertEquals(0, new BigDecimal("620.0000").compareTo(reversed), "红冲 COGS = 5×100+1×120");
        Map<String, Object> batch = jdbc.queryForMap("select qty_remaining,unit_cost from inv_batch where batch_no=?",
                result.restockBatchNo());
        BigDecimal unitCost = (BigDecimal) batch.get("unit_cost");
        assertEquals(0, new BigDecimal("103.3333").compareTo(unitCost), "4 位 HALF_UP：620/6=103.3333");
        BigDecimal restockAsset = unitCost.multiply(
                BigDecimal.valueOf(((Number) batch.get("qty_remaining")).intValue()));
        assertEquals(0, new BigDecimal("619.9998").compareTo(restockAsset), "建批资产=单位成本×数量");
        BigDecimal diff = restockAsset.subtract(reversed);
        assertEquals(0, new BigDecimal("-0.0002").compareTo(diff), "差额=−0.0002，非零");
        assertTrue(diff.abs().compareTo(new BigDecimal("0.00005").multiply(BigDecimal.valueOf(6))) <= 0,
                "4 位单位成本 HALF_UP 舍入差紧界 |差|≤0.00005×qty=0.0003（实得 0.0002），M7 按实际入账金额承接");
        assertEquals(10, available(), "A 级实物回可用：入库10−妥投6+退货6");
        assertEquals(0, unsellable(), "A 级不进不合格库存");
    }

    @Test
    void multiLayerDisposeSplitsLossByOriginalLayerCost()
    {
        long b1 = seedBatch("AE", WAREHOUSE, 5, "100.0000", "2026-11-01 10:00:00");
        seedBatch("AE", WAREHOUSE, 5, "120.0000", "2026-11-02 10:00:00");
        seedLedger("AE", WAREHOUSE, 10);
        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SalesResult imported = flow.importOrder(new OrderImportInput("IMP-" + id, "AE", "O-" + id, 7L, SKU, "SKU",
                new BigDecimal("900.0000"), 10, "AED", "WEBHOOK"));
        flow.deliverOrder(new DeliverInput("DEL-" + id, "AE", imported.orderItemId(), 6));
        SalesResult returned = flow.importReturn(new ReturnImportInput("RET-" + id, "AE", "O-" + id,
                "SR-" + id, imported.orderItemId(), 6));

        long original1 = jdbc.queryForObject("select id from inv_batch_consumption where batch_id=? and sign=1",
                Long.class, b1);
        long original2 = jdbc.queryForObject("select id from inv_batch_consumption where batch_id<>? and sign=1",
                Long.class, b1);
        flow.reverseReturn(new ReturnReverseInput("REV1-" + id, "AE", returned.returnId(), original1, 5));
        flow.reverseReturn(new ReturnReverseInput("REV2-" + id, "AE", returned.returnId(), original2, 1));

        SalesResult result = disposition.dispose(dispose(returned.returnId(), "B", "DSP-0060"));

        List<Map<String, Object>> losses = jdbc.queryForList("select qty,unit_cost,amount,batch_id "
                + "from inv_batch_consumption where sal_return_id=? and move_type='RETURN_LOSS' order by batch_id",
                returned.returnId());
        assertEquals(2, losses.size(), "D5：按原消耗层拆两行，成本不混算");
        assertEquals(0, new BigDecimal("500.0000").compareTo((BigDecimal) losses.get(0).get("amount")));
        assertEquals(0, new BigDecimal("120.0000").compareTo((BigDecimal) losses.get(1).get("amount")));
        assertEquals(0, new BigDecimal("620.0000").compareTo(result.returnLossAmount()));
        assertEquals(0, new BigDecimal("620.0000").compareTo(
                jdbc.queryForObject("select return_loss_amount from sal_return where id=?", BigDecimal.class,
                        returned.returnId())));
        assertEquals(6, unsellable());
        // 守恒：可用 4 + 不合格 6 = 10（进货 10 − 妥投 6 + 退货 6）
        assertEquals(4, available());
    }
}
