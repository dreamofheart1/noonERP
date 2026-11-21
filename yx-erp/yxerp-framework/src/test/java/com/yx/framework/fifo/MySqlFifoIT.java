package com.yx.framework.fifo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.fifo.FifoConsumptionService.DeliveryInput;
import com.yx.framework.fifo.FifoConsumptionService.DeliveryLine;
import com.yx.framework.fifo.FifoConsumptionService.Result;
import com.yx.framework.reversal.ReversalService;
import com.yx.framework.reversal.ReversalService.ReversalInput;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/** NOON-20261121-FIFO 隔离 MySQL 集成验证（opt-in）：用真实 schema v0.5 DDL 验证 FIFO/守恒/并发在真库成立。 */
@EnabledIfEnvironmentVariable(named = "NOON_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlFifoIT
{
    private static final ThreadLocal<String> DATABASE = new ThreadLocal<>();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private FifoConsumptionService service;
    private ReversalService reversalService;

    private static DataSource serverDataSource()
    {
        int port = Integer.parseInt(System.getenv("NOON_MYSQL_TEST_PORT"));
        String password = System.getenv("NOON_MYSQL_TEST_PASSWORD");
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + port + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai",
                "root", password);
    }

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(MysqlConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        String siteSql = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String s : siteSql.split(";")) if (!s.isBlank()) jdbc.execute(s);

        String reviewSql = Files.readString(Path.of("../sql/noon_fifo_review.sql"));
        for (String s : reviewSql.split(";"))
        {
            String t = stripComments(s);
            if (!t.isBlank()) jdbc.execute(t);
        }

        // 红冲隔离层 DDL（端到端：FIFO 流水 → 退货 → 红冲，失败时隔离落点须存在）。
        String reversalReviewSql = Files.readString(Path.of("../sql/noon_reversal_review.sql"));
        for (String s : reversalReviewSql.split(";"))
        {
            String t = stripComments(s);
            if (!t.isBlank()) jdbc.execute(t);
        }

        // 真实 schema DDL：sal_order / sal_order_item / inv_batch / inv_batch_consumption /
        // inv_stock_ledger / int_webhook_event / sal_return（端到端可信父退货）。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"sal_order", "sal_order_item", "inv_batch", "inv_batch_consumption",
                "inv_stock_ledger", "int_webhook_event", "sal_return"})
            jdbc.execute(extractCreateTable(schema, table));

        service = context.getBean(FifoConsumptionService.class);
        reversalService = context.getBean(ReversalService.class);
        login(1L);
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

    private static String stripComments(String sql)
    {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R"))
            if (!line.stripLeading().startsWith("--")) out.append(line).append('\n');
        return out.toString();
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
        String database = DATABASE.get();
        if (database != null)
        {
            new JdbcTemplate(serverDataSource()).execute("drop database if exists `" + database + "`");
            DATABASE.remove();
        }
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

    private long seedOrder(String site, String orderNo)
    {
        jdbc.update("insert into sal_order(site_code,order_no,store_id,partner_sku,qty,gross_amount,currency) "
                + "values (?,?,1,'SKU',10,100,'AED')", site, orderNo);
        return jdbc.queryForObject("select id from sal_order where order_no=? and site_code=?", Long.class, orderNo, site);
    }

    private long seedItem(long orderId, long sku, int qty)
    {
        jdbc.update("insert into sal_order_item(sal_order_id,order_no,sku_id,qty,unit_price) "
                + "values (?,?,?,?,10)", orderId, "ON:" + UUID.randomUUID(), sku, qty);
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=? and sku_id=?", Long.class, orderId, sku);
    }

    private long seedBatch(String site, long sku, long wh, String receivedAt, int qty, String cost)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                + "unit_cost,status) values (?,?,?,?,?,?,?,?,'OPEN')",
                "B:" + UUID.randomUUID(), sku, wh, site, Timestamp.valueOf(receivedAt), qty, qty, new BigDecimal(cost));
        return jdbc.queryForObject("select id from inv_batch where batch_no like 'B:%' and sku_id=? order by id desc limit 1",
                Long.class, sku);
    }

    private void seedLedger(String site, long sku, long wh, int avail)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (?,?,?,?)",
                sku, wh, site, avail);
    }

    private void seedEvent(String eventId, String site, String payload)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                        + "values (?,?,?,?,current_timestamp(3),'PROCESSED')",
                eventId, "SALE_DELIVERY", site, payload);
    }

    private static String payload(long itemId, long sku, int qty)
    {
        return "{\"sal_order_item_id\":" + itemId + ",\"lines\":[{\"sku_id\":" + sku + ",\"qty\":" + qty + "}]}";
    }

    private static DeliveryInput input(String site, String eventId, long itemId, long sku, int qty, String raw)
    {
        return new DeliveryInput(site, eventId, itemId, List.of(new DeliveryLine(sku, qty)), raw);
    }

    private static String payloadOf(long itemId, List<DeliveryLine> lines)
    {
        StringBuilder sb = new StringBuilder("{\"sal_order_item_id\":").append(itemId).append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++)
        {
            if (i > 0) sb.append(',');
            sb.append("{\"sku_id\":").append(lines.get(i).skuId())
                    .append(",\"qty\":").append(lines.get(i).qty()).append('}');
        }
        return sb.append("]}").toString();
    }

    private static DeliveryInput inputOf(String site, String eventId, long itemId, List<DeliveryLine> lines, String raw)
    {
        return new DeliveryInput(site, eventId, itemId, lines, raw);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    /** 端到端：建真实 sal_return 父退货（真实 DDL 必填字段齐备），返回其 id。 */
    private long seedReturn(String site, long sku, long itemId, int qty)
    {
        jdbc.update("insert into sal_return(return_no,biz_return_key,order_no,site_code,sku_id,qty,"
                        + "sal_order_item_id,status) values (?,?,?,?,?,?,?,'OPEN')",
                "R:" + UUID.randomUUID(), "BR:" + UUID.randomUUID(), "O:" + UUID.randomUUID(),
                site, sku, qty, itemId);
        return jdbc.queryForObject("select id from sal_return where sku_id=? and qty=? order by id desc limit 1",
                Long.class, sku, qty);
    }

    /** 端到端：种系统侧红冲事件（内部契约 RETURN_REVERSAL），payload 与红冲入参同内容。 */
    private void seedReversalEvent(String eventId, String site, String payload)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                        + "values (?,'RETURN_REVERSAL',?,?,current_timestamp(3),'PROCESSED')",
                eventId, site, payload);
    }

    private static String reversalPayload(long returnId, long originalId, int qty, long sku)
    {
        return "{\"sal_return_id\":" + returnId + ",\"reversal_of_id\":" + originalId
                + ",\"qty\":" + qty + ",\"sku_id\":" + sku + "}";
    }

    @Test
    void fifoOrderAndConservationOnRealSchema()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000");
        seedBatch("AE", 11L, 100L, "2026-11-05 00:00:00", 5, "12.0000");
        String raw = payload(item, 11L, 7);
        seedEvent("EVT-R1", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-R1", item, 11L, 7, raw));
        assertTrue(r.posted(), "真库应过账");
        assertEquals(2, count("inv_batch_consumption"), "跨 2 批应写 2 行");
        assertEquals(5, jdbc.queryForObject("select sum(qty) from inv_batch_consumption where unit_cost=10.0000", Integer.class));
        assertEquals(2, jdbc.queryForObject("select sum(qty) from inv_batch_consumption where unit_cost=12.0000", Integer.class));
        assertEquals(13, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where status='CLOSED'", Integer.class), "老批扣满关批");
    }

    @Test
    void negativeSignTinyIntColumnReadsNumericallyOnRealSchema()
    {
        // 真实 schema sign TINYINT(1)：服务写 sign=1 后，读回必须是数值语义（当前读 sum 用 sign=1）。
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-R2", "AE", raw);
        assertTrue(service.postDelivery(input("AE", "EVT-R2", item, 11L, 2, raw)).posted());
        // 累计妥投守恒 sumPostedOnItem 用 sign=1 过滤；若 TINYINT→Boolean 映射错误会读不到、导致第二次超限误判。
        String raw2 = payload(item, 11L, 2);
        seedEvent("EVT-R2B", "AE", raw2);
        assertTrue(service.postDelivery(input("AE", "EVT-R2B", item, 11L, 2, raw2)).posted(),
                "第二次妥投应能正确读取已投 2，累计 4<=10");
        assertEquals(4, jdbc.queryForObject("select sum(qty) from inv_batch_consumption", Integer.class));
    }

    @Test
    void concurrentSameEventOnlyPostsOnceOnRealSchema() throws Exception
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 20, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-R3", "AE", raw);
        DeliveryInput in = input("AE", "EVT-R3", item, 11L, 2, raw);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postDelivery(in); });
        Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postDelivery(in); });
        start.countDown();
        Result r1 = f1.get(), r2 = f2.get();
        pool.shutdownNow();
        login(1L);
        assertTrue(r1.posted() && r2.posted());
        assertEquals(2, jdbc.queryForObject("select coalesce(sum(qty),0) from inv_batch_consumption", Integer.class),
                "同事件并发只扣一次，不双计");
    }

    @Test
    void crossSiteSameSkuOnlyConsumesOwnSiteBatchOnRealSchema()
    {
        long aeOrder = seedOrder("AE", "O-AE");
        long aeItem = seedItem(aeOrder, 11L, 10);
        long saOrder = seedOrder("SA", "O-SA");
        long saItem = seedItem(saOrder, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedLedger("SA", 11L, 200L, 20);
        long saBatch = seedBatch("SA", 11L, 200L, "2026-11-01 00:00:00", 5, "10.0000");
        long aeBatch = seedBatch("AE", 11L, 100L, "2026-11-03 00:00:00", 5, "12.0000");
        String raw = payload(aeItem, 11L, 3);
        seedEvent("EVT-RX", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-RX", aeItem, 11L, 3, raw));
        assertTrue(r.posted(), "真库 AE 请求应只扣 AE 批");
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, saBatch),
                "SA 批次不应被扣，剩余量应保持 5");
        assertEquals(2, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, aeBatch));
    }

    @Test
    void itemSkuMismatchQuarantinedOnRealSchema()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 22L, 100L, 20);
        seedBatch("AE", 22L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 22L, 2);
        seedEvent("EVT-RI", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-RI", item, 22L, 2, raw));
        assertFalse(r.posted());
        assertEquals("ITEM_MISMATCH", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"));
    }

    @Test
    void deliveredCountSyncedOnRealSchema()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 3);
        seedEvent("EVT-RD2", "AE", raw);
        assertTrue(service.postDelivery(input("AE", "EVT-RD2", item, 11L, 3, raw)).posted());
        assertEquals(3, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, item));
        assertEquals(3, jdbc.queryForObject("select coalesce(sum(qty),0) from inv_batch_consumption", Integer.class));
    }

    @Test
    void sameBatchMultipleLinesNoUniqueKeyOnRealSchema()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        List<DeliveryLine> lines = List.of(new DeliveryLine(11L, 2), new DeliveryLine(11L, 3));
        String raw = payloadOf(item, lines);
        seedEvent("EVT-RS", "AE", raw);
        Result r = service.postDelivery(inputOf("AE", "EVT-RS", item, lines, raw));
        assertTrue(r.posted(), "真库同批多行应聚合为一行过账");
        assertEquals(1, count("inv_batch_consumption"), "真库唯一键 (biz_event_key,batch_id) 不应被双插撞键");
        assertEquals(5, jdbc.queryForObject("select qty from inv_batch_consumption", Integer.class));
    }

    @Test
    void batchShortageRollsBackOnRealSchema()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 20);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 3, "10.0000");
        String raw = payload(item, 11L, 5);
        seedEvent("EVT-R4", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-R4", item, 11L, 5, raw));
        assertFalse(r.posted());
        assertEquals("BATCH_QTY_SHORTAGE", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"), "守恒失败无部分写入");
        assertEquals(3, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class), "批次未扣");
        assertEquals(20, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class), "台账未扣");
    }

    @Test
    void fifoProducesSaleFlowThenReversalPostsAcrossBatchesAndRetransmitOnRealSchema()
    {
        // 端到端（Codex 跨服务复核）：不造 SALE 原行夹具，原行必须由 FIFO 真实产出。
        long order = seedOrder("AE", "O-E2E");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000");
        seedBatch("AE", 11L, 100L, "2026-11-05 00:00:00", 5, "12.0000");
        String saleRaw = payload(item, 11L, 6);
        seedEvent("EVT-E2E-SALE", "AE", saleRaw);
        Result sale = service.postDelivery(input("AE", "EVT-E2E-SALE", item, 11L, 6, saleRaw));
        assertTrue(sale.posted(), "FIFO 出库应先过账");
        // FIFO 产出的流水必须是 schema 契约 SALE（红冲原行校验接受的值），且跨批 2 行。
        assertEquals(2, count("inv_batch_consumption"));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from inv_batch_consumption where move_type<>'SALE' or sign<>1", Integer.class),
                "FIFO 流水 move_type 必须 SALE，才能被红冲接受");
        // 取 FIFO 真实产出的两条原消耗行（跨批 5+1），逐条红冲，不再手工造 SALE 夹具。
        List<Long> originalIds = jdbc.queryForList(
                "select id from inv_batch_consumption where move_type='SALE' and sign=1 order by id", Long.class);
        assertEquals(2, originalIds.size());
        long firstOriginal = originalIds.get(0);   // 老批 5 件，unit_cost=10
        long secondOriginal = originalIds.get(1);  // 新批 1 件，unit_cost=12

        // 可信父退货（真实 sal_return DDL；站点/SKU/订单行与 FIFO 流水一致）。
        long ret = seedReturn("AE", 11L, item, 6);

        // 批次1 红冲 3 件：FIFO 原行 → 红冲应过账（若 move_type=SALE_DELIVERY 会判 ORIGINAL_NOT_SALE）。
        String r1 = reversalPayload(ret, firstOriginal, 3, 11L);
        seedReversalEvent("EVT-E2E-REV1", "AE", r1);
        ReversalService.Result rev1 = reversalService.postReversal(
                new ReversalInput("AE", "EVT-E2E-REV1", ret, firstOriginal, 3, r1));
        assertTrue(rev1.posted(), "FIFO 产出的 SALE 原行应可红冲，实际 reason=" + rev1.reasonCode());

        // 同事件重传：应 ACK 而非双计（biz_event_key=REV:event 幂等）。
        ReversalService.Result again = reversalService.postReversal(
                new ReversalInput("AE", "EVT-E2E-REV1", ret, firstOriginal, 3, r1));
        assertTrue(again.posted(), "重传应 ACK");
        assertEquals(3, jdbc.queryForObject(
                "select coalesce(sum(qty),0) from inv_batch_consumption where reversal_of_id=? and sign=-1",
                Integer.class, firstOriginal), "重传不双计");

        // 跨批：红冲第二条 FIFO 原行 1 件，验证跨批原行同样可冲。
        String r2 = reversalPayload(ret, secondOriginal, 1, 11L);
        seedReversalEvent("EVT-E2E-REV2", "AE", r2);
        ReversalService.Result rev2 = reversalService.postReversal(
                new ReversalInput("AE", "EVT-E2E-REV2", ret, secondOriginal, 1, r2));
        assertTrue(rev2.posted(), "跨批第二条 FIFO 原行应可红冲，实际 reason=" + rev2.reasonCode());

        // 红冲流水落账：sign=-1、move_type=RETURN_REVERSAL、金额按各自批次单位成本；累计不超退货单 qty。
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from inv_batch_consumption where sign=-1", Integer.class),
                "2 条红冲流水（批1 一次 + 重传不新增 + 批2 一次）");
        assertEquals(4, jdbc.queryForObject(
                "select coalesce(sum(qty),0) from inv_batch_consumption where sal_return_id=? and sign=-1",
                Integer.class, ret), "累计红冲 3+1=4 <= 退货 qty 6");
        assertEquals(0, count("noon_reversal_review"), "全链路合法不应进红冲隔离层");
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MysqlConfig
    {
        @Bean
        DataSource dataSource()
        {
            DataSource ds = serverDataSource();
            String database = "noon_fifo_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            DATABASE.set(database);
            org.springframework.jdbc.datasource.DriverManagerDataSource configured =
                    (org.springframework.jdbc.datasource.DriverManagerDataSource) ds;
            configured.setUrl(configured.getUrl().replace("/?", "/" + database + "?"));
            return configured;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }

        @Bean
        SysUserSiteMapper mapper(DataSource ds) throws Exception
        {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserSiteMapper.xml"));
            return new SqlSessionTemplate(factory.getObject()).getMapper(SysUserSiteMapper.class);
        }

        @Bean
        SiteAuthGuard guard(SysUserSiteMapper mapper) { return new SiteAuthGuard(mapper); }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }

        @Bean
        FifoConsumptionService fifoConsumptionService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new FifoConsumptionService(jdbcTemplate, guard);
        }

        @Bean
        ReversalService reversalService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new ReversalService(jdbcTemplate, guard);
        }
    }
}
