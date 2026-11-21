package com.yx.framework.reversal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.reversal.ReversalService.ReversalInput;
import com.yx.framework.reversal.ReversalService.Result;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOON-20261121-REVERSAL：隔离 MySQL 集成验证（opt-in）。
 * 用 verify-reversal-runtime.py 拉起的临时 MySQL，加载原始 noon_reversal_review.sql，
 * 验证 CHECK/唯一键在真实 MySQL 生效、服务闸门在真实事务下正确。不读应用数据源配置。
 */
@EnabledIfEnvironmentVariable(named = "NOON_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlReversalIT
{
    private static final ThreadLocal<String> DATABASE = new ThreadLocal<>();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ReversalService service;

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

        // 原始隔离层 DDL（含 CHECK），在真实 MySQL 验证可执行且约束生效。
        String reviewSql = Files.readString(Path.of("../sql/noon_reversal_review.sql"));
        for (String s : reviewSql.split(";")) if (!s.isBlank()) jdbc.execute(s);

        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw json, received_at datetime(3) not null, status varchar(24) not null, "
                + "unique key(event_id))");
        jdbc.execute("create table sal_return (id bigint auto_increment primary key, site_code varchar(16), "
                + "sku_id bigint not null, sal_order_item_id bigint, qty int not null, status varchar(24))");

        // 用【实际主 schema v0.5】的 inv_batch_consumption DDL 建表（含全部 CHECK/唯一键/真实索引，
        // 不含 idx_sal_return），再执行【实际迁移脚本】补索引——所有用例均基于"真实 schema + 迁移"，
        // 而非测试表硬编码索引（Codex 三次复核意见 1）。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        jdbc.execute(extractCreateTable(schema, "inv_batch_consumption"));
        applyIdxMigration();

        // 每个用例开始时把全局锁等待超时复位为默认 50s，避免故障注入用例
        // 临时改小后残留，影响可控同步测试对 LOCK WAIT 的观察窗口。
        jdbc.execute("set global innodb_lock_wait_timeout=50");

        service = context.getBean(ReversalService.class);
        login(1L);
    }

    /** 从主 schema SQL 提取指定表的 CREATE TABLE 语句（到分号为止）。 */
    private static String extractCreateTable(String schema, String table)
    {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = schema.indexOf(marker);
        if (start < 0) throw new IllegalStateException("主 schema 未找到建表语句：" + table);
        int end = schema.indexOf(";", start);
        if (end < 0) throw new IllegalStateException("主 schema 建表语句未闭合：" + table);
        return schema.substring(start, end + 1);
    }

    /** 执行实际迁移脚本（幂等）：无索引则建、列正确跳过、同名错列则明确报错（Duplicate key name）。 */
    private void applyIdxMigration() { applySqlScriptFile("../sql/noon_reversal_idx_migration.sql"); }

    /** 执行实际回滚脚本（幂等）：有 idx_sal_return 则删、无则跳过。 */
    private void applyIdxRollback() { applySqlScriptFile("../sql/noon_reversal_idx_rollback.sql"); }

    /**
     * 通用：执行一个含会话变量 + PREPARE 的 SQL 脚本文件。必须在【同一连接】上按顺序执行，
     * 否则新连接看不到会话变量（DriverManagerDataSource 每次 getConnection 是新连接）。
     */
    private void applySqlScriptFile(String path)
    {
        String sql;
        try { sql = Files.readString(Path.of(path)); }
        catch (Exception e) { throw new IllegalStateException("读取 SQL 脚本失败：" + path, e); }
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (Statement st = connection.createStatement())
            {
                for (String statement : sql.split(";"))
                {
                    String s = stripSqlComments(statement);
                    if (!s.isBlank()) st.execute(s);
                }
            }
            return null;
        });
    }

    /** 去掉以 -- 开头的整行 SQL 注释（迁移脚本头部说明），保留语句本体。 */
    private static String stripSqlComments(String sql)
    {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R"))
        {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("--")) continue;
            out.append(line).append('\n');
        }
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

    private long seedReturn(String site, long sku, long itemId, int qty)
    {
        jdbc.update("insert into sal_return(site_code,sku_id,sal_order_item_id,qty,status) values (?,?,?,?,'OPEN')",
                site, sku, itemId, qty);
        return jdbc.queryForObject("select id from sal_return where site_code=? and sku_id=? and qty=?",
                Long.class, site, sku, qty);
    }

    private long seedConsumption(long batchId, long sku, long itemId, int qty, String unitCost)
    {
        return seedConsumption(batchId, sku, itemId, qty, unitCost, "SALE", 1);
    }

    private long seedConsumption(long batchId, long sku, long itemId, int qty, String unitCost,
                                 String moveType, int sign)
    {
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                        + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,period,cross_period,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,current_timestamp(3),?,?,?,0,current_timestamp(3))",
                "CN:" + UUID.randomUUID(), "SALE:" + UUID.randomUUID(), null, batchId, itemId, sku, qty,
                new BigDecimal(unitCost), new BigDecimal(unitCost).multiply(BigDecimal.valueOf(qty)),
                moveType, sign, "2026-11");
        return jdbc.queryForObject("select id from inv_batch_consumption where batch_id=?", Long.class, batchId);
    }

    private void seedEvent(String eventId, String site, String payload, String status)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                + "values (?,'RETURN_REVERSAL',?,?,current_timestamp(3),?)", eventId, site, payload, status);
    }

    private static String payload(long salReturnId, long originalId, int qty, long sku)
    {
        return "{\"sal_return_id\":" + salReturnId + ",\"reversal_of_id\":" + originalId
                + ",\"qty\":" + qty + ",\"sku_id\":" + sku + "}";
    }

    private static ReversalInput input(String site, String eventId, long returnId, long originalId, int qty, String raw)
    {
        return new ReversalInput(site, eventId, returnId, originalId, qty, raw);
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    @Test
    void originalReviewDdlEnforcesChecksOnRealMySql()
    {
        // CHECK 在真实 MySQL 生效：非法 entity_type 被拒。
        assertThrows(Exception.class, () -> jdbc.update("insert into noon_reversal_review"
                + "(review_no,entity_type,reason_code,status) values ('RV-BAD','BAD','X','NEEDS_REVIEW')"));
        // resolution CHECK：RESOLVED 但缺 resolved_by/resolved_at 被拒。
        assertThrows(Exception.class, () -> jdbc.update("insert into noon_reversal_review"
                + "(review_no,entity_type,reason_code,status) values ('RV-BAD2','REVERSAL','X','RESOLVED')"));
    }

    @Test
    void postedReversalOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(1L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-OK", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-OK", returnId, originalId, 2, raw));
        assertTrue(r.posted());
        assertEquals(-1, jdbc.queryForObject("select sign from inv_batch_consumption where id=?",
                Integer.class, r.entityId()));
        assertEquals(0, new BigDecimal("20.0000").compareTo(jdbc.queryForObject(
                "select amount from inv_batch_consumption where id=?", BigDecimal.class, r.entityId())));
        assertEquals(0, count("noon_reversal_review"));
    }

    @Test
    void cumulativeReversalCannotExceedOriginalOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 10);
        long originalId = seedConsumption(2L, 11L, 100L, 4, "10.0000");
        String raw1 = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-C1", "AE", raw1, "PROCESSED");
        assertTrue(service.postReversal(input("AE", "EVT-C1", returnId, originalId, 3, raw1)).posted());
        String raw2 = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-C2", "AE", raw2, "PROCESSED");
        Result second = service.postReversal(input("AE", "EVT-C2", returnId, originalId, 3, raw2));
        assertFalse(second.posted());
        assertEquals("REVERSAL_EXCEEDS_ORIGINAL", second.reasonCode());
        assertEquals(2, count("inv_batch_consumption"));
        assertEquals(1, count("noon_reversal_review"));
    }

    @Test
    void excessOverReturnQtyIsQuarantinedOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 2);
        long originalId = seedConsumption(3L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-ER", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-ER", returnId, originalId, 3, raw));
        assertFalse(r.posted());
        assertEquals("REVERSAL_EXCEEDS_RETURN", r.reasonCode());
        assertEquals(1, count("inv_batch_consumption"));
    }

    @Test
    void unborrowedEventAndParentMissingQuarantinedOnRealMySql()
    {
        long originalId = seedConsumption(4L, 11L, 100L, 10, "10.0000");
        String raw = payload(99999L, originalId, 2, 11L);
        seedEvent("EVT-PM", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-PM", 99999L, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("PARENT_RETURN_MISSING", r.reasonCode());

        long returnId = seedReturn("AE", 11L, 100L, 5);
        String raw2 = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-NEVER", "AE", raw2, "PROCESSED");
        Result u = service.postReversal(input("AE", "EVT-NO-WEBHOOK", returnId, originalId, 2, raw2));
        assertFalse(u.posted());
        assertEquals("SOURCE_UNVERIFIED", u.reasonCode());
        assertEquals(1, count("inv_batch_consumption"), "仅原消耗行，两笔均不得红冲入账");
        assertEquals(2, count("noon_reversal_review"));
    }

    // ---------- 二次复核意见 1：已用满仍 ACK 重传（真 MySQL） ----------

    @Test
    void retransmitAfterOriginalFullIsAckOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(10L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-RFULL", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-RFULL", returnId, originalId, 3, raw);
        assertTrue(service.postReversal(in).posted());
        Result again = service.postReversal(in);
        assertTrue(again.posted(), "已用满原消耗同事件重传应 ACK：实际 " + again.reasonCode());
        assertEquals("ALREADY_POSTED", again.reasonCode());
        assertEquals(2, count("inv_batch_consumption"));
        assertEquals(0, count("noon_reversal_review"));
    }

    @Test
    void retransmitAfterReturnFullIsAckOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 2);
        long originalId = seedConsumption(11L, 11L, 100L, 10, "10.0000");
        String raw = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-RFULLR", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-RFULLR", returnId, originalId, 2, raw);
        assertTrue(service.postReversal(in).posted());
        Result again = service.postReversal(in);
        assertTrue(again.posted(), "退货用满同事件重传应 ACK：实际 " + again.reasonCode());
        assertEquals("ALREADY_POSTED", again.reasonCode());
        assertEquals(2, count("inv_batch_consumption"));
    }

    @Test
    void concurrentRetransmitAfterFullPostsOnceOnRealMySql() throws Exception
    {
        // 用满后两个并发重传：均应 ACK 同一行，且只入账一次。
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(12L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-RCONC", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-RCONC", returnId, originalId, 3, raw);
        assertTrue(service.postReversal(in).posted()); // 首次用满
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(in); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(in); });
            start.countDown();
            Result a = f1.get();
            Result b = f2.get();
            assertTrue(a.posted() && b.posted(), "并发重传均应 ACK：a=" + a.reasonCode() + " b=" + b.reasonCode());
            assertEquals(a.entityId(), b.entityId());
            assertEquals(2, count("inv_batch_consumption"), "并发重传不得新增行");
        }
        finally { pool.shutdownNow(); login(1L); }
    }

    // ---------- 二次复核意见 2：不同原行同父退货并发不超额（真 MySQL，RR 当前读） ----------

    @Test
    void concurrentDifferentOriginalsCannotOverdrawReturnOnRealMySql() throws Exception
    {
        // 父退货 qty=5；两个不同原消耗行各 10，各红冲 3（合计 6>5）。
        // service 内部 requireSite/requireCurrentUserId 是普通读，会在各自事务里建立 RR 快照；
        // 两个并发事务锁同一父退货行而串行，后到者必须用【当前读】SUM 才能看到先到者已提交
        // 的红冲行并拒绝，否则普通一致性读用旧快照会放行导致 6>5 超额。
        // 允许数据库层锁等待超时/死锁异常——那也是事务整体回滚、未部分入账的合法结果。
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long o1 = seedConsumption(13L, 11L, 100L, 10, "10.0000");
        long o2 = seedConsumption(14L, 11L, 100L, 10, "10.0000");
        String rawA = payload(returnId, o1, 3, 11L);
        String rawB = payload(returnId, o2, 3, 11L);
        seedEvent("EVT-OS-A", "AE", rawA, "PROCESSED");
        seedEvent("EVT-OS-B", "AE", rawB, "PROCESSED");
        var a = input("AE", "EVT-OS-A", returnId, o1, 3, rawA);
        var b = input("AE", "EVT-OS-B", returnId, o2, 3, rawB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(a); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReversal(b); });
            start.countDown();
            Result r1 = settle(f1);
            Result r2 = settle(f2);
            int posted = (r1 != null && r1.posted() ? 1 : 0) + (r2 != null && r2.posted() ? 1 : 0);
            assertTrue(posted <= 1, "并发不同原行同父退货最多一笔入账，实际=" + posted);
            // 核心不变量：退货累计红冲数量不得超过父退货 qty=5。
            Integer reversed = jdbc.queryForObject(
                    "select coalesce(sum(qty),0) from inv_batch_consumption where sal_return_id=? and sign=-1",
                    Integer.class, returnId);
            assertNotNull(reversed);
            assertTrue(reversed <= 5, "退货累计红冲 " + reversed + " 不得超过 5（当前读守恒必须生效）");
            assertEquals(2 + posted, count("inv_batch_consumption"), "只入账 posted 笔红冲，无部分写入");
        }
        finally { pool.shutdownNow(); login(1L); }
    }

    /** 并发结果归一：锁等待超时/死锁异常视为事务整体回滚（未入账），返回 null。 */
    private static Result settle(Future<Result> f) throws Exception
    {
        try { return f.get(); }
        catch (java.util.concurrent.ExecutionException e)
        {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String name = cause.getClass().getName();
            if (name.contains("CannotAcquireLock") || name.contains("Deadlock")
                    || name.contains("PessimisticLocking") || name.contains("QueryTimeout"))
                return null;
            throw e;
        }
    }

    // ---------- 三次复核意见：可控同步双连接，证明旧快照与父退货行锁等待真实出现 ----------

    // ---------- 三次复核意见 2：正式重试组件 + 真实锁异常故障注入 ----------

    /**
     * 正式组件 ReversalRetryInvoker 的同键幂等冒烟：首次 POSTED，相同入参重复调用命中
     * ALREADY_POSTED 同一行 ID、不新增红冲。真实锁异常下的【重试成功/耗尽抛错】见下方两个
     * 故障注入测试（Codex 三次复核意见 2：不再用测试私有重试方法充当"已验证的重试机制"）。
     */
    @Test
    void retryInvokerSameKeyIsIdempotentOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(16L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-CALLER-RETRY", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-CALLER-RETRY", returnId, originalId, 3, raw);
        ReversalRetryInvoker invoker = context.getBean(ReversalRetryInvoker.class);

        ReversalRetryInvoker.RetryResult first = invoker.postWithRetry(in, 3, 0);
        assertTrue(first.posted());
        assertEquals(1, first.attempts(), "首次即成功，attempts=1");
        for (int k = 0; k < 3; k++)
        {
            ReversalRetryInvoker.RetryResult retry = invoker.postWithRetry(in, 3, 0);
            assertTrue(retry.posted());
            assertEquals("ALREADY_POSTED", retry.result().reasonCode());
            assertEquals(first.result().entityId(), retry.result().entityId(), "重试必须返回同一行 ID");
        }
        assertEquals(2, count("inv_batch_consumption"), "重试不得新增红冲行");
        assertEquals(0, count("noon_reversal_review"));
    }

    /**
     * 真实锁异常故障注入·重试成功（确定性两段式）：
     * 第 1 段 blocker 持有父退货行锁，调用组件（attempts=1）真实撞锁等待超时并抛锁异常，
     * 证明"锁异常真实发生且 service 事务整体回滚（无部分写入）"；
     * 第 2 段测试主动释放锁，再用相同入参调用组件（attempts=3）成功过账。
     * 两段合起来证明"锁异常 → 回滚 → 以同键重试 → 成功且不重复入账"。
     * 组件内部的【有界重试与耗尽抛错】由 retryInvokerExhaustsAndThrowsOnRealLockTimeoutOnRealMySql 覆盖。
     */
    @Test
    void retryInvokerRecoversFromRealLockTimeoutOnRealMySql() throws Exception
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(17L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-RETRY-OK", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-RETRY-OK", returnId, originalId, 3, raw);

        DataSource ds = context.getBean(DataSource.class);
        ReversalRetryInvoker invoker = context.getBean(ReversalRetryInvoker.class);
        Integer oldTimeout = jdbc.queryForObject("select @@global.innodb_lock_wait_timeout", Integer.class);
        jdbc.execute("set global innodb_lock_wait_timeout=1");
        try
        {
            // 第 1 段：持锁调用，真实锁超时 → 组件 attempts=1 抛锁异常。
            try (Connection blocker = ds.getConnection(); Statement st = blocker.createStatement())
            {
                blocker.setAutoCommit(false);
                try (ResultSet rs = st.executeQuery("select qty from sal_return where id=" + returnId + " for update"))
                {
                    assertTrue(rs.next());
                }
                assertThrows(org.springframework.dao.PessimisticLockingFailureException.class,
                        () -> invoker.postWithRetry(in, 1, 0));
                assertEquals(1, count("inv_batch_consumption"), "锁失败那次事务整体回滚，无部分写入");
                assertEquals(0, count("noon_reversal_review"));
                // 主动释放锁，进入第 2 段。
                blocker.rollback();
            }

            // 第 2 段：锁已释放，相同入参重试成功，attempts=1（首次即成功）。
            ReversalRetryInvoker.RetryResult retry = invoker.postWithRetry(in, 3, 0);
            assertTrue(retry.posted(), "锁故障解除后相同键重试应成功：" + retry.result().reasonCode());
            assertEquals(1, retry.attempts());
            assertEquals(2, count("inv_batch_consumption"), "只入账一次红冲，无部分写入");
            assertEquals(0, count("noon_reversal_review"));
            // 再重试一次 → 幂等 ACK 同一行，验证故障恢复后重复调用不重复入账。
            ReversalRetryInvoker.RetryResult again = invoker.postWithRetry(in, 3, 0);
            assertEquals("ALREADY_POSTED", again.result().reasonCode());
            assertEquals(retry.result().entityId(), again.result().entityId());
            assertEquals(2, count("inv_batch_consumption"));
        }
        finally
        {
            jdbc.execute("set global innodb_lock_wait_timeout=" + oldTimeout);
        }
    }

    /**
     * 真实锁异常故障注入·耗尽抛错：锁持续被持有，ReversalRetryInvoker 用尽 attempts 后
     * 抛出锁异常（不吞），且 service 每次失败事务整体回滚——无部分写入、无隔离行。
     */
    @Test
    void retryInvokerExhaustsAndThrowsOnRealLockTimeoutOnRealMySql() throws Exception
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(18L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-RETRY-EXHAUST", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-RETRY-EXHAUST", returnId, originalId, 3, raw);

        DataSource ds = context.getBean(DataSource.class);
        ReversalRetryInvoker invoker = context.getBean(ReversalRetryInvoker.class);
        Integer oldTimeout = jdbc.queryForObject("select @@global.innodb_lock_wait_timeout", Integer.class);
        jdbc.execute("set global innodb_lock_wait_timeout=1");
        try (Connection blocker = ds.getConnection(); Statement st = blocker.createStatement())
        {
            blocker.setAutoCommit(false);
            try (ResultSet rs = st.executeQuery("select qty from sal_return where id=" + returnId + " for update"))
            {
                assertTrue(rs.next());
            }
            // 锁始终持有；attempts=3、每次等待约 1s，用尽后必须向上抛锁异常。
            assertThrows(org.springframework.dao.PessimisticLockingFailureException.class,
                    () -> invoker.postWithRetry(in, 3, 20));
            assertEquals(1, count("inv_batch_consumption"), "仅原消耗行，失败事务整体回滚无部分写入");
            assertEquals(0, count("noon_reversal_review"), "锁异常由调用方处理，service 不写隔离行");
        }
        finally
        {
            jdbc.execute("set global innodb_lock_wait_timeout=" + oldTimeout);
        }
    }

    /**
     * 可控同步双连接测试（Codex 三次复核意见）：此前并发测试用普通并发 + 容忍锁异常，
     * 无法证明 MySQL RR 下旧快照与父退货行锁等待<b>真实出现</b>。本测试用两个真实独立连接
     * + latch 精确编排，重放 service 的锁序/当前读序列：
     * <ol>
     *   <li>A、B 都在 A 提交前先做普通读建立 RR 旧快照；</li>
     *   <li>A 锁原行 o1 + 父退货行；B 锁不同原行 o2 后阻塞在同一父退货行锁上；</li>
     *   <li>主线程从 information_schema.innodb_trx 观察到 LOCK WAIT（锁等待真实发生）；</li>
     *   <li>A 当前读守恒放行并提交；B 拿到锁后做快照读/当前读对照；</li>
     *   <li>B 依当前读判定 3+3&gt;5 超额 → 整体回滚，无部分写入。</li>
     * </ol>
     * 硬证据：B 事务内普通快照读=0（看不到 A 已提交行，证明旧快照确实建立），当前读
     * FOR UPDATE=3（看到 A 已提交行，证明当前读生效）——两者不同，才能证明"必须用当前读
     * SUM 避免旧快照超额"这一不变量，而非仅靠结果不超额的反推。
     */
    @Test
    void controlledOldSnapshotAndLockWaitCurrentReadPreventOverdrawOnRealMySql() throws Exception
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long o1 = seedConsumption(20L, 11L, 100L, 10, "10.0000");
        long o2 = seedConsumption(21L, 11L, 100L, 10, "10.0000");

        DataSource ds = context.getBean(DataSource.class);
        AtomicReference<Throwable> bError = new AtomicReference<>();
        AtomicReference<String> bOutcome = new AtomicReference<>("NOT_RUN");
        AtomicReference<Long> bStaleSum = new AtomicReference<>();
        AtomicReference<Long> bFreshSum = new AtomicReference<>();
        CountDownLatch bAboutToLockParent = new CountDownLatch(1);
        CountDownLatch aCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> bFuture = null;

        try (Connection xa = ds.getConnection(); Connection xb = ds.getConnection())
        {
            xa.setAutoCommit(false);
            xb.setAutoCommit(false);
            // 稳定性加固：B 连接显式设较长锁等待超时，避免前序故障注入用例调整 global
            // innodb_lock_wait_timeout 的残留（测试执行顺序不确定）令 B 过早超时、观察不到 LOCK WAIT。
            try (Statement sb = xb.createStatement())
            {
                sb.execute("set session innodb_lock_wait_timeout=30");
            }
            try
            {
                bFuture = pool.submit(() -> {
                    try
                    {
                        // B 的第一次普通读：A 提交前建立 RR 旧快照（快照里没有 A 的红冲行）。
                        assertEquals(5, queryInt(xb, "select qty from sal_return where id=" + returnId));
                        // B 锁自己的原行 o2（与 o1 不同，不冲突）。
                        assertNotNull(lockOne(xb, "select id from inv_batch_consumption where id=" + o2 + " for update"));
                        bAboutToLockParent.countDown();
                        // B 尝试锁同一父退货行 —— A 持有，B 在此阻塞。
                        assertNotNull(lockOne(xb, "select qty from sal_return where id=" + returnId + " for update"));
                        aCommitted.await(20, TimeUnit.SECONDS);

                        // 对照 1：快照读（普通 SELECT）—— B 旧快照看不到 A 已提交的红冲行。
                        long stale = sumPlain(xb, "select coalesce(sum(qty),0) from inv_batch_consumption "
                                + "where sal_return_id=" + returnId + " and sign=-1");
                        bStaleSum.set(stale);
                        // 对照 2：当前读（FOR UPDATE + Java long 累加），与 service sumReversedOnReturn 同构。
                        long fresh = sumLocked(xb, "select id,qty from inv_batch_consumption "
                                + "where sal_return_id=" + returnId + " and sign=-1 order by id for update");
                        bFreshSum.set(fresh);
                        if (fresh + 3 > 5)
                        {
                            xb.rollback(); // 超额：模拟 service 隔离，事务整体回滚。
                            bOutcome.set("ROLLED_BACK_EXCEEDS_RETURN");
                        }
                        else
                        {
                            insertRed(xb, 21L, "REV:EVT-LW-B", "EVT-LW-B", returnId, o2, 3);
                            xb.commit();
                            bOutcome.set("POSTED_OVERDRAW"); // 走到这里说明当前读失效，测试将失败。
                        }
                    }
                    catch (Throwable t)
                    {
                        bError.set(t);
                        try { xb.rollback(); } catch (Exception ignore) { }
                    }
                });

                // ---- A 事务（主线程）----
                assertEquals(5, queryInt(xa, "select qty from sal_return where id=" + returnId)); // A 建立旧快照
                assertNotNull(lockOne(xa, "select id from inv_batch_consumption where id=" + o1 + " for update"));
                assertNotNull(lockOne(xa, "select qty from sal_return where id=" + returnId + " for update"));

                assertTrue(bAboutToLockParent.await(20, TimeUnit.SECONDS), "B 应到达即将锁父退货行处");
                // B 已发出"即将锁父退货行"，但真正发起 for update 并进入 LOCK WAIT 有微小调度延迟；
                // 稍等再开始轮询，避免首帧观察过早（偶发竞态加固）。
                Thread.sleep(200);
                // 观察锁等待：轮询 innodb_trx，直到出现 LOCK WAIT 状态事务。
                boolean observed = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline)
                {
                    Integer waits = jdbc.queryForObject(
                            "select count(*) from information_schema.innodb_trx where trx_state='LOCK WAIT'",
                            Integer.class);
                    if (waits != null && waits > 0) { observed = true; break; }
                    Thread.sleep(50);
                }
                assertTrue(observed, "必须从 information_schema.innodb_trx 观察到真实 LOCK WAIT（B 等待 A 持有的父退货行锁）");
                assertEquals("NOT_RUN", bOutcome.get(), "A 提交前 B 不应拿到父退货行锁");

                // A 当前读守恒：原行 A 已冲回 0、父退货已冲回 0，3<=10 且 3<=5 → 放行。
                assertEquals(0L, sumLocked(xa, "select id,qty from inv_batch_consumption "
                        + "where reversal_of_id=" + o1 + " and sign=-1 order by id for update"));
                assertEquals(0L, sumLocked(xa, "select id,qty from inv_batch_consumption "
                        + "where sal_return_id=" + returnId + " and sign=-1 order by id for update"));
                insertRed(xa, 20L, "REV:EVT-LW-A", "EVT-LW-A", returnId, o1, 3);
                xa.commit();
                aCommitted.countDown();

                bFuture.get(20, TimeUnit.SECONDS);
                assertNull(bError.get(), "B 线程不应异常：" + bError.get());
                assertEquals("ROLLED_BACK_EXCEEDS_RETURN", bOutcome.get());
                assertEquals(0L, bStaleSum.get(), "B 的 RR 旧快照应看不到 A 已提交的红冲行（证明旧快照确实建立）");
                assertEquals(3L, bFreshSum.get(), "B 的当前读 FOR UPDATE 应看到 A 已提交的 3 件（证明当前读生效）");
                assertTrue(bStaleSum.get() < bFreshSum.get(), "快照读与当前读必须不同，否则无法证明旧快照场景成立");

                Integer reversed = jdbc.queryForObject(
                        "select coalesce(sum(qty),0) from inv_batch_consumption where sal_return_id=? and sign=-1",
                        Integer.class, returnId);
                assertEquals(3, reversed, "最终退货累计红冲只能是 A 的 3，不得超过 5");
                assertEquals(3, count("inv_batch_consumption"), "2 原行 + 1 红冲；B 整体回滚无部分写入");
            }
            finally
            {
                aCommitted.countDown(); // 主线程断言失败时也不让 B 永久阻塞。
                pool.shutdownNow();
                if (bFuture != null)
                    try { bFuture.get(5, TimeUnit.SECONDS); } catch (Exception ignore) { }
            }
        }
    }

    private static Integer queryInt(Connection c, String sql) throws Exception
    {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            return rs.next() ? ((Number) rs.getObject(1)).intValue() : null;
        }
    }

    private static Object lockOne(Connection c, String sql) throws Exception
    {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private static long sumPlain(Connection c, String sql) throws Exception
    {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            rs.next();
            return ((Number) rs.getObject(1)).longValue();
        }
    }

    private static long sumLocked(Connection c, String sql) throws Exception
    {
        long total = 0L;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
        {
            while (rs.next()) total += ((Number) rs.getObject("qty")).longValue();
        }
        return total;
    }

    private static void insertRed(Connection c, long batchId, String bizKey, String srcEvent,
                                  long returnId, long origId, int qty) throws Exception
    {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                        + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                        + "reversal_of_id,sal_return_id,period,cross_period,raw_payload,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,current_timestamp(3),'RETURN_REVERSAL',-1,?,?,'2026-11',0,?,current_timestamp(3))"))
        {
            ps.setString(1, "CN:" + UUID.randomUUID());
            ps.setString(2, bizKey);
            ps.setString(3, srcEvent);
            ps.setLong(4, batchId);
            ps.setLong(5, 100L);
            ps.setLong(6, 11L);
            ps.setInt(7, qty);
            ps.setBigDecimal(8, new BigDecimal("10.0000"));
            ps.setBigDecimal(9, new BigDecimal("10.0000").multiply(BigDecimal.valueOf(qty)));
            ps.setLong(10, origId);
            ps.setLong(11, returnId);
            ps.setString(12, "{}");
            assertEquals(1, ps.executeUpdate());
        }
    }

    // ---------- 三次复核意见 1：实际 schema 迁移幂等 + 执行计划走索引 ----------

    /**
     * 实际 schema/迁移验证：setUp 已用主 schema v0.5 的真实 DDL 建 inv_batch_consumption
     * （无 idx_sal_return）并执行 noon_reversal_idx_migration.sql。本测试再执行一次迁移，
     * 验证幂等（不报错、索引仍只有 1 个），并用 EXPLAIN 证明 sumReversedOnReturn 的当前读
     * 走 idx_sal_return（type=ref）而非全表扫描（type=ALL）——迁移确实改变执行计划。
     */
    @Test
    void idxMigrationIsIdempotentAndCurrentReadUsesIndexOnRealMySql()
    {
        // 1) 索引已由 setUp 迁移建立，恰好一个。
        Integer before = jdbc.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and table_name='inv_batch_consumption' "
                + "and index_name='idx_sal_return'", Integer.class);
        assertEquals(1, before, "setUp 迁移应已建立且仅建立一个 idx_sal_return");

        // 2) 重复执行迁移脚本应幂等：不抛异常、索引数不变。
        assertDoesNotThrow(this::applyIdxMigration);
        Integer after = jdbc.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and table_name='inv_batch_consumption' "
                + "and index_name='idx_sal_return'", Integer.class);
        assertEquals(1, after, "重复迁移不得新增索引");

        // 3) EXPLAIN：当前读 SUM 的 WHERE sal_return_id=? AND sign=-1 走 idx_sal_return。
        List<Map<String, Object>> plan = jdbc.queryForList("explain select id,qty from inv_batch_consumption "
                + "where sal_return_id=? and sign=-1 for update", 999L);
        assertFalse(plan.isEmpty(), "EXPLAIN 应返回执行计划");
        String accessType = String.valueOf(plan.get(0).get("type"));
        String key = plan.get(0).get("key") == null ? "" : String.valueOf(plan.get(0).get("key"));
        assertEquals("idx_sal_return", key, "当前读应命中 idx_sal_return，实际 key=" + key);
        assertNotEquals("ALL", accessType, "不应全表扫描；实际 type=" + accessType);
    }

    /**
     * 五次复核意见 2·同名错列校验：先删掉 setUp 建立的正确 idx_sal_return，改建成同名但错列
     * （batch_id）的索引，再跑迁移脚本——列定义校验必须【明确报错】（走 ADD INDEX 触发
     * Duplicate key name），绝不静默跳过错列索引。
     */
    @Test
    void idxMigrationRejectsSameNamedWrongColumnIndexOnRealMySql()
    {
        jdbc.execute("alter table inv_batch_consumption drop index idx_sal_return");
        jdbc.execute("alter table inv_batch_consumption add index idx_sal_return (batch_id)");
        Integer salCount = jdbc.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and table_name='inv_batch_consumption' "
                + "and index_name='idx_sal_return' and column_name='sal_return_id'", Integer.class);
        assertEquals(0, salCount, "错列索引不应包含 sal_return_id 列");

        // 同名索引列不匹配 -> 迁移脚本应明确报错（Duplicate key name），不静默跳过。
        RuntimeException ex = assertThrows(RuntimeException.class, this::applyIdxMigration,
                "同名错列索引必须导致迁移明确失败");
        String msg = String.valueOf(ex.getMessage()) + " / " + String.valueOf(ex.getCause());
        assertTrue(msg.contains("Duplicate") || msg.contains("idx_sal_return"),
                "报错应指出索引冲突，实际：" + msg);
    }

    /**
     * 五次复核意见 2·回滚/重跑：执行回滚脚本删索引 -> 重复回滚幂等 -> 再跑迁移恢复 -> EXPLAIN 仍命中，
     * 证明迁移+回滚脚本配套的恢复/重跑行为在真库可用（文档所述与实际一致）。
     */
    @Test
    void idxRollbackAndRerunRestoresIndexOnRealMySql()
    {
        applyIdxRollback();
        assertEquals(0, idxSalCount(), "回滚后索引应消失");

        assertDoesNotThrow(this::applyIdxRollback, "重复回滚应幂等");
        assertEquals(0, idxSalCount(), "重复回滚后仍无索引");

        assertDoesNotThrow(this::applyIdxMigration, "回滚后重跑迁移应恢复索引");
        assertEquals(1, idxSalCount(), "重跑迁移应恢复唯一 idx_sal_return");

        List<Map<String, Object>> plan = jdbc.queryForList("explain select id,qty from inv_batch_consumption "
                + "where sal_return_id=? and sign=-1 for update", 999L);
        assertEquals("idx_sal_return", String.valueOf(plan.get(0).get("key")),
                "恢复后当前读仍应命中 idx_sal_return");
    }

    private int idxSalCount()
    {
        Integer n = jdbc.queryForObject("select count(*) from information_schema.statistics "
                + "where table_schema=database() and table_name='inv_batch_consumption' "
                + "and index_name='idx_sal_return'", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 五次复核意见 1·事务边界：外层已有活动事务时调用 ReversalRetryInvoker 必须被显式拒绝
     * （IllegalStateException），且不执行任何 service 写入、不污染外层事务为 rollback-only；
     * 被拒后在事务外再用相同入参调用仍可正常过账（fail-fast 不破坏功能与幂等）。
     */
    @Test
    void retryInvokerRejectsCallInsideActiveTransactionOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(19L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-INNER-TX", "AE", raw, "PROCESSED");
        ReversalInput in = input("AE", "EVT-INNER-TX", returnId, originalId, 3, raw);
        ReversalRetryInvoker invoker = context.getBean(ReversalRetryInvoker.class);
        PlatformTransactionManager txManager = context.getBean(PlatformTransactionManager.class);
        login(1L);

        // 外层事务内调用：被拒绝，但外层事务本身未被标记 rollback-only，可正常完成提交。
        assertDoesNotThrow(() -> new TransactionTemplate(txManager).execute(status -> {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> invoker.postWithRetry(in, 3, 0), "外层事务中调用应被显式拒绝");
            assertTrue(ex.getMessage().contains("事务外"), "拒绝原因应指明事务边界契约");
            return null;
        }), "外层事务不应因组件拒绝而被污染为 rollback-only");

        assertEquals(1, count("inv_batch_consumption"), "被拒调用不得触发任何 service 写入");
        assertEquals(0, count("noon_reversal_review"));

        // 事务外用相同入参调用仍成功，fail-fast 不破坏功能；原 1 行 + 红冲 1 行。
        ReversalRetryInvoker.RetryResult ok = invoker.postWithRetry(in, 3, 0);
        assertTrue(ok.posted(), "事务外重试应成功：reason=" + ok.result().reasonCode());
        assertEquals(2, count("inv_batch_consumption"));
    }

    @Test
    void negativeSignOriginalIsQuarantinedOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 5);
        long originalId = seedConsumption(15L, 11L, 100L, 10, "10.0000", "SALE", -1);
        String raw = payload(returnId, originalId, 2, 11L);
        seedEvent("EVT-NEGSIGN", "AE", raw, "PROCESSED");
        Result r = service.postReversal(input("AE", "EVT-NEGSIGN", returnId, originalId, 2, raw));
        assertFalse(r.posted());
        assertEquals("ORIGINAL_NOT_SALE", r.reasonCode());
        assertEquals(1, count("inv_batch_consumption"));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MysqlConfig
    {
        @Bean
        DataSource dataSource()
        {
            DataSource ds = serverDataSource();
            String database = "noon_reversal_" + UUID.randomUUID().toString().replace("-", "");
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
        ReversalService reversalService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new ReversalService(jdbcTemplate, guard);
        }

        @Bean
        ReversalRetryInvoker reversalRetryInvoker(ReversalService service) { return new ReversalRetryInvoker(service); }
    }
}
