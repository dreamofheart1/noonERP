package com.yx.framework.reversal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                        + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,period,cross_period,created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,current_timestamp(3),'SALE',1,?,0,current_timestamp(3))",
                "CN:" + UUID.randomUUID(), "SALE:" + UUID.randomUUID(), null, batchId, itemId, sku, qty,
                new BigDecimal(unitCost), new BigDecimal(unitCost).multiply(BigDecimal.valueOf(qty)), "2026-11");
        return jdbc.queryForObject("select id from inv_batch_consumption where batch_id=? and sign=1", Long.class, batchId);
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
