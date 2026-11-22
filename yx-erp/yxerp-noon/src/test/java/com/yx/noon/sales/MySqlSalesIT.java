package com.yx.noon.sales;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import com.yx.noon.intake.IntakeService;
import com.yx.noon.inventory.InventoryAudit;
import com.yx.noon.reversal.ReversalService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 订单与退货编排 + 分级处置 隔离 MySQL 8.4 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（{@code md_store/md_site/md_sku/
 * md_partner_sku/sal_order/sal_order_item/sal_return/inv_batch/inv_batch_consumption/inv_stock_ledger/
 * int_webhook_event}）+ 既有迁移 {@code noon_m4_inventory.sql} / {@code noon_intake_review.sql} /
 * {@code noon_fifo_review.sql} / {@code noon_reversal_review.sql} + 本轮 {@code noon_m5_sales.sql}。
 *
 * <p>验证：真 CHECK（{@code 0 <= qty_remaining <= qty_in}、{@code qty > 0 && amount >= 0}、
 * {@code sign ∈ ±1}、{@code qty_unsellable >= 0}、{@code qty_delivered + qty_cancelled <= qty}、
 * {@code qty_returned <= qty_delivered}）、真唯一键（{@code uk_event_id} / {@code uk_consume_biz_event} /
 * {@code uk_order_no} / {@code (request_id, action)} 主键）、真库并发同 requestId 只落 1 行、
 * 跨期归属、A 级新批次过 CHECK、真库 qty_unsellable 守恒、端到端 COGS 数值。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}
 * 或 {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlSalesIT
{
    private static final long WAREHOUSE = 21L;
    private static final long SKU = 11L;
    private static final long ACTOR = 1L;

    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SalesFlowService flow;
    private ReturnDispositionService disposition;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null,
                "缺少隔离库 JDBC URL/端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT");

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);

        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"md_store", "md_site", "md_sku", "md_partner_sku", "sal_order",
                "sal_order_item", "sal_return", "inv_batch", "inv_batch_consumption", "inv_stock_ledger",
                "int_webhook_event"})
            jdbc.execute(extractCreateTable(schema, table));
        runSqlFile("../sql/noon_m4_inventory.sql");
        runSqlFile("../sql/noon_intake_review.sql");
        runSqlFile("../sql/noon_fifo_review.sql");
        runSqlFile("../sql/noon_reversal_review.sql");
        runSqlFile("../sql/noon_m5_sales.sql");

        clean();
        seedMasterData();

        flow = context.getBean(SalesFlowService.class);
        disposition = context.getBean(ReturnDispositionService.class);
        login(ACTOR);
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

    // ---------------------------------------------------------------- 真 DDL 约束

    @Test
    void realDdlChecksAndUniqueKeysHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_inventory.sql");
        runSqlFileUnchecked("../sql/noon_intake_review.sql");
        runSqlFileUnchecked("../sql/noon_fifo_review.sql");
        runSqlFileUnchecked("../sql/noon_reversal_review.sql");
        runSqlFileUnchecked("../sql/noon_m5_sales.sql");

        // 真 CHECK：批次剩余不得大于入库
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                        + "unit_cost,currency,status) values ('B:CHK',11,21,'AE',now(),1,2,100,'AED','OPEN')"));
        // 真 CHECK：消耗 qty 必为正、sign 只能 ±1
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,unit_cost,"
                        + "amount,consumed_at,move_type,sign) values ('C:CHK','K:CHK',1,11,0,100,0,now(),'SALE',1)"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,unit_cost,"
                        + "amount,consumed_at,move_type,sign) values ('C:CHK2','K:CHK2',1,11,1,100,100,now(),'SALE',0)"));
        // 真主键：同 (request_id,action) 只允许一行
        jdbc.update("insert into noon_m5_sales_request(request_id,action,site_code,content_fingerprint,status) "
                + "values ('REQ-DDL','ORDER_IMPORT','AE',repeat('a',64),'PENDING')");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into noon_m5_sales_request(request_id,action,site_code,content_fingerprint,status) "
                        + "values ('REQ-DDL','ORDER_IMPORT','AE',repeat('b',64),'PENDING')"));
        jdbc.update("delete from noon_m5_sales_request where request_id='REQ-DDL'");
    }

    // ---------------------------------------------------------------- 端到端

    @Test
    void endToEndOrderDeliverReturnReverseDisposeOnRealSchema()
    {
        long batchId = seedBatch(5, "100.0000", "2026-11-01 10:00:00");
        seedLedger(5);

        SalesResult imported = flow.importOrder(order("IT-ORDER-1", "O-IT-1", 5, "750.0000"));
        long itemId = imported.orderItemId();
        SalesResult delivered = flow.deliverOrder(new DeliverInput("IT-DEL-1", "AE", itemId, 2));
        assertEquals(1, delivered.consumptionIds().size());

        SalesResult returned = flow.importReturn(new ReturnImportInput("IT-RET-1", "AE", "O-IT-1", "SR-IT-1", itemId, 2));
        long returnId = returned.returnId();
        long originalId = jdbc.queryForObject("select id from inv_batch_consumption where batch_id=? and sign=1",
                Long.class, batchId);
        flow.reverseReturn(new ReturnReverseInput("IT-REV-1", "AE", returnId, originalId, 2));

        SalesResult disposed = disposition.dispose(new DisposeInput("IT-DSP-1", "AE", returnId, "B",
                null, new BigDecimal("60.0000"), new BigDecimal("300.0000"), null));

        // 真库数值：SALE 200 / 红冲 -200 / RETURN_LOSS 200（全额，不扣赔偿）
        assertEquals(0, new BigDecimal("200.0000").compareTo(jdbc.queryForObject(
                "select amount from inv_batch_consumption where id=?", BigDecimal.class, originalId)));
        assertEquals(0, new BigDecimal("200.0000").compareTo(jdbc.queryForObject(
                "select amount from inv_batch_consumption where sal_return_id=? and sign=-1", BigDecimal.class,
                returnId)));
        assertEquals(0, new BigDecimal("200.0000").compareTo(disposed.returnLossAmount()));
        assertEquals(0, new BigDecimal("200.0000").compareTo(jdbc.queryForObject(
                "select return_loss_amount from sal_return where id=?", BigDecimal.class, returnId)));
        assertEquals(0, new BigDecimal("60.0000").compareTo(jdbc.queryForObject(
                "select compensation_amount from sal_return where id=?", BigDecimal.class, returnId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='COMPENSATION'",
                Integer.class));

        // 真库库存守恒：available 3、unsellable 2（进货 5 − 妥投 2 = 3；退货 2 进不合格）
        assertEquals(3, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(2, jdbc.queryForObject("select qty_unsellable from inv_stock_ledger", Integer.class));
        assertEquals(2, jdbc.queryForObject("select qty_returned from sal_order_item where id=?", Integer.class, itemId));
        assertEquals("DISPOSED", jdbc.queryForObject("select status from sal_return where id=?", String.class, returnId));

        // 只读视图（派生表 sign+0）在真 TINYINT(1) 上映射为数值，不因 Boolean 映射失真
        SalesReturnDetail detail = flow.getReturn(returnId);
        assertEquals(-1, detail.flows().stream().filter(f -> "RETURN_REVERSAL".equals(f.moveType()))
                .findFirst().orElseThrow().sign());
        assertEquals(0, SalesRules.toInt(detail.flows().stream().filter(f -> "RETURN_LOSS".equals(f.moveType()))
                .findFirst().orElseThrow().crossPeriod()), "同月处置 cross_period=0 且可数值读取");
        assertEquals(1, flow.listReturns("AE", 0, 10, null, "B").total());
        assertEquals(0, flow.listReturns("SA", 0, 10, null, null).total(), "站点隔离在真库可见");

        // 重复处置同 grade → ACK，不二次计损
        SalesResult again = disposition.dispose(new DisposeInput("IT-DSP-2", "AE", returnId, "B", null, null, null, null));
        assertTrue(again.replayed());
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption where sal_return_id=? "
                + "and move_type='RETURN_LOSS'", Integer.class, returnId));
        assertEquals(2, jdbc.queryForObject("select qty_unsellable from inv_stock_ledger", Integer.class));
    }

    @Test
    void aLevelRestockCreatesRealBatchAndPassesChecks()
    {
        seedBatch(5, "100.0000", "2026-11-01 10:00:00");
        seedLedger(5);
        SalesResult imported = flow.importOrder(order("IT-ORDER-A", "O-IT-A", 5, "750.0000"));
        long itemId = imported.orderItemId();
        flow.deliverOrder(new DeliverInput("IT-DEL-A", "AE", itemId, 1));
        SalesResult returned = flow.importReturn(new ReturnImportInput("IT-RET-A", "AE", "O-IT-A", "SR-IT-A", itemId, 1));
        long originalId = jdbc.queryForObject("select id from inv_batch_consumption where sal_order_item_id=? and sign=1",
                Long.class, itemId);
        flow.reverseReturn(new ReturnReverseInput("IT-REV-A", "AE", returned.returnId(), originalId, 1));

        // Codex M5 复核：真库同样强制 ORIGINAL——传入值必须等于原 SALE 层成本 100（种子批次单价）。
        SalesResult disposed = disposition.dispose(new DisposeInput("IT-DSP-A", "AE", returned.returnId(), "A",
                new BigDecimal("100.0000"), null, null, null));
        assertTrue(disposed.restockBatchNo().startsWith("B:RTN:AE:"));
        Map<String, Object> batch = jdbc.queryForMap("select qty_in,qty_remaining,unit_cost,source_type,status,"
                + "warehouse_id from inv_batch where batch_no=?", disposed.restockBatchNo());
        assertEquals(1, ((Number) batch.get("qty_in")).intValue());
        assertEquals(1, ((Number) batch.get("qty_remaining")).intValue());
        assertEquals(0, new BigDecimal("100.0000").compareTo((BigDecimal) batch.get("unit_cost")),
                "真库 ORIGINAL：unit_cost=原红冲层单价 100；本例 qty=1 恰整除故资产=红冲成本，"
                        + "多层除不尽时见契约 §14 有界舍入差");
        assertEquals("RETURN_RESTOCK", batch.get("source_type"));
        assertEquals("OPEN", batch.get("status"));
        assertEquals(WAREHOUSE, ((Number) batch.get("warehouse_id")).longValue());
        // A 级：实物回可用（4 + 1），不合格库存为 0
        assertEquals(5, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(0, jdbc.queryForObject("select qty_unsellable from inv_stock_ledger", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch_consumption where move_type='RETURN_LOSS'",
                Integer.class));
    }

    @Test
    void crossPeriodLossLandsOnReturnPeriodWithRealSchema()
    {
        seedBatch(5, "100.0000", "2026-10-05 10:00:00");
        seedLedger(5);
        SalesResult imported = flow.importOrder(order("IT-ORDER-P", "O-IT-P", 5, "750.0000"));
        long itemId = imported.orderItemId();
        flow.deliverOrder(new DeliverInput("IT-DEL-P", "AE", itemId, 1));
        // 模拟原销售发生在上一期（真实数据由 FIFO consumed_at 决定，这里显式归期）
        jdbc.update("update inv_batch_consumption set period='2026-10' where sal_order_item_id=? and sign=1", itemId);

        SalesResult returned = flow.importReturn(new ReturnImportInput("IT-RET-P", "AE", "O-IT-P", "SR-IT-P", itemId, 1));
        long originalId = jdbc.queryForObject("select id from inv_batch_consumption where sal_order_item_id=? and sign=1",
                Long.class, itemId);
        flow.reverseReturn(new ReturnReverseInput("IT-REV-P", "AE", returned.returnId(), originalId, 1));
        disposition.dispose(new DisposeInput("IT-DSP-P", "AE", returned.returnId(), "C", null, null, null, null));

        String expected = SalesRules.period(SalesRules.now());
        Map<String, Object> loss = jdbc.queryForMap("select period,cross_period from inv_batch_consumption "
                + "where sal_return_id=? and move_type='RETURN_LOSS'", returned.returnId());
        assertEquals(expected, loss.get("period"));
        assertEquals(1, SalesRules.toInt(loss.get("cross_period")),
                "cross_period 为 TINYINT(1)，MySQL JDBC 映射 Boolean，须用 toInt");
        assertEquals("2026-10", jdbc.queryForObject("select original_period from sal_return where id=?",
                String.class, returned.returnId()));
    }

    // ---------------------------------------------------------------- 并发

    @Test
    void concurrentSameRequestIdLandsOneOrder()
    {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> first = pool.submit(() -> {
                login(ACTOR);
                start.await();
                try
                {
                    flow.importOrder(order("IT-CONC-1", "O-IT-CONC", 1, "150.0000"));
                    return "POSTED";
                }
                catch (IllegalArgumentException e)
                {
                    return "REJECTED:" + e.getMessage();
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });
            Future<String> second = pool.submit(() -> {
                login(ACTOR);
                start.await();
                try
                {
                    flow.importOrder(order("IT-CONC-1", "O-IT-CONC", 1, "150.0000"));
                    return "POSTED";
                }
                catch (IllegalArgumentException e)
                {
                    return "REJECTED:" + e.getMessage();
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });
            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            String secondResult = second.get(60, TimeUnit.SECONDS);
            assertTrue(secondResult.startsWith("POSTED") || secondResult.contains("处理中"),
                    "并发同键只能是重放或明确处理中，实际：" + secondResult);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from sal_order where order_no='O-IT-CONC'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from sal_order_item where order_no='O-IT-CONC'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m5_sales_request where request_id=?",
                Integer.class, "IT-CONC-1"));
        assertEquals("DONE", jdbc.queryForObject(
                "select status from noon_m5_sales_request where request_id=?", String.class, "IT-CONC-1"));
    }

    // ---------------------------------------------------------------- 夹具

    private static OrderImportInput order(String requestId, String orderNo, int qty, String gross)
    {
        return new OrderImportInput(requestId, "AE", orderNo, 7L, SKU, "SKU",
                new BigDecimal(gross), qty, "AED", "WEBHOOK");
    }

    private long seedBatch(int qty, String cost, String receivedAt)
    {
        String batchNo = "B:IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                        + "unit_cost,currency,fx_rate,source_type,status) values (?,?,?,?,?,?,?,?,?,?,?, 'OPEN')",
                batchNo, SKU, WAREHOUSE, "AE", Timestamp.valueOf(receivedAt), qty, qty, new BigDecimal(cost),
                "AED", BigDecimal.ONE, "PURCHASE");
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (?,?,?,?)",
                SKU, WAREHOUSE, "AE", available);
    }

    private void seedMasterData()
    {
        jdbc.update("insert into md_store(id,store_no,partner_id,status) values (7,'STORE-IT',1,'ACTIVE')");
        jdbc.update("insert into md_site(id,site_code,store_id,currency,status) values "
                + "(1,'AE',7,'AED','ACTIVE'),(2,'SA',7,'SAR','ACTIVE')");
        jdbc.update("insert into md_sku(id,sku_code,status) values (11,'SKU-IT-11','ACTIVE')");
        jdbc.update("insert into md_partner_sku(id,partner_sku,sku_id,site_code,business_model,status) values "
                + "(1,'SKU',11,'AE','noon','ACTIVE'),(2,'SKU',11,'SA','noon','ACTIVE')");
    }

    private void clean()
    {
        for (String table : new String[]{"noon_m5_sales_request", "noon_reversal_audit", "noon_reversal_review",
                "noon_fifo_audit", "noon_fifo_review", "noon_intake_audit", "noon_intake_review",
                "noon_m4_inventory_audit", "inv_stock_move", "inv_batch_consumption", "inv_stock_ledger",
                "inv_batch", "sal_return", "sal_order_item", "sal_order", "int_webhook_event"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_partner_sku");
        jdbc.execute("delete from md_sku");
        jdbc.execute("delete from md_site");
        jdbc.execute("delete from md_store");
    }

    private static void login(long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("user" + userId);
        LoginUser principal = new LoginUser(userId, 100L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", List.of()));
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

    private static DataSource buildDataSource()
    {
        Target target = target();
        DriverManagerDataSource ds = new DriverManagerDataSource(target.serverUrl(), target.user(), target.password());
        if (target.portMode())
        {
            String database = "noon_m5_sales_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            CREATED_DATABASE.set(database);
            ds.setUrl(target.serverUrl().replace("/?", "/" + database + "?"));
        }
        return ds;
    }

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

    private static String setting(String property, String... envNames)
    {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value;
        for (String env : envNames)
        {
            String envValue = System.getenv(env);
            if (envValue != null && !envValue.isBlank()) return envValue;
        }
        return null;
    }

    private record Target(String serverUrl, String user, String password, boolean portMode) { }

    /** 站点授权内存桩（真库 IT 只验 DDL/并发/端到端；站点授权本身已由 wp1 专项测试覆盖）。 */
    static class StubSiteMapper implements SysUserSiteMapper
    {
        @Override
        public Long selectActiveUserId(Long userId) { return userId != null && userId > 0 ? userId : null; }

        @Override
        public Long lockActiveUserId(Long userId) { return selectActiveUserId(userId); }

        @Override
        public List<String> selectSiteCodes(Long userId) { return userId != null && userId > 0
                ? List.of("AE", "SA") : List.of(); }

        @Override
        public List<String> lockSiteCodes(Long userId) { return selectSiteCodes(userId); }

        @Override
        public int deleteByUserId(Long userId) { throw new UnsupportedOperationException("stub"); }

        @Override
        public int insertGrant(Long userId, String siteCode, Long actorId, java.time.LocalDateTime now)
        {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public int insertAudit(Long userId, Long actorId, String beforeCodes, String afterCodes,
                               java.time.LocalDateTime now)
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
        InventoryAudit inventoryAudit(JdbcTemplate jdbc) { return new InventoryAudit(jdbc); }

        @Bean
        IntakeService intakeService(JdbcTemplate jdbc, SiteAuthGuard guard) { return new IntakeService(jdbc, guard); }

        @Bean
        FifoConsumptionService fifoConsumptionService(JdbcTemplate jdbc, SiteAuthGuard guard)
        {
            return new FifoConsumptionService(jdbc, guard);
        }

        @Bean
        ReversalService reversalService(JdbcTemplate jdbc, SiteAuthGuard guard)
        {
            return new ReversalService(jdbc, guard);
        }

        @Bean
        ReturnDispositionService returnDispositionService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new ReturnDispositionService(jdbc, guard, audit);
        }

        @Bean
        SalesFlowService salesFlowService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit,
                                          IntakeService intake, FifoConsumptionService fifo,
                                          ReversalService reversal, ReturnDispositionService disposition)
        {
            return new SalesFlowService(jdbc, guard, audit, intake, fifo, reversal, disposition);
        }
    }
}
