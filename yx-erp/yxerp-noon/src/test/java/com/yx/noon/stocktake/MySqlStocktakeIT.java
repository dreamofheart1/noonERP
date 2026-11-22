package com.yx.noon.stocktake;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 第三轮第一批盘点隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（md_sku / md_warehouse /
 * inv_batch_consumption / inv_stocktake / inv_stocktake_item）+ {@code yx-erp/sql/noon_m3_purchasing.sql}
 * （M3 收货表）+ {@code yx-erp/sql/noon_m4_inventory.sql}（第一轮库存表）+
 * {@code yx-erp/sql/noon_m4_stocktake.sql}（本轮盘点幂等表，WorkBuddy 正式迁移）。
 *
 * <p>验证：真 DDL CHECK（{@code qty_book/qty_actual >= 0}）与唯一键（{@code stocktake_no}、{@code request_id}、
 * {@code (biz_event_key,batch_id)}）、真库端到端「采购入库 → 建单 → 盘亏 FIFO 消耗 → 盘盈 ADJUSTMENT」、
 * <b>建单后真实出入库 → 快照闸门整单拒绝 + 按新账面重盘成功</b>、<b>零成本盘盈拒绝</b>、
 * 并发同 apply requestId 只过账一次、同事件同批次重传被真库唯一键拦下且整单回滚。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}
 * 或 {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlStocktakeIT
{
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private InboundService inboundService;
    private StocktakeService stocktakeService;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null,
                "缺少隔离库 JDBC URL/端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT");

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        // 真实 schema v0.5 表（M4 迁移不建）：主数据、盘点业务表、FIFO 消耗流水。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"md_sku", "md_warehouse", "inv_batch_consumption",
                "inv_stocktake", "inv_stocktake_item"})
            jdbc.execute(extractCreateTable(schema, table));
        // M3 正式迁移（pur_order / pur_order_item / noon_m3_receipt）。
        runSqlFile("../sql/noon_m3_purchasing.sql");
        // M4 第一轮正式迁移（inv_batch / inv_stock_ledger / inv_stock_move / 幂等/审计）。
        runSqlFile("../sql/noon_m4_inventory.sql");
        // M4 第三轮第一批正式迁移（noon_m4_stocktake_request）。
        runSqlFile("../sql/noon_m4_stocktake.sql");

        clean();
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        inboundService = context.getBean(InboundService.class);
        stocktakeService = context.getBean(StocktakeService.class);
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
    void migrationIsIdempotentAndRealStocktakeChecksHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_stocktake.sql"); // 第二次：幂等
        assertEquals(3, jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name in ('inv_stocktake','inv_stocktake_item',"
                        + "'noon_m4_stocktake_request')", Integer.class),
                "盘点 2 张 schema 表 + 幂等表应存在");

        // DB CHECK：qty_book / qty_actual 不得为负（qty_diff 允许负，表示盘亏）。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stocktake_item"
                + "(stocktake_id,sku_id,qty_book,qty_actual,qty_diff) values (1,1,-1,0,0)"),
                "qty_book=-1 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stocktake_item"
                + "(stocktake_id,sku_id,qty_book,qty_actual,qty_diff) values (1,1,0,-1,0)"),
                "qty_actual=-1 必须被 CHECK 拒绝");

        // 单号唯一（uk_stocktake_no）。
        jdbc.update("insert into inv_stocktake(stocktake_no,warehouse_id,period,status) "
                + "values ('ST:IT-DDL:1',1,'2026-11','DRAFT')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stocktake"
                + "(stocktake_no,warehouse_id,period,status) values ('ST:IT-DDL:1',1,'2026-11','DRAFT')"),
                "stocktake_no 必须唯一");

        // 幂等表 request_id 主键 + action 列存在（CREATE/APPLY 隔离依据）。
        jdbc.update("insert into noon_m4_stocktake_request"
                + "(request_id,stocktake_id,warehouse_id,operator_id,action,content_fingerprint,status) "
                + "values ('IT-STK-REQ-1',0,1,1,'CREATE','" + "a".repeat(64) + "','PENDING')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_stocktake_request"
                + "(request_id,stocktake_id,warehouse_id,operator_id,action,content_fingerprint,status) "
                + "values ('IT-STK-REQ-1',0,1,1,'CREATE','" + "b".repeat(64) + "','PENDING')"),
                "request_id 必须主键唯一");
        assertEquals("CREATE", jdbc.queryForObject("select action from noon_m4_stocktake_request "
                + "where request_id='IT-STK-REQ-1'", String.class));

        // 消耗流水唯一键 (biz_event_key,batch_id)：同事件跨批两行合法、同批重传撞键。
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('SC:IT-1','STK:9:evt',1,1,1,'1.0000','1.0000',now(3),'STOCKTAKE',1,'2026-11',0)");
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('SC:IT-2','STK:9:evt',2,1,1,'1.0000','1.0000',now(3),'STOCKTAKE',1,'2026-11',0)");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch_consumption"
                + "(consumption_no,biz_event_key,batch_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                + "period,cross_period) values ('SC:IT-3','STK:9:evt',1,1,1,'1.0000','1.0000',now(3),"
                + "'STOCKTAKE',1,'2026-11',0)"), "同事件同批次必须被唯一键拦下");
    }

    // ------------------------------------------------------------ 端到端：入库 → 盘亏 → 盘盈

    @Test
    void createApplyLossGainEndToEndOnRealSchema()
    {
        long wh = newWarehouse("WH-ST-IT-E1", "AE");
        long sku = newSku("SKU-ST-IT-E1");
        long purOrder = newOrder("PO-IT-ST-E1", "AE", "RECEIVED");
        long purItem = newItem(purOrder, sku, 10, 10, "12.3400");
        newReceipt(purOrder, purItem, 10, "12.3400");
        InboundResult inbound = inboundService.confirm(new InboundInput(purOrder, wh, null,
                List.of(new InboundLineInput(purItem, 10)), rid()));
        long batch = inbound.batchIds().get(0);

        // ① create：真库写 DRAFT + 明细快照（qty_book=10），不动库存/不写流水。
        String createKey = rid();
        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", createKey,
                List.of(new StocktakeLineInput(sku, null, 7, "CNT-IT-E1"))));
        assertFalse(created.replayed());
        assertTrue(created.stocktakeNo().matches("ST:AE:[0-9A-F]{8}"), created.stocktakeNo());
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        assertEquals("2026-11", jdbc.queryForObject("select period from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        Map<String, Object> item = jdbc.queryForMap("select qty_book,qty_actual,qty_diff from inv_stocktake_item "
                + "where stocktake_id=?", created.stocktakeId());
        assertEquals(10, ((Number) item.get("qty_book")).intValue());
        assertEquals(-3, ((Number) item.get("qty_diff")).intValue());
        assertEquals(10, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh), "create 不动台账");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where ref_doc_type='STOCKTAKE'", Integer.class), "create 不写盘点流水（入库流水非盘点）");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_stocktake_request "
                + "where request_id=?", String.class, createKey));
        assertEquals(created.stocktakeId(), jdbc.queryForObject("select stocktake_id "
                + "from noon_m4_stocktake_request where request_id=?", Long.class, createKey),
                "DONE 回填真实 stocktake_id");

        // ② apply 盘亏 3：真库 CHECK/唯一键下 FIFO 扣批、写 STOCKTAKE 消耗、台账与流水。
        StocktakeResult applied = stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), rid()));
        assertFalse(applied.replayed());
        assertEquals("APPLIED", applied.status());
        assertEquals(7, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh));
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?", String.class, batch));

        long itemId = jdbc.queryForObject("select id from inv_stocktake_item where stocktake_id=?",
                Long.class, created.stocktakeId());
        Map<String, Object> consumption = jdbc.queryForMap("select biz_event_key,batch_id,qty,unit_cost,amount,"
                + "move_type,sign,period,source_event_id from inv_batch_consumption where move_type='STOCKTAKE'");
        assertEquals("STK:" + itemId + ":CNT-IT-E1", consumption.get("biz_event_key"));
        assertEquals(batch, ((Number) consumption.get("batch_id")).longValue());
        assertEquals(3, ((Number) consumption.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) consumption.get("unit_cost")));
        assertEquals(0, new BigDecimal("37.0200").compareTo((BigDecimal) consumption.get("amount")));
        Object sign = consumption.get("sign");
        assertEquals(1, sign instanceof Boolean b ? (b ? 1 : 0) : ((Number) sign).intValue(), "盘亏 sign=+1");
        assertEquals("2026-11", consumption.get("period"));
        assertEquals("CNT-IT-E1", consumption.get("source_event_id"));
        Map<String, Object> move = jdbc.queryForMap("select change_type,qty_delta,ref_doc_type,ref_doc_no "
                + "from inv_stock_move where ref_doc_type='STOCKTAKE'");
        assertEquals("STOCKTAKE_LOSS", move.get("change_type"));
        assertEquals(-3, ((Number) move.get("qty_delta")).intValue());
        assertEquals("STOCKTAKE", move.get("ref_doc_type"));
        assertEquals(created.stocktakeNo(), move.get("ref_doc_no"));

        // ③ 第二张单盘盈 2（账面 7、实盘 9）：ADJUSTMENT 承接现有 OPEN 批次成本 12.34、不写消耗。
        StocktakeResult gain = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 9, "CNT-IT-E2"))));
        stocktakeService.apply(new StocktakeApplyInput(gain.stocktakeId(), rid()));
        assertEquals(9, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh));
        Map<String, Object> adjust = jdbc.queryForMap("select batch_no,qty_in,qty_remaining,unit_cost,currency,"
                + "fx_rate,source_type,status from inv_batch where source_type='ADJUSTMENT'");
        assertTrue(String.valueOf(adjust.get("batch_no")).matches("B:STK:AE:[0-9A-F]{8}"),
                String.valueOf(adjust.get("batch_no")));
        assertEquals(2, ((Number) adjust.get("qty_in")).intValue());
        assertEquals(2, ((Number) adjust.get("qty_remaining")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) adjust.get("unit_cost")),
                "盘盈承接现有 OPEN 批次加权平均成本");
        assertEquals("CNY", adjust.get("currency"));
        assertEquals(0, new BigDecimal("1").compareTo((BigDecimal) adjust.get("fx_rate")));
        assertEquals("OPEN", adjust.get("status"));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption "
                + "where move_type='STOCKTAKE'", Integer.class), "盘盈不得新增消耗行");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='STOCKTAKE_GAIN' and qty_delta=2", Integer.class));

        // ④ 总量守恒：批次剩余 9 + 消耗 3 = 初始入库 10 + 盘盈 2；台账 = 批次剩余。
        assertEquals(12, jdbc.queryForObject("select (select coalesce(sum(qty_remaining),0) from inv_batch) "
                + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='STOCKTAKE')",
                Integer.class));
        assertEquals(9, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch", Integer.class));
        assertEquals(4, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit "
                + "where biz_type='STOCKTAKE'", Integer.class), "CREATE×2 + APPLY×2");
    }

    // ------------------------------------------------------------ 快照闸门：建单后真实出库 → 拒绝；重盘成功

    /**
     * Codex 场景真库复现：入库 10（账面=实物=10）→ 建单（快照 10、实盘 10）→ 真实出库 4（账面/实物同步 6）
     * → apply 必须被快照闸门整单拒绝（不得凭空盘盈），按新账面 6 重盘后才允许应用。
     */
    @Test
    void outboundAfterCreateRejectedAsStaleThenRecountApplies()
    {
        long wh = newWarehouse("WH-ST-IT-S1", "AE");
        long sku = newSku("SKU-ST-IT-S1");
        long order = newOrder("PO-IT-ST-S1", "AE", "RECEIVED");
        long purItem = newItem(order, sku, 10, 10, "12.3400");
        newReceipt(order, purItem, 10, "12.3400");
        long batch = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(purItem, 10)), rid())).batchIds().get(0);

        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 10, "CNT-IT-S1"))));
        assertEquals(10, bookOf(created.stocktakeId()), "建单快照 10");
        assertEquals(0, diffOf(created.stocktakeId()));

        // 建单后的真实出库 4：实物与账面同步降到 6（合法业务变动，不是测试造错）。
        jdbc.update("update inv_stock_ledger set qty_available=6 where sku_id=? and warehouse_id=?", sku, wh);
        jdbc.update("update inv_batch set qty_remaining=6 where id=?", batch);

        String applyKey = rid();
        StaleStocktakeException stale = assertThrows(StaleStocktakeException.class,
                () -> stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertTrue(stale.getMessage().contains("skuId=" + sku), stale.getMessage());
        assertTrue(stale.getMessage().contains("snapshotBook=10"), stale.getMessage());
        assertTrue(stale.getMessage().contains("currentBook=6"), stale.getMessage());
        assertTrue(stale.getMessage().contains("请重新盘点"), stale.getMessage());

        // 整单拒绝且零写入：台账/批次不变、无盘盈批次/流水/消耗、单仍 DRAFT、快照未被覆盖、PENDING 占位回滚。
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh), "台账仍 6");
        assertEquals(6, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "批次仍 6");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='ADJUSTMENT'",
                Integer.class), "不得凭空造盘盈批次");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move where ref_doc_type='STOCKTAKE'",
                Integer.class), "不得写 STOCKTAKE_GAIN/LOSS 流水");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='STOCKTAKE'",
                Integer.class));
        assertEquals(10, bookOf(created.stocktakeId()), "item.qty_book 保持建单快照");
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_stocktake_request where request_id=?",
                Integer.class, applyKey), "PENDING 占位随整单回滚");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));

        // 重盘：按新账面 6 新建单（实盘 6、快照 6）→ 应用成功、台账不变、无盘盈盘亏流水。
        StocktakeResult recount = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 6, "CNT-IT-S1B"))));
        assertEquals(6, bookOf(recount.stocktakeId()), "新单快照 = 新账面 6");
        assertEquals(0, diffOf(recount.stocktakeId()));
        StocktakeResult applied = stocktakeService.apply(new StocktakeApplyInput(recount.stocktakeId(), rid()));

        assertFalse(applied.replayed());
        assertEquals("APPLIED", applied.status());
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh), "实盘=账面，台账不变");
        assertEquals(6, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move where ref_doc_type='STOCKTAKE'",
                Integer.class), "无盘盈盘亏流水");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='STOCKTAKE'",
                Integer.class));
    }

    /** 建单后补入库（账面 10 → 13）同样整单拒绝，防用旧实盘算出凭空盘亏。 */
    @Test
    void inboundAfterCreateRejectedAsStale()
    {
        long wh = newWarehouse("WH-ST-IT-S2", "AE");
        long sku = newSku("SKU-ST-IT-S2");
        long order1 = newOrder("PO-IT-ST-S2A", "AE", "RECEIVED");
        long item1 = newItem(order1, sku, 10, 10, "10.0000");
        newReceipt(order1, item1, 10, "10.0000");
        long batch = inboundService.confirm(new InboundInput(order1, wh, null,
                List.of(new InboundLineInput(item1, 10)), rid())).batchIds().get(0);

        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 7, "CNT-IT-S2"))));
        assertEquals(-3, diffOf(created.stocktakeId()), "建单快照差 -3");

        // 建单后补入库 3：账面 10 → 13（实物同步 13）。
        long order2 = newOrder("PO-IT-ST-S2B", "AE", "RECEIVED");
        long item2 = newItem(order2, sku, 3, 3, "10.0000");
        newReceipt(order2, item2, 3, "10.0000");
        inboundService.confirm(new InboundInput(order2, wh, null, List.of(new InboundLineInput(item2, 3)), rid()));

        StaleStocktakeException stale = assertThrows(StaleStocktakeException.class,
                () -> stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), rid())));
        assertTrue(stale.getMessage().contains("snapshotBook=10"), stale.getMessage());
        assertTrue(stale.getMessage().contains("currentBook=13"), stale.getMessage());
        assertEquals(13, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh), "拒绝后不得凭空盘亏");
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "不得按旧实盘扣批次");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move where ref_doc_type='STOCKTAKE'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='STOCKTAKE'",
                Integer.class));
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        assertEquals(10, bookOf(created.stocktakeId()));
    }

    // ------------------------------------------------------------ 零成本盘盈闸门

    /** 无任何 OPEN 批次（无台账）时盘盈 → 真库上整单拒绝，不得造零成本 ADJUSTMENT 批次进 FIFO。 */
    @Test
    void zeroCostGainRejectedOnRealSchema()
    {
        long wh = newWarehouse("WH-ST-IT-Z1", "AE");
        long sku = newSku("SKU-ST-IT-Z1");

        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 5, "CNT-IT-Z1"))));
        assertEquals(0, bookOf(created.stocktakeId()), "无台账快照 0");
        assertEquals(5, diffOf(created.stocktakeId()));

        ZeroCostGainException zero = assertThrows(ZeroCostGainException.class,
                () -> stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), rid())));
        assertTrue(zero.getMessage().contains("skuId=" + sku), zero.getMessage());
        assertTrue(zero.getMessage().contains("warehouseId=" + wh), zero.getMessage());
        assertTrue(zero.getMessage().contains("gain=5"), zero.getMessage());
        assertTrue(zero.getMessage().contains("无可追溯成本，请先补录采购价或由业务确认估值"), zero.getMessage());

        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_ledger where sku_id=?",
                Integer.class, sku), "拒绝后不得新建台账行");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where sku_id=?",
                Integer.class, sku), "拒绝后不得造零成本批次");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move",
                Integer.class));
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        assertEquals(5, diffOf(created.stocktakeId()), "快照差保持建单值");
    }

    // ------------------------------------------------------------ 并发同键

    @Test
    void concurrentSameApplyRequestIdAppliesOnce() throws Exception
    {
        long wh = newWarehouse("WH-ST-IT-C1", "AE");
        long sku = newSku("SKU-ST-IT-C1");
        long order = newOrder("PO-IT-ST-C1", "AE", "RECEIVED");
        long purItem = newItem(order, sku, 10, 10, "10.0000");
        newReceipt(order, purItem, 10, "10.0000");
        inboundService.confirm(new InboundInput(order, wh, null, List.of(new InboundLineInput(purItem, 10)), rid()));

        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 4, "CNT-IT-C1"))));
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
                    StocktakeResult result = stocktakeService.apply(
                            new StocktakeApplyInput(created.stocktakeId(), requestId));
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
                "同一 apply requestId 并发只能一次真实过账: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(4, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh), "台账只扣一次");
        assertEquals(4, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch",
                Integer.class), "批次只扣一次");
        assertEquals(6, jdbc.queryForObject("select coalesce(sum(qty),0) from inv_batch_consumption where "
                + "move_type='STOCKTAKE'", Integer.class), "只写一条消耗且总量 6");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move where "
                + "change_type='STOCKTAKE_LOSS'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_stocktake_request where request_id=?",
                String.class, requestId));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_stocktake_request where "
                + "status='PENDING'", Integer.class), "不得残留 PENDING 占位");
    }

    // ------------------------------------------------------------ 真库唯一键防双计

    @Test
    void duplicateStkEventBlockedByRealUniqueKeyAndRollsBack()
    {
        long wh = newWarehouse("WH-ST-IT-F1", "AE");
        long sku = newSku("SKU-ST-IT-F1");
        long order = newOrder("PO-IT-ST-F1", "AE", "RECEIVED");
        long purItem = newItem(order, sku, 10, 10, "10.0000");
        newReceipt(order, purItem, 10, "10.0000");
        long batch = inboundService.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(purItem, 10)), rid())).batchIds().get(0);

        StocktakeResult created = stocktakeService.create(new StocktakeInput(wh, "2026-11", rid(),
                List.of(new StocktakeLineInput(sku, null, 6, "CNT-IT-F1"))));
        long itemId = jdbc.queryForObject("select id from inv_stocktake_item where stocktake_id=?",
                Long.class, created.stocktakeId());

        // 预置同事件键 + 同批次消耗（模拟另一路径已过账），服务再记同事件必须被真库唯一键拦下。
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('SC:IT-MANUAL',?,?,?,1,'10.0000','10.0000',now(3),'STOCKTAKE',1,'2026-11',0)",
                "STK:" + itemId + ":CNT-IT-F1", batch, sku);
        String applyKey = rid();

        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class,
                () -> stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertTrue(duplicated.getMessage().contains("盘点事件已过账"), duplicated.getMessage());

        // 整事务回滚：批次/台账未动、单据仍 DRAFT、无流水、幂等占位与审计一起回滚。
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption", Integer.class),
                "唯一键拦截后不得双计");
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals(10, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where ref_doc_type='STOCKTAKE'", Integer.class), "失败事务不得留下盘点流水");
        assertEquals(10, jdbc.queryForObject("select qty_book from inv_stocktake_item where id=?",
                Integer.class, itemId), "行快照未被失败事务改动");
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_stocktake where id=?",
                String.class, created.stocktakeId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_stocktake_request where request_id=?",
                Integer.class, applyKey), "失败事务的 PENDING 占位必须回滚");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));

        // 清理预置行后同键重试可成功（回滚不会留下半成品）。
        jdbc.execute("delete from inv_batch_consumption where consumption_no='SC:IT-MANUAL'");
        assertFalse(stocktakeService.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)).replayed());
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and "
                + "warehouse_id=?", Integer.class, sku, wh));
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

    private int bookOf(long stocktakeId)
    {
        return jdbc.queryForObject("select qty_book from inv_stocktake_item where stocktake_id=?",
                Integer.class, stocktakeId);
    }

    private int diffOf(long stocktakeId)
    {
        return jdbc.queryForObject("select qty_diff from inv_stocktake_item where stocktake_id=?",
                Integer.class, stocktakeId);
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

    private void clean()
    {
        for (String table : new String[]{"noon_m4_stocktake_request", "inv_stocktake_item", "inv_stocktake",
                "noon_m4_inbound_request", "noon_m4_inventory_audit", "inv_stock_move",
                "inv_stock_ledger", "inv_batch_consumption", "inv_batch", "inv_inbound_item", "inv_inbound",
                "noon_m3_receipt", "pur_order_item", "pur_order"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_sku where sku_code like 'SKU-ST-IT-%' or sku_code like 'SKU-IT-%'");
        jdbc.execute("delete from md_warehouse where warehouse_code like 'WH-ST-IT-%' or warehouse_code like 'WH-IT-%'");
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
            String database = "noon_m4_stk_" + UUID.randomUUID().toString().replace("-", "");
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
        StocktakeService stocktakeService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new StocktakeService(jdbcTemplate, guard, audit);
        }
    }
}
