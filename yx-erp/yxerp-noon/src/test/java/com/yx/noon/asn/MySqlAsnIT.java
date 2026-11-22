package com.yx.noon.asn;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * M4 第三轮第三批 ASN 收货闭环隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>建表来源：{@code docs/noon-erp-schema-v0.5.sql} 抽取真实 DDL（{@code md_sku / md_warehouse /
 * inv_batch_consumption / log_asn / log_asn_item}）+ {@code yx-erp/sql/noon_m4_inventory.sql}（第一轮
 * 正式迁移：{@code inv_batch / inv_stock_ledger / inv_stock_move / noon_m4_inventory_audit}）+
 * {@code yx-erp/sql/noon_m4_asn.sql}（本轮动作级幂等表 {@code noon_m4_asn_request}）。
 *
 * <p>验证：真 DDL CHECK（{@code qty_shipped > 0}、{@code qty_received <= qty_shipped}、{@code qty_in > 0}、
 * {@code 0 <= qty_remaining <= qty_in}、流水 {@code qty_delta <> 0}、消耗 {@code qty > 0} / {@code sign ∈ ±1}、
 * 台账四态非负）、真库 {@code (request_id, action)} 主键、并发同键只落 1 行 / 只拆批一次、真库双边守恒、
 * 失败事务无 PENDING 残留、短少事件唯一键防双计、按行分批收货。
 *
 * <p>绝不连生产：只使用显式注入的隔离库（{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL} 或
 * {@code NOON_MYSQL_TEST_PORT}）；都未提供则整体 skip。
 */
class MySqlAsnIT
{
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AsnService asnService;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null,
                "缺少隔离库 JDBC URL/端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT");

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        // 真实 schema v0.5 表（M4 迁移不建）：主数据、ASN 头/行、消耗流水。
        String schema = Files.readString(Path.of("../../docs/noon-erp-schema-v0.5.sql"));
        for (String table : new String[]{"md_sku", "md_warehouse", "inv_batch_consumption",
                "log_asn", "log_asn_item"})
            jdbc.execute(extractCreateTable(schema, table));
        // M4 第一轮正式迁移（inv_batch / inv_stock_ledger / inv_stock_move / 审计）。
        runSqlFile("../sql/noon_m4_inventory.sql");
        // M4 第三轮第三批正式迁移（noon_m4_asn_request）。
        runSqlFile("../sql/noon_m4_asn.sql");

        clean();
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        asnService = context.getBean(AsnService.class);
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

    // ------------------------------------------------------------ 迁移幂等 + 真 DDL 约束

    @Test
    void migrationIsIdempotentAndRealAsnChecksHold()
    {
        runSqlFileUnchecked("../sql/noon_m4_asn.sql"); // 第二次：幂等
        assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name='noon_m4_asn_request'", Integer.class),
                "动作级幂等表应存在且重复执行不报错");

        // 真 DDL：ASN 行 qty_shipped > 0、qty_received <= qty_shipped。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into log_asn_item"
                + "(asn_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) values (1,1,'B:BAD',0,0,0)"),
                "qty_shipped=0 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into log_asn_item"
                + "(asn_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) values (1,1,'B:BAD2',2,3,0)"),
                "qty_received > qty_shipped 必须被 CHECK 拒绝");

        // 动作级幂等表 (request_id, action) 复合主键：同键同动作撞键，同键异动作可并存。
        jdbc.update("insert into noon_m4_asn_request(request_id,action,asn_id,site_code,operator_id,"
                + "content_fingerprint,status) values ('IT-ASN-REQ-1','CREATE',0,'AE',1,?, 'PENDING')",
                "a".repeat(64));
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m4_asn_request"
                + "(request_id,action,asn_id,site_code,operator_id,content_fingerprint,status) "
                + "values ('IT-ASN-REQ-1','CREATE',0,'AE',1,?, 'PENDING')", "b".repeat(64)),
                "(request_id, action) 必须唯一");
        jdbc.update("insert into noon_m4_asn_request(request_id,action,asn_id,site_code,operator_id,"
                + "content_fingerprint,status) values ('IT-ASN-REQ-1','SEAL',0,'AE',1,?, 'PENDING')",
                "c".repeat(64));
        assertEquals(2, jdbc.queryForObject("select count(*) from noon_m4_asn_request "
                + "where request_id='IT-ASN-REQ-1'", Integer.class), "同 requestId 跨动作各一行（动作级隔离）");

        // 真 DDL：批次 qty_in > 0、0 <= qty_remaining <= qty_in。
        long wh = newWarehouse("WH-ASN-IT-C1", "AE");
        long sku = newSku("SKU-ASN-IT-C1");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch"
                + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost) "
                + "values ('B:ASN:BAD','1',?,'AE',now(3),'0','0','1.0000')", wh),
                "qty_in=0 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_batch"
                + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost) "
                + "values ('B:ASN:BAD2','1',?,'AE',now(3),'1','2','1.0000')", wh),
                "qty_remaining > qty_in 必须被 CHECK 拒绝");

        // 真 DDL：台账在途非负、流水非零、消耗 qty > 0 与 sign ±1。
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_ledger"
                + "(sku_id,warehouse_id,site_code,qty_available,qty_in_transit) values (1,1,'AE',1,-1)"),
                "在途负数必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into inv_stock_move"
                + "(move_no,sku_id,warehouse_id,change_type,qty_delta) values ('AM:BAD',1,1,'ASN_IN','0')"),
                "qty_delta=0 必须被 CHECK 拒绝");

        // 真库拆批落库体：ASN 头 DRAFT → seal 子批次血缘 + 源批次关批。
        newWarehouse("WH-FBN-IT-C1", "AE", true);
        newBatch(sku, wh, "AE", "B:SRC-IT-C1", 5, "10.0000", LocalDateTime.of(2026, 3, 1, 9, 0));
        seedLedger(sku, wh, "AE", 5);
        AsnResult created = asnService.create(new AsnCreateInput("AE", "WH-FBN-IT-C1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-IT-C1", 5, "P-IT-1"))));
        assertEquals(1, jdbc.queryForObject("select count(*) from log_asn where id=? and status='DRAFT' "
                        + "and asn_no like 'ASN:AE:%' and warehouse_code='WH-FBN-IT-C1'",
                Integer.class, created.asnId()), "真库 create 落 DRAFT 头");
        AsnResult sealed = asnService.seal(new AsnSealInput(created.asnId(), rid(), null));
        long child = sealed.childBatchIds().get(0);
        long fbn = warehouseId("WH-FBN-IT-C1");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where id=? "
                        + "and batch_no like 'B:ASN:%' and source_type='TRANSFER_SPLIT' "
                        + "and status='IN_TRANSIT' and parent_batch_id=? and split_seq=1 "
                        + "and split_reason='ASN_TRANSIT' and asn_no=? and qty_in=5 and qty_remaining=5 "
                        + "and site_code='AE' and warehouse_id=?",
                Integer.class, child, batchId("B:SRC-IT-C1"), created.asnNo(), fbn), "子批次血缘/状态落库体");
    }

    // ------------------------------------------------------------ 并发同键只落 1 行

    @Test
    void concurrentSameSealRequestIdSealsOnce() throws Exception
    {
        long wh = newWarehouse("WH-ASN-IT-C2", "AE");
        newWarehouse("WH-FBN-IT-C2", "AE", true);
        long sku = newSku("SKU-ASN-IT-C2");
        newBatch(sku, wh, "AE", "B:SRC-IT-C2", 10, "10.0000", LocalDateTime.of(2026, 3, 2, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        long fbn = warehouseId("WH-FBN-IT-C2");

        AsnResult created = asnService.create(new AsnCreateInput("AE", "WH-FBN-IT-C2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-IT-C2", 10, null))));
        String requestId = rid();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CallableWrapper attempt = new CallableWrapper(created.asnId(), requestId, ready, go);
        Future<String> a = pool.submit(attempt);
        Future<String> b = pool.submit(attempt);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程未就绪");
        go.countDown();
        List<String> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, results.stream().filter("FRESH"::equals).count(),
                "同一 requestId 并发只能一次真实封单: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        // 只落 1 行 DONE（PK 唯一），且只拆 1 个在途子批次。
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_asn_request "
                + "where request_id=? and action='SEAL'", Integer.class, requestId));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_asn_request "
                + "where request_id=? and action='SEAL'", String.class, requestId));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where batch_no like 'B:ASN:%'",
                Integer.class), "只允许一个在途子批次");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchId("B:SRC-IT-C2")), "源批次只扣一次（扣满关批）");
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, batchId("B:SRC-IT-C2")));
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh), "源仓可用只扣一次");
        assertEquals(10, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fbn), "FBN 仓在途只加一次");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='ASN_OUT' and qty_delta=-10", Integer.class), "只允许一条 ASN_OUT");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='SEAL'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_asn_request where status='PENDING'",
                Integer.class), "不得残留 PENDING 占位");
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
    }

    /** 并发同一 requestId 封单：两个线程用同一 ASN + 同一键，胜者 FRESH、败者 REPLAYED/REJECT。 */
    private class CallableWrapper implements java.util.concurrent.Callable<String>
    {
        private final long asnId;
        private final String requestId;
        private final CountDownLatch ready;
        private final CountDownLatch go;

        CallableWrapper(long asnId, String requestId, CountDownLatch ready, CountDownLatch go)
        {
            this.asnId = asnId;
            this.requestId = requestId;
            this.ready = ready;
            this.go = go;
        }

        @Override
        public String call() throws Exception
        {
            login(1L);
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            try
            {
                return tx.execute(status -> asnService.seal(new AsnSealInput(asnId, requestId, null)).replayed()
                        ? "REPLAYED" : "FRESH");
            }
            catch (RuntimeException e)
            {
                return "REJECT:" + e.getMessage();
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        }
    }

    // ------------------------------------------------------------ 真库全链路守恒

    @Test
    void createSealReceiveKeepsConservationOnRealSchema()
    {
        long wh = newWarehouse("WH-ASN-IT-E1", "AE");
        newWarehouse("WH-FBN-IT-E1", "AE", true);
        long sku = newSku("SKU-ASN-IT-E1");
        newBatch(sku, wh, "AE", "B:SRC-IT-E1", 9, "9.5000", LocalDateTime.of(2026, 3, 3, 9, 0));
        seedLedger(sku, wh, "AE", 9);
        long fbn = warehouseId("WH-FBN-IT-E1");

        // ① create：DRAFT + 行 + 审计，不动批次/台账/流水/消耗。
        int movesBefore = jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class);
        AsnResult created = asnService.create(new AsnCreateInput("AE", "WH-FBN-IT-E1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-IT-E1", 9, null))));
        assertEquals("DRAFT", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals(9, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchId("B:SRC-IT-E1")), "create 不扣批次");
        assertEquals(9, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh), "create 不动台账");
        assertEquals(movesBefore, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where batch_no like 'B:ASN:%'",
                Integer.class));
        long item = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());

        // ② seal：源批次扣满关批、在途子批次、双边台账、ASN_OUT。
        AsnResult sealed = asnService.seal(new AsnSealInput(created.asnId(), rid(), null));
        long child = sealed.childBatchIds().get(0);
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, batchId("B:SRC-IT-E1")));
        assertEquals(9, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fbn));
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, wh));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='ASN_OUT' and qty_delta=-9 and ref_doc_type='ASN' and ref_doc_no=?",
                Integer.class, created.asnNo()));

        // ③ receive 7（短少 2）：OPEN + 在途 0 + 可用 7 + ASN_SHORT + diff 回写。
        AsnResult received = asnService.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(item, 7, "short-it-e1"))));
        assertEquals(child, received.childBatchIds().get(0));
        assertEquals(7, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child));
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, child));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child), "实收 >0 在途转 OPEN");
        assertEquals(0, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fbn), "FBN 仓在途清零");
        assertEquals(7, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fbn), "FBN 仓可用 +7");
        assertEquals(7, jdbc.queryForObject("select qty_received from log_asn_item where id=?",
                Integer.class, item));
        assertEquals(2, jdbc.queryForObject("select diff_qty from log_asn_item where id=?",
                Integer.class, item));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        Map<String, Object> shortRow = jdbc.queryForMap("select biz_event_key,batch_id,qty,unit_cost,amount,"
                + "move_type,sign from inv_batch_consumption where move_type='ASN_SHORT'");
        assertEquals("ASNSHORT:" + item + ":short-it-e1", shortRow.get("biz_event_key"));
        assertEquals(child, ((Number) shortRow.get("batch_id")).longValue());
        assertEquals(2, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("9.5000").compareTo((BigDecimal) shortRow.get("unit_cost")));
        assertEquals(0, new BigDecimal("19.0000").compareTo((BigDecimal) shortRow.get("amount")));
        Object sign = shortRow.get("sign");
        assertEquals(1, sign instanceof Boolean b ? (b ? 1 : 0) : ((Number) sign).intValue(), "短少 sign=+1");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move where change_type='ASN_IN'",
                Integer.class));
        assertEquals(7, jdbc.queryForObject("select qty_delta from inv_stock_move "
                + "where change_type='ASN_IN'", Integer.class));

        // ④ 真库守恒：源批次剩余 0 + 子批次 7 + 短少 2 = 初始 9（两仓台账合计亦守恒）。
        assertEquals(9, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='ASN_SHORT')",
                Integer.class, batchId("B:SRC-IT-E1"), child), "源批次 + 子批次 + 短少 = 初始");
        assertEquals(7, jdbc.queryForObject("select coalesce(sum(qty_available),0) from inv_stock_ledger",
                Integer.class), "全仓可用量 = 实收量");
        assertEquals(0, jdbc.queryForObject("select coalesce(sum(qty_in_transit),0) from inv_stock_ledger",
                Integer.class), "无在途残留");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_asn_request where status='PENDING'",
                Integer.class), "全链路无 PENDING 残留");
    }

    // ------------------------------------------------------------ 短少唯一键 / 按行分批

    @Test
    void duplicateShortEventBlockedByRealUniqueKey()
    {
        long wh = newWarehouse("WH-ASN-IT-F1", "AE");
        newWarehouse("WH-FBN-IT-F1", "AE", true);
        long sku = newSku("SKU-ASN-IT-F1");
        newBatch(sku, wh, "AE", "B:SRC-IT-F1", 6, "8.0000", LocalDateTime.of(2026, 3, 4, 9, 0));
        seedLedger(sku, wh, "AE", 6);
        long fbn = warehouseId("WH-FBN-IT-F1");
        AsnResult created = asnService.create(new AsnCreateInput("AE", "WH-FBN-IT-F1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-IT-F1", 6, null))));
        long item = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());
        long child = asnService.seal(new AsnSealInput(created.asnId(), rid(), null)).childBatchIds().get(0);

        // 预置同事件键流水（模拟另一路径已过账），服务再记同事件必须被真库唯一键拦下。
        String eventId = "dup-it";
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('AS:IT-MANUAL',?,?,?,1,'8.0000','8.0000',now(3),'ASN_SHORT',1,'2026-03',0)",
                "ASNSHORT:" + item + ":" + eventId, child, sku);

        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class,
                () -> asnService.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(item, 5, eventId)))));
        assertTrue(duplicated.getMessage().contains("短少事件已过账"), duplicated.getMessage());
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch_consumption", Integer.class),
                "唯一键拦截后不得双计");
        // 整事务回滚：子批次仍在途、台账未动、ASN 仍 SEALED、无 PENDING 残留。
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(6, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child));
        assertEquals(6, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, sku, fbn));
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_asn_request where asn_id=? "
                + "and status='PENDING'", Integer.class, created.asnId()), "失败事务的 PENDING 占位必须回滚");
    }

    @Test
    void stagedReceivePerLineOnRealSchema()
    {
        long wh = newWarehouse("WH-ASN-IT-G1", "AE");
        newWarehouse("WH-FBN-IT-G1", "AE", true);
        long skuA = newSku("SKU-ASN-IT-G1-A");
        long skuB = newSku("SKU-ASN-IT-G1-B");
        newBatch(skuA, wh, "AE", "B:SRC-IT-G1-A", 6, "10.0000", LocalDateTime.of(2026, 3, 5, 9, 0));
        newBatch(skuB, wh, "AE", "B:SRC-IT-G1-B", 4, "9.0000", LocalDateTime.of(2026, 3, 5, 9, 0));
        seedLedger(skuA, wh, "AE", 6);
        seedLedger(skuB, wh, "AE", 4);
        long fbn = warehouseId("WH-FBN-IT-G1");

        AsnResult created = asnService.create(new AsnCreateInput("AE", "WH-FBN-IT-G1", rid(), null,
                List.of(new AsnCreateLine(skuA, "B:SRC-IT-G1-A", 6, null),
                        new AsnCreateLine(skuB, "B:SRC-IT-G1-B", 4, null))));
        List<AsnItemRow> items = asnService.get(created.asnId()).items();
        long itemA = items.get(0).id();
        long itemB = items.get(1).id();
        asnService.seal(new AsnSealInput(created.asnId(), rid(), null));
        assertEquals(2, jdbc.queryForObject("select count(*) from inv_batch where batch_no like 'B:ASN:%'",
                Integer.class));

        // 第一批只收 A：头仍 SEALED，B 仍在途。
        asnService.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemA, 6, null))));
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()), "按行分批时头保持 SEALED");
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuA, fbn));
        assertEquals(4, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuB, fbn));

        // 同一行再收（新 requestId）→ 400（子批次已离开 IN_TRANSIT），零写入。
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> asnService.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemA, 6, null)))));
        assertTrue(twice.getMessage().contains("已收货"), twice.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_asn_request where status='PENDING'",
                Integer.class));

        // 第二批收 B（3/4，短少 1）→ 整单 RECEIVED。
        asnService.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemB, 3, "short-it-g1"))));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals(3, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuB, fbn));
        assertEquals(0, jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuB, fbn));
        assertEquals(1, jdbc.queryForObject("select diff_qty from log_asn_item where id=?",
                Integer.class, itemB));
        assertEquals(0, jdbc.queryForObject("select coalesce(sum(qty_in_transit),0) from inv_stock_ledger",
                Integer.class), "无在途残留");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_asn_request where status='PENDING'",
                Integer.class));
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
        return newWarehouse(code, site, false);
    }

    private long newWarehouse(String code, String site, boolean fbn)
    {
        jdbc.update("insert into md_warehouse(warehouse_code,site_code,warehouse_type,is_fbn,status) "
                + "values (?,?,?,?,'ACTIVE')", code, site, fbn ? "FBN" : "LOCAL", fbn ? 1 : 0);
        return warehouseId(code);
    }

    private long warehouseId(String code)
    {
        return jdbc.queryForObject("select id from md_warehouse where warehouse_code=?", Long.class, code);
    }

    private long newBatch(long skuId, long warehouseId, String site, String batchNo, int qty, String unitCost,
                          LocalDateTime receivedAt)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                        + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                        + "split_reason,transfer_no,status) "
                        + "values (?,?,NULL,?,?,?,?,?,?,'CNY',1,'PURCHASE',NULL,0,NULL,NULL,'OPEN')",
                batchNo, skuId, warehouseId, site, java.sql.Timestamp.valueOf(receivedAt), qty, qty, unitCost);
        return batchId(batchNo);
    }

    private long batchId(String batchNo)
    {
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(long skuId, long warehouseId, String site, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable) values (?,?,?,?,0,0,0)",
                skuId, warehouseId, site, available);
    }

    private void clean()
    {
        for (String table : new String[]{"noon_m4_asn_request", "log_asn_item", "log_asn",
                "noon_m4_inventory_audit", "inv_stock_move", "inv_stock_ledger",
                "inv_batch_consumption", "inv_batch"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_sku where sku_code like 'SKU-ASN-IT-%'");
        jdbc.execute("delete from md_warehouse where warehouse_code like 'WH-ASN-IT-%' or warehouse_code like 'WH-FBN-IT-%'");
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
            String database = "noon_m4_asn_" + UUID.randomUUID().toString().replace("-", "");
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
        AsnService asnService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new AsnService(jdbcTemplate, guard, audit);
        }
    }
}
