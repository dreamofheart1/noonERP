package com.yx.framework.reversal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null, biz_event_key varchar(160) not null, "
                + "source_event_id varchar(128), batch_id bigint not null, sal_order_item_id bigint, "
                + "sku_id bigint not null, qty int not null, unit_cost decimal(18,4) not null, "
                + "amount decimal(18,4) not null, consumed_at datetime(3) not null, "
                + "move_type varchar(24) not null, sign tinyint not null, reversal_of_id bigint, "
                + "sal_return_id bigint, period char(7), cross_period tinyint not null default 0, "
                + "raw_payload json, created_at datetime(3) not null, "
                + "unique key(consumption_no), unique key(biz_event_key,batch_id), "
                + "key idx_reversal (reversal_of_id), key idx_sal_return (sal_return_id), "
                + "check (qty > 0 and amount >= 0), check (sign in (-1,1)))");

        service = context.getBean(ReversalService.class);
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

    /**
     * 调用方重试范式（service 不吞锁异常，重试在调用方）：捕获锁等待超时/死锁异常后，以
     * <b>相同 sourceEventId 与相同入参</b>重试；service 前置幂等键保证重试命中 ALREADY_POSTED
     * 返回同一行 ID，不重复入账。用尽 attempts 仍失败则向上抛，由调用方决定隔离/告警。
     */
    private Result callerPostWithRetry(ReversalInput in, int attempts)
    {
        for (int i = 1; i <= attempts; i++)
        {
            try { return service.postReversal(in); }
            catch (org.springframework.dao.PessimisticLockingFailureException
                    | org.springframework.dao.QueryTimeoutException lockEx)
            {
                if (i == attempts) throw lockEx;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Test
    void callerRetryWithSameEventIsIdempotentOnRealMySql()
    {
        long returnId = seedReturn("AE", 11L, 100L, 3);
        long originalId = seedConsumption(16L, 11L, 100L, 3, "10.0000");
        String raw = payload(returnId, originalId, 3, 11L);
        seedEvent("EVT-CALLER-RETRY", "AE", raw, "PROCESSED");
        var in = input("AE", "EVT-CALLER-RETRY", returnId, originalId, 3, raw);

        Result first = callerPostWithRetry(in, 3);
        assertTrue(first.posted());
        // 模拟调用方未收到首次响应，按相同入参重试 3 次。
        for (int k = 0; k < 3; k++)
        {
            Result retry = callerPostWithRetry(in, 3);
            assertTrue(retry.posted());
            assertEquals("ALREADY_POSTED", retry.reasonCode());
            assertEquals(first.entityId(), retry.entityId(), "重试必须返回同一行 ID");
        }
        assertEquals(2, count("inv_batch_consumption"), "重试不得新增红冲行");
        assertEquals(0, count("noon_reversal_review"));
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
    }
}
