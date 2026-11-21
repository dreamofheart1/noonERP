package com.yx.framework.intake;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.intake.IntakeService.OrderInput;
import com.yx.framework.intake.IntakeService.Result;
import com.yx.framework.intake.IntakeService.ReturnInput;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOON-20261121-RETURN：隔离 MySQL 集成验证（opt-in，首次复核修订版）。
 * 用 verify-intake-runtime.py 拉起的临时 MySQL，加载原始 noon_intake_review.sql，
 * 验证 CHECK 约束在真实 MySQL 生效、服务闸门在真实事务下正确。不读应用数据源配置。
 */
@EnabledIfEnvironmentVariable(named = "NOON_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlIntakeIT
{
    private static final ThreadLocal<String> DATABASE = new ThreadLocal<>();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private IntakeService service;

    private static DataSource serverDataSource()
    {
        int port = Integer.parseInt(System.getenv("NOON_MYSQL_TEST_PORT"));
        String password = System.getenv("NOON_MYSQL_TEST_PASSWORD");
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai", "root", password);
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

        // 原始待核对 DDL（含 CHECK / 生成语义），验证脚本在真实 MySQL 可执行。
        String reviewSql = Files.readString(Path.of("../sql/noon_intake_review.sql"));
        for (String s : reviewSql.split(";")) if (!s.isBlank()) jdbc.execute(s);

        // 系统侧 webhook 事件留痕。
        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw json, received_at datetime(3) not null, status varchar(24) not null, "
                + "unique key(event_id))");

        // 正式表最小结构（本工作包验证目标是服务闸门，DDL 触发器另由 verify-schema-v05.py 覆盖）。
        jdbc.execute("create table sal_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "site_code varchar(16) not null, store_id bigint not null, partner_sku varchar(128) not null, "
                + "sku_id bigint, qty int not null, gross_amount decimal(18,4) not null, currency char(3) not null, "
                + "source_channel varchar(24), import_fingerprint varchar(64), status varchar(32), "
                + "state_version int not null default 1, raw_payload json, created_at datetime(3), updated_at datetime(3), "
                + "unique key(order_no, site_code))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, sal_order_id bigint not null, "
                + "order_no varchar(64) not null, platform_item_no varchar(64), sku_id bigint not null, "
                + "qty int not null, qty_delivered int not null default 0, qty_returned int not null default 0, "
                + "qty_cancelled int not null default 0, unit_price decimal(18,4) not null)");
        jdbc.execute("create table sal_return (id bigint auto_increment primary key, return_no varchar(64) not null, "
                + "biz_return_key varchar(160) not null, source_return_no varchar(128), order_no varchar(64) not null, "
                + "site_code varchar(16), sku_id bigint, qty int not null, sal_order_item_id bigint, "
                + "status varchar(24), raw_payload json, created_at datetime(3), "
                + "unique key(biz_return_key), unique key(site_code,order_no,source_return_no))");

        service = context.getBean(IntakeService.class);
        login(1L);
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

    private void seedWebhook(String eventId, String site, String type, String payload, String status)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                + "values (?,?,?,?,current_timestamp(3),?)",
                eventId, type, site, payload, status);
    }

    private void seedOrderEvent(String eventId, String site, String orderNo)
    {
        seedWebhook(eventId, site, "ORDER", orderPayload(orderNo, 7L, 11L, "88.5000", 1, "AED"), "RECEIVED");
    }

    private void seedReturnEvent(String eventId, String site, String orderNo, String returnNo, int qty, long itemId)
    {
        seedWebhook(eventId, site, "RETURN", returnPayload(orderNo, returnNo, qty, itemId), "PROCESSED");
    }

    private static String orderPayload(String no, long store, long sku, String amount, int qty, String currency)
    {
        return "{\"order_no\":\"" + no + "\",\"store_id\":" + store + ",\"sku_id\":" + sku
                + ",\"gross_amount\":\"" + amount + "\",\"qty\":" + qty
                + ",\"currency\":\"" + currency + "\"}";
    }

    private static String returnPayload(String orderNo, String returnNo, int qty, long itemId)
    {
        return "{\"order_no\":\"" + orderNo + "\",\"return_no\":\"" + returnNo
                + "\",\"qty\":" + qty + ",\"item_id\":" + itemId + "}";
    }

    private long seedParent()
    {
        jdbc.update("insert into sal_order(order_no,site_code,store_id,partner_sku,qty,gross_amount,currency,status,state_version) "
                + "values ('MO-1','AE',1,'SKU',10,100,'AED','IMPORTED',1)");
        long oid = jdbc.queryForObject("select id from sal_order where order_no='MO-1'", Long.class);
        jdbc.update("insert into sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,qty_delivered,unit_price) "
                + "values (?,'MO-1','L1',1,10,10,10)", oid);
        return jdbc.queryForObject("select id from sal_order_item where platform_item_no='L1'", Long.class);
    }

    /** 指定站点的父订单行，用于跨站同号测试。 */
    private long seedParentAt(String orderNo, String site)
    {
        jdbc.update("insert into sal_order(order_no,site_code,store_id,partner_sku,qty,gross_amount,currency,status,state_version) "
                + "values (?,?,1,'SKU',10,100,'AED','IMPORTED',1)", orderNo, site);
        long oid = jdbc.queryForObject("select id from sal_order where order_no=? and site_code=?", Long.class, orderNo, site);
        jdbc.update("insert into sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,qty_delivered,unit_price) "
                + "values (?,?,'L1',1,10,10,10)", oid, orderNo);
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=? and platform_item_no='L1'", Long.class, oid);
    }

    private static OrderInput order(String no, String eventId) { return orderAt("AE", no, eventId); }

    private static OrderInput orderAt(String site, String no, String eventId)
    {
        return new OrderInput(site, no, "WEBHOOK", eventId, "CALLER-LIES", "SKU",
                7L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload(no, 7L, 11L, "88.5000", 1, "AED"));
    }

    private static ReturnInput ret(String site, String orderNo, String eventId, String sr, Long itemId)
    {
        return new ReturnInput(site, orderNo, eventId, sr, itemId, 1,
                returnPayload(orderNo, sr, 1, itemId == null ? 0L : itemId));
    }

    private int count(String t) { return jdbc.queryForObject("select count(*) from " + t, Integer.class); }

    @Test
    void originalReviewDdlEnforcesChecksOnRealMySql()
    {
        // CHECK 在真实 MySQL 生效：非法 entity_type 被拒。
        assertThrows(Exception.class, () -> jdbc.update("insert into noon_intake_review"
                + "(review_no,entity_type,reason_code,status) values ('RV-BAD','BAD','X','NEEDS_REVIEW')"));
        // 来源粗识别（无佐证）→ 隔离，不臆造。
        Result r = service.postOrder(order("MO-X", "EVT-NONE"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(1, count("noon_intake_review"));
    }

    @Test
    void verifiedMasterDataOrderHasNoPlaceholderOnRealMySql()
    {
        seedOrderEvent("EVT-OK", "AE", "MO-OK");
        Result r = service.postOrder(order("MO-OK", "EVT-OK"));
        assertTrue(r.posted());
        assertEquals(7L, jdbc.queryForObject("select store_id from sal_order where order_no='MO-OK'", Long.class));
        assertEquals(11L, jdbc.queryForObject("select sku_id from sal_order where order_no='MO-OK'", Long.class));
        assertEquals(0, new BigDecimal("88.5000").compareTo(
                jdbc.queryForObject("select gross_amount from sal_order where order_no='MO-OK'", BigDecimal.class)));
    }

    @Test
    void missingMasterDataIsQuarantinedNoPlaceholderOnRealMySql()
    {
        seedOrderEvent("EVT-NM", "AE", "MO-NM");
        OrderInput missing = new OrderInput("AE", "MO-NM", "WEBHOOK", "EVT-NM", null, "SKU",
                null, null, null, 1, "AED", "{}");
        Result r = service.postOrder(missing);
        assertFalse(r.posted());
        assertEquals("MASTER_DATA_INCOMPLETE", r.reasonCode());
        assertEquals(0, count("sal_order"), "真 MySQL 正式表不得出现占位");
    }

    @Test
    void trustedParentPostAndCrossSiteQuarantineOnRealMySql()
    {
        long itemId = seedParent();
        seedReturnEvent("EVT-R", "AE", "MO-1", "SR-1", 1, itemId);
        Result ok = service.postReturn(ret("AE", "MO-1", "EVT-R", "SR-1", itemId));
        assertTrue(ok.posted());
        assertEquals("AE", jdbc.queryForObject("select site_code from sal_return where id=?", String.class, ok.entityId()));
        assertEquals(1L, jdbc.queryForObject("select sku_id from sal_return where id=?", Long.class, ok.entityId()));
        // 超管对 SA 也有权，但父订单是 AE → SITE_MISMATCH 隔离，不写正式表。
        Result bad = service.postReturn(ret("SA", "MO-1", "EVT-R", "SR-2", itemId));
        assertFalse(bad.posted());
        assertEquals("SITE_MISMATCH", bad.reasonCode());
        assertEquals(1, count("sal_return"));
    }

    @Test
    void auditFailureRollsBackOnRealMySql()
    {
        seedOrderEvent("EVT-RB", "AE", "MO-2");
        jdbc.execute("alter table noon_intake_audit add constraint reject_posted check (action <> 'POSTED')");
        assertThrows(RuntimeException.class, () -> service.postOrder(order("MO-2", "EVT-RB")));
        assertEquals(0, count("sal_order"));
    }

    @Test
    void zeroAmountWithEvidencePostedOnRealMySql()
    {
        String payload = orderPayload("MO-ZERO", 7L, 11L, "0", 1, "AED");
        seedWebhook("EVT-ZERO", "AE", "ORDER", payload, "RECEIVED");
        Result r = service.postOrder(new OrderInput("AE", "MO-ZERO", "WEBHOOK", "EVT-ZERO", null,
                "SKU", 7L, 11L, BigDecimal.ZERO, 1, "AED", payload));
        assertTrue(r.posted(), "真 MySQL：有可信证据的零金额应可入账");
        assertEquals(0, jdbc.queryForObject("select gross_amount from sal_order where order_no='MO-ZERO'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
    }

    @Test
    void evidenceBindingRejectsBorrowedEventAndForgedMasterDataOnRealMySql()
    {
        seedOrderEvent("EVT-BORROW", "AE", "MO-BORROW");
        Result borrowed = service.postOrder(order("MO-FAKE", "EVT-BORROW"));
        assertFalse(borrowed.posted());
        assertEquals("SOURCE_UNVERIFIED", borrowed.reasonCode());

        seedOrderEvent("EVT-FORGE", "AE", "MO-FORGE");
        OrderInput forged = new OrderInput("AE", "MO-FORGE", "WEBHOOK", "EVT-FORGE", null,
                "SKU", 999L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload("MO-FORGE", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(forged);
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void crossSiteSameNumberAckReturnsOwnRowOnRealMySql()
    {
        // 订单：AE 先、SA 后，再反向一组；ACK 必须返回与鉴权站点一致的行 ID。
        seedOrderEvent("EVT-X-AE1", "AE", "MX-1");
        seedOrderEvent("EVT-X-SA1", "SA", "MX-1");
        Result ae1 = service.postOrder(orderAt("AE", "MX-1", "EVT-X-AE1"));
        Result sa1 = service.postOrder(orderAt("SA", "MX-1", "EVT-X-SA1"));
        assertNotEquals(ae1.entityId(), sa1.entityId());
        assertEquals(ae1.entityId(), service.postOrder(orderAt("AE", "MX-1", "EVT-X-AE1")).entityId());
        assertEquals(sa1.entityId(), service.postOrder(orderAt("SA", "MX-1", "EVT-X-SA1")).entityId());

        seedOrderEvent("EVT-X-AE2", "AE", "MX-2");
        seedOrderEvent("EVT-X-SA2", "SA", "MX-2");
        Result sa2 = service.postOrder(orderAt("SA", "MX-2", "EVT-X-SA2"));
        Result ae2 = service.postOrder(orderAt("AE", "MX-2", "EVT-X-AE2"));
        assertEquals(sa2.entityId(), service.postOrder(orderAt("SA", "MX-2", "EVT-X-SA2")).entityId());
        assertEquals(ae2.entityId(), service.postOrder(orderAt("AE", "MX-2", "EVT-X-AE2")).entityId());

        // 退货：跨站同退货号、不同父订单行，分别 ACK。
        long aeItem = seedParentAt("MR-1", "AE");
        long saItem = seedParentAt("MR-1", "SA");
        seedReturnEvent("EVT-XR-AE1", "AE", "MR-1", "SRX-1", 1, aeItem);
        seedReturnEvent("EVT-XR-SA1", "SA", "MR-1", "SRX-1", 1, saItem);
        Result rAe = service.postReturn(ret("AE", "MR-1", "EVT-XR-AE1", "SRX-1", aeItem));
        Result rSa = service.postReturn(ret("SA", "MR-1", "EVT-XR-SA1", "SRX-1", saItem));
        assertTrue(rAe.posted());
        assertTrue(rSa.posted());
        assertNotEquals(rAe.entityId(), rSa.entityId());
        assertEquals(rAe.entityId(),
                service.postReturn(ret("AE", "MR-1", "EVT-XR-AE1", "SRX-1", aeItem)).entityId());
        assertEquals(rSa.entityId(),
                service.postReturn(ret("SA", "MR-1", "EVT-XR-SA1", "SRX-1", saItem)).entityId());
        // 4 笔正式入账订单 + 2 笔退货父订单
        assertEquals(6, count("sal_order"));
        assertEquals(2, count("sal_return"));
    }

    @Test
    void concurrentSameOrderPostsOnceOnRealMySql() throws Exception
    {
        seedOrderEvent("EVT-CONC-M", "AE", "MC-1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            OrderInput in = order("MC-1", "EVT-CONC-M");
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postOrder(in); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postOrder(in); });
            start.countDown();
            Result a = f1.get();
            Result b = f2.get();
            assertTrue(a.posted());
            assertTrue(b.posted());
            assertEquals(a.entityId(), b.entityId(), "真 MySQL 并发 ACK 应返回同一行 ID");
            assertEquals(1, count("sal_order"));
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MysqlConfig
    {
        @Bean
        DataSource dataSource()
        {
            DataSource ds = serverDataSource();
            String database = "noon_intake_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            DATABASE.set(database);
            DriverManagerDataSource configured = (DriverManagerDataSource) ds;
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
        IntakeService intakeService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new IntakeService(jdbcTemplate, guard);
        }
    }
}
