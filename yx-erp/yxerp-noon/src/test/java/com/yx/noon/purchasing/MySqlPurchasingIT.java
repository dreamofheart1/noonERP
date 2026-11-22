package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 供应商与采购隔离 MySQL 真库集成验证（opt-in）。
 *
 * <p>验证迁移脚本幂等、DB CHECK（数量守恒/收货量/审计 action）、JSON 列、唯一键、
 * 审计落库与业务失败同事务回滚、并发超收由 FOR UPDATE 串行化只允许一个成功。
 * 绝不连生产：只使用显式注入的隔离库 JDBC URL；未注入则整体 skip。
 *
 * <p>注入方式（系统属性优先，其次环境变量）：
 * <ul>
 *   <li>{@code noon.mysql.test.url} / {@code NOON_MYSQL_TEST_URL}（或 {@code NOON_MYSQL_TEST_JDBC_URL}）</li>
 *   <li>{@code noon.mysql.test.user} / {@code NOON_MYSQL_TEST_USER}（默认 root）</li>
 *   <li>{@code noon.mysql.test.password} / {@code NOON_MYSQL_TEST_PASSWORD}</li>
 * </ul>
 * 由 WorkBuddy 的 verify-m3-runtime.py 注入。库内需允许建表；应用不创建数据库本身。
 */
class MySqlPurchasingIT
{
    private static final String SKIP_REASON = "缺少隔离库 JDBC URL 或端口（noon.mysql.test.url / NOON_MYSQL_TEST_URL / NOON_MYSQL_TEST_PORT），跳过真库 IT";
    private static final ThreadLocal<String> CREATED_DATABASE = new ThreadLocal<>();

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SupplierService supplierService;
    private QuotationService quotationService;
    private OrderService orderService;
    private ReceiptService receiptService;

    @BeforeEach
    void setUp() throws Exception
    {
        Target target = target();
        Assumptions.assumeTrue(target != null, SKIP_REASON);

        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        runSqlFile("../sql/noon_m3_purchasing.sql");
        // M2 前置：真实 M2 表存在则不动；未建时补最小 md_sku（仅测试隔离库）。
        jdbc.execute("create table if not exists md_sku (id bigint auto_increment primary key, "
                + "sku_code varchar(64) not null, name_en varchar(512), status varchar(16) not null default 'ACTIVE', "
                + "created_at datetime(3) not null default current_timestamp(3), unique key uk_sku_code(sku_code))");
        clean();

        // 站点授权桩基线：1=超管、2=仅 AE（可在用例内 grant 切换验证撤销/跨站）
        GRANTS.clear();
        grant(1L, "AE", "SA");
        grant(2L, "AE");

        supplierService = context.getBean(SupplierService.class);
        quotationService = context.getBean(QuotationService.class);
        orderService = context.getBean(OrderService.class);
        receiptService = context.getBean(ReceiptService.class);
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

    @Test
    void migrationIsIdempotentAndRealConstraintsHold()
    {
        runSqlFileUnchecked("../sql/noon_m3_purchasing.sql"); // 第二次：幂等
        assertEquals(8, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema=database() and table_name in ('md_supplier','md_sku_supplier',"
                + "'pur_order','pur_order_item','noon_m3_receipt','noon_m3_audit_log',"
                + "'noon_m3_receipt_request','noon_m3_quotation_lock')", Integer.class),
                "4 张业务表 + 收货明细 + M3 审计表 + v2 幂等请求头/报价组合锁表应存在");

        long sku = newSku("SKU-IT-1");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-1", "IT Supplier", null, null));
        long order = confirmedOrder("PO-IT-1", supplier, sku, 5);

        // DB CHECK：绕过 Service 直写 qty_received > qty_ordered 必须被拒（error 3819 → DataAccessException）
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into pur_order_item"
                + "(pur_order_id,sku_id,qty_ordered,qty_received,unit_price,currency,fx_rate) "
                + "values (?,?,'2','3','1.0000','CNY','1.00000000')", order, sku));
        // DB CHECK：收货量必须 > 0
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m3_receipt"
                + "(order_id,order_item_id,qty,unit_cost_cny,received_at) values (?,?,'0','1.0000',now(3))",
                order, orderService.get(order).items().get(0).id()));
        // DB CHECK：审计 action 枚举
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m3_audit_log"
                + "(entity_type,entity_id,action,changed_at) values ('PUR_ORDER','1','DELETE',now(3))"));
        // DB JSON：非法 contact_json
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into md_supplier"
                + "(supplier_no,supplier_name,contact_json) values ('SUP-IT-BAD','Bad','{oops}')"));

        // 唯一键：supplier_no、订单号（应用层转 400 中文）
        IllegalArgumentException dupSupplier = assertThrows(IllegalArgumentException.class,
                () -> supplierService.save(new SupplierInput("SUP-IT-1", "Dup", null, null)));
        assertTrue(dupSupplier.getMessage().contains("supplier_no"), dupSupplier.getMessage());
        IllegalArgumentException dupOrder = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-IT-1", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku, 1, new BigDecimal("1.0000"), "CNY", BigDecimal.ONE)))));
        assertTrue(dupOrder.getMessage().contains("order_no"), dupOrder.getMessage());

        // 引用完整性 + 审计落库
        IllegalArgumentException orphan = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-IT-ORPHAN", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(9999L, 1, new BigDecimal("1"), "CNY", BigDecimal.ONE)))));
        assertTrue(orphan.getMessage().contains("SKU 不存在"), orphan.getMessage());
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_audit_log where entity_type='SUPPLIER' "
                + "and entity_id=? and action='INSERT' and before_json is null", Integer.class, supplier),
                "新增应留 before=NULL 的审计（JSON 列落库）");
        assertEquals(1L, jdbc.queryForObject("select operator_id from noon_m3_audit_log where entity_type='SUPPLIER' "
                + "and entity_id=? order by id limit 1", Long.class, supplier), "审计记录真实登录人");
        assertNotNull(jdbc.queryForObject("select after_json from noon_m3_audit_log where entity_type='SUPPLIER' "
                + "and entity_id=? order by id limit 1", String.class, supplier), "after_json 必须可写入 JSON 列");
    }

    @Test
    void auditPersistsAndBusinessFailureRollsBack()
    {
        long sku = newSku("SKU-IT-2");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-2", "Rollback Supplier", null, null));
        long quotation = quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("10.0000"),
                "CNY", 100, 7, "2026-01-01", null));
        long order = confirmedOrder("PO-IT-2", supplier, sku, 4);

        ReceiptResult part = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(orderService.get(order).items().get(0).id(), 3)), rid()));
        assertEquals("PARTIAL_RECEIVED", part.status());
        assertEquals(0, new BigDecimal("10.0000").compareTo(part.lines().get(0).unitCostCny()));

        Map<String, Object> receiptAudit = jdbc.queryForMap("select operator_id,operator_name,before_json,after_json "
                + "from noon_m3_audit_log where entity_type='RECEIPT' order by id desc limit 1");
        assertEquals(1L, ((Number) receiptAudit.get("operator_id")).longValue(), "收货审计记录真实登录人");
        String beforeJson = compact(receiptAudit.get("before_json"));
        String afterJson = compact(receiptAudit.get("after_json"));
        assertTrue(beforeJson.contains("\"qtyReceived\":0"), beforeJson);
        assertTrue(afterJson.contains("\"qtyReceived\":3"), afterJson);
        assertTrue(afterJson.contains("receipts"), "审计含本次收货行: " + afterJson);
        assertTrue(afterJson.contains("\"status\":\"PARTIAL_RECEIVED\""), afterJson);

        // 失败回滚：超收后 qty_received、收货明细、审计均不得变化
        int auditBefore = auditCount();
        int receiptRows = jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order);
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order,
                        List.of(new ReceiptLineInput(orderService.get(order).items().get(0).id(), 2)), rid())));
        assertTrue(over.getMessage().contains("可收余量"), over.getMessage());
        assertEquals(3, jdbc.queryForObject("select qty_received from pur_order_item where pur_order_id=?",
                Integer.class, order), "超收失败必须回滚 qty_received");
        assertEquals(receiptRows, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "超收失败不得写收货明细");
        assertEquals(auditBefore, auditCount(), "失败回滚不得留审计");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt_request where status='PENDING' "
                + "and order_id=?", Integer.class, order), "失败回滚不得留 PENDING 占位行");
        assertEquals("PARTIAL_RECEIVED", orderService.get(order).status());

        // 合法补齐后审计再次落库
        receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(orderService.get(order).items().get(0).id(), 1)), rid()));
        assertEquals("RECEIVED", orderService.get(order).status());
        assertEquals(auditBefore + 1, auditCount());
        assertEquals(0, new BigDecimal("10.0000").compareTo(quotationService.effectivePrice(sku, supplier,
                java.time.LocalDate.parse("2026-06-01")).unitPrice()));
        assertEquals(quotation, quotationService.effectivePrice(sku, supplier,
                java.time.LocalDate.parse("2026-06-01")).id());

        // 惰性删除/改价：新增版本不物理删除旧行
        quotationService.update(quotation, new QuotationInput(sku, supplier, new BigDecimal("10.0000"),
                "CNY", 100, 7, "2026-01-01", "2026-09-01"));
        quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("11.0000"), "CNY",
                100, 7, "2026-09-01", null));
        assertEquals(2, quotationService.list(null, 0, 10, new QuotationQuery(sku, supplier, null, null)).total());
    }

    @Test
    void concurrentReceiptCannotOverReceive() throws Exception
    {
        long sku = newSku("SKU-IT-3");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-3", "Concurrent Supplier", null, null));
        long order = confirmedOrder("PO-IT-3", supplier, sku, 10);
        long item = orderService.get(order).items().get(0).id();

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
                String requestId = rid(); // 两个线程是两次真实收货，必须各自持有不同幂等键
                tx.execute(status -> {
                    receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item, 6)),
                            requestId));
                    return null;
                });
                return "OK";
            }
            catch (IllegalArgumentException e)
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

        long success = results.stream().filter("OK"::equals).count();
        assertEquals(1, success, "两个 6 件收货并发提交，只能一个成功: " + results);
        String rejected = results.stream().filter(r -> r.startsWith("REJECT:")).findFirst().orElse("");
        assertTrue(rejected.contains("可收余量"), rejected);
        assertEquals(6, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "累计收货不得超过订货量（DB CHECK 双保险）");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "只允许一条收货明细落库");
        assertEquals("PARTIAL_RECEIVED", orderService.get(order).status());

        login(1L);
        ReceiptResult done = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 4)), rid()));
        assertEquals("RECEIVED", done.status(), "补齐余量后全收齐");
        assertEquals(10, done.receivedTotalQty());
    }

    /**
     * v2 §9.1 真库并发幂等：两线程同一 requestId 同时收货 → 恰好 1 次入账；
     * 另一个要么 replayed=true 原样重放、要么明确 400「正在处理中」；qty_received 与明细行数守恒。
     */
    @Test
    void concurrentSameRequestIdReceiptBooksOnce() throws Exception
    {
        long sku = newSku("SKU-IT-4");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-4", "Idem Supplier", null, null));
        long order = confirmedOrder("PO-IT-4", supplier, sku, 10);
        long item = orderService.get(order).items().get(0).id();
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
                return tx.execute(status -> {
                    ReceiptResult result = receiptService.receive(new ReceiptInput(order,
                            List.of(new ReceiptLineInput(item, 3)), requestId));
                    return result.replayed() ? "REPLAYED" : "FRESH";
                });
            }
            catch (IllegalArgumentException e)
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
                "同一 requestId 并发只能有一次真实入账: " + results);
        assertEquals(1, results.stream().filter("REPLAYED"::equals).count()
                + results.stream().filter(r -> r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(3, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "并发同请求不得重复累加 qty");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "并发同请求只允许一条收货明细");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt_request "
                + "where request_id=? and status<>'DONE'", Integer.class, requestId), "请求行必须收敛为 DONE");
        assertEquals("PARTIAL_RECEIVED", orderService.get(order).status());
    }

    /**
     * v2 §9.2 真库并发报价：两线程同一 (sku,supplier)、不同 effective_from 且区间重叠 → 恰好 1 成功 1 失败；
     * 组合锁串行化后后者读到前者提交的行由 requireNoOverlap 拒绝，不会产生交叉区间。
     */
    @Test
    void concurrentOverlappingQuotationOnlyOneSucceeds() throws Exception
    {
        long sku = newSku("SKU-IT-5");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-5", "Quote Race Supplier", null, null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<QuotationInput> inputs = List.of(
                new QuotationInput(sku, supplier, new BigDecimal("10.0000"), "CNY", 0, 0, "2026-01-01", "2026-06-01"),
                new QuotationInput(sku, supplier, new BigDecimal("11.0000"), "CNY", 0, 0, "2026-03-01", null));
        List<Callable<String>> attempts = inputs.stream().<Callable<String>>map(in -> () ->
        {
            login(1L);
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            try
            {
                tx.execute(status -> {
                    quotationService.save(in);
                    return null;
                });
                return "OK";
            }
            catch (IllegalArgumentException e)
            {
                return "REJECT:" + e.getMessage();
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        }).toList();

        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : attempts) futures.add(pool.submit(task));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, results.stream().filter("OK"::equals).count(), "并发重叠报价只能一个成功: " + results);
        String rejected = results.stream().filter(r -> r.startsWith("REJECT:")).findFirst().orElse("");
        assertTrue(rejected.contains("重叠"), "失败方必须是区间重叠 400: " + rejected);
        assertEquals(1, quotationService.list(null, 0, 10, new QuotationQuery(sku, supplier, null, null)).total(),
                "同组合最终只落一行报价（无交叉区间）");
    }

    /**
     * v2 §9.1 真库重试：首个收货事务业务失败回滚后，PENDING 行一并回滚，同 requestId 可重试成功。
     */
    @Test
    void failedReceiptRollsBackRequestAndSameRequestIdCanRetry()
    {
        long sku = newSku("SKU-IT-6");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-6", "Retry Supplier", null, null));
        long order = confirmedOrder("PO-IT-6", supplier, sku, 4);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();

        // 首次：超出可收余量（收 5 > 订 4）→ 业务失败，整事务回滚（含 PENDING 占位行）
        IllegalArgumentException failed = assertThrows(IllegalArgumentException.class,
                () -> tx.execute(status -> {
                    receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item, 5)),
                            requestId));
                    return null;
                }));
        assertTrue(failed.getMessage().contains("可收余量"), failed.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt_request where request_id=?",
                Integer.class, requestId), "业务失败必须回滚 PENDING 幂等行");
        assertEquals(0, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "业务失败不得改动 qty_received");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "业务失败不得写收货明细");

        // 同 requestId 重试（模拟响应丢失后客户端用原幂等键重发）→ 成功入账，replayed=false
        ReceiptResult retried = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 4)), requestId));
        assertEquals(false, retried.replayed(), "首次成功不是重放");
        assertEquals("RECEIVED", retried.status());
        assertEquals(4, retried.receivedTotalQty());
        assertEquals(4, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m3_receipt_request where request_id=?",
                String.class, requestId));

        // 再次用同一 requestId 重发 → 幂等重放，不重复入账
        ReceiptResult replay = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 4)), requestId));
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "重放不得新增收货明细");
        assertEquals(4, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "重放不得累加 qty_received");
    }

    /**
     * v2.1 §9.6 ① 真库：重放前必须按订单真实站点授权。
     * 授权撤销后（桩切换）opAE 重放原 AE 单 403、resultOf 403；opAE 持 SA 单键 403；越权不改数据。
     */
    @Test
    void receiptReplayAfterGrantRevokedIsForbidden()
    {
        long sku = newSku("SKU-IT-7");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-7", "Revoke Supplier", null, null));
        long order = confirmedOrder("PO-IT-7", supplier, sku, 10);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();

        login(1L);
        ReceiptResult first = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 3)), requestId));
        assertEquals(3, first.receivedTotalQty());

        login(2L); // 仅 AE：有该单站点权限，重放正常
        ReceiptResult replay = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 3)), requestId));
        assertTrue(replay.replayed());

        grant(2L); // 撤销 AE 授权
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 3)), requestId)), "授权撤销后重放必须 403");
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(requestId), "授权撤销后查结果必须 403");

        // 跨站：opAE 持 SA 单的合法键 → 403（重放与查询）
        login(1L);
        grant(2L, "AE");
        long saOrder = confirmedOrder("PO-IT-7S", supplier, sku, 5, "SA");
        long saItem = orderService.get(saOrder).items().get(0).id();
        String saRequest = rid();
        receiptService.receive(new ReceiptInput(saOrder, List.of(new ReceiptLineInput(saItem, 2)), saRequest));

        login(2L);
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(saOrder,
                List.of(new ReceiptLineInput(saItem, 2)), saRequest)), "跨站重放必须 403");
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(saRequest), "跨站查结果必须 403");

        assertEquals(3, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "越权重放不得改动 AE 单 qty");
        assertEquals(2, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, saItem), "越权重放不得改动 SA 单 qty");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "越权重放不得新增收货明细");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m3_receipt_request where request_id=?",
                String.class, requestId), "越权访问不得改变幂等行状态");
    }

    /**
     * v2.1 §9.6 ② 真库：同键不同内容 400 拒绝；content_fingerprint NOT NULL 且为 64 位小写十六进制。
     */
    @Test
    void receiptContentFingerprintConflictRejectedOnRealDb()
    {
        long sku = newSku("SKU-IT-8");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-8", "Fingerprint Supplier", null, null));
        long order = confirmedOrder("PO-IT-8", supplier, sku, 60);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();

        login(1L);
        ReceiptResult full = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 60)), requestId));
        assertEquals(60, full.receivedTotalQty());
        assertEquals("RECEIVED", full.status());

        String stored = jdbc.queryForObject("select content_fingerprint from noon_m3_receipt_request "
                + "where request_id=?", String.class, requestId);
        assertNotNull(stored, "首次占位必须写入内容指纹（NOT NULL）");
        assertEquals(ReceiptService.contentFingerprint(order, List.of(new ReceiptLineInput(item, 60))), stored,
                "落库指纹必须等于规范化算法结果");
        assertEquals(64, stored.length());
        assertTrue(stored.matches("[0-9a-f]{64}"), "SHA-256 必须 64 位小写十六进制: " + stored);

        // DB 约束：指纹列 NOT NULL（绕过 Service 直插缺列必须被拒）
        assertThrows(DataAccessException.class, () -> jdbc.update("insert into noon_m3_receipt_request"
                + "(request_id,order_id,status) values ('it-missing-fp','" + order + "','PENDING')"),
                "content_fingerprint NOT NULL 必须由 DB 兜底");

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order,
                        List.of(new ReceiptLineInput(item, 50)), requestId)));
        assertEquals("requestId 对应的收货内容与原请求不一致", changed.getMessage());
        assertEquals(60, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "内容冲突不得改动 qty_received");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "内容冲突不得新增收货明细");
        assertEquals("RECEIVED", orderService.get(order).status());
    }

    /**
     * v2.1 §9.6 ③ 真库：resultOf 命中 DONE 返回 replayed=true 且只读；不存在的键返回 null。
     */
    @Test
    void resultOfReturnsDoneAndNullOnRealDb()
    {
        long sku = newSku("SKU-IT-9");
        long supplier = supplierService.save(new SupplierInput("SUP-IT-9", "Result Supplier", null, null));
        long order = confirmedOrder("PO-IT-9", supplier, sku, 5);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();

        login(1L);
        ReceiptResult first = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 2)), requestId));
        int audits = auditCount();

        ReceiptResult queried = receiptService.resultOf(requestId);
        assertNotNull(queried);
        assertTrue(queried.replayed(), "查询结果标记 replayed=true");
        assertEquals("PARTIAL_RECEIVED", queried.status());
        assertEquals(2, queried.receivedTotalQty());
        assertEquals(first.lines().get(0).id(), queried.lines().get(0).id());
        assertEquals(2, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "resultOf 只读：qty 不变");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "resultOf 只读：明细不变");
        assertEquals(audits, auditCount(), "resultOf 不得写审计");

        assertNull(receiptService.resultOf("r-" + UUID.randomUUID()), "不存在的键返回 null");
        assertNull(receiptService.resultOf("short"), "非法格式返回 null");
    }

    // ------------------------------------------------------------------ helpers

    /** 站点授权桩数据：按 userId 存授权站点，{@link #grant} 切换。 */
    private static final Map<Long, List<String>> GRANTS = new ConcurrentHashMap<>();

    private static void grant(long userId, String... sites)
    {
        GRANTS.put(userId, List.of(sites));
    }

    /** 一次性幂等键（模拟前端 crypto.randomUUID()），符合 8–64 位 [A-Za-z0-9_-]。 */
    private static String rid()
    {
        return "r-" + UUID.randomUUID();
    }

    private long confirmedOrder(String orderNo, long supplier, long sku, int qty)
    {
        return confirmedOrder(orderNo, supplier, sku, qty, "AE");
    }

    private long confirmedOrder(String orderNo, long supplier, long sku, int qty, String site)
    {
        long order = orderService.save(new OrderInput(orderNo, supplier, site, "CNY", null, null,
                List.of(new OrderItemInput(sku, qty, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE))));
        orderService.confirm(order);
        return order;
    }

    private long newSku(String code)
    {
        jdbc.update("insert into md_sku(sku_code,name_en,status) values (?,?,'ACTIVE')", code, code);
        return jdbc.queryForObject("select id from md_sku where sku_code=?", Long.class, code);
    }

    /** MySQL JSON 列会规范化空白，断言前压缩空白。 */
    private static String compact(Object json)
    {
        return json == null ? "" : String.valueOf(json).replaceAll("\\s+", "");
    }

    private int auditCount()
    {
        return jdbc.queryForObject("select count(*) from noon_m3_audit_log", Integer.class);
    }

    private void clean()
    {
        // 只清本 IT 造的数据，不动隔离库内可能存在的其他种子
        for (String table : new String[]{"noon_m3_receipt_request", "noon_m3_quotation_lock",
                "noon_m3_receipt", "pur_order_item", "pur_order", "md_sku_supplier", "noon_m3_audit_log"})
            jdbc.execute("delete from " + table);
        jdbc.execute("delete from md_supplier where supplier_no like 'SUP-IT-%'");
        jdbc.execute("delete from md_sku where sku_code like 'SKU-IT-%'");
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

    /** 系统属性优先，其次环境变量；都缺失返回 null。 */
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
            String database = "noon_m3_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            CREATED_DATABASE.set(database);
            ds.setUrl(target.serverUrl().replace("/?", "/" + database + "?"));
        }
        return ds;
    }

    /**
     * 目标库解析：优先完整 JDBC URL（WorkBuddy 可注入独立库）；否则用 NOON_MYSQL_TEST_PORT
     * 在同一隔离实例上建一次性库（与 verify-m3-runtime.py 现约定一致），结束时 drop。
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

    /**
     * 站点授权使用内存桩：真库 IT 只验证 DDL/CHECK/审计/并发，不依赖若依 sys_user 结构；
     * 真实登录与站点授权由 HTTP E2E 验证。{@link #grant(long, String...)} 可在用例内切换授权，
     * 用于「授权撤销后重放 403」「跨站重放 403」。
     */
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
        PurchasingAudit purchasingAudit(JdbcTemplate jdbcTemplate) { return new PurchasingAudit(jdbcTemplate); }

        @Bean
        SupplierService supplierService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, PurchasingAudit audit)
        { return new SupplierService(jdbcTemplate, guard, audit); }

        @Bean
        QuotationService quotationService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, PurchasingAudit audit)
        { return new QuotationService(jdbcTemplate, guard, audit); }

        @Bean
        OrderService orderService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, PurchasingAudit audit)
        { return new OrderService(jdbcTemplate, guard, audit); }

        @Bean
        ReceiptService receiptService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, PurchasingAudit audit)
        { return new ReceiptService(jdbcTemplate, guard, audit); }
    }
}
