package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.sql.DataSource;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.masterdata.PageResult;
import com.yx.system.mapper.SysUserSiteMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 供应商与采购 H2 单测：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper。
 * 覆盖四类实体：命中/未命中、分页 total 为筛选后真实数、翻页无重复无遗漏、空授权零行、
 * 引用完整性负例（孤儿报价/孤儿采购单/停用 sku 或 supplier）、唯一键转中文、状态机非法跳转、
 * 数量守恒超收、成本口径（CNY fx=1 / 外币换算）、报价历史价与 effectivePrice、站点隔离、审计同事务回滚。
 * H2 宽松建表（不含 CHECK/JSON）；CHECK、并发 FOR UPDATE 由 {@link MySqlPurchasingIT} 在隔离 MySQL 验证。
 */
class PurchasingServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SupplierService supplierService;
    private QuotationService quotationService;
    private OrderService orderService;
    private ReceiptService receiptService;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0'), (3,'0','0'), (4,'0','0')");
        String siteMigration = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String statement : h2Ddl(siteMigration).split(";"))
            if (!statement.isBlank()) jdbc.execute(statement.replaceAll("--.*", "").trim());
        // 授权：1=超管；2 仅 AE；3 无站点；4 AE+SA。
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) "
                + "values (2,'AE',1,current_timestamp(3)), (4,'AE',1,current_timestamp(3)), (4,'SA',1,current_timestamp(3))");

        createPurchasingTables();
        supplierService = context.getBean(SupplierService.class);
        quotationService = context.getBean(QuotationService.class);
        orderService = context.getBean(OrderService.class);
        receiptService = context.getBean(ReceiptService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ------------------------------------------------------------ 供应商（4.1）

    @Test
    void supplierFiltersPagingUniquenessAndPermissions()
    {
        login(1L);
        long alpha1 = supplierService.save(new SupplierInput("SUP-001", "Alpha Trading",
                "{\"tel\":\"+9715\"}", null));
        supplierService.save(new SupplierInput("SUP-002", "Alpha FZE", null, "ACTIVE"));
        long beta = supplierService.save(new SupplierInput("SUP-003", "Beta Goods", null, null));

        PageResult<SupplierRow> all = supplierService.list(null, 0, 10, null);
        assertEquals(3, all.total(), "无筛选 total 为全部行数");
        assertEquals(3, all.rows().size());
        assertEquals("{\"tel\":\"+9715\"}", supplierService.get(alpha1).contactJson());

        assertEquals(2, supplierService.list(null, 0, 10,
                new SupplierQuery(null, " alpha ", null)).total(), "supplierName 模糊、大小写不敏感、去空白");
        assertEquals(1, supplierService.list(null, 0, 10,
                new SupplierQuery("SUP-002", "fze", null)).total(), "多条件 AND");
        assertEquals(0, supplierService.list(null, 0, 10,
                new SupplierQuery("ZZZ", null, null)).total(), "未命中 total=0 而非全表数");

        PageResult<SupplierRow> truncated = supplierService.list(null, 0, 2,
                new SupplierQuery("SUP-", null, null));
        assertEquals(2, truncated.rows().size());
        assertEquals(3, truncated.total(), "total 不受 LIMIT 截断");

        List<Long> expected = ids(all.rows(), SupplierRow::id);
        assertPageWalk(expected, 2, (offset, size) -> ids(supplierService.list(null, offset, size,
                new SupplierQuery("SUP-", null, null)).rows(), SupplierRow::id));

        supplierService.setStatus(beta, "INACTIVE");
        assertEquals(1, supplierService.list(null, 0, 10,
                new SupplierQuery(null, null, "inactive")).total(), "status 精确且大小写不敏感");
        assertThrows(IllegalArgumentException.class, () -> supplierService.list(null, 0, 10,
                new SupplierQuery(null, null, "DELETED")), "非法 status 400");

        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> supplierService.save(new SupplierInput("SUP-001", "Another", null, null)));
        assertTrue(dup.getMessage().contains("供应商编号"), dup.getMessage());
        IllegalArgumentException badJson = assertThrows(IllegalArgumentException.class,
                () -> supplierService.save(new SupplierInput("SUP-004", "Bad", "{oops}", null)));
        assertTrue(badJson.getMessage().contains("合法 JSON"), badJson.getMessage());
        IllegalArgumentException noName = assertThrows(IllegalArgumentException.class,
                () -> supplierService.save(new SupplierInput("SUP-005", "  ", null, null)));
        assertTrue(noName.getMessage().contains("不能为空"), noName.getMessage());

        login(2L); // 仅 AE：供应商是全局档案，有授权站点即可读全部
        assertEquals(3, supplierService.list(null, 0, 10, null).total());
        assertNotNull(supplierService.get(alpha1));
        assertThrows(AccessDeniedException.class, () -> supplierService.save(
                new SupplierInput("SUP-006", "By Op", null, null)), "供应商写仅超管");
        assertThrows(AccessDeniedException.class, () -> supplierService.setStatus(alpha1, "INACTIVE"));

        login(3L); // 空授权：零行（列表），详情拒绝
        assertEquals(0, supplierService.list(null, 0, 10, null).total(), "空授权零行");
        assertEquals(0, supplierService.list(null, 0, 10,
                new SupplierQuery("SUP-001", null, null)).total(), "筛选条件不得绕开空授权");
        assertThrows(AccessDeniedException.class, () -> supplierService.get(alpha1));
        assertThrows(AccessDeniedException.class, () -> supplierService.list("AE", 0, 10, null),
                "传 siteCode 先 requireSite");

        login(4L); // AE+SA 仍可读全部
        assertEquals(3, supplierService.list("SA", 0, 10, null).total());
    }

    // -------------------------------------------------------------- 报价（4.2）

    @Test
    void quotationHistoryEffectivePriceAndReferenceIntegrity()
    {
        login(1L);
        long sku = newSku("SKU-Q1", "ACTIVE");
        long skuInactive = newSku("SKU-Q2", "INACTIVE");
        long supplier = newSupplier("SUP-Q1", "Quote Supplier");
        long supplierInactive = newSupplier("SUP-Q2", "Stopped Supplier");
        supplierService.setStatus(supplierInactive, "INACTIVE");

        long v1 = quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("10.0000"), "CNY",
                100, 7, "2026-01-01", "2026-07-01"));
        assertEquals("2026-01-01", quotationService.get(v1).effectiveFrom());
        assertEquals("2026-07-01", quotationService.get(v1).effectiveTo());

        QuotationRow atMarch = quotationService.effectivePrice(sku, supplier,
                java.time.LocalDate.parse("2026-03-01"));
        assertEquals(0, new BigDecimal("10").compareTo(atMarch.unitPrice()), "有效区间内取 v1");
        assertNull(quotationService.effectivePrice(sku, supplier, java.time.LocalDate.parse("2025-12-31")),
                "生效日之前无报价");
        assertNull(quotationService.effectivePrice(sku, supplier, java.time.LocalDate.parse("2026-07-01")),
                "effective_to 不含端点（右开区间）");

        // 改价用新 effective_from 插入新行（旧行保留，不物理删除）
        long v2 = quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("12.0000"), "CNY",
                100, 7, "2026-07-01", null));
        assertEquals(0, new BigDecimal("12").compareTo(
                quotationService.effectivePrice(sku, supplier, java.time.LocalDate.parse("2026-08-01")).unitPrice()));
        assertEquals(0, new BigDecimal("10").compareTo(
                quotationService.effectivePrice(sku, supplier, java.time.LocalDate.parse("2026-06-30")).unitPrice()));
        assertEquals(2, quotationService.list(null, 0, 10, new QuotationQuery(sku, supplier, null, null)).total(),
                "历史版本保留两行");

        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-03-01", "2026-05-01")));
        assertTrue(overlap.getMessage().contains("重叠"), overlap.getMessage());

        IllegalArgumentException dupVersion = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("11"), "CNY",
                        0, 0, "2026-01-01", null)));
        assertTrue(dupVersion.getMessage().contains("重叠"), "同一 effective_from 必与已有版本相交: "
                + dupVersion.getMessage());

        IllegalArgumentException badDate = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026/01/02", null)));
        assertTrue(badDate.getMessage().contains("yyyy-MM-dd"), badDate.getMessage());
        IllegalArgumentException badRange = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-09-01", "2026-09-01")));
        assertTrue(badRange.getMessage().contains("晚于"), badRange.getMessage());

        // 引用完整性：孤儿/停用
        IllegalArgumentException orphanSku = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(9999L, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-10-01", null)));
        assertTrue(orphanSku.getMessage().contains("SKU 不存在"), orphanSku.getMessage());
        IllegalArgumentException orphanSupplier = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, 9999L, new BigDecimal("9"), "CNY",
                        0, 0, "2026-10-01", null)));
        assertTrue(orphanSupplier.getMessage().contains("供应商不存在"), orphanSupplier.getMessage());
        IllegalArgumentException inactiveSku = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(skuInactive, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-10-01", null)));
        assertTrue(inactiveSku.getMessage().contains("已停用"), inactiveSku.getMessage());
        IllegalArgumentException inactiveSupplier = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplierInactive, new BigDecimal("9"), "CNY",
                        0, 0, "2026-10-01", null)));
        assertTrue(inactiveSupplier.getMessage().contains("已停用"), inactiveSupplier.getMessage());

        IllegalArgumentException zeroPrice = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, BigDecimal.ZERO, "CNY",
                        0, 0, "2026-11-01", null)));
        assertTrue(zeroPrice.getMessage().contains("大于 0"), zeroPrice.getMessage());
        IllegalArgumentException badCurrency = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "AEDX",
                        0, 0, "2026-11-01", null)));
        assertTrue(badCurrency.getMessage().contains("3 位"), badCurrency.getMessage());
        IllegalArgumentException negativeMoq = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY",
                        -1, 0, "2026-11-01", null)));
        assertTrue(negativeMoq.getMessage().contains("moq"), negativeMoq.getMessage());

        // 旧单快照：报价改价后既有采购单 unit_price 不变
        long order = orderService.save(new OrderInput("PO-SNAP", supplier, "AE", "CNY", "MANUAL", null,
                List.of(new OrderItemInput(sku, 10, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE))));
        assertEquals(0, new BigDecimal("100.0000").compareTo(orderService.get(order).totalAmount()));
        quotationService.update(v2, new QuotationInput(sku, supplier, new BigDecimal("12.0000"), "CNY",
                100, 7, "2026-07-01", "2026-11-01"));
        quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("15.0000"), "CNY",
                0, 0, "2026-11-01", null));
        assertEquals(0, new BigDecimal("15").compareTo(quotationService.effectivePrice(sku, supplier,
                java.time.LocalDate.parse("2026-12-01")).unitPrice()), "新版本生效");
        assertEquals(0, new BigDecimal("10.0000").compareTo(
                orderService.get(order).items().get(0).unitPrice()), "旧单单价快照不随报价变动");
        assertEquals(0, new BigDecimal("100.0000").compareTo(orderService.get(order).totalAmount()));

        // 读授权：普通用户可读全部；空授权零行；写仅超管
        login(2L);
        assertEquals(3, quotationService.list(null, 0, 10, new QuotationQuery(null, null, null, null)).total());
        assertThrows(AccessDeniedException.class, () -> quotationService.save(
                new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY", 0, 0, "2027-01-01", null)));
        login(3L);
        assertEquals(0, quotationService.list(null, 0, 10, null).total(), "空授权零行");
        assertThrows(AccessDeniedException.class, () -> quotationService.get(v1));
        assertThrows(AccessDeniedException.class, () -> quotationService.effectivePrice(sku, supplier, null));
    }

    /**
     * v2 §9.2 组合串行化锁（H2）：save 先建/锁组合行；update 改组合时 old 与 new 两个组合都持锁，
     * 旧组合释放后可新增不重叠价，新组合仍受 requireNoOverlap 保护；校验失败的锁行随事务回滚不残留。
     */
    @Test
    void quotationCombinationLockCoversSaveAndUpdate()
    {
        login(1L);
        long skuA = newSku("SKU-LK1", "ACTIVE");
        long skuB = newSku("SKU-LK2", "ACTIVE");
        long supplier = newSupplier("SUP-LK1", "Lock Supplier");
        long supplier2 = newSupplier("SUP-LK2", "Rollback Supplier");

        long row = quotationService.save(new QuotationInput(skuA, supplier, new BigDecimal("10.0000"), "CNY",
                100, 7, "2026-01-01", "2026-07-01"));
        assertEquals(1, lockCount(skuA, supplier), "save 必须为该组合建锁行");

        // 改组合 skuA→skuB：old 与 new 两个组合都要加锁（锁行均存在）
        quotationService.update(row, new QuotationInput(skuB, supplier, new BigDecimal("11.0000"), "CNY",
                100, 7, "2026-01-01", "2026-07-01"));
        assertEquals(skuB, quotationService.get(row).skuId());
        assertEquals(1, lockCount(skuA, supplier), "旧组合锁行保留（锁已随提交释放）");
        assertEquals(1, lockCount(skuB, supplier), "新组合锁行必须存在（改组合需锁 new 组合）");

        // 旧组合已无占用行：可新增不重叠价（若仍残留行会命中「区间重叠」）
        long oldComboNew = quotationService.save(new QuotationInput(skuA, supplier, new BigDecimal("12.0000"),
                "CNY", 0, 0, "2026-07-01", null));
        assertEquals(0, new BigDecimal("12").compareTo(quotationService.effectivePrice(skuA, supplier,
                java.time.LocalDate.parse("2026-08-01")).unitPrice()), "旧组合可独立新增新版本");
        assertEquals(oldComboNew, quotationService.list(null, 0, 10,
                new QuotationQuery(skuA, supplier, null, null)).rows().get(0).id(), "旧组合新增行 id 回读");
        assertEquals(1, quotationService.list(null, 0, 10, new QuotationQuery(skuA, supplier, null, null)).total(),
                "旧组合只有新版本一行");

        // 新组合仍受 requireNoOverlap 保护（锁不替代第二道防线）
        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(skuB, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-03-01", "2026-05-01")));
        assertTrue(overlap.getMessage().contains("重叠"), overlap.getMessage());

        // 锁行已存在的组合再 save：DuplicateKeyException 被忽略，仍可正常写入
        long nextVersion = quotationService.save(new QuotationInput(skuB, supplier, new BigDecimal("13.0000"),
                "CNY", 0, 0, "2026-07-01", null));
        assertTrue(nextVersion > 0);
        assertEquals(2, quotationService.list(null, 0, 10, new QuotationQuery(skuB, supplier, null, null)).total());

        // 校验失败（非法日期）在建锁行之后：锁行必须随事务回滚，不残留
        assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(skuA, supplier2, new BigDecimal("9"), "CNY",
                        0, 0, "2026/01/02", null)));
        assertEquals(0, lockCount(skuA, supplier2), "失败事务不得残留组合锁行");
    }

    // ------------------------------------------------------------ 采购单（4.3）

    @Test
    void orderCostCurrencyAndStateMachine()
    {
        login(1L);
        long sku1 = newSku("SKU-O1", "ACTIVE");
        long sku2 = newSku("SKU-O2", "ACTIVE");
        long supplier = newSupplier("SUP-O1", "Order Supplier");

        long cny = orderService.save(new OrderInput("PO-CNY-1", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku1, 100, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE),
                        new OrderItemInput(sku2, 50, new BigDecimal("20.0000"), "CNY", BigDecimal.ONE))));
        assertEquals(0, new BigDecimal("2000.0000").compareTo(orderService.get(cny).totalAmount()),
                "CNY 成本 = Σ(qty × unit_price × 1)");
        assertEquals(2, orderService.get(cny).items().size());
        assertEquals("DRAFT", orderService.get(cny).status());

        IllegalArgumentException missingFx = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-CNY-2", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", null)))));
        assertTrue(missingFx.getMessage().contains("fxRate=1"), missingFx.getMessage());
        IllegalArgumentException wrongCnyFx = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-CNY-3", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", new BigDecimal("1.5"))))));
        assertTrue(wrongCnyFx.getMessage().contains("只能为 1"), wrongCnyFx.getMessage());

        long usd = orderService.save(new OrderInput("PO-USD-1", supplier, "AE", "USD", "REPLENISH_SUGGESTION",
                "SUG-1", List.of(new OrderItemInput(sku1, 5, new BigDecimal("10.0000"), "USD",
                        new BigDecimal("7.2000")))));
        assertEquals(0, new BigDecimal("360.0000").compareTo(orderService.get(usd).totalAmount()),
                "外币成本 = qty × unit_price × fx_rate");
        assertEquals(0, new BigDecimal("7.2").compareTo(orderService.get(usd).items().get(0).fxRate()));

        IllegalArgumentException mixedCurrency = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-USD-2", supplier, "AE", "USD", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(mixedCurrency.getMessage().contains("不一致"), mixedCurrency.getMessage());
        IllegalArgumentException noItems = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-EMPTY", supplier, "AE", "CNY", null, null, List.of())));
        assertTrue(noItems.getMessage().contains("至少需要 1 行"), noItems.getMessage());
        IllegalArgumentException badSite = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-BAD-SITE", supplier, "XX", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(badSite.getMessage().contains("AE/SA"), badSite.getMessage());
        IllegalArgumentException orphanSku = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-ORPHAN", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(9999L, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(orphanSku.getMessage().contains("SKU 不存在"), orphanSku.getMessage());

        IllegalArgumentException dupNo = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-CNY-1", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(dupNo.getMessage().contains("采购单号"), dupNo.getMessage());

        // 修改：仅 DRAFT，orderNo/siteCode 不可变
        IllegalArgumentException codeChanged = assertThrows(IllegalArgumentException.class,
                () -> orderService.update(cny, new OrderInput("PO-OTHER", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 5, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(codeChanged.getMessage().contains("不可变更"), codeChanged.getMessage());
        IllegalArgumentException siteChanged = assertThrows(IllegalArgumentException.class,
                () -> orderService.update(cny, new OrderInput("PO-CNY-1", supplier, "SA", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 5, new BigDecimal("10"), "CNY", BigDecimal.ONE)))));
        assertTrue(siteChanged.getMessage().contains("不可变更"), siteChanged.getMessage());

        orderService.update(cny, new OrderInput("PO-CNY-1", supplier, "AE", "CNY", "MANUAL", null,
                List.of(new OrderItemInput(sku1, 10, new BigDecimal("11.0000"), "CNY", BigDecimal.ONE))));
        assertEquals(0, new BigDecimal("110.0000").compareTo(orderService.get(cny).totalAmount()),
                "整体替换明细并重算合计");
        assertEquals(1, orderService.get(cny).items().size());

        // 状态机：DRAFT→CONFIRMED，只能单向前进
        orderService.confirm(cny);
        assertEquals("CONFIRMED", orderService.get(cny).status());
        IllegalArgumentException confirmAgain = assertThrows(IllegalArgumentException.class,
                () -> orderService.confirm(cny));
        assertTrue(confirmAgain.getMessage().contains("仅 DRAFT"), confirmAgain.getMessage());
        IllegalArgumentException updateConfirmed = assertThrows(IllegalArgumentException.class,
                () -> orderService.update(cny, new OrderInput("PO-CNY-1", supplier, "AE", "CNY", null, null,
                        List.of(new OrderItemInput(sku1, 1, new BigDecimal("11"), "CNY", BigDecimal.ONE)))));
        assertTrue(updateConfirmed.getMessage().contains("仅 DRAFT"), updateConfirmed.getMessage());
        IllegalArgumentException closeTooEarly = assertThrows(IllegalArgumentException.class,
                () -> orderService.close(cny));
        assertTrue(closeTooEarly.getMessage().contains("RECEIVED"), closeTooEarly.getMessage());

        // supplier 停用后不可建单/确认
        long supplier2 = newSupplier("SUP-O2", "Second");
        long draft2 = orderService.save(new OrderInput("PO-DRAFT-2", supplier2, "SA", "CNY", null, null,
                List.of(new OrderItemInput(sku2, 3, new BigDecimal("5.0000"), "CNY", BigDecimal.ONE))));
        supplierService.setStatus(supplier2, "INACTIVE");
        IllegalArgumentException inactiveOnConfirm = assertThrows(IllegalArgumentException.class,
                () -> orderService.confirm(draft2));
        assertTrue(inactiveOnConfirm.getMessage().contains("已停用"), inactiveOnConfirm.getMessage());
        IllegalArgumentException inactiveOnSave = assertThrows(IllegalArgumentException.class,
                () -> orderService.save(new OrderInput("PO-DRAFT-3", supplier2, "SA", "CNY", null, null,
                        List.of(new OrderItemInput(sku2, 1, new BigDecimal("5"), "CNY", BigDecimal.ONE)))));
        assertTrue(inactiveOnSave.getMessage().contains("已停用"), inactiveOnSave.getMessage());

        // 列表筛选
        assertEquals(3, orderService.list(null, 0, 10, null).total(), "超管看全部（AE/SA）");
        assertEquals(1, orderService.list(null, 0, 10, new OrderQuery("po-usd", null, null, null, null)).total());
        assertEquals(1, orderService.list(null, 0, 10,
                new OrderQuery(null, null, "SA", null, null)).total());
        assertEquals(1, orderService.list(null, 0, 10,
                new OrderQuery(null, null, null, "confirmed", null)).total(), "status 过滤大小写不敏感");
        assertEquals(1, orderService.list(null, 0, 10,
                new OrderQuery(null, null, null, null, "replenish_suggestion")).total());
        assertEquals(0, orderService.list(null, 0, 10,
                new OrderQuery("NOPE", null, null, null, null)).total());
        assertThrows(IllegalArgumentException.class, () -> orderService.list(null, 0, 10,
                new OrderQuery(null, null, null, "PICKED", null)), "五态之外 400");

        // 站点隔离：AE 用户只看 AE 单据；无站点零行
        login(2L);
        PageResult<OrderRow> aeOrders = orderService.list(null, 0, 10, null);
        assertEquals(2, aeOrders.total(), "AE 用户只统计 AE 单据");
        assertEquals(0, orderService.list(null, 0, 10, new OrderQuery(null, null, "SA", null, null)).total(),
                "条件站点与授权交集为空即零行");
        assertThrows(AccessDeniedException.class, () -> orderService.get(draft2), "SA 单据详情拒绝");
        assertThrows(AccessDeniedException.class, () -> orderService.save(new OrderInput("PO-OP-AE", supplier,
                "AE", "CNY", null, null, List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"),
                "CNY", BigDecimal.ONE)))), "建单仅超管");
        login(1L);
        long aeDraft = orderService.save(new OrderInput("PO-AE-4", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE))));
        login(2L);
        orderService.confirm(aeDraft);
        assertEquals("CONFIRMED", orderService.get(aeDraft).status(), "站点内普通用户可确认");
        assertThrows(AccessDeniedException.class, () -> orderService.confirm(draft2), "跨站确认拒绝");

        login(3L);
        assertEquals(0, orderService.list(null, 0, 10, null).total(), "空授权零行");
        assertThrows(AccessDeniedException.class, () -> orderService.get(cny));
    }

    // -------------------------------------------------------------- 收货（4.4）

    @Test
    void receiptConservationCostAndStatusRecompute()
    {
        login(1L);
        long sku1 = newSku("SKU-R1", "ACTIVE");
        long sku2 = newSku("SKU-R2", "ACTIVE");
        long supplier = newSupplier("SUP-R1", "Receipt Supplier");
        long order = orderService.save(new OrderInput("PO-RCV-1", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku1, 10, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE),
                        new OrderItemInput(sku2, 5, new BigDecimal("50.0000"), "CNY", BigDecimal.ONE))));
        orderService.confirm(order);
        List<OrderItemRow> items = orderService.get(order).items();
        long item1 = items.get(0).id();
        long item2 = items.get(1).id();

        // DRAFT 不可收：另建一张草稿验证
        long draft = orderService.save(new OrderInput("PO-RCV-DRAFT", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku1, 1, new BigDecimal("10"), "CNY", BigDecimal.ONE))));
        IllegalArgumentException draftReceipt = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(draft,
                        List.of(new ReceiptLineInput(orderService.get(draft).items().get(0).id(), 1)), rid())));
        assertTrue(draftReceipt.getMessage().contains("不可收货"), draftReceipt.getMessage());

        ReceiptResult part = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item1, 3)), rid()));
        assertEquals("PARTIAL_RECEIVED", part.status());
        assertEquals(3, part.receivedTotalQty());
        assertEquals(1, part.lines().size());
        assertEquals(false, part.replayed(), "首次收货 replayed=false");
        assertEquals(0, new BigDecimal("10.0000").compareTo(part.lines().get(0).unitCostCny()),
                "unit_cost_cny = unit_price × fx_rate（只含采购价）");
        assertNotNull(part.lines().get(0).receivedAt());
        assertEquals(3, orderService.get(order).items().get(0).qtyReceived());

        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item1, 8)),
                        rid())));
        assertTrue(over.getMessage().contains("行 id=" + item1), over.getMessage());
        assertTrue(over.getMessage().contains("可收余量=7"), over.getMessage());
        assertEquals(3, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item1), "超收失败必须回滚，不得改动 qty_received");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order), "超收失败不得写收货明细");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt_request where status='PENDING'",
                Integer.class), "失败事务的幂等占位行必须一并回滚");

        receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item1, 7)), rid()));
        assertEquals("PARTIAL_RECEIVED", orderService.get(order).status(), "另一行未齐仍为部分收货");
        ReceiptResult done = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item2, 5)), rid()));
        assertEquals("RECEIVED", done.status(), "全行收齐转 RECEIVED");
        assertEquals(15, done.receivedTotalQty());
        assertEquals(1, done.lines().size(), "本次只提交了 item2 一行");

        IllegalArgumentException afterReceived = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item1, 1)),
                        rid())));
        assertTrue(afterReceived.getMessage().contains("不可收货"), afterReceived.getMessage());

        orderService.close(order);
        assertEquals("CLOSED", orderService.get(order).status());
        IllegalArgumentException closeAgain = assertThrows(IllegalArgumentException.class,
                () -> orderService.close(order));
        assertTrue(closeAgain.getMessage().contains("RECEIVED"), closeAgain.getMessage());
        IllegalArgumentException closedReceipt = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order, List.of(new ReceiptLineInput(item1, 1)),
                        rid())));
        assertTrue(closedReceipt.getMessage().contains("不可收货"), closedReceipt.getMessage());

        // 行归属、数量与重复提交（用另一张已确认单据验证，避免被 CLOSED 状态闸门掩盖）
        long checkOrder = orderService.save(new OrderInput("PO-RCV-CHK", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku1, 2, new BigDecimal("10"), "CNY", BigDecimal.ONE),
                        new OrderItemInput(sku2, 2, new BigDecimal("50"), "CNY", BigDecimal.ONE))));
        orderService.confirm(checkOrder);
        long checkItem = orderService.get(checkOrder).items().get(0).id();
        long otherItem = orderService.get(draft).items().get(0).id();
        IllegalArgumentException foreignItem = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(checkOrder,
                        List.of(new ReceiptLineInput(otherItem, 1)), rid())));
        assertTrue(foreignItem.getMessage().contains("不属于该订单"), foreignItem.getMessage());
        IllegalArgumentException zeroQty = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(checkOrder,
                        List.of(new ReceiptLineInput(checkItem, 0)), rid())));
        assertTrue(zeroQty.getMessage().contains("大于 0"), zeroQty.getMessage());
        IllegalArgumentException sameLineTwice = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(checkOrder, List.of(
                        new ReceiptLineInput(checkItem, 1), new ReceiptLineInput(checkItem, 1)), rid())));
        assertTrue(sameLineTwice.getMessage().contains("不能重复提交"), sameLineTwice.getMessage());

        // 外币订单收货成本换算
        long usdOrder = orderService.save(new OrderInput("PO-RCV-USD", supplier, "AE", "USD", null, null,
                List.of(new OrderItemInput(sku1, 5, new BigDecimal("10.0000"), "USD", new BigDecimal("7.2000")))));
        orderService.confirm(usdOrder);
        long usdItem = orderService.get(usdOrder).items().get(0).id();
        ReceiptResult usdReceipt = receiptService.receive(new ReceiptInput(usdOrder,
                List.of(new ReceiptLineInput(usdItem, 5)), rid()));
        assertEquals(0, new BigDecimal("72.0000").compareTo(usdReceipt.lines().get(0).unitCostCny()),
                "外币收货成本 = 10 × 7.2 = 72 CNY");
        assertEquals("RECEIVED", usdReceipt.status());
    }

    /**
     * v2 §9.1 幂等（H2）：同 requestId 只入账一次且重放 replayed=true；不同 requestId 正常累加；
     * requestId 非法统一 400；requestId 复用到另一订单 400。不写业务/审计。
     */
    @Test
    void receiptIdempotencyReplayValidationAndCrossOrder()
    {
        login(1L);
        long sku = newSku("SKU-ID1", "ACTIVE");
        long supplier = newSupplier("SUP-ID1", "Idempotent Supplier");
        long order = confirmedOrder("PO-IDEM-1", supplier, "AE", sku, 10);
        long item = orderService.get(order).items().get(0).id();
        long other = confirmedOrder("PO-IDEM-2", supplier, "AE", sku, 5);
        long otherItem = orderService.get(other).items().get(0).id();

        String requestId = rid();
        ReceiptResult first = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 3)), requestId));
        assertEquals(false, first.replayed());
        int auditAfterFirst = auditCount();
        int qtyAfterFirst = jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item);
        int receiptsAfterFirst = jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?",
                Integer.class, order);

        // 1) 同一 requestId 第二次提交：replayed=true，返回首次结果，qty/明细/审计都不变
        ReceiptResult replay = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 3)), requestId));
        assertTrue(replay.replayed(), "同 requestId 重试必须标记 replayed");
        assertEquals(first.status(), replay.status(), "重放返回首次状态");
        assertEquals(first.receivedTotalQty(), replay.receivedTotalQty(), "重放返回首次累计数");
        assertEquals(first.lines().get(0).id(), replay.lines().get(0).id(), "重放返回首次收货行 id");
        assertEquals(first.lines().get(0).qty(), replay.lines().get(0).qty());
        assertEquals(qtyAfterFirst, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, item), "重试不得累加 qty_received");
        assertEquals(receiptsAfterFirst, jdbc.queryForObject(
                "select count(*) from noon_m3_receipt where order_id=?", Integer.class, order),
                "重试不得新增收货明细");
        assertEquals(auditAfterFirst, auditCount(), "重试不得写审计");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m3_receipt_request where request_id=?",
                String.class, requestId));
        String storedJson = jdbc.queryForObject("select result_json from noon_m3_receipt_request where request_id=?",
                String.class, requestId);
        assertNotNull(storedJson, "DONE 行必须缓存首次结果");
        assertTrue(storedJson.contains("\"replayed\":false"), "缓存的是首次结果: " + storedJson);

        // 2) 不同 requestId 的第二次真实收货：正常累加
        ReceiptResult second = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 2)), rid()));
        assertEquals(false, second.replayed(), "新 requestId 不是重放");
        assertEquals(5, second.receivedTotalQty(), "3+2 正常累加");
        assertEquals(receiptsAfterFirst + 1, jdbc.queryForObject(
                "select count(*) from noon_m3_receipt where order_id=?", Integer.class, order));

        // 3) requestId 非法：空/空白/过短/超长/中文/含空格 → 统一 400，且不落任何写入
        int reqRowsBefore = jdbc.queryForObject("select count(*) from noon_m3_receipt_request", Integer.class);
        for (String bad : new String[]{null, "", "   ", "short", "a".repeat(65), "收货请求1号",
                "req id 01", "请求-req-01"})
        {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> receiptService.receive(new ReceiptInput(order,
                            List.of(new ReceiptLineInput(item, 1)), bad)), "非法 requestId 必须 400: [" + bad + "]");
            assertEquals("收货请求缺少有效的 requestId", e.getMessage());
        }
        assertEquals(reqRowsBefore, jdbc.queryForObject("select count(*) from noon_m3_receipt_request",
                Integer.class), "非法 requestId 不得写幂等表");

        // 4) requestId 复用到另一采购单：400，且另一单据不受影响
        IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(other,
                        List.of(new ReceiptLineInput(otherItem, 1)), requestId)));
        assertTrue(cross.getMessage().contains("requestId 已用于其他采购单"), cross.getMessage());
        assertEquals(0, jdbc.queryForObject("select qty_received from pur_order_item where id=?",
                Integer.class, otherItem), "跨单复用不得改动另一单据");
        assertEquals("CONFIRMED", orderService.get(other).status());
    }

    @Test
    void receiptLedgerRespectsOrderSiteAuthorization()
    {
        login(1L);
        long sku = newSku("SKU-L1", "ACTIVE");
        long supplier = newSupplier("SUP-L1", "Ledger Supplier");
        long aeOrder = confirmedOrder("PO-LED-AE", supplier, "AE", sku, 4);
        long saOrder = confirmedOrder("PO-LED-SA", supplier, "SA", sku, 2);
        receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(orderService.get(aeOrder).items().get(0).id(), 2)), rid()));
        receiptService.receive(new ReceiptInput(saOrder,
                List.of(new ReceiptLineInput(orderService.get(saOrder).items().get(0).id(), 2)), rid()));

        PageResult<ReceiptRow> all = receiptService.list(null, 0, 10, null);
        assertEquals(2, all.total(), "超管看全部收货明细");
        assertEquals(2, all.rows().size());
        assertEquals(1, receiptService.list(null, 0, 10, aeOrder).total(), "orderId 精确过滤");
        assertEquals(0, receiptService.list(null, 0, 10, 9999L).total(), "未命中 total=0");

        login(2L); // 仅 AE
        assertEquals(1, receiptService.list(null, 0, 10, null).total(), "普通用户仅授权站点订单的收货明细");
        assertEquals(1, receiptService.list(null, 0, 10, aeOrder).total());
        assertEquals(0, receiptService.list(null, 0, 10, saOrder).total(), "跨站订单台账零行");
        assertThrows(AccessDeniedException.class, () -> receiptService.list("SA", 0, 10, null),
                "传 siteCode 先 requireSite：未授权站点拒绝");
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(saOrder,
                List.of(new ReceiptLineInput(orderService.get(saOrder).items().get(0).id(), 1)), rid())),
                "跨站收货拒绝");

        login(3L);
        PageResult<ReceiptRow> zero = receiptService.list(null, 0, 10, aeOrder);
        assertEquals(0, zero.total(), "空授权零行");
        assertTrue(zero.rows().isEmpty());
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(orderService.get(aeOrder).items().get(0).id(), 1)), rid())),
                "空授权不可收货");
    }

    /**
     * v2.1 §9.6 ①：重放前必须按订单真实站点授权。
     * viewer（无站点）持 admin 的合法 requestId+orderId → 403；opAE 持 SA 单 requestId → 403；
     * 授权撤销后 opAE 重放同一 AE 单 → 403；三者都读不到结果、不改动任何数据。
     */
    @Test
    void receiptReplayRequiresOrderSiteAuthorization()
    {
        login(1L);
        long sku = newSku("SKU-V21-A", "ACTIVE");
        long supplier = newSupplier("SUP-V21-A", "Replay Auth Supplier");
        long aeOrder = confirmedOrder("PO-V21-AE", supplier, "AE", sku, 10);
        long aeItem = orderService.get(aeOrder).items().get(0).id();
        String aeRequest = rid();
        ReceiptResult aeFirst = receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(aeItem, 4)), aeRequest));
        assertEquals(false, aeFirst.replayed());

        long saOrder = confirmedOrder("PO-V21-SA", supplier, "SA", sku, 5);
        long saItem = orderService.get(saOrder).items().get(0).id();
        String saRequest = rid();
        receiptService.receive(new ReceiptInput(saOrder, List.of(new ReceiptLineInput(saItem, 2)), saRequest));

        int qtyAe = qtyReceived(aeItem);
        int qtySa = qtyReceived(saItem);
        int receiptsAe = receiptRows(aeOrder);
        int audits = auditCount();

        login(3L); // viewer：无任何站点授权
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(aeItem, 4)), aeRequest)), "无站点用户持合法键重放必须 403");
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(aeRequest), "无站点查结果必须 403");

        login(2L); // opAE：仅 AE
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(saOrder,
                List.of(new ReceiptLineInput(saItem, 2)), saRequest)), "opAE 持 SA 单键重放必须 403");
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(saRequest), "跨站查结果必须 403");
        ReceiptResult aeReplay = receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(aeItem, 4)), aeRequest));
        assertTrue(aeReplay.replayed(), "有该站点权限的用户重放同一键 → 正常重放");

        // 授权撤销：删除 opAE 的 AE 授权后，原 AE 单的同一键重放/查询也必须 403
        jdbc.update("delete from sys_user_site where user_id=2 and site_code='AE'");
        assertThrows(AccessDeniedException.class, () -> receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(aeItem, 4)), aeRequest)), "授权撤销后重放必须 403");
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(aeRequest), "授权撤销后查结果必须 403");

        assertEquals(qtyAe, qtyReceived(aeItem), "越权重放不得改动 qty_received");
        assertEquals(qtySa, qtyReceived(saItem), "跨站重放不得改动另一单据");
        assertEquals(receiptsAe, receiptRows(aeOrder), "越权重放不得新增收货明细");
        assertEquals(audits, auditCount(), "越权重放不得写审计");
        assertEquals("DONE", requestStatus(aeRequest), "越权访问不得改变幂等行状态");
    }

    /**
     * v2.1 §9.6 ②：同键不同内容必须 400（且优先于状态判定）；指纹按 itemId 数值升序规范化，与数组提交顺序无关。
     */
    @Test
    void receiptReplayRejectsChangedContentFingerprint()
    {
        login(1L);
        long sku = newSku("SKU-V21-F", "ACTIVE");
        long supplier = newSupplier("SUP-V21-F", "Fingerprint Supplier");

        // 60 件一次收满 → RECEIVED；同键改 50 必须报内容不一致（而非被「不可收货」状态闸门先拦住）
        long order = confirmedOrder("PO-V21-FP", supplier, "AE", sku, 60);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();
        ReceiptResult full = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 60)), requestId));
        assertEquals(60, full.receivedTotalQty());
        assertEquals("RECEIVED", full.status());
        String stored = jdbc.queryForObject("select content_fingerprint from noon_m3_receipt_request "
                + "where request_id=?", String.class, requestId);
        assertEquals(ReceiptService.contentFingerprint(order, List.of(new ReceiptLineInput(item, 60))), stored,
                "首次占位必须写入规范化内容指纹");
        assertTrue(stored != null && stored.matches("[0-9a-f]{64}"), "指纹必须是 64 位小写十六进制: " + stored);
        int audits = auditCount();

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order,
                        List.of(new ReceiptLineInput(item, 50)), requestId)));
        assertEquals("requestId 对应的收货内容与原请求不一致", changed.getMessage(), "同键不同数量必须报内容不一致");
        assertEquals(60, qtyReceived(item), "内容冲突不得改动 qty_received");
        assertEquals("RECEIVED", orderService.get(order).status(), "内容冲突不得回退状态");
        assertEquals(audits, auditCount(), "内容冲突不得写审计");

        // 部分收货场景：10 → 同键 20 也必须 400
        long partialOrder = confirmedOrder("PO-V21-FP2", supplier, "AE", sku, 60);
        long partialItem = orderService.get(partialOrder).items().get(0).id();
        String partialRequest = rid();
        receiptService.receive(new ReceiptInput(partialOrder,
                List.of(new ReceiptLineInput(partialItem, 10)), partialRequest));
        IllegalArgumentException changedPartial = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(partialOrder,
                        List.of(new ReceiptLineInput(partialItem, 20)), partialRequest)));
        assertEquals("requestId 对应的收货内容与原请求不一致", changedPartial.getMessage());
        assertEquals(10, qtyReceived(partialItem), "内容冲突不得累加");
        assertEquals("PARTIAL_RECEIVED", orderService.get(partialOrder).status());

        // 多行：数组顺序颠倒但内容相同 → 指纹一致，命中重放；数量在两行间互换 → 指纹不同，400
        long sku2 = newSku("SKU-V21-G", "ACTIVE");
        long twoLines = orderService.save(new OrderInput("PO-V21-FP3", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku, 6, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE),
                        new OrderItemInput(sku2, 6, new BigDecimal("20.0000"), "CNY", BigDecimal.ONE))));
        orderService.confirm(twoLines);
        long firstItem = orderService.get(twoLines).items().get(0).id();
        long secondItem = orderService.get(twoLines).items().get(1).id();
        String twoRequest = rid();
        receiptService.receive(new ReceiptInput(twoLines,
                List.of(new ReceiptLineInput(secondItem, 5), new ReceiptLineInput(firstItem, 2)), twoRequest));
        ReceiptResult reordered = receiptService.receive(new ReceiptInput(twoLines,
                List.of(new ReceiptLineInput(firstItem, 2), new ReceiptLineInput(secondItem, 5)), twoRequest));
        assertTrue(reordered.replayed(), "内容相同（数组顺序不同）必须命中重放");
        IllegalArgumentException swapped = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(twoLines,
                        List.of(new ReceiptLineInput(firstItem, 5), new ReceiptLineInput(secondItem, 2)), twoRequest)));
        assertEquals("requestId 对应的收货内容与原请求不一致", swapped.getMessage(), "数量换行后指纹不同");
    }

    /**
     * v2.1 §9.6 ③：resultOf 只读、按订单真实站点授权；DONE 返回 replayed=true；不存在/非法键返回 null。
     */
    @Test
    void receiptResultOfIsReadOnlyAndSiteAuthorized()
    {
        login(1L);
        long sku = newSku("SKU-V21-Q", "ACTIVE");
        long supplier = newSupplier("SUP-V21-Q", "Result Supplier");
        long aeOrder = confirmedOrder("PO-V21-QA", supplier, "AE", sku, 10);
        long aeItem = orderService.get(aeOrder).items().get(0).id();
        String requestId = rid();
        ReceiptResult first = receiptService.receive(new ReceiptInput(aeOrder,
                List.of(new ReceiptLineInput(aeItem, 3)), requestId));
        int qty = qtyReceived(aeItem);
        int receipts = receiptRows(aeOrder);
        int audits = auditCount();

        ReceiptResult queried = receiptService.resultOf(requestId);
        assertNotNull(queried);
        assertTrue(queried.replayed(), "查询结果标记 replayed=true（非首次入账）");
        assertEquals(first.orderId(), queried.orderId());
        assertEquals(first.status(), queried.status());
        assertEquals(first.receivedTotalQty(), queried.receivedTotalQty());
        assertEquals(first.lines().get(0).id(), queried.lines().get(0).id());
        assertEquals(qty, qtyReceived(aeItem), "resultOf 是只读接口");
        assertEquals(receipts, receiptRows(aeOrder), "resultOf 不得新增收货明细");
        assertEquals(audits, auditCount(), "resultOf 不得写审计");
        assertEquals("DONE", requestStatus(requestId), "resultOf 不得改幂等状态");

        assertNull(receiptService.resultOf("r-" + UUID.randomUUID()), "不存在的键返回 null");
        assertNull(receiptService.resultOf(null), "null 键返回 null");
        assertNull(receiptService.resultOf(""), "空键返回 null");
        assertNull(receiptService.resultOf("short"), "非法格式返回 null 而非 400");

        long saOrder = confirmedOrder("PO-V21-QS", supplier, "SA", sku, 5);
        long saItem = orderService.get(saOrder).items().get(0).id();
        String saRequest = rid();
        receiptService.receive(new ReceiptInput(saOrder, List.of(new ReceiptLineInput(saItem, 2)), saRequest));

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(saRequest), "跨站查结果必须 403");
        assertNotNull(receiptService.resultOf(requestId), "有 AE 权限可查 AE 单结果");

        login(3L); // 无站点
        assertThrows(AccessDeniedException.class, () -> receiptService.resultOf(requestId), "无站点查结果必须 403");
    }

    /**
     * H2 回归：同键首次业务失败→整事务回滚（含 PENDING 占位与指纹），同键重试成功；
     * 同键再发（订单已 RECEIVED）仍重放不增产；不同键正常累加。
     */
    @Test
    void receiptFailedAttemptRollsBackAndSameRequestIdRetries()
    {
        login(1L);
        long sku = newSku("SKU-V21-R", "ACTIVE");
        long supplier = newSupplier("SUP-V21-R", "Retry Supplier");
        long order = confirmedOrder("PO-V21-RT", supplier, "AE", sku, 4);
        long item = orderService.get(order).items().get(0).id();
        String requestId = rid();

        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order,
                        List.of(new ReceiptLineInput(item, 5)), requestId)));
        assertTrue(over.getMessage().contains("可收余量"), over.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m3_receipt_request where request_id=?",
                Integer.class, requestId), "失败事务的 PENDING 行（含指纹）必须回滚");
        assertEquals(0, qtyReceived(item), "失败事务不得改动 qty_received");
        assertEquals(0, receiptRows(order), "失败事务不得写收货明细");

        ReceiptResult retried = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 4)), requestId));
        assertEquals(false, retried.replayed(), "重试是首次成功");
        assertEquals("RECEIVED", retried.status());
        assertEquals(4, qtyReceived(item));
        assertEquals(1, receiptRows(order));

        ReceiptResult replay = receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(item, 4)), requestId));
        assertTrue(replay.replayed(), "同键再发必须重放（即使订单已 RECEIVED）");
        assertEquals(4, qtyReceived(item), "重放不得累加");
        assertEquals(1, receiptRows(order), "重放不得新增明细");
    }

    /**
     * v2.1 §9.6 ② 指纹算法固定值：规范化串 {@code 12|3:60,7:40}（按 itemId 数值升序）的 SHA-256，
     * 与实现往返比对互补，防止规范化串或排序规则悄悄漂移。
     */
    @Test
    void contentFingerprintMatchesContractCanonicalForm()
    {
        assertEquals("8d50b685d6b4fa09322bfee789a7292bb59db1d79284b86294b1e5d68afdf2ba",
                ReceiptService.contentFingerprint(12L,
                        List.of(new ReceiptLineInput(7L, 40), new ReceiptLineInput(3L, 60))),
                "契约示例 12|3:60,7:40 的 SHA-256（输入顺序无关、itemId 升序规范化）");
    }

    // ------------------------------------------------------------------ 审计

    @Test
    void auditRecordsBeforeAfterAndRollsBackWithBusinessFailure()
    {
        login(1L);
        long sku = newSku("SKU-A1", "ACTIVE");
        long supplier = newSupplier("SUP-A1", "Audit Supplier");
        supplierService.update(supplier, new SupplierInput("SUP-A1", "Audit Supplied", null, "ACTIVE"));
        long quotation = quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9.0000"),
                "CNY", 0, 0, "2026-01-01", null));
        quotationService.update(quotation, new QuotationInput(sku, supplier, new BigDecimal("9.5000"),
                "CNY", 0, 0, "2026-01-01", null));
        long order = orderService.save(new OrderInput("PO-AUD-1", supplier, "AE", "CNY", null, null,
                List.of(new OrderItemInput(sku, 4, new BigDecimal("9.5000"), "CNY", BigDecimal.ONE))));
        orderService.confirm(order);
        receiptService.receive(new ReceiptInput(order,
                List.of(new ReceiptLineInput(orderService.get(order).items().get(0).id(), 2)), rid()));

        for (String entity : new String[]{PurchasingAudit.ENTITY_SUPPLIER, PurchasingAudit.ENTITY_QUOTATION,
                PurchasingAudit.ENTITY_ORDER, PurchasingAudit.ENTITY_RECEIPT})
        {
            assertTrue(jdbc.queryForObject("select count(*) from noon_m3_audit_log where entity_type=?",
                    Integer.class, entity) >= 1, "缺少审计: " + entity);
            assertEquals(1L, jdbc.queryForObject("select max(operator_id) from noon_m3_audit_log where entity_type=?",
                    Long.class, entity), "操作人应为真实登录人: " + entity);
        }
        assertTrue(jdbc.queryForObject("select count(*) from noon_m3_audit_log "
                + "where entity_type='SUPPLIER' and action='INSERT' and before_json is null", Integer.class) >= 1);
        String orderStatusJson = jdbc.queryForObject("select after_json from noon_m3_audit_log "
                + "where entity_type='PUR_ORDER' and action='STATUS' order by id desc limit 1", String.class);
        assertTrue(orderStatusJson.contains("\"status\":\"CONFIRMED\""), orderStatusJson);
        String receiptJson = jdbc.queryForObject("select after_json from noon_m3_audit_log "
                + "where entity_type='RECEIPT' order by id desc limit 1", String.class);
        assertTrue(receiptJson.contains("\"qtyReceived\":2"), receiptJson);
        assertTrue(receiptJson.contains("\"receipts\""), "审计需含本次收货行: " + receiptJson);
        String receiptBefore = jdbc.queryForObject("select before_json from noon_m3_audit_log "
                + "where entity_type='RECEIPT' order by id desc limit 1", String.class);
        assertTrue(receiptBefore.contains("\"qtyReceived\":0"), receiptBefore);

        int beforeCount = auditCount();
        assertThrows(IllegalArgumentException.class,
                () -> supplierService.save(new SupplierInput("SUP-A1", "Dup", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> receiptService.receive(new ReceiptInput(order,
                        List.of(new ReceiptLineInput(orderService.get(order).items().get(0).id(), 3)), rid())));
        assertThrows(IllegalArgumentException.class,
                () -> quotationService.save(new QuotationInput(sku, supplier, new BigDecimal("9"), "CNY",
                        0, 0, "2026-01-01", null)));
        assertEquals(beforeCount, auditCount(), "失败写入/校验不得留下审计（同事务回滚）");
    }

    // ------------------------------------------------------------------ helpers

    private long confirmedOrder(String orderNo, long supplier, String site, long sku, int qty)
    {
        long order = orderService.save(new OrderInput(orderNo, supplier, site, "CNY", null, null,
                List.of(new OrderItemInput(sku, qty, new BigDecimal("10.0000"), "CNY", BigDecimal.ONE))));
        orderService.confirm(order);
        return order;
    }

    private long newSupplier(String no, String name)
    {
        return supplierService.save(new SupplierInput(no, name, null, null));
    }

    /** 直接造 SKU 主数据（M2 owner，测试只读引用；避免跨包依赖 M2 Service）。 */
    private long newSku(String code, String status)
    {
        jdbc.update("insert into md_sku(sku_code,status) values (?,?)", code, status);
        return jdbc.queryForObject("select max(id) from md_sku where sku_code=?", Long.class, code);
    }

    private int auditCount()
    {
        return jdbc.queryForObject("select count(*) from noon_m3_audit_log", Integer.class);
    }

    private int qtyReceived(long itemId)
    {
        return jdbc.queryForObject("select qty_received from pur_order_item where id=?", Integer.class, itemId);
    }

    private int receiptRows(long orderId)
    {
        return jdbc.queryForObject("select count(*) from noon_m3_receipt where order_id=?", Integer.class, orderId);
    }

    private String requestStatus(String requestId)
    {
        return jdbc.queryForObject("select status from noon_m3_receipt_request where request_id=?",
                String.class, requestId);
    }

    /** 一次性幂等键：模拟前端 crypto.randomUUID()，长度 38 位，符合 8–64 与 [A-Za-z0-9_-]。 */
    private static String rid()
    {
        return "r-" + UUID.randomUUID();
    }

    private int lockCount(long skuId, long supplierId)
    {
        return jdbc.queryForObject("select count(*) from noon_m3_quotation_lock "
                + "where sku_id=? and supplier_id=?", Integer.class, skuId, supplierId);
    }

    private static <T> List<Long> ids(List<T> rows, Function<T, Long> id)
    {
        return rows.stream().map(id).toList();
    }

    private static void assertPageWalk(List<Long> expected, int pageSize,
                                       BiFunction<Integer, Integer, List<Long>> page)
    {
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += pageSize)
        {
            List<Long> ids = page.apply(offset, pageSize);
            assertEquals(Math.min(pageSize, expected.size() - offset), ids.size(), "offset=" + offset);
            seen.addAll(ids);
        }
        assertEquals(expected, seen, "翻页必须无重复、无遗漏");
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

    private static String h2Ddl(String sql)
    {
        return sql.replaceAll("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replaceAll("COMMENT\\s*=\\s*'[^']*'", "");
    }

    /**
     * H2 宽松建表：列名与 sql/noon_m3_purchasing.sql 对齐；JSON 用文本列、不含 CHECK
     * （CHECK 与并发 FOR UPDATE 由隔离 MySQL IT 验证）。唯一键命名与 MySQL 一致便于验证冲突转中文。
     */
    private void createPurchasingTables()
    {
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "status varchar(16) not null default 'ACTIVE', constraint uk_sku_code unique(sku_code))");
        jdbc.execute("create table md_supplier (id bigint auto_increment primary key, "
                + "supplier_no varchar(64) not null, supplier_name varchar(256) not null, "
                + "contact_json varchar(4000), status varchar(16) not null default 'ACTIVE', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_supplier_no unique(supplier_no))");
        jdbc.execute("create table md_sku_supplier (id bigint auto_increment primary key, sku_id bigint not null, "
                + "supplier_id bigint not null, unit_price decimal(18,4) not null, "
                + "currency char(3) not null default 'CNY', moq int default 0, lead_time_days int default 0, "
                + "effective_from date not null, effective_to date, "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_sku_sup_eff unique(sku_id,supplier_id,effective_from))");
        jdbc.execute("create table pur_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "supplier_id bigint not null, site_code varchar(16) not null, currency char(3) not null default 'CNY', "
                + "total_amount decimal(18,4) default 0, status varchar(24) not null default 'DRAFT', "
                + "source varchar(24) default 'MANUAL', source_ref_no varchar(64), operator_id bigint, "
                + "config_snapshot_batch_no varchar(64), created_at timestamp(3) not null default current_timestamp(3), "
                + "updated_at timestamp(3) not null default current_timestamp(3), constraint uk_pur_no unique(order_no))");
        jdbc.execute("create table pur_order_item (id bigint auto_increment primary key, pur_order_id bigint not null, "
                + "sku_id bigint not null, qty_ordered int not null, qty_received int not null default 0, "
                + "unit_price decimal(18,4) not null, currency char(3) not null default 'CNY', "
                + "fx_rate decimal(18,8) default null, created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m3_receipt (id bigint auto_increment primary key, order_id bigint not null, "
                + "order_item_id bigint not null, qty int not null, unit_cost_cny decimal(18,4) not null, "
                + "received_at timestamp(3) not null, operator_id bigint)");
        jdbc.execute("create table noon_m3_audit_log (id bigint auto_increment primary key, "
                + "entity_type varchar(32) not null, entity_id bigint not null, action varchar(16) not null, "
                + "operator_id bigint, operator_name varchar(64), before_json varchar(10000), "
                + "after_json varchar(10000), changed_at timestamp(3) not null)");
        // v2 §9.1 + v2.1 §9.6：幂等请求头（request_id 为业务主键，冲突需触发 DuplicateKeyException）；
        // content_fingerprint 与迁移 DDL 同构（CHAR(64) 定长），JSON 用文本列。
        jdbc.execute("create table noon_m3_receipt_request (request_id varchar(64) not null primary key, "
                + "order_id bigint not null, operator_id bigint, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', "
                + "result_json varchar(10000), created_at timestamp(3) not null default current_timestamp(3))");
        // v2 §9.2：报价组合串行化锁表（组合主键，冲突需触发 DuplicateKeyException）。
        jdbc.execute("create table noon_m3_quotation_lock (sku_id bigint not null, supplier_id bigint not null, "
                + "primary key(sku_id,supplier_id))");
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
