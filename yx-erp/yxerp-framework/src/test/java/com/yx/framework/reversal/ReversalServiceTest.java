package com.yx.framework.reversal;

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
import com.yx.framework.reversal.ReversalService.ReversalInput;
import com.yx.framework.reversal.ReversalService.Result;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOON-20261121-REVERSAL 独立验证：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper。
 * 不启动 MySQL/Redis/生产连接，任何人执行 mvn test 即可复跑。全部数据为系统内部构造的测试夹具。
 */
class ReversalServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ReversalService service;

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

        jdbc.execute("create table noon_reversal_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), sal_return_id_claimed bigint, "
                + "original_consumption_id_claimed bigint, source_event_id varchar(128), "
                + "raw_payload varchar(4000), reason_code varchar(48) not null, reason_detail varchar(512), "
                + "status varchar(24) not null default 'NEEDS_REVIEW', resolution_biz_key varchar(160), "
                + "resolved_by bigint, resolved_at timestamp, created_by bigint, "
                + "created_at timestamp not null default current_timestamp, unique key(review_no))");
        jdbc.execute("create table noon_reversal_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");

        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw varchar(4000), received_at timestamp not null, status varchar(24) not null, "
                + "unique key(event_id))");

        // 父退货（最小列，满足 service 查询）
        jdbc.execute("create table sal_return (id bigint auto_increment primary key, site_code varchar(16), "
                + "sku_id bigint not null, sal_order_item_id bigint, qty int not null, status varchar(24))");
        // FIFO 批次消耗流水（service 查询与写入列）
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null, biz_event_key varchar(160) not null, "
                + "source_event_id varchar(128), batch_id bigint not null, sal_order_item_id bigint, "
                + "sku_id bigint not null, qty int not null, unit_cost decimal(18,4) not null, "
                + "amount decimal(18,4) not null, consumed_at timestamp not null, "
                + "move_type varchar(24) not null, sign int not null, reversal_of_id bigint, "
                + "sal_return_id bigint, period char(7), cross_period int not null default 0, "
                + "raw_payload varchar(4000), created_at timestamp not null, "
                + "unique key(consumption_no), unique key(biz_event_key,batch_id))");
        // 注：H2 2.4 对跨内存库 CHECK 常量比较存在全局缓存缺陷；单行 CHECK 真实性由
        // MySqlReversalIT 用原始 noon_reversal_review.sql 在真 MySQL 验证，此处只保留唯一键。

        service = context.getBean(ReversalService.class);
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

    /** 种一条可信父退货，返回其 id。 */
    private long seedReturn(String site, long sku, long itemId, int qty)
    {
        jdbc.update("insert into sal_return(site_code,sku_id,sal_order_item_id,qty,status) values (?,?,?,?,'OPEN')",
                site, sku, itemId, qty);
        return jdbc.queryForObject("select id from sal_return where site_code=? and sku_id=? and qty=?",
                Long.class, site, sku, qty);
    }

    /** 种一条销售正常扣减（sign=+1），返回其 id。 */
    private long seedConsumption(long batchId, long sku, long itemId, int qty, String unitCost)
    {
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                        + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,period,cross_period,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,current_timestamp,'SALE',1,?,0,current_timestamp)",
                "CN:" + UUID.randomUUID(), "SALE:" + UUID.randomUUID(), null, batchId, itemId, sku, qty,
                new BigDecimal(unitCost), new BigDecimal(unitCost).multiply(BigDecimal.valueOf(qty)), "2026-11");
        return jdbc.queryForObject("select id from inv_batch_consumption where batch_id=? and sign=1", Long.class, batchId);
    }

    /** 种系统侧红冲事件（内部契约类型 RETURN_REVERSAL），payload 与入参一致。 */
    private void seedEvent(String eventId, String site, String payload, String status)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                + "values (?,'RETURN_REVERSAL',?,?,current_timestamp,?)", eventId, site, payload, status);
    }

    private static String payload(long salReturnId, long originalId, int qty, long sku)
    {
        return "{\"sal_return_id\":" + salReturnId + ",\"reversal_of_id\":" + originalId
                + ",\"qty\":" + qty + ",\"sku_id\":" + sku + "}";
    }

    private static ReversalInput input(String site, String eventId, Long returnId, Long originalId, int qty, String raw)
    {
        return new ReversalInput(site, eventId, returnId, originalId, qty, raw);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    /** 完整合法场景夹具：返回 input，事件已种，原消耗/父退货已建。 */
    private ReversalInput happyPath(String tag, String site, int returnQty, int originalQty, int reversalQty)
    {
        long sku = 11L;
        long itemId = 100L;
        long returnId = seedReturn(site, sku, itemId, returnQty);
        long originalId = seedConsumption(1L, sku, itemId, originalQty, "10.0000");
        String raw = payload(returnId, originalId, reversalQty, sku);
        seedEvent("EVT-" + tag, site, raw, "PROCESSED");
        return input(site, "EVT-" + tag, returnId, originalId, reversalQty, raw);
    }

    // ---------- 正向 ----------

    @Test
    void postedReversalWithTrustedParentAndEvidence()
    {
        ReversalInput in = happyPath("1", "AE", 5, 10, 2);
        Result r = service.postReversal(in);
        assertTrue(r.posted());
        assertEquals("POSTED", r.reasonCode());
        assertEquals(2, count("inv_batch_consumption")); // 原 + 红冲
        assertEquals(0, count("noon_reversal_review"));
        assertEquals(1, count("noon_reversal_audit"));
        // 红冲行：sign=-1、金额正值、biz_event_key 确定性、关联原行/父退货。
        assertEquals(-1, jdbc.queryForObject("select sign from inv_batch_consumption where id=?",
                Integer.class, r.entityId()));
        assertEquals("REV:EVT-1", jdbc.queryForObject(
                "select biz_event_key from inv_batch_consumption where id=?", String.class, r.entityId()));
        assertEquals(0, new BigDecimal("20.0000").compareTo(jdbc.queryForObject(
                "select amount from inv_batch_consumption where id=?", BigDecimal.class, r.entityId())));
    }

    // ---------- 反向：站点授权 ----------

    @Test
    void unauthorizedSiteIsDeniedNotQuarantined()
    {
        login(2L); // 无站点授权
        ReversalInput in = happyPath("2", "AE", 5, 10, 2);
        assertThrows(AccessDeniedException.class, () -> service.postReversal(in));
        assertEquals(1, count("inv_batch_consumption"), "仅原消耗行，无红冲");
        assertEquals(0, count("noon_reversal_review"));
    }

    // ---------- 反向：可信父记录 / 原消耗行 ----------

    @Test
    void orphanReversalIsQuarantinedParentMissing()
    {
        long sku = 11L;
        long originalId = seedConsumption(3L, sku, 100L, 10, "10.0000");
        String raw = payload(99999L, originalId, 2, sku);
        seedEvent("EVT-3", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-3", 99999L, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("PARENT_RETURN_MISSING", r.reasonCode());
        assertEquals(1, count("inv_batch_consumption"));
        assertEquals(1, count("noon_reversal_review"));
    }

    @Test
    void missingOriginalConsumptionIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        String raw = payload(returnId, 99999L, 2, 11L);
        seedEvent("EVT-4", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-4", returnId, 99999L, 2, raw));
        assertFalse(r.posted());
        assertEquals("ORIGINAL_CONSUMPTION_MISSING", r.reasonCode());
        assertEquals(1, count("noon_reversal_review"));
    }

    @Test
    void crossSiteParentIsQuarantined()
    {
        // 父退货在 SA，调用方为 AE（超管对两站都有权），站点不一致必须隔离。
        long sku = 11L;
        long returnId = seedReturn("SA", sku, 100L, 5);
        long originalId = seedConsumption(4L, sku, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, sku);
        seedEvent("EVT-5", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-5", returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("SITE_MISMATCH", r.reasonCode());
        assertEquals(1, count("noon_reversal_review"));
    }

    @Test
    void itemMismatchIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(5L, 22L, 200L, 10, "10.0000"); // SKU/订单行不同
        String raw = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-6", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-6", returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("ITEM_MISMATCH", r.reasonCode());
    }

    // ---------- 反向：来源证据绑定 ----------

    @Test
    void reversalWithoutEventIdIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(6L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, 11L);
        Result r = service.postReversal(input("AE", null, returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNIDENTIFIABLE", r.reasonCode());
    }

    @Test
    void uncorroboratedEventIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(7L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, 11L);
        Result r = service.postReversal(input("AE", "EVT-NEVER", returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    @Test
    void borrowedEventFromOtherSiteIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(8L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-8", "SA", raw, "PROCESSED"); // 事件站点为 SA，请求为 AE
        Result r = service.postReversal(input("AE", "EVT-8", returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    @Test
    void eventWithForgedQtyIsQuarantined()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(9L, 11L, 100L, 10, "10.0000");
        String eventRaw = payload(returnId, originalId, 2, 11L); // 事件佐证 qty=2
        seedEvent("EVT-9", "AE", eventRaw, "PROCESSED");
        // 入参偷改 qty=5，但 rawPayload 仍与事件一致 → 证据层 payload 与入参 qty 不符即拒。
        Result r = service.postReversal(input("AE", "EVT-9", returnId, originalId, 5, eventRaw));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
    }

    // ---------- 反向：累计守恒 ----------

    @Test
    void reversalExceedingOriginalIsQuarantined()
    {
        // 原消耗 qty=3，退货 qty=10，本次红冲 5 > 原消耗 3。
        long returnId = seedReturn("AE", 11L, 100L, 10);
        long originalId = seedConsumption(10L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 5, 11L);
        seedEvent("EVT-10", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-10", returnId, originalId, 5, raw));
        assertFalse(r.posted());
        assertEquals("REVERSAL_EXCEEDS_ORIGINAL", r.reasonCode());
        assertEquals(1, count("inv_batch_consumption"));
    }

    @Test
    void cumulativeReversalAcrossTwoEventsCannotExceedOriginal()
    {
        // 原消耗 qty=4；两次独立事件各红冲 3：第二次累计 6 > 4 必须隔离。
        long returnId = seedReturn("AE", 11L, 100L, 10);
        long originalId = seedConsumption(11L, 11L, 100L, 4, "10.0000");
        String raw1 = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-11A", "AE", raw1, "PROCESSED");
        assertTrue(service.postReversal(input("AE", "EVT-11A", returnId, originalId, 3, raw1)).posted());
        String raw2 = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-11B", "AE", raw2, "PROCESSED");
        Result second = service.postReversal(input("AE", "EVT-11B", returnId, originalId, 3, raw2));
        assertFalse(second.posted());
        assertEquals("REVERSAL_EXCEEDS_ORIGINAL", second.reasonCode());
        assertEquals(2, count("inv_batch_consumption")); // 原 + 第一笔红冲，第二笔未写
    }

    @Test
    void reversalExceedingReturnQtyIsQuarantined()
    {
        // 原消耗 qty=10，但退货单 qty=2，本次红冲 3 > 退货 qty。
        long returnId = seedReturn("AE", 11L, 100L, 2);
        long originalId = seedConsumption(12L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-12", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-12", returnId, originalId, 3, raw));
        assertFalse(r.posted());
        assertEquals("REVERSAL_EXCEEDS_RETURN", r.reasonCode());
        assertEquals(1, count("inv_batch_consumption"));
    }

    // ---------- 幂等 / 冲突 ----------

    @Test
    void duplicateReversalIsIdempotentAck()
    {
        ReversalInput in = happyPath("13", "AE", 5, 10, 2);
        Result first = service.postReversal(in);
        Result second = service.postReversal(in);
        assertTrue(first.posted());
        assertTrue(second.posted());
        assertEquals("ALREADY_POSTED", second.reasonCode());
        assertEquals(first.entityId(), second.entityId());
        assertEquals(2, count("inv_batch_consumption"));
        assertEquals(0, count("noon_reversal_review"));
    }

    @Test
    void sameEventDifferentCoreFieldIsConflictNotAck()
    {
        ReversalInput in = happyPath("14", "AE", 5, 10, 2);
        service.postReversal(in);
        // 同一 sourceEventId，但 payload 与入参都换成 qty=3 → 证据层与已入账核心字段不同。
        // 需重置事件 payload 为新内容，再入参同内容。
        jdbc.update("update int_webhook_event set payload_raw=? where event_id='EVT-14'",
                payload(in.salReturnId(), in.originalConsumptionId(), 3, 11L));
        String changed = payload(in.salReturnId(), in.originalConsumptionId(), 3, 11L);
        Result conflict = service.postReversal(input("AE", "EVT-14", in.salReturnId(), in.originalConsumptionId(), 3, changed));
        assertFalse(conflict.posted());
        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertEquals(2, count("inv_batch_consumption"));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentSameReversalPostsOnceWithIdenticalRowId() throws Exception
    {
        ReversalInput in = happyPath("15", "AE", 5, 10, 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(in); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(in); });
            start.countDown();
            Result a = f1.get();
            Result b = f2.get();
            assertTrue(a.posted());
            assertTrue(b.posted());
            assertEquals(a.entityId(), b.entityId(), "并发同源红冲应 ACK 同一行 ID");
            assertEquals(2, count("inv_batch_consumption"), "并发同源红冲只入账一次");
            assertEquals(0, count("noon_reversal_review"));
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    @Test
    void concurrentReversalsCannotExceedOriginal() throws Exception
    {
        // 原消耗 qty=4；两并发各红冲 3：串行化后第二个必被累计守恒拒绝（或撞键隔离），总量不超 4。
        long returnId = seedReturn("AE", 11L, 100L, 10);
        long originalId = seedConsumption(16L, 11L, 100L, 4, "10.0000");
        String rawA = payload(returnId, originalId, 3, 11L);
        String rawB = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-16A", "AE", rawA, "PROCESSED");
        seedEvent("EVT-16B", "AE", rawB, "PROCESSED");
        ReversalInput a = input("AE", "EVT-16A", returnId, originalId, 3, rawA);
        ReversalInput b = input("AE", "EVT-16B", returnId, originalId, 3, rawB);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(a); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(b); });
            start.countDown();
            Result r1 = f1.get();
            Result r2 = f2.get();
            assertTrue(r1.posted() || r2.posted(), "至少一笔先到者入账");
            assertTrue(!r1.posted() || !r2.posted(), "后到者不得放行超额");
            assertEquals(2, count("inv_batch_consumption"), "只入账一笔红冲");
            assertEquals(1, count("noon_reversal_review"), "后到者被隔离");
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    // ---------- 事务回滚 ----------

    @Test
    void auditFailureRollsBackReversal()
    {
        ReversalInput in = happyPath("17", "AE", 5, 10, 2);
        jdbc.execute("alter table noon_reversal_audit add constraint reject_posted check (action <> 'POSTED')");
        assertThrows(RuntimeException.class, () -> service.postReversal(in));
        assertEquals(1, count("inv_batch_consumption"), "审计失败应回滚红冲过账");
        assertEquals(0, count("noon_reversal_audit"));
    }

    // ---------- 入参 / SQL 契约 ----------

    @Test
    void invalidInputRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> service.postReversal(input("AE", "E", null, 1L, 2, "{}")));
        assertThrows(IllegalArgumentException.class,
                () -> service.postReversal(input("AE", "E", 1L, 1L, 0, "{}")));
    }

    @Test
    void reviewSqlContainsRequiredConstraints()
    {
        String ddl;
        try { ddl = Files.readString(Path.of("../sql/noon_reversal_review.sql")); }
        catch (Exception e) { throw new IllegalStateException(e); }
        for (String code : List.of("REVERSAL", "PARENT_RETURN_MISSING", "ORIGINAL_CONSUMPTION_MISSING",
                "SOURCE_UNVERIFIED", "REVERSAL_EXCEEDS_ORIGINAL", "REVERSAL_EXCEEDS_RETURN", "SOURCE_CONFLICT"))
            assertTrue(ddl.contains(code), "隔离层契约缺: " + code);
        assertTrue(ddl.contains("noon_reversal_audit"));
        assertTrue(ddl.contains("chk_reversal_resolution"));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config
    {
        @Bean
        DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource ds)
        {
            return new DataSourceTransactionManager(ds);
        }

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
        ReversalService reversalService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new ReversalService(jdbcTemplate, guard);
        }
    }
}
