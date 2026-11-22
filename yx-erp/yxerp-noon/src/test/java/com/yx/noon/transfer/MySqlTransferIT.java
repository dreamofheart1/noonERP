package com.yx.noon.transfer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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
import com.yx.noon.inventory.InboundInput;
import com.yx.noon.inventory.InboundLineInput;
import com.yx.noon.inventory.InboundResult;
import com.yx.noon.inventory.InboundService;
import com.yx.noon.inventory.InventoryAudit;
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
 * M4 第二轮调拨隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（md_sku / md_warehouse / sal_order /
 * sal_order_item / inv_batch_consumption / int_webhook_event）+ {@code yx-erp/sql/noon_m3_purchasing.sql}
 * （M3 收货表）+ {@code yx-erp/sql/noon_m4_inventory.sql}（第一轮库存表，WorkBuddy 正式迁移）+
 * {@code yx-erp/sql/noon_m4_transfer.sql}（本轮调拨 2 张 schema 表 + 调拨幂等表）。
 *
 * <p>验证：真 DDL CHECK（{@code qty_shipped > 0}、{@code qty_received <= qty_shipped}、四态非负、
 * 流水非零、调拨幂等表主键）、并发同 requestId 只拆批一次、真库端到端「调拨子批次收货转 OPEN 后被现有
 * {@link FifoConsumptionService#postDelivery} 直接消费」、总量守恒（源批次剩余 + 子批次 + 短少 + 已售 = 初始）。
 * FIFO 源码不在本轮修改，只在测试中调用。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}
 * 或 {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlTransferIT
{
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InboundService inboundService;
    private TransferService transferService;
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

        // 真实 schema v0.5 表（M4 迁移不建）：主数据、FIFO 父订单/消耗流水/事件。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"md_sku", "md_warehouse", "sal_order", "sal_order_item",
                "inv_batch_consumption", "int_webhook_event"})
            jdbc.execute(extractCreateTable(schema, table));
        // M3 正式迁移（pur_order / pur_order_item / noon_m3_receipt）。
        runSqlFile("../sql/noon_m3_purchasing.sql");
        // M4 第一轮正式迁移（inv_batch / inv_stock_ledger / inv_stock_move / 幂等/审计）。
        runSqlFile("../sql/noon_m4_inventory.sql");
        // M4 第二轮正式迁移（inv_transfer / inv_transfer_item / noon_m4_transfer_request）。
        runSqlFile("../sql/noon_m4_transfer.sql");
        // FIFO 出库隔离层（成功出库会写 noon_fifo_audit）。
        runSqlFile("../sql/noon_fifo_review.sql");

        clean();
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        inboundService = context.getBean(InboundService.class);
        transferService = context.getBean(TransferService.class);
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
    void migrationIsIdempotentAndRealTransferChecksHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_transfer.sql"); // 第二次：幂等
        assertEquals(4, jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name in ('inv_transfer','inv_transfer_item',"
                        + "'noon_m4_transfer_request','noon_m4_transfer_create_request')", Integer.class),
                "调拨 2 张 schema 表 + ship/receive 幂等表 + 建单幂等表应存在");

        // DB CHECK：调拨行 qty_shipped > 0 且 qty_received <= qty_shipped。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_transfer_item"
                + "(transfer_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) "
                + "values ('1','1','B:BAD','0','0','0')"), "qty_shipped=0 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_transfer_item"
                + "(transfer_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) "
                + "values ('1','1','B:BAD2','2','3','0')"), "qty_received > qty_shipped 必须被 CHECK 拒绝");

        // 调拨幂等表 request_id 主键（同键第二次插入必须撞键）。
        jdbc.update("insert into noon_m4_transfer_request"
                + "(request_id,transfer_id,operator_id,content_fingerprint,status) values ('IT-REQ-1',1,1,"
                + "'" + "a".repeat(64) + "','PENDING')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_transfer_request"
                + "(request_id,transfer_id,operator_id,content_fingerprint,status) values ('IT-REQ-1',1,1,"
                + "'" + "b".repeat(64) + "','PENDING')"), "request_id 必须主键唯一");

        // 建单幂等表（v1.2）：真库存在 + request_id 主键 + transfer_id NOT NULL（PENDING 期占位）。
        jdbc.update("insert into noon_m4_transfer_create_request"
                + "(request_id,transfer_id,operator_id,content_fingerprint,status) values ('IT-CREQ-1',0,1,"
                + "'" + "c".repeat(64) + "','PENDING')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_transfer_create_request"
                + "(request_id,transfer_id,operator_id,content_fingerprint,status) values ('IT-CREQ-1',0,1,"
                + "'" + "d".repeat(64) + "','PENDING')"), "create request_id 必须主键唯一");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_transfer_create_request"
                + "(request_id,operator_id,content_fingerprint,status) values ('IT-CREQ-2',1,"
                + "'" + "e".repeat(64) + "','PENDING')"), "create transfer_id NOT NULL（占位 0 必须显式给值）");

        // 台账四态非负（真 DDL CHECK）。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_ledger"
                + "(sku_id,warehouse_id,site_code,qty_available,qty_in_transit) values (1,1,'AE',1,-1)"),
                "在途负数必须被 CHECK 拒绝");

        // 真库拆批落库体：子批次数量/状态/血缘与 FIFO 期望一致。
        long fromWh = newWarehouse("WH-TR-IT-C1", "AE");
        long toWh = newWarehouse("WH-TR-IT-C2", "AE");
        long sku = newSku("SKU-TR-IT-C1");
        long sourceBatch = newBatch(sku, fromWh, "AE", "B:SRC-IT-C1", 5, "10.0000",
                LocalDateTime.of(2026, 3, 1, 9, 0));
        seedLedger(sku, fromWh, "AE", 5);
        long transfer = newTransfer("TR-IT-C1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-IT-C1", 5);
        TransferResult ship = transferService.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 3)), rid()));
        assertEquals(1, ship.childBatchIds().size());
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where id=? and source_type='TRANSFER_SPLIT' "
                        + "and status='IN_TRANSIT' and parent_batch_id=? and split_seq=1 and split_reason='TRANSFER' "
                        + "and transfer_no='TR-IT-C1' and qty_in=3 and qty_remaining=3 and site_code='AE'",
                Integer.class, ship.childBatchIds().get(0), sourceBatch), "子批次血缘/状态落库体");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch"
                + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost) "
                + "values ('B:TR:BAD','1',?,'AE',now(3),'1','2','1.0000')", toWh),
                "qty_remaining > qty_in 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_move"
                + "(move_no,sku_id,warehouse_id,change_type,qty_delta) values ('TM:BAD',1,1,'TRANSFER_OUT','0')"),
                "qty_delta=0 必须被 CHECK 拒绝");
    }

    // ------------------------------------------------------------ 并发同键

    @Test
    void concurrentSameShipRequestIdSplitsOnce() throws Exception
    {
        long fromWh = newWarehouse("WH-TR-IT-C3", "AE");
        long toWh = newWarehouse("WH-TR-IT-C4", "AE");
        long sku = newSku("SKU-TR-IT-C3");
        long sourceBatch = newBatch(sku, fromWh, "AE", "B:SRC-IT-C3", 10, "10.0000",
                LocalDateTime.of(2026, 3, 2, 9, 0));
        seedLedger(sku, fromWh, "AE", 10);
        long transfer = newTransfer("TR-IT-C3", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-IT-C3", 10);
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
                    TransferResult result = transferService.ship(new TransferShipInput(transfer, fromWh, toWh,
                            null, List.of(new TransferShipLine(item, 3)), requestId));
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
                "同一 requestId 并发只能一次真实拆批: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where batch_no like 'B:TR:%'",
                Integer.class), "只允许一个在途子批次");
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch), "源批次只扣一次");
        assertEquals(7, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, fromWh), "源仓可用只扣一次");
        assertEquals(3, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh), "目标仓在途只加一次");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move where change_type='TRANSFER_OUT'",
                Integer.class), "只允许一条 TRANSFER_OUT 流水");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit", Integer.class));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_request where request_id=?",
                String.class, requestId));
    }

    // ------------------------------------------------------------ 端到端：调拨收货 → 现有 FIFO 出库

    @Test
    void transferChildBatchIsReceivedOpenThenConsumedByExistingFifo()
    {
        long fromWh = newWarehouse("WH-TR-IT-E1", "AE");
        long toWh = newWarehouse("WH-TR-IT-E2", "AE");
        long sku = newSku("SKU-TR-IT-E2");

        // 收货：先用第一轮 InboundService 把 10 件入到源仓（真库跨模块链路）。
        long purOrder = newOrder("PO-IT-TR-E2", "AE", "RECEIVED");
        long purItem = newItem(purOrder, sku, 10, 10, "12.3400");
        newReceipt(purOrder, purItem, 10, "12.3400");
        InboundResult inbound = inboundService.confirm(new InboundInput(purOrder, fromWh, null,
                List.of(new InboundLineInput(purItem, 10)), rid()));
        long sourceBatch = inbound.batchIds().get(0);
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));

        // 调拨：拆批 10 到目标仓（在途），再收货 8（短少 2）。
        String sourceBatchNo = jdbc.queryForObject("select batch_no from inv_batch where id=?",
                String.class, sourceBatch);
        long transfer = newTransfer("TR-IT-E2", fromWh, toWh);
        long item = newTransferItem(transfer, sku, sourceBatchNo, 10);

        TransferResult ship = transferService.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 10)), rid()));
        long child = ship.childBatchIds().get(0);
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch), "源批次扣满");
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, sourceBatch), "源批次关批");
        assertEquals(10, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh));
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, fromWh));

        // FIFO 延续：子批次 received_at 必须等于源批次 received_at。
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch c join inv_batch p "
                        + "on p.id=c.parent_batch_id where c.id=? and c.received_at=p.received_at",
                Integer.class, child), "子批次 received_at 继承父批次（FIFO 顺序延续）");

        String shortEvent = "short-it-e2";
        TransferResult receive = transferService.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item, 8, shortEvent)), rid()));
        assertEquals(8, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child));
        assertEquals(8, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, child));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?", String.class, child),
                "实收 >0 在途转 OPEN");
        assertEquals(0, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh), "目标仓在途清零");
        assertEquals(8, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh), "目标仓可用 +8");
        assertEquals(8, jdbc.queryForObject("select qty_received from inv_transfer_item where id=?",
                Integer.class, item));
        assertEquals(2, jdbc.queryForObject("select diff_qty from inv_transfer_item where id=?",
                Integer.class, item));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer));

        // 短少流水（真库）：TRANSFER_SHORT / sign=1 / 事件键 / 成本继承。
        Map<String, Object> shortRow = jdbc.queryForMap("select biz_event_key,batch_id,qty,unit_cost,amount,"
                + "move_type,sign,period,sal_order_item_id from inv_batch_consumption "
                + "where move_type='TRANSFER_SHORT'");
        assertEquals("TSHORT:" + item + ":" + shortEvent, shortRow.get("biz_event_key"));
        assertEquals(child, ((Number) shortRow.get("batch_id")).longValue());
        assertEquals(2, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) shortRow.get("unit_cost")));
        assertEquals(0, new BigDecimal("24.6800").compareTo((BigDecimal) shortRow.get("amount")));
        Object sign = shortRow.get("sign");
        int signValue = sign instanceof Boolean b ? (b ? 1 : 0) : ((Number) sign).intValue();
        assertEquals(1, signValue, "短少 sign=+1");
        assertEquals(null, shortRow.get("sal_order_item_id"), "不绑定销售订单行");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move where change_type='TRANSFER_IN'",
                Integer.class), "实收 8 写一条 TRANSFER_IN");
        assertEquals(8, jdbc.queryForObject("select qty_delta from inv_stock_move where change_type='TRANSFER_IN'",
                Integer.class));

        // 总量守恒（发货前）：源批次剩余 0 + 子批次 8 + 短少 2 = 初始 10。
        assertEquals(10, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='TRANSFER_SHORT')",
                Integer.class, sourceBatch, child), "源批次剩余 + 子批次 + 短少 = 初始剩余");

        // 现有 FIFO 消费：目标仓 OPEN 子批次 8 件被妥投 8 直接吃掉。
        long salOrder = seedSalOrder("AE", "SAL-IT-TR-E2");
        long salItem = seedSalItem(salOrder, "SAL-IT-TR-E2", sku, 8);
        String payload = deliveryPayload(salItem, sku, 8);
        seedEvent("EVT-TR-IT-E2", "AE", payload);
        FifoConsumptionService.Result posted = fifoService.postDelivery(
                new DeliveryInput("AE", "EVT-TR-IT-E2", salItem, List.of(new DeliveryLine(sku, 8)), payload));
        assertTrue(posted.posted(), "现有 FIFO 应能消费调拨子批次，实际 reason=" + posted.reasonCode());

        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, child));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?", String.class, child));
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh), "目标仓可用清零");
        Map<String, Object> saleRow = jdbc.queryForMap("select batch_id,qty,move_type,sign from inv_batch_consumption "
                + "where move_type='SALE'");
        assertEquals(child, ((Number) saleRow.get("batch_id")).longValue(), "FIFO 消耗的是调拨子批次");
        assertEquals(8, ((Number) saleRow.get("qty")).intValue());
        Object saleSign = saleRow.get("sign");
        int saleSignValue = saleSign instanceof Boolean b ? (b ? 1 : 0) : ((Number) saleSign).intValue();
        assertEquals(1, saleSignValue);
        assertEquals(8, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?",
                Integer.class, salItem));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_fifo_review", Integer.class),
                "全链路合法不应进 FIFO 隔离层");

        // 总量守恒（售完后）：源批次 0 + 子批次 0 + 短少 2 + 已售 8 = 初始 10。
        assertEquals(10, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='TRANSFER_SHORT') "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='SALE')",
                Integer.class, sourceBatch, child), "源批次 + 子批次 + 短少 + 已售 = 初始剩余");
        assertEquals(0, jdbc.queryForObject("select coalesce(sum(qty_available),0) from inv_stock_ledger",
                Integer.class), "全部售出后可用为 0");
    }

    // ------------------------------------------------------------ 真库唯一键防双计

    @Test
    void duplicateShortEventBlockedByRealUniqueKey()
    {
        long fromWh = newWarehouse("WH-TR-IT-F1", "AE");
        long toWh = newWarehouse("WH-TR-IT-F2", "AE");
        long sku = newSku("SKU-TR-IT-F1");
        long sourceBatch = newBatch(sku, fromWh, "AE", "B:SRC-IT-F1", 6, "8.0000",
                LocalDateTime.of(2026, 3, 3, 9, 0));
        seedLedger(sku, fromWh, "AE", 6);
        long transfer = newTransfer("TR-IT-F1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-IT-F1", 6);
        long child = transferService.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 6)), rid())).childBatchIds().get(0);

        // 预置同事件键流水（模拟另一路径已过账），服务再记同事件必须被真库唯一键拦下。
        String eventId = "dup-it";
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('TS:IT-MANUAL',?,?,?,1,'8.0000','8.0000',now(3),'TRANSFER_SHORT',1,'2026-03',0)",
                "TSHORT:" + item + ":" + eventId, child, sku);

        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class,
                () -> transferService.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 5, eventId)), rid())));
        assertTrue(duplicated.getMessage().contains("短少事件已过账"), duplicated.getMessage());
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption", Integer.class),
                "唯一键拦截后不得双计");
        // 整事务回滚：子批次仍在途、台账未动、调拨单仍 IN_TRANSIT。
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(6, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child));
        assertEquals(6, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, sku, toWh));
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_transfer_request where transfer_id=? "
                + "and status='PENDING'", Integer.class, transfer), "失败事务的 PENDING 占位必须回滚");
    }

    // ------------------------------------------------------------ 建单 → 发运 → 收货（不依赖种子单）

    @Test
    void createShipReceiveFullChainOnRealSchemaWithoutSeedTransfer()
    {
        long fromWh = newWarehouse("WH-TR-IT-G1", "AE");
        long toWh = newWarehouse("WH-TR-IT-G2", "AE");
        long sku = newSku("SKU-TR-IT-G1");

        // 源批次由第一轮 InboundService 真实入库产生（不手工造调拨单，也不造调拨种子行）。
        long purOrder = newOrder("PO-IT-TR-G1", "AE", "RECEIVED");
        long purItem = newItem(purOrder, sku, 9, 9, "9.5000");
        newReceipt(purOrder, purItem, 9, "9.5000");
        InboundResult inbound = inboundService.confirm(new InboundInput(purOrder, fromWh, null,
                List.of(new InboundLineInput(purItem, 9)), rid()));
        long sourceBatch = inbound.batchIds().get(0);
        String batchNo = jdbc.queryForObject("select batch_no from inv_batch where id=?", String.class, sourceBatch);

        // ① create：真库写 DRAFT 单 + 计划行，不扣批次/台账/流水。
        int movesBeforeCreate = jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class);
        String createKey = rid();
        TransferResult created = transferService.create(new TransferCreateInput(fromWh, toWh, "AE",
                List.of(new TransferCreateLine(sku, batchNo, 9)), createKey));
        assertFalse(created.replayed());
        assertEquals("CREATE", created.action());
        assertTrue(created.childBatchIds().isEmpty());
        assertTrue(created.transferNo().matches("TR:AE:[0-9A-F]{8}"), created.transferNo());
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        assertEquals(9, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch), "create 不扣批次");
        assertEquals(9, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fromWh), "create 不动源仓台账");
        assertEquals(movesBeforeCreate, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class),
                "create 不写流水");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move where change_type like 'TRANSFER%'",
                Integer.class), "create 不得写 TRANSFER 流水");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where batch_no like 'B:TR:%'",
                Integer.class), "create 不生成子批次");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_create_request "
                + "where request_id=?", String.class, createKey));
        assertEquals(created.transferId(), jdbc.queryForObject("select transfer_id "
                + "from noon_m4_transfer_create_request where request_id=?", Long.class, createKey),
                "DONE 时回填真实 transfer_id");

        TransferItemRow line = transferService.get(created.transferId()).items().get(0);
        long item = line.itemId();
        assertEquals(9, line.qtyShipped(), "DRAFT 期 qty_shipped = 计划量");
        assertEquals(0, line.qtyReceived());
        assertEquals(0, line.diffQty());

        // ② ship：真库 FOR UPDATE 重新校验后扣批次、拆出在途子批次、双边台账、TRANSFER_OUT。
        TransferResult ship = transferService.ship(new TransferShipInput(created.transferId(), fromWh, toWh,
                null, List.of(new TransferShipLine(item, 9)), rid()));
        long child = ship.childBatchIds().get(0);
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, sourceBatch));
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fromWh));
        assertEquals(9, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, toWh));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='TRANSFER_OUT' and qty_delta=-9", Integer.class));

        // ③ receive 7（短少 2）：子批次转 OPEN、在途减可用增、短少记 TRANSFER_SHORT、diff 回写。
        TransferResult receive = transferService.receive(new TransferReceiveInput(created.transferId(), toWh,
                List.of(new TransferReceiveLine(item, 7, "short-it-g1")), rid()));
        assertEquals(child, receive.childBatchIds().get(0));
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, child));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child), "实收 >0 在途转 OPEN");
        assertEquals(0, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, toWh));
        assertEquals(7, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, toWh));
        assertEquals(2, jdbc.queryForObject("select diff_qty from inv_transfer_item where id=?",
                Integer.class, item));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        Map<String, Object> shortRow = jdbc.queryForMap("select biz_event_key,qty,unit_cost,amount,move_type "
                + "from inv_batch_consumption where move_type='TRANSFER_SHORT'");
        assertEquals("TSHORT:" + item + ":short-it-g1", shortRow.get("biz_event_key"));
        assertEquals(2, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("9.5000").compareTo((BigDecimal) shortRow.get("unit_cost")),
                "短少成本继承真实入库批次");
        assertEquals(0, new BigDecimal("19.0000").compareTo((BigDecimal) shortRow.get("amount")));

        // ④ 总量守恒：源批次剩余 + 子批次剩余 + 短少 = 初始入库量。
        assertEquals(9, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='TRANSFER_SHORT')",
                Integer.class, sourceBatch, child));
    }

    // ------------------------------------------------------------ 建单并发同键

    @Test
    void concurrentSameCreateRequestIdCreatesOneTransfer() throws Exception
    {
        long fromWh = newWarehouse("WH-TR-IT-H1", "AE");
        long toWh = newWarehouse("WH-TR-IT-H2", "AE");
        long sku = newSku("SKU-TR-IT-H1");
        newBatch(sku, fromWh, "AE", "B:SRC-IT-H1", 10, "10.0000", LocalDateTime.of(2026, 3, 4, 9, 0));
        String requestId = rid();
        List<TransferCreateLine> lines = List.of(new TransferCreateLine(sku, "B:SRC-IT-H1", 4));

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
                    TransferResult result = transferService.create(new TransferCreateInput(fromWh, toWh, null,
                            lines, requestId));
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
                "同一 requestId 并发只能建一张单: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_transfer", Integer.class),
                "并发同键只允许一张调拨单");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_transfer_item", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit", Integer.class));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_create_request "
                + "where request_id=?", String.class, requestId));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, newBatchId("B:SRC-IT-H1")), "建单不扣库存，批次仍为 10");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_transfer_create_request "
                + "where status='PENDING'", Integer.class), "不得残留 PENDING 占位");
    }

    private long newBatchId(String batchNo)
    {
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
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

    private long newBatch(long skuId, long warehouseId, String site, String batchNo, int qty, String unitCost,
                          LocalDateTime receivedAt)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                        + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                        + "split_reason,transfer_no,status) "
                        + "values (?,?,NULL,?,?,?,?,?,?,'CNY',1,'PURCHASE',NULL,0,NULL,NULL,'OPEN')",
                batchNo, skuId, warehouseId, site, java.sql.Timestamp.valueOf(receivedAt), qty, qty, unitCost);
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(long skuId, long warehouseId, String site, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable) values (?,?,?,?,0,0,0)",
                skuId, warehouseId, site, available);
    }

    private long newTransfer(String transferNo, long fromWarehouseId, long toWarehouseId)
    {
        jdbc.update("insert into inv_transfer(transfer_no,from_warehouse_id,to_warehouse_id,status) "
                + "values (?,?,?,'DRAFT')", transferNo, fromWarehouseId, toWarehouseId);
        return jdbc.queryForObject("select id from inv_transfer where transfer_no=?", Long.class, transferNo);
    }

    private long newTransferItem(long transferId, long skuId, String batchNo, int qtyShipped)
    {
        jdbc.update("insert into inv_transfer_item(transfer_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) "
                + "values (?,?,?,?,0,0)", transferId, skuId, batchNo, qtyShipped);
        return jdbc.queryForObject("select max(id) from inv_transfer_item where transfer_id=?",
                Long.class, transferId);
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
        for (String table : new String[]{"noon_m4_transfer_create_request", "noon_m4_transfer_request",
                "inv_transfer_item", "inv_transfer",
                "noon_m4_inbound_request", "noon_m4_inventory_audit", "inv_stock_move",
                "inv_stock_ledger", "inv_batch_consumption", "inv_batch", "inv_inbound_item", "inv_inbound",
                "noon_fifo_audit", "noon_fifo_review", "int_webhook_event", "sal_order_item", "sal_order",
                "noon_m3_receipt", "pur_order_item", "pur_order"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_sku where sku_code like 'SKU-TR-IT-%' or sku_code like 'SKU-IT-%'");
        jdbc.execute("delete from md_warehouse where warehouse_code like 'WH-TR-IT-%' or warehouse_code like 'WH-IT-%'");
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
            String database = "noon_m4_tr_" + UUID.randomUUID().toString().replace("-", "");
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
        TransferService transferService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new TransferService(jdbcTemplate, guard, audit);
        }

        @Bean
        FifoConsumptionService fifoConsumptionService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new FifoConsumptionService(jdbcTemplate, guard);
        }
    }
}
