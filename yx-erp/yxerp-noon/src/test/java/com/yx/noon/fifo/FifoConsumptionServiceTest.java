package com.yx.noon.fifo;

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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryInput;
import com.yx.noon.fifo.FifoConsumptionService.DeliveryLine;
import com.yx.noon.fifo.FifoConsumptionService.Result;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOON-20261121-FIFO 独立验证：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper。
 * 不启动 MySQL/Redis/生产连接，任何人 mvn test 即可复跑。全部数据为系统内部构造的测试夹具。
 */
class FifoConsumptionServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private FifoConsumptionService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        String siteMigration = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String statement : h2Ddl(siteMigration).split(";"))
            if (!statement.isBlank()) jdbc.execute(statement);

        jdbc.execute("create table noon_fifo_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), sal_order_item_id_claimed bigint, source_event_id varchar(128), "
                + "raw_payload varchar(4000), reason_code varchar(48) not null, reason_detail varchar(512), "
                + "status varchar(24) not null default 'NEEDS_REVIEW', created_by bigint, "
                + "created_at timestamp not null default current_timestamp, unique key(review_no))");
        jdbc.execute("create table noon_fifo_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");
        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw varchar(4000), received_at timestamp not null, status varchar(24) not null, "
                + "unique key(event_id))");
        jdbc.execute("create table sal_order (id bigint auto_increment primary key, site_code varchar(16) not null, "
                + "order_no varchar(64) not null, unique key(order_no,site_code))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, sal_order_id bigint not null, "
                + "sku_id bigint not null, qty int not null, qty_delivered int not null default 0, "
                + "qty_cancelled int not null default 0)");
        jdbc.execute("create table inv_batch (id bigint auto_increment primary key, batch_no varchar(64) not null, "
                + "sku_id bigint not null, warehouse_id bigint not null, site_code varchar(16) not null, "
                + "received_at timestamp not null, qty_in int not null, qty_remaining int not null, "
                + "unit_cost decimal(18,4) not null, status varchar(16) not null default 'OPEN', "
                + "unique key(batch_no))");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint not null, "
                + "warehouse_id bigint not null, site_code varchar(16) not null, qty_available int not null default 0, "
                + "unique key(sku_id,warehouse_id))");
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null, biz_event_key varchar(160) not null, "
                + "source_event_id varchar(128), batch_id bigint not null, sal_order_item_id bigint, "
                + "sku_id bigint not null, qty int not null, unit_cost decimal(18,4) not null, "
                + "amount decimal(18,4) not null, consumed_at timestamp not null, "
                + "move_type varchar(24) not null, sign int not null, period char(7), cross_period int not null default 0, "
                + "raw_payload varchar(4000), created_at timestamp not null, "
                + "unique key(consumption_no), unique key(biz_event_key,batch_id))");

        service = context.getBean(FifoConsumptionService.class);
        login(1L);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    private static String h2Ddl(String sql)
    {
        return sql.replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replaceAll("COMMENT\\s*=\\s*'[^']*'", "");
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
        jdbc.update("insert into sal_order(site_code,order_no) values (?,?)", site, orderNo);
        return jdbc.queryForObject("select id from sal_order where order_no=? and site_code=?", Long.class, orderNo, site);
    }

    private long seedItem(long orderId, long sku, int qty)
    {
        jdbc.update("insert into sal_order_item(sal_order_id,sku_id,qty) values (?,?,?)", orderId, sku, qty);
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=? and sku_id=?", Long.class, orderId, sku);
    }

    private long seedBatch(String site, long sku, long wh, String receivedAt, int qty, String cost)
    {
        String batchNo = "B:" + UUID.randomUUID();
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                + "unit_cost,status) values (?,?,?,?,?,?,?,?,'OPEN')",
                batchNo, sku, wh, site, Timestamp.valueOf(receivedAt), qty, qty, new BigDecimal(cost));
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(String site, long sku, long wh, int avail)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (?,?,?,?)",
                sku, wh, site, avail);
    }

    private String seedEvent(String eventId, String site, String payload)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                        + "values (?,?,?,?,?,?)",
                eventId, "SALE_DELIVERY", site, payload, java.sql.Timestamp.valueOf("2026-11-21 10:00:00"), "PROCESSED");
        return payload;
    }

    private static String payload(long itemId, long sku, int qty)
    {
        return "{\"sal_order_item_id\":" + itemId + ",\"lines\":[{\"sku_id\":" + sku + ",\"qty\":" + qty + "}]}";
    }

    private static DeliveryInput input(String site, String eventId, long itemId, long sku, int qty, String raw)
    {
        return new DeliveryInput(site, eventId, itemId, List.of(new DeliveryLine(sku, qty)), raw);
    }

    /** 多行 payload：与 DeliveryLine 列表逐项对应，供 verifyEvidence 逐项绑定。 */
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

    @Test
    void postedFifoConsumesOldestBatchFirst()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000"); // 老批
        seedBatch("AE", 11L, 100L, "2026-11-05 00:00:00", 5, "12.0000"); // 新批
        String raw = payload(item, 11L, 7);
        seedEvent("EVT-1", "AE", raw);

        Result r = service.postDelivery(input("AE", "EVT-1", item, 11L, 7, raw));
        assertTrue(r.posted(), "应过账");
        assertEquals(5, jdbc.queryForObject("select qty from inv_batch_consumption where batch_id="
                + jdbc.queryForObject("select id from inv_batch where unit_cost=10.0000", Long.class), Integer.class),
                "老批应被扣满 5");
        assertEquals(2, jdbc.queryForObject("select qty from inv_batch_consumption where batch_id="
                + jdbc.queryForObject("select id from inv_batch where unit_cost=12.0000", Long.class), Integer.class),
                "新批扣 2");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where unit_cost=10.0000", Integer.class));
        assertEquals(13, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
    }

    @Test
    void unauthorizedSiteRejected()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        String raw = payload(item, 11L, 1);
        seedEvent("EVT-U", "SA", raw);
        // 非超管用户 2 无任何站点授权：requireSite('SA') 必须拒绝（超管 1 才拥有全部站点）。
        login(2L);
        assertThrows(AccessDeniedException.class, () -> service.postDelivery(input("SA", "EVT-U", item, 11L, 1, raw)));
        login(1L);
    }

    @Test
    void missingParentItemQuarantined()
    {
        String raw = payload(999L, 11L, 1);
        seedEvent("EVT-M", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-M", 999L, 11L, 1, raw));
        assertFalse(r.posted());
        assertEquals("PARENT_ORDER_ITEM_MISSING", r.reasonCode());
        assertEquals(1, count("noon_fifo_review"));
    }

    @Test
    void siteMismatchQuarantined()
    {
        long order = seedOrder("AE", "O-1"); // 父订单 AE
        long item = seedItem(order, 11L, 10);
        String raw = payload(item, 11L, 1);
        seedEvent("EVT-SM", "SA", raw);
        // 用超管登录拥有 SA 权限，但父订单是 AE -> SITE_MISMATCH
        Result r = service.postDelivery(input("SA", "EVT-SM", item, 11L, 1, raw));
        assertFalse(r.posted());
        assertEquals("SITE_MISMATCH", r.reasonCode());
    }

    @Test
    void unverifiedSourceQuarantined()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        String raw = payload(item, 11L, 1);
        // 不种事件 -> SOURCE_UNVERIFIED
        Result r = service.postDelivery(input("AE", "EVT-NO", item, 11L, 1, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    @Test
    void sourcePayloadMismatchQuarantined()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        String raw = payload(item, 11L, 1);
        seedEvent("EVT-PM", "AE", payload(item, 11L, 2)); // 事件 qty=2，入参 qty=1
        Result r = service.postDelivery(input("AE", "EVT-PM", item, 11L, 1, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    @Test
    void retransmitIsAckSameEntity()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000");
        String raw = payload(item, 11L, 3);
        seedEvent("EVT-RT", "AE", raw);
        Result first = service.postDelivery(input("AE", "EVT-RT", item, 11L, 3, raw));
        assertTrue(first.posted());
        Result retry = service.postDelivery(input("AE", "EVT-RT", item, 11L, 3, raw));
        assertTrue(retry.posted());
        assertEquals("ALREADY_POSTED", retry.reasonCode());
        assertEquals(first.entityId(), retry.entityId());
        assertEquals(1, count("inv_batch_consumption"));
    }

    @Test
    void sameEventDifferentQtyIsConflict()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000");
        String raw3 = payload(item, 11L, 3);
        seedEvent("EVT-CF", "AE", raw3);
        service.postDelivery(input("AE", "EVT-CF", item, 11L, 3, raw3));
        // 事件内容必须匹配入参，故构造第二个事件号复用同 biz key 不可能；此处验证 payload 不符被隔离
        Result r = service.postDelivery(input("AE", "EVT-CF", item, 11L, 4, payload(item, 11L, 4)));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    @Test
    void exceedsOrderDeliverableQuarantined()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 5); // 订单行 5
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 20, "10.0000");
        String raw = payload(item, 11L, 6); // 妥投 6 > 5
        seedEvent("EVT-EX", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-EX", item, 11L, 6, raw));
        assertFalse(r.posted());
        assertEquals("DELIVERY_EXCEEDS_ORDER", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"));
    }

    @Test
    void cancelledQuantityReducesDeliverable()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 5);
        jdbc.update("update sal_order_item set qty_cancelled=2 where id=?", item);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 20, "10.0000");
        String raw = payload(item, 11L, 4); // 可投=5-2=3，妥投4超限
        seedEvent("EVT-CX", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-CX", item, 11L, 4, raw));
        assertFalse(r.posted());
        assertEquals("DELIVERY_EXCEEDS_ORDER", r.reasonCode());
    }

    @Test
    void batchShortageQuarantinedWithNoPartialWrite()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 20);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 3, "10.0000"); // 批次仅 3
        String raw = payload(item, 11L, 5); // 妥投 5 > 批次 3
        seedEvent("EVT-BS", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-BS", item, 11L, 5, raw));
        assertFalse(r.posted());
        assertEquals("BATCH_QTY_SHORTAGE", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"), "守恒失败整体回滚无部分写入");
        assertEquals(3, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class), "批次未被扣减");
        assertEquals(20, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class), "台账未被扣减");
    }

    @Test
    void ledgerShortageQuarantinedAndRolledBack()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 1); // 台账仅 1
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-LS", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-LS", item, 11L, 2, raw));
        assertFalse(r.posted());
        assertEquals("LEDGER_SHORTAGE", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class), "批次扣减被回滚");
    }

    @Test
    void cumulativeDeliveriesCannotExceedOrder()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 5);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 20, "10.0000");
        String raw3 = payload(item, 11L, 3);
        seedEvent("EVT-A", "AE", raw3);
        assertTrue(service.postDelivery(input("AE", "EVT-A", item, 11L, 3, raw3)).posted());
        String raw3b = payload(item, 11L, 3);
        seedEvent("EVT-B", "AE", raw3b);
        Result second = service.postDelivery(input("AE", "EVT-B", item, 11L, 3, raw3b));
        assertFalse(second.posted());
        assertEquals("DELIVERY_EXCEEDS_ORDER", second.reasonCode(), "累计 3+3>5 应隔离");
    }

    @Test
    void concurrentSameEventOnlyPostsOnce() throws Exception
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 20, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-CC", "AE", raw);
        DeliveryInput in = input("AE", "EVT-CC", item, 11L, 2, raw);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postDelivery(in); });
        Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postDelivery(in); });
        start.countDown();
        Result r1 = f1.get(), r2 = f2.get();
        pool.shutdownNow();
        login(1L);
        assertTrue(r1.posted() && r2.posted());
        assertEquals(1, count("inv_batch_consumption"), "同事件并发只入账一次");
    }

    // ---- Codex 阶段一静态复核四点整改反例/正例 ----

    @Test
    void itemSkuMismatchQuarantined() // 意见2：禁止对订单 A 扣减 SKU B
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10); // 父行 SKU 11
        seedLedger("AE", 22L, 100L, 20);
        seedBatch("AE", 22L, 100L, "2026-11-01 00:00:00", 10, "10.0000"); // 另一 SKU 22 有批次
        String raw = payload(item, 22L, 2); // 明细却是 SKU 22
        seedEvent("EVT-IM", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-IM", item, 22L, 2, raw));
        assertFalse(r.posted());
        assertEquals("ITEM_MISMATCH", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class), "SKU22 批次未被扣");
    }

    @Test
    void crossSiteSameSkuOnlyConsumesOwnSiteBatch() // 意见1：跨站同 SKU 不扣他站批次
    {
        long aeOrder = seedOrder("AE", "O-AE");
        long aeItem = seedItem(aeOrder, 11L, 10);
        long saOrder = seedOrder("SA", "O-SA");
        long saItem = seedItem(saOrder, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedLedger("SA", 11L, 200L, 20);
        long saBatch = seedBatch("SA", 11L, 200L, "2026-11-01 00:00:00", 5, "10.0000"); // SA 老批更早
        long aeBatch = seedBatch("AE", 11L, 100L, "2026-11-03 00:00:00", 5, "12.0000"); // AE 批更晚
        String raw = payload(aeItem, 11L, 3);
        seedEvent("EVT-XS", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-XS", aeItem, 11L, 3, raw));
        assertTrue(r.posted(), "AE 请求应只扣 AE 批，不被 SA 老批干扰");
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, saBatch),
                "SA 批次不应被扣，剩余量应保持 5");
        // AE 批扣 3，剩 2；注意 SA 批未动
        assertEquals(2, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, aeBatch));
    }

    @Test
    void saBatchUntouchedWhenAeRequestHasNoAeBatch() // 意见1：AE 请求只有 SA 批 -> 隔离而非扣 SA
    {
        long aeOrder = seedOrder("AE", "O-AE");
        long aeItem = seedItem(aeOrder, 11L, 10);
        seedLedger("SA", 11L, 200L, 20);
        seedBatch("SA", 11L, 200L, "2026-11-01 00:00:00", 5, "10.0000");
        String raw = payload(aeItem, 11L, 2);
        seedEvent("EVT-XS2", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-XS2", aeItem, 11L, 2, raw));
        assertFalse(r.posted());
        assertEquals("BATCH_QTY_SHORTAGE", r.reasonCode(), "AE 站点无可用批次应隔离");
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class), "SA 批未被扣");
    }

    @Test
    void sameBatchMultipleLinesDoesNotHitUniqueKey() // 意见3：同批多行聚合，不撞 (biz_event_key,batch_id)
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000"); // 单批 10
        List<DeliveryLine> lines = List.of(new DeliveryLine(11L, 2), new DeliveryLine(11L, 3));
        String raw = payloadOf(item, lines);
        seedEvent("EVT-SB", "AE", raw);
        Result r = service.postDelivery(inputOf("AE", "EVT-SB", item, lines, raw));
        assertTrue(r.posted());
        assertEquals(1, count("inv_batch_consumption"), "同批多行应聚合为一行，不撞唯一键");
        assertEquals(5, jdbc.queryForObject("select qty from inv_batch_consumption", Integer.class));
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class));
        assertEquals(15, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
    }

    @Test
    void multipleBatchesShareLedgerAccumulatesPlannedUsage() // 意见3：多批同仓，规划累计占用，超额准确隔离
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 4); // 台账仅 4
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 5, "10.0000");
        seedBatch("AE", 11L, 100L, "2026-11-05 00:00:00", 5, "12.0000");
        List<DeliveryLine> lines = List.of(new DeliveryLine(11L, 3), new DeliveryLine(11L, 2)); // 合计 5 > 台账 4
        String raw = payloadOf(item, lines);
        seedEvent("EVT-LA", "AE", raw);
        Result r = service.postDelivery(inputOf("AE", "EVT-LA", item, lines, raw));
        assertFalse(r.posted());
        assertEquals("LEDGER_SHORTAGE", r.reasonCode(), "两批各够但台账合计不足，应准确隔离而非异常回滚");
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(10, jdbc.queryForObject("select sum(qty_remaining) from inv_batch", Integer.class), "批次未被扣");
        assertEquals(4, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class), "台账未被扣");
    }

    @Test
    void deliveredCountSyncedWithConsumption() // 意见4：qty_delivered 与流水同步一致
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 3);
        seedEvent("EVT-DS", "AE", raw);
        assertTrue(service.postDelivery(input("AE", "EVT-DS", item, 11L, 3, raw)).posted());
        assertEquals(3, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, item));
        assertEquals(3, jdbc.queryForObject("select coalesce(sum(qty),0) from inv_batch_consumption", Integer.class));
        // 第二次累计仍一致，可继续妥投
        String raw2 = payload(item, 11L, 2);
        seedEvent("EVT-DS2", "AE", raw2);
        assertTrue(service.postDelivery(input("AE", "EVT-DS2", item, 11L, 2, raw2)).posted());
        assertEquals(5, jdbc.queryForObject("select qty_delivered from sal_order_item where id=?", Integer.class, item));
    }

    @Test
    void deliveredMismatchQuarantined() // 意见4：父行 qty_delivered 与流水不一致 -> 隔离不臆造
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        jdbc.update("update sal_order_item set qty_delivered=5 where id=?", item); // 历史脏值，流水为 0
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-DM", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-DM", item, 11L, 2, raw));
        assertFalse(r.posted());
        assertEquals("DELIVERED_MISMATCH", r.reasonCode());
        assertEquals(0, count("inv_batch_consumption"));
    }

    @Test
    void retransmitDifferentPayloadNotAck() // 意见2：不同明细的 payload 不 ACK（证据闸门先拦）
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        List<DeliveryLine> first = List.of(new DeliveryLine(11L, 2), new DeliveryLine(11L, 3));
        String raw = payloadOf(item, first);
        seedEvent("EVT-RD", "AE", raw);
        assertTrue(service.postDelivery(inputOf("AE", "EVT-RD", item, first, raw)).posted());
        // 同事件号但明细分布不同（1+4）→ payload 与库内证据不符，证据闸门隔离，绝不 ACK。
        List<DeliveryLine> changed = List.of(new DeliveryLine(11L, 1), new DeliveryLine(11L, 4));
        String rawChanged = payloadOf(item, changed);
        Result r = service.postDelivery(inputOf("AE", "EVT-RD", item, changed, rawChanged));
        assertFalse(r.posted(), "不同明细分布不应被当重传 ACK");
        assertEquals(1, count("inv_batch_consumption"));
    }

    @Test
    void idempotencyComparesFullQuantityNotJustSku() // 意见2：同键已入账数量与本次明细不同 -> SOURCE_CONFLICT
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        long batch1 = seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        long batch2 = seedBatch("AE", 11L, 100L, "2026-11-05 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-IQ", "AE", raw);
        assertTrue(service.postDelivery(input("AE", "EVT-IQ", item, 11L, 2, raw)).posted());
        // 手工注入同 biz_event_key 的另一批 + 不同数量，模拟"同键但核心数量不同"的历史脏行，
        // 绕过证据闸门直接命中幂等完整比较：应 SOURCE_CONFLICT 而非 ACK。
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                        + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,period,"
                        + "cross_period,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "FC:manual", "SALE:EVT-IQ", "EVT-IQ", batch2, item, 11L, 9,
                new BigDecimal("10.0000"), new BigDecimal("90.0000"),
                Timestamp.valueOf("2026-11-21 00:00:00"), "SALE", 1, "2026-11", 0,
                Timestamp.valueOf("2026-11-21 00:00:00"));
        Result r = service.postDelivery(input("AE", "EVT-IQ", item, 11L, 2, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_CONFLICT", r.reasonCode(), "同键数量 9+2 与本次 2 不一致，不 ACK");
        assertEquals(2, count("inv_batch_consumption"));
        // batch1 是正常过账批次，扣 2 后剩 8；冲突重传不应再扣
        assertEquals(8, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, batch1));
    }

    @Test
    void consumptionMoveTypeIsSaleForReversalCompatibility() // Codex 跨服务复核：流水类型须 SALE 而非事件类型 SALE_DELIVERY
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 3);
        seedEvent("EVT-MT", "AE", raw);
        assertTrue(service.postDelivery(input("AE", "EVT-MT", item, 11L, 3, raw)).posted());
        // 事件类型是 SALE_DELIVERY，但落账流水类型必须是 schema 契约 SALE，红冲服务才认 sign=+1 的 SALE 原行。
        assertEquals("SALE", jdbc.queryForObject("select move_type from inv_batch_consumption", String.class));
        assertEquals(1, jdbc.queryForObject("select sign from inv_batch_consumption", Integer.class));
        // 历史兼容：sumPostedOnItem 以 move_type=SALE 统计，第二次妥投仍能读到已投 3，累计 6<=10 通过。
        String raw2 = payload(item, 11L, 3);
        seedEvent("EVT-MT2", "AE", raw2);
        assertTrue(service.postDelivery(input("AE", "EVT-MT2", item, 11L, 3, raw2)).posted(),
                "move_type=SALE 历史流水应被累计统计正确读取");
        assertEquals(6, jdbc.queryForObject("select sum(qty) from inv_batch_consumption", Integer.class));
    }

    @Test
    void missingLedgerQuarantinedWithNoPartialWrite() // Codex 跨服务复核：台账不存在应隔离而非 EmptyResultDataAccessException
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        // 故意不 seed 台账：批次存在但台账行缺失。
        long batch = seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 2);
        seedEvent("EVT-NL", "AE", raw);
        Result r = service.postDelivery(input("AE", "EVT-NL", item, 11L, 2, raw));
        assertFalse(r.posted(), "台账缺失应隔离不抛异常");
        assertEquals("LEDGER_SHORTAGE", r.reasonCode());
        assertEquals(1, count("noon_fifo_review"), "应写隔离记录而非异常回滚");
        assertEquals(0, count("inv_batch_consumption"), "无部分写入");
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, batch),
                "批次未扣");
    }

    @Test
    void auditWrittenOnPostAndQuarantine()
    {
        long order = seedOrder("AE", "O-1");
        long item = seedItem(order, 11L, 10);
        seedLedger("AE", 11L, 100L, 20);
        seedBatch("AE", 11L, 100L, "2026-11-01 00:00:00", 10, "10.0000");
        String raw = payload(item, 11L, 1);
        seedEvent("EVT-AU", "AE", raw);
        service.postDelivery(input("AE", "EVT-AU", item, 11L, 1, raw));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_fifo_audit where action='POSTED'", Integer.class));
        Result bad = service.postDelivery(input("AE", "EVT-NO", item, 11L, 1, raw));
        assertFalse(bad.posted());
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_fifo_audit where action='QUARANTINED'", Integer.class));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config
    {
        @Bean
        DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:fifo-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
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
    }
}
