package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 第三轮第二批「头程费用登记」H2 单测（真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper + 真 SiteAuthGuard）。
 *
 * <p>覆盖：登记成功（单号格式 / 金额 4 位 HALF_UP / 汇率 8 位 / 期间默认业务月 / CREATE 审计 / 不动库存批次台账）；
 * 全部校验分支（feeType、amount≤0、currency、fx_rate 四种口径、period、allocation_flag、remark 超长、asnId、
 * requestId、站点）；客户端伪造字段结构性忽略；动作级幂等（DONE 重放零写入、同键改内容 400、跨站点同键 400、
 * PENDING 占位 400、cost_id 0 占位→真实 id 回填、resultOf 命中与只读）；授权（越权站点 403、跨站 ASN 400、
 * 撤销授权后 resultOf/get/list 403、空授权零行、授权先于重放）；列表真实 total + feeType/asnId/period 过滤 +
 * 翻页不重不漏；详情；并发同 requestId 只登记一笔；uk_cost_no 唯一键。
 *
 * <p>H2 建表为宽松 DDL（无 CHECK），真实 DDL CHECK/唯一键/并发由 {@link MySqlShipmentCostIT} 验证。
 */
class ShipmentCostServiceTest
{
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ShipmentCostService service;

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
                + "values (2,'AE',1,current_timestamp(3)), (4,'AE',1,current_timestamp(3)), "
                + "(4,'SA',1,current_timestamp(3))");

        createTables();
        service = context.getBean(ShipmentCostService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ------------------------------------------------------- 登记成功

    @Test
    void createSuccessWritesCostAuditAndLeavesInventoryUntouched()
    {
        login(1L);
        long asnAe = newAsn("ASN-AE-1", "AE");
        int batches = count("inv_batch");
        int ledgers = count("inv_stock_ledger");
        int moves = count("inv_stock_move");
        int consumptions = count("inv_batch_consumption");

        ShipmentCostResult created = service.create(new ShipmentCostInput(asnAe, "ae", "2026-11", "FREIGHT",
                new BigDecimal("1234.56789"), "usd", new BigDecimal("3.6725"), null, "  E2E 头程运费  ", rid()));

        assertFalse(created.replayed(), "首次登记 replayed=false");
        assertTrue(created.costId() > 0);
        assertTrue(created.costNo().matches("SC:AE:[0-9A-F]{8}"), created.costNo());

        Map<String, Object> row = jdbc.queryForMap("select * from log_shipment_cost where id=?",
                created.costId());
        assertEquals(created.costNo(), row.get("cost_no"));
        assertEquals(asnAe, ((Number) row.get("asn_id")).longValue());
        assertEquals("AE", row.get("site_code"));
        assertEquals("2026-11", row.get("period"));
        assertEquals("FREIGHT", row.get("fee_type"));
        assertMoney("1234.5679", row.get("amount"), "amount 4 位 HALF_UP");
        assertEquals("USD", row.get("currency"), "币种统一大写");
        assertMoney("3.67250000", row.get("fx_rate"), "汇率按列宽固定 8 位");
        assertEquals("NONE", row.get("allocation_flag"), "空分摊标记默认 NONE");
        assertEquals("E2E 头程运费", row.get("remark"), "备注去首尾空白");
        assertNotNull(row.get("created_at"));

        // 审计同事务：共享 M4 审计表，biz_type 区分 SHIPMENT_COST。
        Map<String, Object> audit = jdbc.queryForMap("select biz_type,biz_id,action,detail,operator_id "
                + "from noon_m4_inventory_audit");
        assertEquals("SHIPMENT_COST", audit.get("biz_type"));
        assertEquals(String.valueOf(created.costId()), audit.get("biz_id"));
        assertEquals("CREATE", audit.get("action"));
        assertTrue(String.valueOf(audit.get("detail")).contains(created.costNo()),
                String.valueOf(audit.get("detail")));
        assertEquals(1L, ((Number) audit.get("operator_id")).longValue());

        // 幂等行：DONE + cost_id 由 PENDING 占位 0 回填为真实 id。
        Map<String, Object> request = jdbc.queryForMap("select cost_id,site_code,status,result_json "
                + "from noon_m4_shipment_cost_request");
        assertEquals(created.costId(), ((Number) request.get("cost_id")).longValue());
        assertEquals("AE", request.get("site_code"));
        assertEquals("DONE", request.get("status"));
        assertTrue(String.valueOf(request.get("result_json")).contains(created.costNo()));
        assertEquals(1, count("noon_m4_shipment_cost_request"));

        // 契约 §0：纯登记，不动库存/批次/台账/COGS。
        assertEquals(batches, count("inv_batch"));
        assertEquals(ledgers, count("inv_stock_ledger"));
        assertEquals(moves, count("inv_stock_move"));
        assertEquals(consumptions, count("inv_batch_consumption"));
    }

    // ------------------------------------------------------- 采购单多选关联

    @Test
    void createWithMultiplePurchaseOrdersStoresDedupedLinksAndEchoesInDetailAndList()
    {
        login(1L);
        long po1 = newPo("PO-AE-1", "AE");
        long po2 = newPo("PO-AE-2", "AE");
        long poSa = newPo("PO-SA-1", "SA");

        // 传入重复 id（po2 两次）→ 去重升序；含 ASN 并存互不影响。
        ShipmentCostResult created = service.create(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT",
                new BigDecimal("500"), "CNY", BigDecimal.ONE, null, "多采购单费用", rid(),
                java.util.List.of(po2, po1, po2)));
        assertTrue(created.costId() > 0);

        // 关联表：去重后两行，均带费用站点；SA 采购单未被关联。
        assertEquals(2, count("log_shipment_cost_po"));
        assertEquals(2, jdbc.queryForObject("select count(*) from log_shipment_cost_po where cost_id=? and site_code='AE'",
                Integer.class, created.costId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from log_shipment_cost_po where po_id=?",
                Integer.class, poSa));

        // 详情回显升序去重。
        ShipmentCostDetail detail = service.get(created.costId());
        assertEquals(java.util.List.of(po1, po2), detail.poIds());
        assertTrue(String.valueOf(jdbc.queryForMap("select detail from noon_m4_inventory_audit")
                .get("detail")).contains("poIds=" + po1 + "," + po2), "审计记录关联采购单");

        // 列表回显（批量装载，非 N+1 逻辑不影响结果）。
        ShipmentCostRow row = service.list("AE", 0, 10, null).rows().stream()
                .filter(r -> r.id() == created.costId()).findFirst().orElseThrow();
        assertEquals(java.util.List.of(po1, po2), row.poIds());
    }

    @Test
    void createRejectsCrossSiteOrMissingPurchaseOrder()
    {
        login(1L);
        long poSa = newPo("PO-SA-2", "SA");
        // 跨站采购单 400，且不写任何费用/关联行。
        assertBad(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT", new BigDecimal("1"), "CNY",
                BigDecimal.ONE, null, null, rid(), java.util.List.of(poSa)));
        // 不存在的采购单 400。
        assertBad(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT", new BigDecimal("1"), "CNY",
                BigDecimal.ONE, null, null, rid(), java.util.List.of(999999L)));
        assertEquals(0, count("log_shipment_cost"));
        assertEquals(0, count("log_shipment_cost_po"));
    }

    @Test
    void sameRequestIdWithDifferentPoIdsIsContentConflict()
    {
        login(1L);
        long po1 = newPo("PO-AE-3", "AE");
        long po2 = newPo("PO-AE-4", "AE");
        String rid = rid();
        service.create(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT", new BigDecimal("1"), "CNY",
                BigDecimal.ONE, null, null, rid, java.util.List.of(po1)));
        // 同键改选采购单 → 内容指纹不同 → 400 冲突，不新增费用行。
        assertBad(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT", new BigDecimal("1"), "CNY",
                BigDecimal.ONE, null, null, rid, java.util.List.of(po2)));
        assertEquals(1, count("log_shipment_cost"));
    }

    @Test
    void periodDefaultsToBusinessMonthAndCnyFxMustBeExplicitOne()
    {
        login(1L);
        String requestId = rid();
        ShipmentCostResult created = service.create(new ShipmentCostInput(null, "AE", "  ", "PACKAGING",
                new BigDecimal("88.5"), "CNY", BigDecimal.ONE, " none ", null, requestId));

        Map<String, Object> row = jdbc.queryForMap("select period,fx_rate,allocation_flag,currency "
                + "from log_shipment_cost where id=?", created.costId());
        String period = String.valueOf(row.get("period"));
        assertTrue(period.matches("\\d{4}-\\d{2}"), period);
        String expected = ShipmentCostRules.periodOf(LocalDateTime.now(BUSINESS_ZONE));
        assertEquals(expected, period, "空期间取 UTC+8 业务月");
        assertMoney("1.00000000", row.get("fx_rate"), "CNY 显式 1 → 8 位落库");
        assertEquals("NONE", row.get("allocation_flag"));
        assertEquals("CNY", row.get("currency"));

        // 登记时刻为 UTC+8 业务时刻（允许与测试机时钟秒级偏移）。
        LocalDateTime createdAt = jdbc.queryForObject("select created_at from log_shipment_cost where id=?",
                java.sql.Timestamp.class, created.costId()).toLocalDateTime();
        assertTrue(Math.abs(Duration.between(java.time.LocalDateTime.now(BUSINESS_ZONE), createdAt).toMinutes()) < 5,
                "created_at 应为 UTC+8 业务时刻: " + createdAt);
    }

    // ------------------------------------------------------- 校验

    @Test
    void validationsRejectInvalidInput()
    {
        login(1L);
        long asnAe = newAsn("ASN-AE-1", "AE");
        long asnSa = newAsn("ASN-SA-1", "SA");

        // feeType
        assertBad(input("AE", "OTHER", "100", "CNY", "1", null, null, null));
        assertBad(input("AE", null, "100", "CNY", "1", null, null, null));
        // amount
        assertBad(input("AE", "FREIGHT", null, "CNY", "1", null, null, null));
        assertBad(input("AE", "FREIGHT", "0", "CNY", "1", null, null, null));
        assertBad(input("AE", "FREIGHT", "-1", "CNY", "1", null, null, null));
        // currency
        assertBad(input("AE", "FREIGHT", "100", null, "1", null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "USDX", "1", null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "C1N", "1", null, null, null));
        // fx_rate：CNY 缺失/非 1；外币缺失/≤0
        assertBad(input("AE", "FREIGHT", "100", "CNY", null, null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "CNY", "2", null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "USD", null, null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "USD", "0", null, null, null));
        assertBad(input("AE", "FREIGHT", "100", "USD", "-1", null, null, null));
        // period
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", null, "2026-13", null));
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", null, "2026-1", null));
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", null, "202611", null));
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", null, "2026-00", null));
        // allocation_flag
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", "ALL", null, null));
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", "PARTIALX", null, null));
        // remark 超长（512 上限）
        assertBad(input("AE", "FREIGHT", "100", "CNY", "1", null, null, "x".repeat(513)));
        // asnId
        assertBad(new ShipmentCostInput(0L, "AE", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, rid()));
        assertBad(new ShipmentCostInput(-1L, "AE", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, rid()));
        // 跨站 / 不存在 ASN
        IllegalArgumentException crossAsn = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(asnSa, "AE", null, "FREIGHT", new BigDecimal("1"),
                        "CNY", BigDecimal.ONE, null, null, rid())));
        assertTrue(crossAsn.getMessage().contains("不一致"), crossAsn.getMessage());
        IllegalArgumentException missingAsn = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(9999L, "AE", null, "FREIGHT", new BigDecimal("1"),
                        "CNY", BigDecimal.ONE, null, null, rid())));
        assertTrue(missingAsn.getMessage().contains("ASN 不存在"), missingAsn.getMessage());
        // requestId / 站点
        assertBad(new ShipmentCostInput(null, "AE", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, "short"));
        assertBad(new ShipmentCostInput(null, "AE", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, "bad id with space"));
        assertBad(new ShipmentCostInput(null, "AE", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, null));
        assertBad(new ShipmentCostInput(null, null, null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, rid()));
        assertBad(new ShipmentCostInput(null, "XX", null, "FREIGHT", new BigDecimal("1"), "CNY", BigDecimal.ONE,
                null, null, rid()));

        // 全部被拒后零写入、零审计、零幂等行。
        assertEquals(0, count("log_shipment_cost"));
        assertEquals(0, count("noon_m4_inventory_audit"));
        assertEquals(0, count("noon_m4_shipment_cost_request"));

        // 512 长度备注是合法上限。
        ShipmentCostResult ok = service.create(input("AE", "FREIGHT", "100", "CNY", "1", null, null,
                "y".repeat(512)));
        assertEquals(512, String.valueOf(jdbc.queryForObject("select remark from log_shipment_cost where id=?",
                String.class, ok.costId())).length());
        // asn 同站点合法
        assertFalse(service.create(new ShipmentCostInput(asnAe, "AE", "2026-11", "DUTY", new BigDecimal("9"),
                "AED", new BigDecimal("3.6725"), "PARTIAL", "ok", rid())).replayed());
    }

    /** 客户端传入的 id/costNo/status/createdAt 在结构上不存在，反序列化后忽略，落库全部服务端生成。 */
    @Test
    void clientForgedFieldsAreIgnored() throws Exception
    {
        login(1L);
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = "{\"siteCode\":\"AE\",\"period\":\"2026-11\",\"feeType\":\"FREIGHT\","
                + "\"amount\":100,\"currency\":\"CNY\",\"fxRate\":1,\"allocationFlag\":\"NONE\","
                + "\"remark\":\"forged\",\"requestId\":\"" + rid()
                + "\",\"id\":999,\"costId\":888,\"costNo\":\"HACK:001\",\"status\":\"DONE\","
                + "\"createdAt\":\"2000-01-01T00:00:00\"}";
        ShipmentCostInput in = mapper.readValue(json, ShipmentCostInput.class);

        // record 分量只有契约 §2 的业务字段（含新增 poIds）；主键/单号/状态/时刻没有入口。
        List<String> components = new ArrayList<>();
        for (java.lang.reflect.RecordComponent component : ShipmentCostInput.class.getRecordComponents())
            components.add(component.getName());
        assertEquals(List.of("asnId", "siteCode", "period", "feeType", "amount", "currency", "fxRate",
                "allocationFlag", "remark", "requestId", "poIds"), components);

        ShipmentCostResult created = service.create(in);
        assertTrue(created.costId() > 0 && created.costId() != 999, "主键服务端生成");
        assertTrue(created.costNo().startsWith("SC:AE:"), created.costNo());
        assertFalse(created.costNo().contains("HACK"));
        Map<String, Object> row = jdbc.queryForMap("select cost_no,remark from log_shipment_cost where id=?",
                created.costId());
        assertEquals(created.costNo(), row.get("cost_no"));
        assertEquals("forged", row.get("remark"));
    }

    // ------------------------------------------------------- 幂等

    @Test
    void idempotencyReplaysConflictsPendingAndResult()
    {
        login(1L);
        long asn = newAsn("ASN-AE-1", "AE");
        String requestId = rid();
        ShipmentCostInput in = new ShipmentCostInput(asn, "AE", "2026-11", "FREIGHT",
                new BigDecimal("120.5"), "CNY", BigDecimal.ONE, "FULL", "重放测试", requestId);

        ShipmentCostResult first = service.create(in);
        int costs = count("log_shipment_cost");
        int audits = count("noon_m4_inventory_audit");

        // 1) 同键同内容 → replayed=true，返回首次结果，零写入。
        ShipmentCostResult replay = service.create(in);
        assertTrue(replay.replayed());
        assertEquals(first.costId(), replay.costId());
        assertEquals(first.costNo(), replay.costNo());
        assertEquals(costs, count("log_shipment_cost"), "重放不得重复登记");
        assertEquals(costs, count("noon_m4_shipment_cost_request"));
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不得写审计");

        // 2) 同键改内容 → 400。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(asn, "AE", "2026-11", "FREIGHT",
                        new BigDecimal("121.5"), "CNY", BigDecimal.ONE, "FULL", "重放测试", requestId)));
        assertEquals("requestId 对应的费用登记内容与原请求不一致", changed.getMessage());
        assertEquals(costs, count("log_shipment_cost"));

        // 3) 同键换站点 → 400（同键不能跨站复用）。
        IllegalArgumentException crossSite = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(null, "SA", "2026-11", "FREIGHT",
                        new BigDecimal("120.5"), "CNY", BigDecimal.ONE, "FULL", "重放测试", requestId)));
        assertTrue(crossSite.getMessage().contains("其他站点"), crossSite.getMessage());
        assertEquals(costs, count("log_shipment_cost"));

        // 4) 手工造 PENDING 占位（cost_id=0）：同键同内容 → 400「正在处理中」；resultOf → null。
        String pendingKey = rid();
        ShipmentCostInput pendingIn = new ShipmentCostInput(null, "AE", "2026-11", "DUTY",
                new BigDecimal("60"), "CNY", BigDecimal.ONE, null, null, pendingKey);
        jdbc.update("insert into noon_m4_shipment_cost_request(request_id,cost_id,site_code,operator_id,"
                        + "content_fingerprint,status) values (?,?,?,?,?,'PENDING')",
                pendingKey, 0, "AE", 1L, fingerprintOf(pendingIn));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.create(pendingIn));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());
        assertEquals(0L, jdbc.queryForObject("select cost_id from noon_m4_shipment_cost_request "
                + "where request_id=?", Long.class, pendingKey), "PENDING 期 cost_id=0 占位");
        assertNull(service.resultOf(pendingKey), "PENDING 无结果可返回");
        assertEquals(costs, count("log_shipment_cost"));

        // 5) resultOf：DONE 命中（replayed=true、只读）；非法/不存在返回 null。
        int before = count("log_shipment_cost");
        ShipmentCostResult result = service.resultOf(requestId);
        assertNotNull(result);
        assertTrue(result.replayed(), "resultOf replayed 恒 true");
        assertEquals(first.costId(), result.costId());
        assertEquals(first.costNo(), result.costNo());
        assertEquals(before, count("log_shipment_cost"), "resultOf 只读");
        assertNull(service.resultOf(null));
        assertNull(service.resultOf(""));
        assertNull(service.resultOf("short"));
        assertNull(service.resultOf("r-" + UUID.randomUUID()));
    }

    // ------------------------------------------------------- 授权

    @Test
    void authorizationIsSiteScopedAndPrecedesReplay()
    {
        long asnAe = newAsn("ASN-AE-1", "AE");
        long asnSa = newAsn("ASN-SA-1", "SA");

        // 超管建 SA 夹具。
        login(1L);
        String saKey = rid();
        ShipmentCostResult sa = service.create(new ShipmentCostInput(asnSa, "SA", "2026-11", "FREIGHT",
                new BigDecimal("10"), "SAR", new BigDecimal("1.9"), null, "SA 夹具", saKey));

        // 仅 AE 用户。
        login(2L);
        AccessDeniedException crossCreate = assertThrows(AccessDeniedException.class,
                () -> service.create(new ShipmentCostInput(null, "SA", "2026-11", "FREIGHT",
                        new BigDecimal("10"), "SAR", new BigDecimal("1.9"), null, null, rid())));
        assertNotNull(crossCreate.getMessage());
        assertEquals(1, count("log_shipment_cost"), "越权不得登记");

        // 跨站 ASN：AE 登记挂 SA ASN → 400（授权已过，ASN 站点不符）。
        IllegalArgumentException crossAsn = assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(asnSa, "AE", "2026-11", "FREIGHT",
                        new BigDecimal("10"), "CNY", BigDecimal.ONE, null, null, rid())));
        assertTrue(crossAsn.getMessage().contains("不允许跨站关联"), crossAsn.getMessage());

        // 授权先于重放：持 SA 合法 requestId 查/重放都只能 403。
        assertThrows(AccessDeniedException.class, () -> service.resultOf(saKey));
        assertThrows(AccessDeniedException.class,
                () -> service.create(new ShipmentCostInput(asnSa, "SA", "2026-11", "FREIGHT",
                        new BigDecimal("10"), "SAR", new BigDecimal("1.9"), null, "SA 夹具", saKey)));

        // AE 正常登记；list/result/get 均按站点。
        String aeKey = rid();
        ShipmentCostResult ae = service.create(new ShipmentCostInput(asnAe, "AE", "2026-11", "FREIGHT",
                new BigDecimal("20"), "AED", new BigDecimal("3.6725"), null, "AE 夹具", aeKey));
        PageResult<ShipmentCostRow> aeList = service.list(null, 0, 10, null);
        assertEquals(1, aeList.total());
        assertEquals("AE", aeList.rows().get(0).siteCode());
        assertTrue(service.resultOf(aeKey).replayed());
        assertThrows(AccessDeniedException.class, () -> service.get(sa.costId()));
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 10, null));

        // 撤销 AE 授权：create / resultOf / get / 指定站点 list 全部 403；未指定站点零行。
        jdbc.update("delete from sys_user_site where user_id=2 and site_code='AE'");
        String revokedKey = rid();
        assertThrows(AccessDeniedException.class,
                () -> service.create(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT",
                        new BigDecimal("1"), "CNY", BigDecimal.ONE, null, null, revokedKey)));
        assertThrows(AccessDeniedException.class, () -> service.resultOf(aeKey));
        assertThrows(AccessDeniedException.class, () -> service.get(ae.costId()));
        assertThrows(AccessDeniedException.class, () -> service.list("AE", 0, 10, null));
        assertEquals(0, service.list(null, 0, 10, null).total(), "空授权必须零行");
        assertEquals(2, count("log_shipment_cost"), "撤销后不得新增（SA 夹具 + AE 单据）");

        // 无任何站点用户：create 403、列表零行。
        login(3L);
        assertEquals(0, service.list(null, 0, 10, null).total());
        assertThrows(AccessDeniedException.class,
                () -> service.create(new ShipmentCostInput(null, "AE", "2026-11", "FREIGHT",
                        new BigDecimal("1"), "CNY", BigDecimal.ONE, null, null, rid())));

        // 非法站点格式 400（不是越权）。
        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.list("XX", 0, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new ShipmentCostInput(null, "XX", "2026-11", "FREIGHT",
                        new BigDecimal("1"), "CNY", BigDecimal.ONE, null, null, rid())));
    }

    // ------------------------------------------------------- 列表 / 详情

    @Test
    void listPaginationFiltersAndDetail()
    {
        login(1L);
        long asn1 = newAsn("ASN-AE-1", "AE");
        long asn2 = newAsn("ASN-AE-2", "AE");
        long asnSa = newAsn("ASN-SA-1", "SA");

        ShipmentCostResult a1 = service.create(new ShipmentCostInput(asn1, "AE", "2026-11", "FREIGHT",
                new BigDecimal("101"), "CNY", BigDecimal.ONE, null, "a1", rid()));
        ShipmentCostResult a2 = service.create(new ShipmentCostInput(asn1, "AE", "2026-10", "DUTY",
                new BigDecimal("102"), "CNY", BigDecimal.ONE, "PARTIAL", "a2", rid()));
        ShipmentCostResult a3 = service.create(new ShipmentCostInput(asn2, "AE", "2026-11", "FREIGHT",
                new BigDecimal("103"), "AED", new BigDecimal("3.6725"), null, "a3", rid()));
        ShipmentCostResult s1 = service.create(new ShipmentCostInput(asnSa, "SA", "2026-11", "FREIGHT",
                new BigDecimal("104"), "SAR", new BigDecimal("1.9"), null, "s1", rid()));

        PageResult<ShipmentCostRow> all = service.list(null, 0, 10, null);
        assertEquals(4, all.total());
        assertEquals(4, all.rows().size());
        // 列表倒序（id DESC），最近登记的 SA 在首位。
        assertEquals(s1.costId(), all.rows().get(0).id());

        // 过滤：feeType / asnId / period / 组合
        assertEquals(3, service.list(null, 0, 10, new CostQuery("FREIGHT", null, null)).total());
        assertEquals(2, service.list(null, 0, 10, new CostQuery(null, asn1, null)).total());
        assertEquals(1, service.list(null, 0, 10, new CostQuery(null, null, "2026-10")).total());
        assertEquals(1, service.list(null, 0, 10, new CostQuery("FREIGHT", asn2, "2026-11")).total());
        assertEquals(0, service.list(null, 0, 10, new CostQuery("LABEL", null, null)).total());
        // 单站
        assertEquals(3, service.list("AE", 0, 10, null).total());
        assertEquals(1, service.list("sa", 0, 10, null).total(), "站点参数大小写归一");

        // 分页：total 不受 LIMIT 影响；翻页不重复不遗漏。
        List<Long> expected = new ArrayList<>();
        for (ShipmentCostRow row : all.rows()) expected.add(row.id());
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += 2)
        {
            PageResult<ShipmentCostRow> page = service.list(null, offset, 2, null);
            assertEquals(4, page.total());
            assertEquals(Math.min(2, expected.size() - offset), page.rows().size());
            seen.addAll(page.rows().stream().map(ShipmentCostRow::id).toList());
        }
        assertEquals(expected, seen, "翻页无重复无遗漏");

        // 详情：全部字段 + 授权。
        ShipmentCostDetail detail = service.get(a2.costId());
        assertEquals(a2.costNo(), detail.costNo());
        assertEquals("AE", detail.siteCode());
        assertEquals("2026-10", detail.period());
        assertEquals("DUTY", detail.feeType());
        assertMoney("102.0000", detail.amount(), "金额 4 位");
        assertEquals("CNY", detail.currency());
        assertEquals("PARTIAL", detail.allocationFlag());
        assertEquals("a2", detail.remark());
        assertNotNull(detail.createdAt());
        assertThrows(IllegalArgumentException.class, () -> service.get(9999L));
        assertThrows(IllegalArgumentException.class, () -> service.get(null));

        // 非法查询参数 400。
        assertThrows(IllegalArgumentException.class, () -> service.list(null, -1, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, 0, 10, new CostQuery("OTHER", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, 0, 10, new CostQuery(null, 0L, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.list(null, 0, 10, new CostQuery(null, null, "2026-13")));

        // 仅 AE 用户：只看到 AE 的三条。
        login(2L);
        assertEquals(3, service.list(null, 0, 10, null).total());
        assertThrows(AccessDeniedException.class, () -> service.get(s1.costId()));
    }

    // ------------------------------------------------------- 并发 / 唯一键

    @Test
    void concurrentSameRequestIdCreatesOnlyOneCost() throws Exception
    {
        login(1L);
        ShipmentCostInput in = new ShipmentCostInput(null, "AE", "2026-11", "CUSTOMS",
                new BigDecimal("50"), "CNY", BigDecimal.ONE, null, "并发", rid());

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
                ShipmentCostResult result = service.create(in);
                return result.replayed() ? "REPLAYED" : "FRESH";
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
        assertEquals(1, count("log_shipment_cost"), "只落一行");
        assertEquals(1, count("noon_m4_inventory_audit"), "只写一次 CREATE 审计");
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_shipment_cost_request",
                String.class));
    }

    @Test
    void costNoUniqueKeyBlocksDuplicate()
    {
        jdbc.update("insert into log_shipment_cost(cost_no,site_code,fee_type,amount,currency,fx_rate,"
                + "allocation_flag,created_at) values ('SC:AE:DUP00001','AE','FREIGHT',1,'CNY',1,'NONE',"
                + "current_timestamp(3))");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into log_shipment_cost"
                + "(cost_no,site_code,fee_type,amount,currency,fx_rate,allocation_flag,created_at) "
                + "values ('SC:AE:DUP00001','AE','FREIGHT',1,'CNY',1,'NONE',current_timestamp(3))"),
                "uk_cost_no 必须唯一");
    }

    // ------------------------------------------------------------------ helpers

    private ShipmentCostInput input(String site, String feeType, String amount, String currency, String fx,
                                    String allocation, String period, String remark)
    {
        return new ShipmentCostInput(null, site, period, feeType,
                amount == null ? null : new BigDecimal(amount), currency,
                fx == null ? null : new BigDecimal(fx), allocation, remark, rid());
    }

    private void assertBad(ShipmentCostInput in)
    {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> service.create(in));
        assertNotNull(rejected.getMessage());
    }

    /** 与 Service 完全同口径的指纹（PENDING 夹具用）。 */
    private static String fingerprintOf(ShipmentCostInput in)
    {
        String site = ShipmentCostRules.siteCode("siteCode", in.siteCode());
        String feeType = ShipmentCostRules.feeType(in.feeType());
        BigDecimal amount = ShipmentCostRules.amount(in.amount());
        String currency = ShipmentCostRules.currency(in.currency());
        BigDecimal fx = ShipmentCostRules.fxRate(in.fxRate(), currency);
        String period = ShipmentCostRules.period(in.period());
        String allocation = ShipmentCostRules.allocationFlag(in.allocationFlag());
        String remark = ShipmentCostRules.remark(in.remark());
        Long asnId = ShipmentCostRules.asnId(in.asnId());
        return ShipmentCostRules.createFingerprint(asnId, site, period, feeType, amount, currency, fx,
                allocation, remark);
    }

    private long newAsn(String asnNo, String site)
    {
        jdbc.update("insert into log_asn(asn_no,site_code,warehouse_code,status,source) "
                + "values (?,?,?,'DRAFT','MANUAL')", asnNo, site, "WH-" + site);
        return jdbc.queryForObject("select id from log_asn where asn_no=?", Long.class, asnNo);
    }

    private long newPo(String orderNo, String site)
    {
        jdbc.update("insert into pur_order(order_no,site_code,status) values (?,?,'DRAFT')", orderNo, site);
        return jdbc.queryForObject("select id from pur_order where order_no=?", Long.class, orderNo);
    }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void assertMoney(String expected, Object actual)
    {
        assertMoney(expected, actual, null);
    }

    private static void assertMoney(String expected, Object actual, String message)
    {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                (message == null ? "" : message + ": ") + expected + " vs " + actual);
    }

    /** 一次性幂等键（模拟前端 crypto.randomUUID()），长度 38 位，符合 8–64 与 [A-Za-z0-9_-]。 */
    private static String rid()
    {
        return "r-" + UUID.randomUUID();
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
     * H2 宽松建表：列名与 {@code docs/noon-erp-schema-v0.5.sql}（log_shipment_cost / log_asn）及
     * {@code yx-erp/sql/noon_m4_shipment_cost.sql}（幂等表）、{@code noon_m4_inventory.sql}（共享审计表）对齐；
     * 不含 CHECK（CHECK/真库并发由 {@link MySqlShipmentCostIT} 验证）。
     */
    private void createTables()
    {
        jdbc.execute("create table log_shipment_cost (id bigint auto_increment primary key, "
                + "cost_no varchar(64) not null, asn_id bigint, site_code varchar(16) not null, "
                + "period char(7), fee_type varchar(32) not null, amount decimal(18,4) not null, "
                + "currency char(3) not null, fx_rate decimal(18,8), "
                + "allocation_flag varchar(16) not null default 'NONE', remark varchar(512), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_cost_no unique(cost_no))");
        jdbc.execute("create table log_asn (id bigint auto_increment primary key, asn_no varchar(64) not null, "
                + "site_code varchar(16) not null, warehouse_code varchar(64) not null, "
                + "status varchar(24) not null default 'DRAFT', source varchar(24) not null default 'MANUAL', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_asn_no unique(asn_no))");
        // 采购单主表（采购单多选校验：存在 + 同站点；仅取最小列集）。
        jdbc.execute("create table pur_order (id bigint auto_increment primary key, "
                + "order_no varchar(64) not null, site_code varchar(16) not null, "
                + "status varchar(24) not null default 'DRAFT', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_pur_no unique(order_no))");
        // 费用 ↔ 采购单多对多关联表。
        jdbc.execute("create table log_shipment_cost_po (cost_id bigint not null, po_id bigint not null, "
                + "site_code varchar(16) not null, created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint pk_cost_po primary key (cost_id, po_id))");
        jdbc.execute("create table noon_m4_shipment_cost_request (request_id varchar(64) not null primary key, "
                + "cost_id bigint not null, site_code varchar(16) not null, operator_id bigint, "
                + "content_fingerprint char(64) not null, status varchar(16) not null default 'PENDING', "
                + "result_json varchar(10000), created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m4_inventory_audit (id bigint auto_increment primary key, "
                + "biz_type varchar(32) not null, biz_id varchar(64), action varchar(32) not null, "
                + "detail varchar(1024), operator_id bigint, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        // 契约 §0 的不动对象（只做零写入断言，列取最小集）。
        jdbc.execute("create table inv_batch (id bigint auto_increment primary key, batch_no varchar(64) not null "
                + "unique, qty_remaining int not null default 0)");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint not null, "
                + "warehouse_id bigint not null, qty_available int not null default 0)");
        jdbc.execute("create table inv_stock_move (id bigint auto_increment primary key, move_no varchar(64) not null "
                + "unique, qty_delta int not null default 0)");
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null unique, amount decimal(18,4) not null default 0)");
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config
    {
        @Bean
        DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
        ShipmentCostAudit shipmentCostAudit(JdbcTemplate jdbcTemplate) { return new ShipmentCostAudit(jdbcTemplate); }

        @Bean
        ShipmentCostService shipmentCostService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard,
                                                ShipmentCostAudit audit)
        {
            return new ShipmentCostService(jdbcTemplate, guard, audit);
        }
    }
}
