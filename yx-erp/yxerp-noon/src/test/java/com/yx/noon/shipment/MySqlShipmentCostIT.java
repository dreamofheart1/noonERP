package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 第三轮第二批「头程费用登记」隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（{@code log_shipment_cost} / {@code log_asn}）+
 * {@code yx-erp/sql/noon_m4_shipment_cost.sql}（本批幂等表，WorkBuddy 正式迁移）+
 * {@code yx-erp/sql/noon_m4_inventory.sql}（共享审计表与库存对象，用于"不动库存"断言）。
 *
 * <p>验证：真 DDL 的 {@code chk_ship_cost_alloc} CHECK 与 {@code uk_cost_no}/{@code request_id} 唯一键、
 * 真库登记（金额/汇率列宽、审计落库、库存零写入）、幂等重放/内容冲突/resultOf、跨站 ASN/越权站点、
 * <b>并发同 requestId 只登记一笔</b>、迁移脚本幂等。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}
 * 或 {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlShipmentCostIT
{
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ShipmentCostService service;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null,
                "缺少隔离库 JDBC URL/端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT");

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        // 真实 schema v0.5 表（迁移脚本不建）：费用主表、ASN、采购单（多选校验）、批次消耗（零写入断言）。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"log_shipment_cost", "log_asn", "pur_order", "inv_batch_consumption"})
            jdbc.execute(extractCreateTable(schema, table));
        // M4 第一轮正式迁移：共享审计表 + 库存对象（用于契约 §0 零写入断言）。
        runSqlFile("../sql/noon_m4_inventory.sql");
        // 本批正式迁移：幂等表 + 费用↔采购单关联表。
        runSqlFile("../sql/noon_m4_shipment_cost.sql");
        runSqlFile("../sql/noon_m4_shipment_cost_po_link.sql");

        clean();
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        service = context.getBean(ShipmentCostService.class);
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
    void migrationIsIdempotentAndRealChecksHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_shipment_cost.sql"); // 第二次：幂等
        runSqlFileUnchecked("../sql/noon_m4_shipment_cost_po_link.sql"); // 第二次：幂等
        assertEquals(5, jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name in ('log_shipment_cost','log_asn',"
                        + "'noon_m4_shipment_cost_request','noon_m4_inventory_audit',"
                        + "'log_shipment_cost_po')", Integer.class),
                "费用主表 + ASN + 幂等表 + 共享审计表 + 采购单关联表应存在");

        // DB CHECK：allocation_flag ∈ NONE/PARTIAL/FULL（真 DDL chk_ship_cost_alloc）。
        jdbc.update("insert into log_shipment_cost(cost_no,site_code,fee_type,amount,currency,fx_rate,"
                + "allocation_flag) values ('SC:IT:OK1','AE','FREIGHT',1,'CNY',1,'NONE')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into log_shipment_cost"
                + "(cost_no,site_code,fee_type,amount,currency,fx_rate,allocation_flag) "
                + "values ('SC:IT:BAD','AE','FREIGHT',1,'CNY',1,'ALL')"), "allocation_flag='ALL' 必须被 CHECK 拒绝");

        // 唯一键：uk_cost_no。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into log_shipment_cost"
                + "(cost_no,site_code,fee_type,amount,currency,fx_rate,allocation_flag) "
                + "values ('SC:IT:OK1','AE','FREIGHT',1,'CNY',1,'NONE')"), "cost_no 必须唯一");

        // 幂等表 request_id 主键 + cost_id NOT NULL 列存在（PENDING 0 占位的结构基础）。
        jdbc.update("insert into noon_m4_shipment_cost_request(request_id,cost_id,site_code,operator_id,"
                + "content_fingerprint,status) values ('IT-SHIP-REQ-1',0,'AE',1,'" + "a".repeat(64) + "','PENDING')");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_shipment_cost_request"
                + "(request_id,cost_id,site_code,operator_id,content_fingerprint,status) "
                + "values ('IT-SHIP-REQ-1',0,'AE',1,'" + "b".repeat(64) + "','PENDING')"),
                "request_id 必须主键唯一");
        assertEquals("0", jdbc.queryForObject("select cost_id from noon_m4_shipment_cost_request "
                + "where request_id='IT-SHIP-REQ-1'", String.class), "PENDING 占位 0 可在真库落库");
    }

    // ------------------------------------------------------------ 真库登记

    @Test
    void createOnRealSchemaWritesCostAuditAndNoInventoryTouch()
    {
        long asn = newAsn("ASN-IT-AE-1", "AE");
        int batches = count("inv_batch");
        int ledgers = count("inv_stock_ledger");
        int moves = count("inv_stock_move");
        int consumptions = count("inv_batch_consumption");

        String cnyKey = rid();
        ShipmentCostResult cny = service.create(new ShipmentCostInput(asn, "AE", "2026-11", "FREIGHT",
                new BigDecimal("1234.56785"), "CNY", BigDecimal.ONE, null, "真库 CNY", cnyKey));
        assertFalse(cny.replayed());
        assertTrue(cny.costNo().matches("SC:AE:[0-9A-F]{8}"), cny.costNo());

        Map<String, Object> row = jdbc.queryForMap("select * from log_shipment_cost where id=?", cny.costId());
        assertEquals(asn, ((Number) row.get("asn_id")).longValue());
        assertEquals("AE", row.get("site_code"));
        assertEquals("2026-11", row.get("period"));
        assertEquals("FREIGHT", row.get("fee_type"));
        assertEquals(0, new BigDecimal("1234.5679").compareTo((BigDecimal) row.get("amount")),
                "amount DECIMAL(18,4) HALF_UP: " + row.get("amount"));
        assertEquals("CNY", row.get("currency"));
        assertEquals(0, new BigDecimal("1.00000000").compareTo((BigDecimal) row.get("fx_rate")),
                "CNY 显式 fx=1 落 8 位: " + row.get("fx_rate"));
        assertEquals("NONE", row.get("allocation_flag"));
        assertEquals("真库 CNY", row.get("remark"));

        // 外币 + PARTIAL 仅标记：合计金额不做分摊、库存零写入。
        ShipmentCostResult aed = service.create(new ShipmentCostInput(null, "AE", "2026-11", "DUTY",
                new BigDecimal("100"), "AED", new BigDecimal("3.6725"), "PARTIAL", "真库 AED", rid()));
        Map<String, Object> aedRow = jdbc.queryForMap("select * from log_shipment_cost where id=?", aed.costId());
        assertEquals(0, new BigDecimal("3.67250000").compareTo((BigDecimal) aedRow.get("fx_rate")));
        assertEquals("PARTIAL", aedRow.get("allocation_flag"));
        assertNull(aedRow.get("asn_id"), "asnId 可空");

        // 审计同事务：CREATE 行、operator=1、detail 含单号。
        assertEquals(2, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit "
                + "where biz_type='SHIPMENT_COST' and action='CREATE'", Integer.class));
        String detail = jdbc.queryForObject("select detail from noon_m4_inventory_audit where biz_id=?",
                String.class, String.valueOf(cny.costId()));
        assertTrue(detail.contains(cny.costNo()), detail);
        assertEquals(1L, jdbc.queryForObject("select operator_id from noon_m4_inventory_audit where biz_id=?",
                Long.class, String.valueOf(cny.costId())));

        // 契约 §0：不动库存/批次/台账/COGS。
        assertEquals(batches, count("inv_batch"));
        assertEquals(ledgers, count("inv_stock_ledger"));
        assertEquals(moves, count("inv_stock_move"));
        assertEquals(consumptions, count("inv_batch_consumption"));

        // 幂等行 DONE + 真实 id 回填（PENDING 0 → DONE）。
        assertEquals(cny.costId(), jdbc.queryForObject("select cost_id from noon_m4_shipment_cost_request "
                + "where request_id=?", Long.class, cnyKey));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_shipment_cost_request "
                + "where request_id=?", String.class, cnyKey));
    }

    // ------------------------------------------------------------ 幂等 / result

    @Test
    void replayConflictAndResultOnRealSchema()
    {
        String requestId = rid();
        ShipmentCostInput in = new ShipmentCostInput(null, "AE", "2026-11", "PACKAGING",
                new BigDecimal("50.5"), "CNY", BigDecimal.ONE, null, "重放", requestId);
        ShipmentCostResult first = service.create(in);
        int costs = count("log_shipment_cost");
        int audits = count("noon_m4_inventory_audit");

        ShipmentCostResult replay = service.create(in);
        assertTrue(replay.replayed());
        assertEquals(first.costId(), replay.costId());
        assertEquals(costs, count("log_shipment_cost"), "重放零写入");
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不写审计");

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(null, "AE", "2026-11", "PACKAGING",
                        new BigDecimal("51.5"), "CNY", BigDecimal.ONE, null, "重放", requestId)));
        assertTrue(changed.getMessage().contains("内容与原请求不一致"), changed.getMessage());

        ShipmentCostResult result = service.resultOf(requestId);
        assertNotNull(result);
        assertTrue(result.replayed());
        assertEquals(first.costNo(), result.costNo());
        assertEquals(costs, count("log_shipment_cost"), "resultOf 只读");
        assertNull(service.resultOf("r-" + UUID.randomUUID()));
    }

    // ------------------------------------------------------------ 授权 / ASN

    @Test
    void siteAuthorizationAndCrossSiteAsnOnRealSchema()
    {
        long asnSa = newAsn("ASN-IT-SA-1", "SA");
        String saKey = rid();
        ShipmentCostResult sa = service.create(new ShipmentCostInput(asnSa, "SA", "2026-11", "FREIGHT",
                new BigDecimal("10"), "SAR", new BigDecimal("1.9"), null, "SA", saKey));

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class, () -> service.create(new ShipmentCostInput(null, "SA",
                "2026-11", "FREIGHT", new BigDecimal("10"), "SAR", new BigDecimal("1.9"), null, null, rid())));
        IllegalArgumentException crossAsn = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(asnSa, "AE", "2026-11", "FREIGHT",
                        new BigDecimal("10"), "CNY", BigDecimal.ONE, null, null, rid())));
        assertTrue(crossAsn.getMessage().contains("不允许跨站关联"), crossAsn.getMessage());
        assertThrows(AccessDeniedException.class, () -> service.resultOf(saKey));
        assertThrows(AccessDeniedException.class, () -> service.get(sa.costId()));
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 10, null));

        // 撤销授权后：resultOf/get/指定站点 list 403，未指定 list 零行。
        grant(2L);
        login(1L);
        String aeKey = rid();
        ShipmentCostResult ae = service.create(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT",
                new BigDecimal("20"), "AED", new BigDecimal("3.6725"), null, "AE", aeKey));
        login(2L);
        assertThrows(AccessDeniedException.class, () -> service.resultOf(aeKey));
        assertThrows(AccessDeniedException.class, () -> service.get(ae.costId()));
        assertThrows(AccessDeniedException.class, () -> service.list("AE", 0, 10, null));
        assertEquals(0, service.list(null, 0, 10, null).total(), "空授权零行");
    }

    // ------------------------------------------------------------ 并发同键

    @Test
    void concurrentSameRequestIdCreatesSingleCost() throws Exception
    {
        ShipmentCostInput in = new ShipmentCostInput(null, "AE", "2026-11", "CUSTOMS",
                new BigDecimal("30"), "CNY", BigDecimal.ONE, null, "并发", rid());

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
                    ShipmentCostResult result = service.create(in);
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
                "同一 requestId 并发只能真实登记一笔: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, count("log_shipment_cost"), "真库只落一行");
        assertEquals(1, count("noon_m4_inventory_audit"), "只写一次 CREATE 审计");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_shipment_cost_request "
                + "where status='DONE'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_shipment_cost_request "
                + "where status='PENDING'", Integer.class), "不得残留 PENDING");
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

    private long newAsn(String asnNo, String site)
    {
        jdbc.update("insert into log_asn(asn_no,site_code,warehouse_code,status,source) "
                + "values (?,?,?,'DRAFT','MANUAL')", asnNo, site, "WH-IT-" + site);
        return jdbc.queryForObject("select id from log_asn where asn_no=?", Long.class, asnNo);
    }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void clean()
    {
        for (String table : new String[]{"noon_m4_shipment_cost_request", "noon_m4_inventory_audit",
                "log_shipment_cost_po", "log_shipment_cost", "log_asn", "pur_order",
                "inv_stock_move", "inv_stock_ledger", "inv_batch_consumption", "inv_batch",
                "inv_inbound_item", "inv_inbound", "noon_m4_inbound_request"})
            jdbc.execute("delete from " + table);
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
            String database = "noon_m4_ship_" + UUID.randomUUID().toString().replace("-", "");
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
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) { return new TransactionTemplate(tm); }

        @Bean
        ShipmentCostAudit shipmentCostAudit(JdbcTemplate jdbcTemplate) { return new ShipmentCostAudit(jdbcTemplate); }

        @Bean
        ShipmentCostService shipmentCostService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard,
                                                ShipmentCostAudit audit)
        {
            return new ShipmentCostService(jdbcTemplate, guard, audit);
        }
    }
}
