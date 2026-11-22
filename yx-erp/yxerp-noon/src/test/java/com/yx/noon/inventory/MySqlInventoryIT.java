package com.yx.noon.inventory;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.fifo.FifoConsumptionService;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryInput;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryLine;
import com.yx.system.mapper.SysUserSiteMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 库存第一轮隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（sal_order / sal_order_item /
 * inv_batch_consumption / int_webhook_event 等）+ {@code yx-erp/sql/noon_m3_purchasing.sql}（M3 收货表）
 * + {@code yx-erp/sql/noon_m4_inventory.sql}（M4 五张库存表与幂等/审计表，WorkBuddy 的正式迁移）。
 *
 * <p>验证：真 DDL CHECK 约束（入库行数量、批次数量/状态、台账非负、流水非零）、迁移幂等、
 * 并发同 requestId 只生成一次批次/一次台账累加、失败回滚无部分写入，
 * 以及**端到端**：M4 入库生成的批次被现有 {@link FifoConsumptionService#postDelivery} 直接消费，
 * {@code inv_batch.qty_remaining} 与 {@code inv_stock_ledger.qty_available} 同步扣减、
 * {@code inv_batch_consumption} 正确写入。FIFO 源码不在本轮修改，只在测试中调用。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}
 * 或 {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlInventoryIT
{
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InboundService inboundService;
    private FifoConsumptionService fifoService;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null,
                "缺少隔离库 JDBC URL/端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT");

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        // 真实 schema v0.5 表（M4 迁移不建）：FIFO 出库父订单/消耗流水/事件 + M2 主数据。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"md_sku", "md_warehouse", "sal_order", "sal_order_item",
                "inv_batch_consumption", "int_webhook_event"})
            jdbc.execute(extractCreateTable(schema, table));
        // M3 正式迁移（pur_order / pur_order_item / noon_m3_receipt）。
        runSqlFile("../sql/noon_m3_purchasing.sql");
        // M4 正式迁移（inv_inbound / inv_inbound_item / inv_batch / inv_stock_ledger / inv_stock_move
        // + noon_m4_inbound_request / noon_m4_inventory_audit）。
        runSqlFile("../sql/noon_m4_inventory.sql");
        // FIFO 出库隔离层（成功出库会写 noon_fifo_audit）。
        runSqlFile("../sql/noon_fifo_review.sql");

        clean();
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        inboundService = context.getBean(InboundService.class);
        fifoService = context.getBean(FifoConsumptionService.class);
        login(1L);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
        String database = CREATED_DATABASE.get();
        if (database != null)
        {
            Target target = target();
            new JdbcTemplate(new DriverManagerDataSource(target.serverUrl(), target.user(), target.password()))
                    .execute("drop database if exists `" + database + "`");
            CREATED_DATABASE.remove();
        }
    }

    // ------------------------------------------------------------ 真 DDL 约束

    @Test
    void migrationIsIdempotentAndRealCheckConstraintsHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_inventory.sql"); // 第二次：幂等
        assertEquals(7, jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name in ('inv_inbound','inv_inbound_item',"
                        + "'inv_batch','inv_stock_ledger','inv_stock_move','noon_m4_inbound_request',"
                        + "'noon_m4_inventory_audit')", Integer.class),
                "5 张 schema 库存表 + M4 幂等/审计表应存在");

        long wh = newWarehouse("WH-IT-C1", "AE");
        long sku = newSku("SKU-IT-C1");
        long order = newOrder("PO-IT-C1", "AE", "RECEIVED");
        long item = newItem(order, sku, 5, 5, "10.0000");
        newReceipt(order, item, 5, "10.0000");
        InboundResult result = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 5)), rid()));
        assertEquals(1, result.batchIds().size());

        // DB CHECK：入库行 qty_qualified <= qty 且 qty > 0。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_inbound_item"
                + "(inbound_id,pur_order_item_id,sku_id,batch_no,qty,qty_qualified,unit_cost) "
                + "values ('1',? ,?,'B:BAD','2','3','1.0000')", item, sku), "qty_qualified > qty 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_inbound_item"
                + "(inbound_id,pur_order_item_id,sku_id,batch_no,qty,qty_qualified,unit_cost) "
                + "values ('1',?,?,'B:BAD0','0','0','1.0000')", item, sku), "qty=0 必须被 CHECK 拒绝");

        // DB CHECK：批次 qty_remaining <= qty_in 且 status 枚举合法。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch"
                + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost) "
                + "values ('B:IT:BAD','1',?,'AE',now(3),'1','2','1.0000')", wh),
                "qty_remaining > qty_in 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch"
                + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost,status) "
                + "values ('B:IT:BAD2','1',?,'AE',now(3),'1','1','1.0000','BAD')", wh),
                "非法批次状态必须被 CHECK 拒绝");

        // DB CHECK：台账非负；移库流水不得为 0。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_ledger"
                + "(sku_id,warehouse_id,site_code,qty_available) values (?,?,'AE','-1')", sku, wh),
                "台账负数必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_move"
                + "(move_no,sku_id,warehouse_id,change_type,qty_delta) values ('IM:IT:BAD',?,?,'INBOUND','0')",
                sku, wh), "qty_delta=0 必须被 CHECK 拒绝");

        // 真实迁移落库体：批次/台账户外键字段与 FIFO 期望一致。
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where status='OPEN' "
                + "and source_type='PURCHASE' and parent_batch_id is null and split_seq=0 "
                + "and currency='CNY' and fx_rate=1 and qty_in=qty_remaining and site_code='AE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_ledger "
                + "where qty_available=5 and site_code='AE'", Integer.class));
        assertNotNull(jdbc.queryForObject("select result_json from noon_m4_inbound_request where status='DONE'",
                String.class), "DONE 行必须缓存结果快照");
    }

    // ------------------------------------------------------------ 并发同键

    @Test
    void concurrentSameRequestIdCreatesOneBatchAndOneLedgerAccumulation() throws Exception
    {
        long wh = newWarehouse("WH-IT-C2", "AE");
        long sku = newSku("SKU-IT-C2");
        long order = newOrder("PO-IT-C2", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 10, "10.0000");
        newReceipt(order, item, 10, "10.0000");
        String requestId = rid();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> attempt = () ->
        {
            login(1L);
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            try
            {
                return tx.execute(status ->
                {
                    InboundResult result = inboundService.confirm(new InboundInput(order, wh, null,
                            List.of(new InboundLineInput(item, 3)), requestId));
                    return result.replayed() ? "REPLAYED" : "FRESH";
                });
            }
            catch (RuntimeException e)
            {
                return "REJECT:" + e.getMessage();
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        };
        Future<String> a = pool.submit(attempt);
        Future<String> b = pool.submit(attempt);
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<String> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, results.stream().filter("FRESH"::equals).count(),
                "同一 requestId 并发只能一次真实入账: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_inbound", Integer.class),
                "只允许一张入库单");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch", Integer.class),
                "只允许一个批次");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class),
                "只允许一条入库流水");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_ledger", Integer.class),
                "只允许一行台账");
        assertEquals(3, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class),
                "台账只累加一次");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit", Integer.class),
                "并发同键只写一条审计");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_inbound_request "
                + "where request_id=?", String.class, requestId));
    }

    // ------------------------------------------------------------ 失败回滚

    @Test
    void failedConfirmLeavesNoPartialDataAndSameRequestIdRetries()
    {
        long wh = newWarehouse("WH-IT-C3", "AE");
        long sku = newSku("SKU-IT-C3");
        long order = newOrder("PO-IT-C3", "AE", "RECEIVED");
        long item = newItem(order, sku, 4, 4, "10.0000");
        newReceipt(order, item, 4, "10.0000");
        String requestId = rid();

        IllegalArgumentException failed = assertThrows(IllegalArgumentException.class,
                () -> tx.execute(status ->
                {
                    inboundService.confirm(new InboundInput(order, wh, null,
                            List.of(new InboundLineInput(item, 5)), requestId));
                    return null;
                }));
        assertTrue(failed.getMessage().contains("可入库量"), failed.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inbound_request where request_id=?",
                Integer.class, requestId), "失败事务的 PENDING 行必须回滚");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_inbound", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_ledger", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit", Integer.class));

        InboundResult retried = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 4)), requestId));
        assertFalse(retried.replayed(), "重试是首次成功");
        assertEquals(4, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_inbound_request "
                + "where request_id=?", String.class, requestId));
    }

    // ------------------------------------------------------------ PARTIAL_RECEIVED 真库分批入库

    @Test
    void partialReceivedOrderBooksStagedInboundOnRealSchema()
    {
        long wh = newWarehouse("WH-IT-PART", "AE");
        long sku = newSku("SKU-IT-PART");
        long order = newOrder("PO-IT-PART", "AE", "PARTIAL_RECEIVED");
        long item = newItem(order, sku, 30, 30, "10.0000");
        newReceipt(order, item, 20, "10.0000");
        newReceipt(order, item, 10, "10.0000");

        // 首批 20：真 DDL 下 qty_qualified<=qty 与累计守恒同时成立。
        String firstKey = rid();
        InboundResult first = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 20)), firstKey));
        assertFalse(first.replayed());

        // 同单第二批 10 补齐（累计 30=累计收货），仍 PARTIAL_RECEIVED。
        InboundResult second = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 10)), rid()));
        assertFalse(second.replayed());

        assertEquals(2, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
        assertEquals(20, jdbc.queryForObject("select qty_in from inv_batch where id=?",
                Integer.class, first.batchIds().get(0)), "首批 qty_in=20");
        assertEquals(10, jdbc.queryForObject("select qty_in from inv_batch where id=?",
                Integer.class, second.batchIds().get(0)), "第二批 qty_in=10");
        assertEquals(30, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh), "台账 20+10=30");
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='INBOUND'", Integer.class), "两批各一条 INBOUND 流水");
        assertEquals(30, jdbc.queryForObject("select sum(qty_delta) from inv_stock_move "
                + "where change_type='INBOUND'", Integer.class));
        assertEquals("PARTIAL_RECEIVED", jdbc.queryForObject("select status from pur_order where id=?",
                String.class, order), "入库不改采购单收发状态机");

        // 再提交 1 件超累计（30+1>30）→ 400 且库存、流水、批次均不变。
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> inboundService.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 1)), rid())));
        assertTrue(over.getMessage().contains("可入库=0"), over.getMessage());
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_inbound", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class));
        assertEquals(30, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh));

        // DONE 重放不受状态闸门影响。
        InboundResult replay = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 20)), firstKey));
        assertTrue(replay.replayed());
        assertEquals(first.inboundId(), replay.inboundId());
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
    }

    // ------------------------------------------------------------ 端到端：入库批次 → 现有 FIFO 出库

    @Test
    void inboundBatchesAreConsumedByExistingFifoAndLedgerStaysInSync()
    {
        long wh = newWarehouse("WH-IT-E2E", "AE");
        long sku = newSku("SKU-IT-E2E");
        long purOrder = newOrder("PO-IT-E2E", "AE", "RECEIVED");
        long purItem = newItem(purOrder, sku, 10, 10, "12.3400");
        newReceipt(purOrder, purItem, 10, "12.3400");

        InboundResult inbound = inboundService.confirm(new InboundInput(purOrder, wh, null,
                List.of(new InboundLineInput(purItem, 10)), rid()));
        assertEquals(1, inbound.batchIds().size());
        long batchId = inbound.batchIds().get(0);

        // M4 写入后可被现有 FIFO 直接消费：同站点、同仓库、OPEN、received_at 非空。
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchId));
        assertEquals(10, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh));

        // 真实父订单行（FIFO 只信数据库真实 SKU/站点/数量）。
        long salOrder = seedSalOrder("AE", "SAL-IT-E2E");
        long salItem = seedSalItem(salOrder, "SAL-IT-E2E", sku, 10);

        // 第一次妥投 4：FIFO 消费 M4 入库批次。
        String payload1 = deliveryPayload(salItem, sku, 4);
        seedEvent("EVT-M4-E2E-1", "AE", payload1);
        FifoConsumptionService.Result r1 = fifoService.postDelivery(
                new DeliveryInput("AE", "EVT-M4-E2E-1", salItem, List.of(new DeliveryLine(sku, 4)), payload1));
        assertTrue(r1.posted(), "现有 FIFO 应能消费 M4 入库批次，实际 reason=" + r1.reasonCode());

        assertEquals(6, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchId), "批次剩余同步扣减 10-4");
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, batchId), "未扣满仍 OPEN");
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh), "台账 available 同步扣减");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption", Integer.class));
        Map<String, Object> consumption = jdbc.queryForMap("select batch_id,sku_id,qty,unit_cost,amount,"
                + "move_type,sign,sal_order_item_id from inv_batch_consumption");
        assertEquals(batchId, ((Number) consumption.get("batch_id")).longValue(), "消耗必须指向 M4 批次");
        assertEquals(4, ((Number) consumption.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) consumption.get("unit_cost")),
                "COGS 单位成本来自 M3 收货权威成本");
        assertEquals(0, new BigDecimal("49.3600").compareTo((BigDecimal) consumption.get("amount")));
        assertEquals("SALE", consumption.get("move_type"), "FIFO 流水 move_type 必须 SALE（红冲兼容）");
        // MySQL TINYINT(1) 经 JDBC 映射为 Boolean，按语义换算后断言 sign=+1。
        Object sign = consumption.get("sign");
        int signValue = sign instanceof Boolean b ? (b ? 1 : 0) : ((Number) sign).intValue();
        assertEquals(1, signValue, "正常出库 sign=+1");
        assertEquals(salItem, ((Number) consumption.get("sal_order_item_id")).longValue());
        assertEquals(4, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?",
                Integer.class, salItem));

        // 第二次妥投 6（新事件）：扣满关批，台账归零，累计消耗 10。
        String payload2 = deliveryPayload(salItem, sku, 6);
        seedEvent("EVT-M4-E2E-2", "AE", payload2);
        FifoConsumptionService.Result r2 = fifoService.postDelivery(
                new DeliveryInput("AE", "EVT-M4-E2E-2", salItem, List.of(new DeliveryLine(sku, 6)), payload2));
        assertTrue(r2.posted(), "第二批应能继续消费同一入库批次");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchId));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, batchId), "扣到 0 关批");
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh), "台账 available 归零");
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_batch_consumption", Integer.class));
        assertEquals(10, jdbc.queryForObject("select sum(qty) from inv_batch_consumption", Integer.class));
        assertEquals(10, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?",
                Integer.class, salItem));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_fifo_review", Integer.class),
                "全链路合法不应进 FIFO 隔离层");
    }

    // ------------------------------------------------------------ helpers

    private static final Map<Long, List<String>> GRANTS = new ConcurrentHashMap<>();

    private static void grant(long userId, String... sites)
    {
        GRANTS.put(userId, List.of(sites));
    }

    private static String rid()
    {
        return "r-" + UUID.randomUUID();
    }

    private long newSku(String code)
    {
        jdbc.update("insert into md_sku(sku_code,name_en,status) values (?,?,'ACTIVE')", code, code);
        return jdbc.queryForObject("select id from md_sku where sku_code=?", Long.class, code);
    }

    private long newWarehouse(String code, String site)
    {
        jdbc.update("insert into md_warehouse(warehouse_code,site_code,warehouse_type,status) "
                + "values (?,?,'LOCAL','ACTIVE')", code, site);
        return jdbc.queryForObject("select id from md_warehouse where warehouse_code=? and site_code=?",
                Long.class, code, site);
    }

    private long newOrder(String orderNo, String site, String status)
    {
        jdbc.update("insert into pur_order(order_no,supplier_id,site_code,currency,total_amount,status,operator_id) "
                + "values (?,1,?,'CNY',0,?,1)", orderNo, site, status);
        return jdbc.queryForObject("select id from pur_order where order_no=?", Long.class, orderNo);
    }

    private long newItem(long orderId, long skuId, int qtyOrdered, int qtyReceived, String unitPrice)
    {
        jdbc.update("insert into pur_order_item(pur_order_id,sku_id,qty_ordered,qty_received,unit_price,"
                + "currency,fx_rate) values (?,?,?,?,?,'CNY',1)", orderId, skuId, qtyOrdered, qtyReceived, unitPrice);
        return jdbc.queryForObject("select id from pur_order_item where pur_order_id=? and sku_id=? "
                + "order by id desc limit 1", Long.class, orderId, skuId);
    }

    private void newReceipt(long orderId, long itemId, int qty, String unitCost)
    {
        jdbc.update("insert into noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
                + "values (?,?,?,?,now(3),1)", orderId, itemId, qty, unitCost);
    }

    private long seedSalOrder(String site, String orderNo)
    {
        jdbc.update("insert into sal_order(site_code,order_no,store_id,partner_sku,qty,gross_amount,currency) "
                + "values (?,?,1,'SKU',10,100,'AED')", site, orderNo);
        return jdbc.queryForObject("select id from sal_order where order_no=? and site_code=?",
                Long.class, orderNo, site);
    }

    private long seedSalItem(long salOrderId, String orderNo, long sku, int qty)
    {
        jdbc.update("insert into sal_order_item(sal_order_id,order_no,sku_id,qty,unit_price) "
                + "values (?,?,?,?,10)", salOrderId, orderNo, sku, qty);
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=? and sku_id=?",
                Long.class, salOrderId, sku);
    }

    private void seedEvent(String eventId, String site, String payload)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                + "values (?,'SALE_DELIVERY',?,?,now(3),'PROCESSED')", eventId, site, payload);
    }

    private static String deliveryPayload(long itemId, long sku, int qty)
    {
        return "{\"sal_order_item_id\":" + itemId + ",\"lines\":[{\"sku_id\":" + sku + ",\"qty\":" + qty + "}]}";
    }

    private void clean()
    {
        for (String table : new String[]{"noon_m4_inbound_request", "noon_m4_inventory_audit", "inv_stock_move",
                "inv_stock_ledger", "inv_batch_consumption", "inv_batch", "inv_inbound_item", "inv_inbound",
                "noon_fifo_audit", "noon_fifo_review", "int_webhook_event", "sal_order_item", "sal_order",
                "noon_m3_receipt", "pur_order_item", "pur_order"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_sku where sku_code like 'SKU-IT-%'");
        jdbc.execute("delete from md_warehouse where warehouse_code like 'WH-IT-%'");
        jdbc.execute("delete from md_supplier where supplier_no like 'SUP-IT-%'");
    }

    private void runSqlFile(String path) throws Exception
    {
        String sql = Files.readString(Path.of(path));
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R"))
            if (!line.stripLeading().startsWith("--")) cleaned.append(line).append('\n');
        for (String statement : cleaned.toString().split(";"))
            if (!statement.isBlank()) jdbc.execute(statement);
    }

    private void runSqlFileUnchecked(String path)
    {
        try
        {
            runSqlFile(path);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("迁移脚本非幂等: " + path, e);
        }
    }

    private static String extractCreateTable(String schema, String table)
    {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = schema.indexOf(marker);
        if (start < 0) throw new IllegalStateException("未找到建表语句：" + table);
        int end = schema.indexOf(";", start);
        if (end < 0) throw new IllegalStateException("建表语句未闭合：" + table);
        return schema.substring(start, end + 1);
    }

    private static String setting(String property, String... envNames)
    {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value;
        for (String env : envNames)
        {
            value = System.getenv(env);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static DataSource buildDataSource()
    {
        Target target = target();
        DriverManagerDataSource ds = new DriverManagerDataSource(target.serverUrl(), target.user(), target.password());
        if (target.portMode())
        {
            String database = "noon_m4_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            CREATED_DATABASE.set(database);
            ds.setUrl(target.serverUrl().replace("/?", "/" + database + "?"));
        }
        return ds;
    }

    /**
     * 目标库：优先完整 JDBC URL（外部注入独立库）；否则用 NOON_MYSQL_TEST_PORT 在同一隔离实例
     * 建一次性库（结束时 drop）。
     */
    private static Target target()
    {
        String url = setting("noon.mysql.test.url", "NOON_MYSQL_TEST_URL", "NOON_MYSQL_TEST_JDBC_URL");
        String user = setting("noon.mysql.test.user", "NOON_MYSQL_TEST_USER");
        String password = setting("noon.mysql.test.password", "NOON_MYSQL_TEST_PASSWORD");
        String port = setting("noon.mysql.test.port", "NOON_MYSQL_TEST_PORT");
        String finalUser = user == null ? "root" : user;
        String finalPassword = password == null ? "" : password;
        if (url != null && !url.isBlank()) return new Target(url, finalUser, finalPassword, false);
        if (port == null || port.isBlank()) return null;
        return new Target("jdbc:mysql://127.0.0.1:" + port
                + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai",
                finalUser, finalPassword, true);
    }

    private record Target(String serverUrl, String user, String password, boolean portMode)
    {
    }

    private static void login(long id)
    {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName("user" + id);
        LoginUser principal = new LoginUser(id, 100L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", List.of()));
    }

    /** 站点授权内存桩（真库 IT 只验 DDL/并发/端到端，不依赖若依 sys_user 结构）。 */
    static class StubSiteMapper implements SysUserSiteMapper
    {
        @Override
        public Long selectActiveUserId(Long userId) { return userId != null && userId > 0 ? userId : null; }

        @Override
        public Long lockActiveUserId(Long userId) { return selectActiveUserId(userId); }

        @Override
        public List<String> selectSiteCodes(Long userId)
        {
            List<String> sites = userId == null ? null : GRANTS.get(userId);
            return sites == null ? List.of() : sites;
        }

        @Override
        public List<String> lockSiteCodes(Long userId) { return selectSiteCodes(userId); }

        @Override
        public int deleteByUserId(Long userId) { throw new UnsupportedOperationException("stub"); }

        @Override
        public int insertGrant(Long userId, String siteCode, Long actorId, LocalDateTime now)
        {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public int insertAudit(Long userId, Long actorId, String beforeCodes, String afterCodes, LocalDateTime now)
        {
            throw new UnsupportedOperationException("stub");
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config
    {
        @Bean
        DataSource dataSource() { return buildDataSource(); }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }

        @Bean
        SysUserSiteMapper mapper() { return new StubSiteMapper(); }

        @Bean
        SiteAuthGuard guard(SysUserSiteMapper mapper) { return new SiteAuthGuard(mapper); }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) { return new TransactionTemplate(tm); }

        @Bean
        InventoryAudit inventoryAudit(JdbcTemplate jdbcTemplate) { return new InventoryAudit(jdbcTemplate); }

        @Bean
        InboundService inboundService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new InboundService(jdbcTemplate, guard, audit);
        }

        @Bean
        FifoConsumptionService fifoConsumptionService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new FifoConsumptionService(jdbcTemplate, guard);
        }
    }
}
