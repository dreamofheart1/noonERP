package com.yx.noon.intake;

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
import com.yx.noon.intake.IntakeService.OrderInput;
import com.yx.noon.intake.IntakeService.Result;
import com.yx.noon.intake.IntakeService.ReturnInput;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOON-20261121-RETURN 独立验证（首次复核修订）：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper。
 * 不启动 MySQL/Redis/生产连接，任何人执行 mvn test 即可复跑。
 */
class IntakeServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private IntakeService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        // 站点授权所需最小表（与 SiteAuthorizationTest 同源）
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0'), (3,'0','0')");
        String siteMigration = Files.readString(Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String statement : h2Ddl(siteMigration).split(";"))
            if (!statement.isBlank()) jdbc.execute(statement);

        // 待核对层（H2 兼容建表；CHECK 真实性由隔离 MySQL IT 用原始 SQL 文件验证）。
        jdbc.execute("create table noon_intake_review (id bigint auto_increment primary key, "
                + "review_no varchar(64) not null, entity_type varchar(16) not null, "
                + "site_code_claimed varchar(16), order_no_claimed varchar(64), source_return_no varchar(128), "
                + "source_event_id varchar(128), raw_payload varchar(4000), reason_code varchar(48) not null, "
                + "reason_detail varchar(512), status varchar(24) not null default 'NEEDS_REVIEW', "
                + "resolution_biz_key varchar(160), resolved_by bigint, resolved_at timestamp, "
                + "created_by bigint, created_at timestamp not null default current_timestamp, "
                + "unique key(review_no))");
        jdbc.execute("create table noon_intake_audit (id bigint auto_increment primary key, "
                + "audit_no varchar(64) not null, review_no varchar(64), entity_type varchar(16) not null, "
                + "action varchar(32) not null, actor_id bigint not null, act_at timestamp not null, "
                + "detail_json varchar(4000), unique key(audit_no))");

        // 系统侧 webhook 事件留痕：来源可核验的唯一佐证表。
        jdbc.execute("create table int_webhook_event (id bigint auto_increment primary key, "
                + "event_id varchar(128) not null, event_type varchar(128) not null, site_code varchar(16), "
                + "payload_raw varchar(4000), received_at timestamp not null, status varchar(24) not null, "
                + "unique key(event_id))");

        // 正式表最小结构（列与 IntakeService SQL 对齐）。
        jdbc.execute("create table sal_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "site_code varchar(16) not null, store_id bigint not null, partner_sku varchar(128) not null, "
                + "sku_id bigint, qty int not null, gross_amount decimal(18,4) not null, currency char(3) not null, "
                + "source_channel varchar(24), import_fingerprint varchar(64), status varchar(32), "
                + "state_version int not null default 1, raw_payload varchar(4000), "
                + "created_at timestamp, updated_at timestamp, unique key(order_no, site_code))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, sal_order_id bigint not null, "
                + "order_no varchar(64) not null, platform_item_no varchar(64), sku_id bigint not null, "
                + "qty int not null, qty_delivered int not null default 0, qty_returned int not null default 0, "
                + "qty_cancelled int not null default 0, unit_price decimal(18,4) not null)");
        jdbc.execute("create table sal_return (id bigint auto_increment primary key, return_no varchar(64) not null, "
                + "biz_return_key varchar(160) not null, source_return_no varchar(128), order_no varchar(64) not null, "
                + "site_code varchar(16), sku_id bigint, qty int not null, sal_order_item_id bigint, "
                + "status varchar(24), raw_payload varchar(4000), created_at timestamp, "
                + "unique key(biz_return_key), unique key(site_code,order_no,source_return_no))");

        // 本地主数据映射（三次复核意见 3）：store/site/sku/partner_sku 必须真实存在且 ACTIVE。
        jdbc.execute("create table md_store (id bigint auto_increment primary key, store_no varchar(64) not null, "
                + "partner_id bigint not null, status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_site (id bigint auto_increment primary key, site_code varchar(16) not null, "
                + "store_id bigint not null, currency char(3) not null, status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "status varchar(16) not null default 'ACTIVE')");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, "
                + "partner_sku varchar(128) not null, sku_id bigint not null, site_code varchar(16) not null, "
                + "business_model varchar(24) not null default 'noon', offer_code varchar(128), barcode varchar(128), "
                + "noon_sku varchar(128), image_url varchar(512), live_status boolean, offer_issues varchar(4000), synced_at timestamp(3), "
                + "status varchar(16) not null default 'ACTIVE')");
        jdbc.update("insert into md_store(id,store_no,partner_id,status) values (7,'STORE-AE',1,'ACTIVE')");
        jdbc.update("insert into md_site(id,site_code,store_id,currency,status) values "
                + "(1,'AE',7,'AED','ACTIVE'),(2,'SA',7,'SAR','ACTIVE')");
        jdbc.update("insert into md_sku(id,sku_code,status) values (11,'SKU-11','ACTIVE')");
        jdbc.update("insert into md_partner_sku(id,partner_sku,sku_id,site_code,business_model,status) values "
                + "(1,'SKU',11,'AE','noon','ACTIVE'),(2,'SKU',11,'SA','noon','ACTIVE')");

        service = context.getBean(IntakeService.class);
        login(1L); // 默认超管
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    /** 把若依站点迁移脚本转成 H2(MySQL 模式)可执行：仅去 MySQL 专属字符集后缀。 */
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

    /** 种一条系统侧 webhook 留痕（可指定类型/报文，用于反例）。 */
    private void seedWebhook(String eventId, String site, String type, String payload, String status)
    {
        jdbc.update("insert into int_webhook_event(event_id,event_type,site_code,payload_raw,received_at,status) "
                + "values (?,?,?,?,current_timestamp,?)", eventId, type, site, payload, status);
    }

    /** 订单正向事件：payload 与入参报文一致，业务字段可被服务端逐项核验。 */
    private void seedOrderEvent(String eventId, String site, String orderNo)
    {
        seedWebhook(eventId, site, "ORDER", orderPayload(orderNo, 7L, 11L, "88.5000", 1, "AED"), "RECEIVED");
    }

    /** 退货正向事件：payload 含订单号、平台退货号、数量、父订单行。 */
    private void seedReturnEvent(String eventId, String site, String orderNo, String returnNo, int qty, long itemId)
    {
        seedWebhook(eventId, site, "RETURN", returnPayload(orderNo, returnNo, qty, itemId), "PROCESSED");
    }

    private static String orderPayload(String no, long store, long sku, String amount, int qty, String currency)
    {
        return orderPayload(no, store, sku, amount, qty, currency, "SKU");
    }

    private static String orderPayload(String no, long store, long sku, String amount, int qty,
            String currency, String partnerSku)
    {
        return "{\"order_no\":\"" + no + "\",\"store_id\":" + store + ",\"sku_id\":" + sku
                + ",\"partner_sku\":\"" + partnerSku + "\",\"gross_amount\":\"" + amount
                + "\",\"qty\":" + qty + ",\"currency\":\"" + currency + "\"}";
    }

    private static String returnPayload(String orderNo, String returnNo, int qty, long itemId)
    {
        return "{\"order_no\":\"" + orderNo + "\",\"return_no\":\"" + returnNo
                + "\",\"qty\":" + qty + ",\"item_id\":" + itemId + "}";
    }

    /** 退货报文带事件元数据字段：核心业务字段不变，仅多一个元数据字段（四次复核）。 */
    private static String returnPayloadWithMeta(String orderNo, String returnNo, int qty, long itemId, String status)
    {
        return "{\"order_no\":\"" + orderNo + "\",\"return_no\":\"" + returnNo
                + "\",\"qty\":" + qty + ",\"item_id\":" + itemId
                + ",\"event_status\":\"" + status + "\"}";
    }

    private long seedOrder(String orderNo, String site)
    {
        jdbc.update("insert into sal_order(order_no,site_code,store_id,partner_sku,qty,gross_amount,currency,status,state_version,created_at) "
                + "values (?,?,?,?,?,?,?,?,1,current_timestamp)", orderNo, site, 1L, "SKU", 10,
                new java.math.BigDecimal("100.0000"), "AED", "IMPORTED");
        return jdbc.queryForObject("select id from sal_order where order_no=? and site_code=?", Long.class, orderNo, site);
    }

    private long seedItem(long orderId, String orderNo)
    {
        jdbc.update("insert into sal_order_item(sal_order_id,order_no,platform_item_no,sku_id,qty,qty_delivered,unit_price) "
                + "values (?,?,?,?,10,10,10)", orderId, orderNo, "LINE-1", 1L);
        return jdbc.queryForObject("select id from sal_order_item where sal_order_id=? and platform_item_no='LINE-1'",
                Long.class, orderId);
    }

    /** 合法订单：报文与系统侧事件一致，调用方自报指纹故意填错也不影响（服务端现算）。 */
    private static OrderInput order(String site, String no, String eventId)
    {
        return new OrderInput(site, no, "WEBHOOK", eventId, "CALLER-LIES", "SKU",
                7L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload(no, 7L, 11L, "88.5000", 1, "AED"));
    }

    private static ReturnInput ret(String site, String orderNo, String eventId, String sourceReturnNo, Long itemId)
    {
        return new ReturnInput(site, orderNo, eventId, sourceReturnNo, itemId, 1,
                returnPayload(orderNo, sourceReturnNo, 1, itemId == null ? 0L : itemId));
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    // ---------- 正向 ----------

    @Test
    void postedOrderWithVerifiedSourceAndMasterData()
    {
        seedOrderEvent("EVT-1", "AE", "O-1");
        Result r = service.postOrder(order("AE", "O-1", "EVT-1"));
        assertTrue(r.posted());
        assertEquals("POSTED", r.reasonCode());
        assertEquals(1, count("sal_order"));
        assertEquals(0, count("noon_intake_review"));
        assertEquals(1, count("noon_intake_audit"));
        // 正式表无占位：门店/商品/金额为真实值，指纹为服务端 64 位摘要而非自报字面值。
        assertEquals(7L, jdbc.queryForObject("select store_id from sal_order where order_no='O-1'", Long.class));
        assertEquals(11L, jdbc.queryForObject("select sku_id from sal_order where order_no='O-1'", Long.class));
        assertEquals(0, new BigDecimal("88.5000").compareTo(
                jdbc.queryForObject("select gross_amount from sal_order where order_no='O-1'", BigDecimal.class)));
        String fp = jdbc.queryForObject("select import_fingerprint from sal_order where order_no='O-1'", String.class);
        assertEquals(64, fp.length(), "内容指纹应为服务端 SHA-256");
        assertNotEquals("CALLER-LIES", fp, "不得采信调用方自报指纹");
    }

    @Test
    void postedReturnWithTrustedParentAndVerifiedSource()
    {
        long orderId = seedOrder("O-2", "AE");
        long itemId = seedItem(orderId, "O-2");
        seedReturnEvent("EVT-2", "AE", "O-2", "SR-100", 1, itemId);
        Result r = service.postReturn(ret("AE", "O-2", "EVT-2", "SR-100", itemId));
        assertTrue(r.posted());
        assertEquals(1, count("sal_return"));
        // 站点与商品均取父记录真实值，不取请求体、不写 NULL。
        assertEquals("AE", jdbc.queryForObject("select site_code from sal_return where id=?", String.class, r.entityId()));
        assertEquals(1L, jdbc.queryForObject("select sku_id from sal_return where id=?", Long.class, r.entityId()));
        assertEquals(0, count("noon_intake_review"));
    }

    // ---------- 反向：站点授权 ----------

    @Test
    void unauthorizedSiteIsDeniedNotQuarantined()
    {
        login(2L); // 无站点授权
        assertThrows(AccessDeniedException.class, () -> service.postOrder(order("AE", "O-3", "EVT-3")));
        assertEquals(0, count("sal_order"));
        assertEquals(0, count("noon_intake_review"));
    }

    @Test
    void grantedSiteCannotPostOtherSite()
    {
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) values (2,'AE',1,current_timestamp)");
        login(2L);
        assertThrows(AccessDeniedException.class, () -> service.postOrder(order("SA", "O-4", "EVT-4")));
        assertEquals(0, count("sal_order"));
    }

    // ---------- 反向：来源可核验（回应意见 2） ----------

    @Test
    void orderWithoutEventIdIsQuarantined()
    {
        Result r = service.postOrder(order("AE", "O-5", null));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNIDENTIFIABLE", r.reasonCode());
        assertEquals(0, count("sal_order"));
        assertEquals(1, count("noon_intake_review"));
        assertNull(jdbc.queryForObject("select resolution_biz_key from noon_intake_review where review_no=?",
                Object.class, r.reviewNo()));
    }

    @Test
    void orderWithUncorroboratedEventIdIsQuarantined()
    {
        // 事件号非空，但系统侧无 webhook 留痕 —— 不得当已验证可靠。
        Result r = service.postOrder(order("AE", "O-5B", "EVT-NEVER-RECEIVED"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
        assertEquals(1, count("noon_intake_review"));
    }

    @Test
    void orderWithFailedWebhookEventIsQuarantined()
    {
        seedWebhook("EVT-FAILED", "AE", "ORDER", orderPayload("O-5C", 7L, 11L, "88.5000", 1, "AED"), "FAILED");
        Result r = service.postOrder(order("AE", "O-5C", "EVT-FAILED"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void returnWithUncorroboratedEventIdIsQuarantined()
    {
        long orderId = seedOrder("O-5D", "AE");
        long itemId = seedItem(orderId, "O-5D");
        Result r = service.postReturn(ret("AE", "O-5D", "EVT-NO-WEBHOOK", "SR-5D", itemId));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_return"));
    }

    // ---------- 反向：主数据完整性（回应意见 1） ----------

    @Test
    void orderWithoutMasterDataIsQuarantinedNoPlaceholderInFormalTable()
    {
        seedOrderEvent("EVT-6", "AE", "O-6");
        OrderInput missing = new OrderInput("AE", "O-6", "WEBHOOK", "EVT-6", null, "SKU",
                null, null, null, 1, "AED", "{\"raw\":1}");
        Result r = service.postOrder(missing);
        assertFalse(r.posted());
        assertEquals("MASTER_DATA_INCOMPLETE", r.reasonCode());
        assertEquals(0, count("sal_order"), "缺主数据不得写占位到正式表");
        assertEquals(1, count("noon_intake_review"));
    }

    @Test
    void zeroAmountWithVerifiedEvidenceIsPosted()
    {
        // 合法零金额（全折扣）不是缺失：系统侧 payload 明确为 0 且与入参一致即可入账。
        String payload = orderPayload("O-6B", 7L, 11L, "0", 1, "AED");
        seedWebhook("EVT-6B", "AE", "ORDER", payload, "RECEIVED");
        OrderInput zero = new OrderInput("AE", "O-6B", "WEBHOOK", "EVT-6B", null, "SKU",
                7L, 11L, BigDecimal.ZERO, 1, "AED", payload);
        Result r = service.postOrder(zero);
        assertTrue(r.posted(), "有可信证据的零金额不应被当作缺失");
        assertEquals(0, jdbc.queryForObject("select gross_amount from sal_order where order_no='O-6B'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals(0, count("noon_intake_review"));
    }

    @Test
    void missingAmountIsQuarantinedEvenWithEvent()
    {
        seedOrderEvent("EVT-6C", "AE", "O-6C");
        OrderInput missing = new OrderInput("AE", "O-6C", "WEBHOOK", "EVT-6C", null, "SKU",
                7L, 11L, null, 1, "AED", orderPayload("O-6C", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(missing);
        assertFalse(r.posted());
        assertEquals("MASTER_DATA_INCOMPLETE", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void forgedAmountAgainstEventIsQuarantined()
    {
        seedOrderEvent("EVT-6D", "AE", "O-6D");
        OrderInput forged = new OrderInput("AE", "O-6D", "WEBHOOK", "EVT-6D", null, "SKU",
                7L, 11L, new BigDecimal("999.0000"), 1, "AED",
                orderPayload("O-6D", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(forged);
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    // ---------- 反向：可信父记录 ----------

    @Test
    void orphanReturnIsQuarantinedParentMissing()
    {
        Result r = service.postReturn(ret("AE", "O-7", "EVT-7", "SR-7", 999999L));
        assertFalse(r.posted());
        assertEquals("PARENT_MISSING", r.reasonCode());
        assertEquals(0, count("sal_return"));
        assertEquals(1, count("noon_intake_review"));
    }

    @Test
    void crossSiteParentIsQuarantinedSiteMismatch()
    {
        long orderId = seedOrder("O-8", "SA");
        long itemId = seedItem(orderId, "O-8");
        Result r = service.postReturn(ret("AE", "O-8", "EVT-8", "SR-8", itemId));
        assertFalse(r.posted());
        assertEquals("SITE_MISMATCH", r.reasonCode());
        assertEquals(0, count("sal_return"));
    }

    @Test
    void returnOrderNoMismatchWithParentIsQuarantined()
    {
        long orderId = seedOrder("O-9", "AE");
        long itemId = seedItem(orderId, "O-9");
        Result r = service.postReturn(ret("AE", "OTHER-ORDER", "EVT-9", "SR-9", itemId));
        assertFalse(r.posted());
        assertEquals("ITEM_PARENT_MISMATCH", r.reasonCode());
        assertEquals(0, count("sal_return"));
    }

    @Test
    void returnWithoutSourceNumberIsQuarantined()
    {
        long orderId = seedOrder("O-9B", "AE");
        long itemId = seedItem(orderId, "O-9B");
        seedReturnEvent("EVT-9B", "AE", "O-9B", "SR-9B", 1, itemId);
        Result r = service.postReturn(ret("AE", "O-9B", "EVT-9B", null, itemId));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNIDENTIFIABLE", r.reasonCode());
        assertEquals(0, count("sal_return"));
    }

    // ---------- 幂等 / 冲突（回应意见 2） ----------

    @Test
    void duplicateOrderWithSameContentIsIdempotentAck()
    {
        seedOrderEvent("EVT-10", "AE", "O-10");
        service.postOrder(order("AE", "O-10", "EVT-10"));
        Result again = service.postOrder(order("AE", "O-10", "EVT-10"));
        assertEquals("ALREADY_POSTED", again.reasonCode());
        assertEquals(1, count("sal_order"));
        assertEquals(0, count("noon_intake_review"));
    }

    @Test
    void sameOrderNumberWithDifferentTrustedContentIsConflictNotAck()
    {
        // 第二条独立可信事件佐证同一订单号但数量/金额不同 → ACK 层必须识别为冲突。
        seedOrderEvent("EVT-11", "AE", "O-11");
        Result first = service.postOrder(order("AE", "O-11", "EVT-11"));
        assertTrue(first.posted());
        String changedPayload = orderPayload("O-11", 7L, 11L, "999.0000", 5, "AED");
        seedWebhook("EVT-11B", "AE", "ORDER", changedPayload, "RECEIVED");
        Result conflict = service.postOrder(new OrderInput("AE", "O-11", "WEBHOOK", "EVT-11B", null, "SKU",
                7L, 11L, new BigDecimal("999.0000"), 5, "AED", changedPayload));
        assertFalse(conflict.posted(), "同号不同可信内容不得 ACK");
        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertEquals(1, count("sal_order"), "冲突不得覆盖已入账订单");
        assertEquals(1, count("noon_intake_review"));
        assertEquals(first.entityId(), jdbc.queryForObject(
                "select id from sal_order where order_no='O-11' and site_code='AE'", Long.class));
    }

    @Test
    void callerChangingQtyAgainstEventPayloadIsUnverified()
    {
        // 报文未变但调用方偷改数量：证据绑定阶段即拒绝，不可能被误判为重传（回应意见 3）。
        seedOrderEvent("EVT-11C", "AE", "O-11C");
        OrderInput tampered = new OrderInput("AE", "O-11C", "WEBHOOK", "EVT-11C", null, "SKU",
                7L, 11L, new BigDecimal("88.5000"), 9, "AED",
                orderPayload("O-11C", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(tampered);
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void duplicateReturnWithSameContentIsIdempotentAck()
    {
        long orderId = seedOrder("O-12", "AE");
        long itemId = seedItem(orderId, "O-12");
        seedReturnEvent("EVT-12", "AE", "O-12", "SR-12", 1, itemId);
        Result first = service.postReturn(ret("AE", "O-12", "EVT-12", "SR-12", itemId));
        Result second = service.postReturn(ret("AE", "O-12", "EVT-12", "SR-12", itemId));
        assertTrue(first.posted());
        assertTrue(second.posted());
        assertEquals("ALREADY_POSTED", second.reasonCode());
        assertEquals(1, count("sal_return"));
    }

    @Test
    void returnWithSameSourceNumberDifferentQtyIsConflictNotAck()
    {
        long orderId = seedOrder("O-13", "AE");
        long itemId = seedItem(orderId, "O-13");
        seedReturnEvent("EVT-13", "AE", "O-13", "SR-13", 1, itemId);
        service.postReturn(ret("AE", "O-13", "EVT-13", "SR-13", itemId));
        // 另一条可信事件佐证同一退货号、数量不同：证据层通过，ACK 层必须冲突。
        seedWebhook("EVT-13B", "AE", "RETURN", returnPayload("O-13", "SR-13", 9, itemId), "RECEIVED");
        ReturnInput changed = new ReturnInput("AE", "O-13", "EVT-13B", "SR-13", itemId, 9,
                returnPayload("O-13", "SR-13", 9, itemId));
        Result conflict = service.postReturn(changed);
        assertFalse(conflict.posted());
        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertEquals(1, count("sal_return"));
        assertEquals(1, count("noon_intake_review"));
    }

    // ---------- 反向：证据绑定（回应二次复核意见 2） ----------

    @Test
    void borrowedSameSiteEventCannotCorroborateOtherOrder()
    {
        // 事件属于 O-BORROW，却拿来佐证 O-FAKE：同站有效事件也不能借给别的请求。
        seedOrderEvent("EVT-BORROW", "AE", "O-BORROW");
        Result r = service.postOrder(order("AE", "O-FAKE", "EVT-BORROW"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void wrongEventTypeCannotCorroborateOrder()
    {
        seedWebhook("EVT-WRONGTYPE", "AE", "RETURN",
                orderPayload("O-WT", 7L, 11L, "88.5000", 1, "AED"), "RECEIVED");
        Result r = service.postOrder(order("AE", "O-WT", "EVT-WRONGTYPE"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void forgedMasterDataAgainstEventIsQuarantined()
    {
        seedOrderEvent("EVT-FORGE", "AE", "O-FORGE");
        OrderInput forged = new OrderInput("AE", "O-FORGE", "WEBHOOK", "EVT-FORGE", null, "SKU",
                999L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload("O-FORGE", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(forged);
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void emptyPayloadIsQuarantinedNotAck()
    {
        Result r = service.postOrder(new OrderInput("AE", "O-EMPTY", "WEBHOOK", "EVT-EMPTY",
                null, "SKU", 7L, 11L, new BigDecimal("88.5000"), 1, "AED", "   "));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNIDENTIFIABLE", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void concurrentSameReturnPostsOnce() throws Exception
    {
        long orderId = seedOrder("O-14", "AE");
        long itemId = seedItem(orderId, "O-14");
        seedReturnEvent("EVT-14", "AE", "O-14", "SR-14", 1, itemId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            ReturnInput in = ret("AE", "O-14", "EVT-14", "SR-14", itemId);
            // SecurityContext 是 ThreadLocal，工作线程须各自设置登录身份。
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(in); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(in); });
            start.countDown();
            Result a = f1.get();
            Result b = f2.get();
            assertTrue(a.posted());
            assertTrue(b.posted());
            assertEquals(1, count("sal_return"), "并发同源退货只入账一次");
        }
        finally
        {
            pool.shutdownNow();
            login(1L); // 线程切换过 SecurityContext，复位
        }
    }

    @Test
    void concurrentSameOrderPostsOnceWithIdenticalRowId() throws Exception
    {
        seedOrderEvent("EVT-CONC-O", "AE", "O-CONC");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            OrderInput in = order("AE", "O-CONC", "EVT-CONC-O");
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postOrder(in); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postOrder(in); });
            start.countDown();
            Result a = f1.get();
            Result b = f2.get();
            assertTrue(a.posted(), "并发订单首次结果=" + a);
            assertTrue(b.posted(), "并发订单回读结果=" + b);
            assertEquals(a.entityId(), b.entityId(), "ACK 必须返回同一行 ID");
            assertEquals(1, count("sal_order"), "并发同源订单只入账一次");
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    @Test
    void crossSiteSameOrderNumberAckReturnsOwnRowBothInsertionOrders()
    {
        // AE 先插、SA 后插
        seedOrderEvent("EVT-CS-AE1", "AE", "O-CS1");
        seedOrderEvent("EVT-CS-SA1", "SA", "O-CS1");
        Result aeFirst = service.postOrder(order("AE", "O-CS1", "EVT-CS-AE1"));
        Result saSecond = service.postOrder(order("SA", "O-CS1", "EVT-CS-SA1"));
        assertTrue(aeFirst.posted());
        assertTrue(saSecond.posted());
        assertNotEquals(aeFirst.entityId(), saSecond.entityId());
        assertEquals(aeFirst.entityId(),
                service.postOrder(order("AE", "O-CS1", "EVT-CS-AE1")).entityId(), "AE ACK 不得返回 SA 行");
        assertEquals(saSecond.entityId(),
                service.postOrder(order("SA", "O-CS1", "EVT-CS-SA1")).entityId(), "SA ACK 不得返回 AE 行");
        assertEquals(2, count("sal_order"));

        // 反向插入顺序：SA 先插、AE 后插
        seedOrderEvent("EVT-CS-AE2", "AE", "O-CS2");
        seedOrderEvent("EVT-CS-SA2", "SA", "O-CS2");
        Result saFirst = service.postOrder(order("SA", "O-CS2", "EVT-CS-SA2"));
        Result aeSecond = service.postOrder(order("AE", "O-CS2", "EVT-CS-AE2"));
        assertEquals(saFirst.entityId(),
                service.postOrder(order("SA", "O-CS2", "EVT-CS-SA2")).entityId());
        assertEquals(aeSecond.entityId(),
                service.postOrder(order("AE", "O-CS2", "EVT-CS-AE2")).entityId());
        assertEquals(4, count("sal_order"));
    }

    @Test
    void crossSiteSameReturnNumberAckReturnsOwnRowBothInsertionOrders()
    {
        long aeOrder = seedOrder("O-CSR1", "AE");
        long aeItem = seedItem(aeOrder, "O-CSR1");
        long saOrder = seedOrder("O-CSR1", "SA");
        long saItem = seedItem(saOrder, "O-CSR1");
        seedReturnEvent("EVT-CSR-AE1", "AE", "O-CSR1", "SR-CS1", 1, aeItem);
        seedReturnEvent("EVT-CSR-SA1", "SA", "O-CSR1", "SR-CS1", 1, saItem);
        Result ae = service.postReturn(ret("AE", "O-CSR1", "EVT-CSR-AE1", "SR-CS1", aeItem));
        Result sa = service.postReturn(ret("SA", "O-CSR1", "EVT-CSR-SA1", "SR-CS1", saItem));
        assertTrue(ae.posted());
        assertTrue(sa.posted());
        assertNotEquals(ae.entityId(), sa.entityId());
        assertEquals(ae.entityId(),
                service.postReturn(ret("AE", "O-CSR1", "EVT-CSR-AE1", "SR-CS1", aeItem)).entityId());
        assertEquals(sa.entityId(),
                service.postReturn(ret("SA", "O-CSR1", "EVT-CSR-SA1", "SR-CS1", saItem)).entityId());

        long aeOrder2 = seedOrder("O-CSR2", "AE");
        long aeItem2 = seedItem(aeOrder2, "O-CSR2");
        long saOrder2 = seedOrder("O-CSR2", "SA");
        long saItem2 = seedItem(saOrder2, "O-CSR2");
        seedReturnEvent("EVT-CSR-AE2", "AE", "O-CSR2", "SR-CS2", 1, aeItem2);
        seedReturnEvent("EVT-CSR-SA2", "SA", "O-CSR2", "SR-CS2", 1, saItem2);
        Result sa2 = service.postReturn(ret("SA", "O-CSR2", "EVT-CSR-SA2", "SR-CS2", saItem2));
        Result ae2 = service.postReturn(ret("AE", "O-CSR2", "EVT-CSR-AE2", "SR-CS2", aeItem2));
        assertEquals(sa2.entityId(),
                service.postReturn(ret("SA", "O-CSR2", "EVT-CSR-SA2", "SR-CS2", saItem2)).entityId());
        assertEquals(ae2.entityId(),
                service.postReturn(ret("AE", "O-CSR2", "EVT-CSR-AE2", "SR-CS2", aeItem2)).entityId());
        assertEquals(4, count("sal_return"));
    }

    // ---------- 三次复核：站点/Partner SKU/主数据映射/幂等语义 ----------

    @Test
    void eventWithNullSiteCannotCorroborateOrder()
    {
        // 事件站点为空：不能证明属于请求站点，禁止直接正式入账。
        seedWebhook("EVT-NULLSITE", null, "ORDER",
                orderPayload("O-NS", 7L, 11L, "88.5000", 1, "AED"), "RECEIVED");
        Result r = service.postOrder(order("AE", "O-NS", "EVT-NULLSITE"));
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void partnerSkuNotBackedByEventPayloadIsRejected()
    {
        seedOrderEvent("EVT-PS1", "AE", "O-PS1");
        // 报文仍绑定 partner_sku="SKU"，入参却换成 DIFF：证据未绑定该值。
        OrderInput in = new OrderInput("AE", "O-PS1", "WEBHOOK", "EVT-PS1", null, "DIFF",
                7L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload("O-PS1", 7L, 11L, "88.5000", 1, "AED"));
        Result r = service.postOrder(in);
        assertFalse(r.posted());
        assertEquals("SOURCE_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void partnerSkuChangeOnRetryIsConflictNotAck()
    {
        seedOrderEvent("EVT-PS2", "AE", "O-PS2");
        assertTrue(service.postOrder(order("AE", "O-PS2", "EVT-PS2")).posted());
        // 种本地 OTHER 映射 + 独立事件：核心字段 partnerSku 改变 → 指纹不同，不能 ACK。
        jdbc.update("insert into md_partner_sku(id,partner_sku,sku_id,site_code,business_model,status) "
                + "values (3,'OTHER',11,'AE','noon','ACTIVE')");
        String changed = orderPayload("O-PS2", 7L, 11L, "88.5000", 1, "AED", "OTHER");
        seedWebhook("EVT-PS2B", "AE", "ORDER", changed, "RECEIVED");
        Result conflict = service.postOrder(new OrderInput("AE", "O-PS2", "WEBHOOK", "EVT-PS2B", null, "OTHER",
                7L, 11L, new BigDecimal("88.5000"), 1, "AED", changed));
        assertFalse(conflict.posted());
        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertEquals(1, count("sal_order"));
    }

    @Test
    void missingLocalStoreMappingIsQuarantined()
    {
        seedWebhook("EVT-MM1", "AE", "ORDER",
                orderPayload("O-MM1", 999L, 11L, "88.5000", 1, "AED"), "RECEIVED");
        Result r = service.postOrder(new OrderInput("AE", "O-MM1", "WEBHOOK", "EVT-MM1", null, "SKU",
                999L, 11L, new BigDecimal("88.5000"), 1, "AED",
                orderPayload("O-MM1", 999L, 11L, "88.5000", 1, "AED")));
        assertFalse(r.posted());
        assertEquals("MASTER_DATA_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void partnerSkuWrongSkuMappingIsQuarantined()
    {
        jdbc.update("insert into md_sku(id,sku_code,status) values (12,'SKU-12','ACTIVE')");
        String payload = orderPayload("O-MM2", 7L, 12L, "88.5000", 1, "AED");
        seedWebhook("EVT-MM2", "AE", "ORDER", payload, "RECEIVED");
        Result r = service.postOrder(new OrderInput("AE", "O-MM2", "WEBHOOK", "EVT-MM2", null, "SKU",
                7L, 12L, new BigDecimal("88.5000"), 1, "AED", payload));
        assertFalse(r.posted());
        assertEquals("MASTER_DATA_UNVERIFIED", r.reasonCode());
        assertEquals(0, count("sal_order"));
    }

    @Test
    void eventMetadataChangeIsIdempotentAck()
    {
        // 同一订单核心业务字段不变、第二条事件仅多一个元数据字段 → ACK，不判冲突。
        seedOrderEvent("EVT-META1", "AE", "O-META");
        assertTrue(service.postOrder(order("AE", "O-META", "EVT-META1")).posted());
        String base = orderPayload("O-META", 7L, 11L, "88.5000", 1, "AED");
        String changed = base.substring(0, base.length() - 1) + ",\"event_status\":\"PROCESSED\"}";
        seedWebhook("EVT-META2", "AE", "ORDER", changed, "PROCESSED");
        Result again = service.postOrder(new OrderInput("AE", "O-META", "WEBHOOK", "EVT-META2", null, "SKU",
                7L, 11L, new BigDecimal("88.5000"), 1, "AED", changed));
        assertTrue(again.posted());
        assertEquals("ALREADY_POSTED", again.reasonCode());
        assertEquals(1, count("sal_order"));
        assertEquals(0, count("noon_intake_review"));
    }

    @Test
    void differentEventSameReturnStillIdempotentAck()
    {
        long oid = seedOrder("O-EVR", "AE");
        long itemId = seedItem(oid, "O-EVR");
        seedReturnEvent("EVT-EVR1", "AE", "O-EVR", "SR-EVR", 1, itemId);
        Result first = service.postReturn(ret("AE", "O-EVR", "EVT-EVR1", "SR-EVR", itemId));
        assertTrue(first.posted());
        // 不同事件佐证同一退货业务：payload 相同、eventId 不同 → 仍按幂等 ACK。
        seedReturnEvent("EVT-EVR2", "AE", "O-EVR", "SR-EVR", 1, itemId);
        Result second = service.postReturn(ret("AE", "O-EVR", "EVT-EVR2", "SR-EVR", itemId));
        assertTrue(second.posted());
        assertEquals("ALREADY_POSTED", second.reasonCode());
        assertEquals(first.entityId(), second.entityId());
        assertEquals(1, count("sal_return"));
        assertEquals(0, count("noon_intake_review"));
    }

    // ---------- 四次复核：退货幂等核心字段语义（两入口一致） ----------

    @Test
    void returnMetadataChangeIsIdempotentAck()
    {
        // 正常入口 postReturn：核心字段相同、仅事件元数据字段变化 → ACK，不得 SOURCE_CONFLICT。
        long oid = seedOrder("O-RMETA", "AE");
        long itemId = seedItem(oid, "O-RMETA");
        seedReturnEvent("EVT-RM1", "AE", "O-RMETA", "SR-RMETA", 1, itemId);
        Result first = service.postReturn(ret("AE", "O-RMETA", "EVT-RM1", "SR-RMETA", itemId));
        assertTrue(first.posted());
        String changed = returnPayloadWithMeta("O-RMETA", "SR-RMETA", 1, itemId, "PROCESSED");
        seedWebhook("EVT-RM2", "AE", "RETURN", changed, "PROCESSED");
        Result again = service.postReturn(new ReturnInput("AE", "O-RMETA", "EVT-RM2", "SR-RMETA", itemId, 1, changed));
        assertTrue(again.posted(), "仅元数据变化应幂等 ACK");
        assertEquals("ALREADY_POSTED", again.reasonCode());
        assertEquals(first.entityId(), again.entityId());
        assertEquals(1, count("sal_return"));
        assertEquals(0, count("noon_intake_review"));
    }

    @Test
    void returnCoreFieldChangeDespiteSameMetaIsConflictNotAck()
    {
        // 反例：元数据一致但核心字段（数量）变化 → 两入口都不得 ACK，必须隔离。
        long oid = seedOrder("O-RCORE", "AE");
        long itemId = seedItem(oid, "O-RCORE");
        seedReturnEvent("EVT-RC1", "AE", "O-RCORE", "SR-RCORE", 1, itemId);
        service.postReturn(ret("AE", "O-RCORE", "EVT-RC1", "SR-RCORE", itemId));
        String changed = returnPayload("O-RCORE", "SR-RCORE", 7, itemId);
        seedWebhook("EVT-RC2", "AE", "RETURN", changed, "RECEIVED");
        Result conflict = service.postReturn(new ReturnInput("AE", "O-RCORE", "EVT-RC2", "SR-RCORE", itemId, 7, changed));
        assertFalse(conflict.posted());
        assertEquals("SOURCE_CONFLICT", conflict.reasonCode());
        assertEquals(1, count("sal_return"));
        assertEquals(1, count("noon_intake_review"));
    }

    @Test
    void concurrentReturnWithMetadataChangeIsIdempotentAck() throws Exception
    {
        // 并发回读入口 resolveConcurrentReturn：两事件仅元数据不同、核心字段相同 → 同一行 ACK。
        long oid = seedOrder("O-RCONC", "AE");
        long itemId = seedItem(oid, "O-RCONC");
        String base = returnPayload("O-RCONC", "SR-RCONC", 1, itemId);
        String withMeta = returnPayloadWithMeta("O-RCONC", "SR-RCONC", 1, itemId, "PROCESSED");
        seedWebhook("EVT-RCONC1", "AE", "RETURN", base, "PROCESSED");
        seedWebhook("EVT-RCONC2", "AE", "RETURN", withMeta, "PROCESSED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            ReturnInput a = new ReturnInput("AE", "O-RCONC", "EVT-RCONC1", "SR-RCONC", itemId, 1, base);
            ReturnInput b = new ReturnInput("AE", "O-RCONC", "EVT-RCONC2", "SR-RCONC", itemId, 1, withMeta);
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(a); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(b); });
            start.countDown();
            Result r1 = f1.get();
            Result r2 = f2.get();
            assertTrue(r1.posted());
            assertTrue(r2.posted());
            assertEquals(r1.entityId(), r2.entityId(), "并发元数据变化应 ACK 同一行 ID");
            assertEquals(1, count("sal_return"), "并发同源退货只入账一次");
            assertEquals(0, count("noon_intake_review"));
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    @Test
    void concurrentReturnWithCoreFieldChangeIsConflictNotAck() throws Exception
    {
        // 并发回读入口反例：元数据格式不同且核心字段数量不同 → 不得当重传 ACK。
        long oid = seedOrder("O-RCCORE", "AE");
        long itemId = seedItem(oid, "O-RCCORE");
        String base = returnPayload("O-RCCORE", "SR-RCCORE", 1, itemId);
        String changed = returnPayloadWithMeta("O-RCCORE", "SR-RCCORE", 5, itemId, "PROCESSED");
        seedWebhook("EVT-RCC1", "AE", "RETURN", base, "PROCESSED");
        seedWebhook("EVT-RCC2", "AE", "RETURN", changed, "PROCESSED");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            ReturnInput a = new ReturnInput("AE", "O-RCCORE", "EVT-RCC1", "SR-RCCORE", itemId, 1, base);
            ReturnInput b = new ReturnInput("AE", "O-RCCORE", "EVT-RCC2", "SR-RCCORE", itemId, 5, changed);
            Future<Result> f1 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(a); });
            Future<Result> f2 = pool.submit(() -> { login(1L); start.await(); return service.postReturn(b); });
            start.countDown();
            Result r1 = f1.get();
            Result r2 = f2.get();
            assertTrue(r1.posted() || r2.posted(), "至少一笔先到者入账");
            assertTrue(!r1.posted() || !r2.posted(), "核心字段不同的后到者不得 ACK");
            assertEquals(1, count("sal_return"));
            assertEquals(1, count("noon_intake_review"), "后到者必须隔离");
        }
        finally
        {
            pool.shutdownNow();
            login(1L);
        }
    }

    // ---------- 事务回滚 ----------

    @Test
    void auditFailureRollsBackFormalPosting()
    {
        seedOrderEvent("EVT-15", "AE", "O-15");
        jdbc.execute("alter table noon_intake_audit add constraint reject_posted check (action <> 'POSTED')");
        assertThrows(RuntimeException.class, () -> service.postOrder(order("AE", "O-15", "EVT-15")));
        assertEquals(0, count("sal_order"), "审计失败应回滚订单正式入账");
        assertEquals(0, count("noon_intake_audit"));
    }

    @Test
    void auditFailureRollsBackQuarantine()
    {
        jdbc.execute("alter table noon_intake_audit add constraint reject_quarantine check (action <> 'QUARANTINED')");
        assertThrows(RuntimeException.class, () -> service.postOrder(order("AE", "O-16", null)));
        assertEquals(0, count("noon_intake_review"), "审计失败应回滚隔离写入");
    }

    // ---------- 人工解析 ----------

    @Test
    void onlyAdministratorCanResolveWithVerifiedKey()
    {
        Result r = service.postOrder(order("AE", "O-17", "EVT-RESOLVE")); // 无佐证 → 待核对
        String reviewNo = r.reviewNo();
        login(2L); // 普通用户
        assertThrows(AccessDeniedException.class, () -> service.resolve(reviewNo, "VERIFIED-KEY"));
        assertEquals("NEEDS_REVIEW", jdbc.queryForObject(
                "select status from noon_intake_review where review_no=?", String.class, reviewNo));
        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.resolve(reviewNo, "   "));
        service.resolve(reviewNo, "VERIFIED-KEY");
        assertEquals("RESOLVED", jdbc.queryForObject(
                "select status from noon_intake_review where review_no=?", String.class, reviewNo));
        assertThrows(IllegalStateException.class, () -> service.resolve(reviewNo, "AGAIN"));
    }

    // ---------- 交付脚本结构 ----------

    @Test
    void reviewSqlFileContainsRequiredColumnsAndConstraints()
    {
        String ddl = readReviewDdl();
        for (String column : List.of("review_no", "entity_type", "site_code_claimed", "order_no_claimed",
                "source_return_no", "source_event_id", "raw_payload", "reason_code", "status",
                "resolution_biz_key", "resolved_by", "resolved_at", "created_by"))
            assertTrue(ddl.contains(column), "待核对表缺列: " + column);
        assertTrue(ddl.contains("chk_intake_entity"), "缺实体类型约束");
        assertTrue(ddl.contains("chk_intake_resolution"), "缺解析完整性约束");
        assertTrue(ddl.contains("noon_intake_audit"), "缺审计表");
        // 复核新增原因码须在契约文件中声明
        for (String code : List.of("SOURCE_UNVERIFIED", "MASTER_DATA_INCOMPLETE",
                "MASTER_DATA_UNVERIFIED", "SOURCE_CONFLICT"))
            assertTrue(ddl.contains(code), "待核对契约缺原因码: " + code);
        assertFalse(ddl.contains("GENERATED ALWAYS") && ddl.contains("biz_return_key"),
                "待核对表不应臆造业务幂等键");
    }

    private static String readReviewDdl()
    {
        try { return Files.readString(Path.of("../sql/noon_intake_review.sql")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------- 入参 ----------

    @Test
    void invalidInputRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> service.postOrder(order("AE", "", "E")));
        assertThrows(IllegalArgumentException.class, () -> service.postReturn(ret("AE", "O-X", "E", "SR-X", null)));
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
        IntakeService intakeService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard)
        {
            return new IntakeService(jdbcTemplate, guard);
        }
    }
}
