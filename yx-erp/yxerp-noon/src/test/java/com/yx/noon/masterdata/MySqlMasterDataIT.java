package com.yx.noon.masterdata;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.system.mapper.SysUserSiteMapper;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 主数据隔离 MySQL 集成验证（opt-in）：真实迁移脚本在建好的一次性库上验证幂等、唯一键、CHECK、JSON、
 * 引用完整性（存在+ACTIVE）、不可变字段、审计同事务/回滚与站点越权。绝不连已有库/生产。
 * 运行：设置 NOON_MYSQL_TEST_PORT（及 NOON_MYSQL_TEST_PASSWORD）后 mvn -pl yxerp-noon -am test。
 */
@EnabledIfEnvironmentVariable(named = "NOON_MYSQL_TEST_PORT", matches = "[0-9]+")
class MySqlMasterDataIT
{
    private static final ThreadLocal<String> DATABASE = new ThreadLocal<>();
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private StoreService storeService;
    private SiteService siteService;
    private WarehouseService warehouseService;
    private SkuService skuService;
    private PartnerSkuService partnerSkuService;

    private static DataSource serverDataSource()
    {
        int port = Integer.parseInt(System.getenv("NOON_MYSQL_TEST_PORT"));
        String password = System.getenv("NOON_MYSQL_TEST_PASSWORD");
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + port + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai",
                "root", password);
    }

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(MysqlConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        runSqlFile("../sql/noon_wp1_site_auth.sql");
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) "
                + "values (2,'AE',1,current_timestamp(3))");

        // 真实迁移脚本，连续执行两次验证幂等。
        runSqlFile("../sql/noon_m2_masterdata.sql");
        runSqlFile("../sql/noon_m2_masterdata.sql");
        // 引用完整性需要父 Partner 账号：1=ACTIVE，2=INACTIVE。
        jdbc.update("insert into md_partner_account(project_code,account_name,status) "
                + "values ('PRJ-A','Partner A','ACTIVE'), ('PRJ-B','Partner B','INACTIVE')");

        storeService = context.getBean(StoreService.class);
        siteService = context.getBean(SiteService.class);
        warehouseService = context.getBean(WarehouseService.class);
        skuService = context.getBean(SkuService.class);
        partnerSkuService = context.getBean(PartnerSkuService.class);
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

    @Test
    void migrationIsIdempotentAndRealConstraintsHold()
    {
        assertEquals(7, jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema=database() and table_name in ('md_partner_account','md_store','md_site',"
                + "'md_warehouse','md_sku','md_partner_sku','noon_m2_audit_log')", Integer.class),
                "6 张 md_ 表 + 审计表应存在");

        long storeId = storeService.save(new StoreInput("ST-MY-1", 1L, "Store", "متجر", "LLC", null));
        long ae = siteService.save(new SiteInput("AE", storeId, "UAE", "AED", new BigDecimal("0.05"), null, null));
        long skuId = skuService.save(new SkuInput("SKU-MY-1", null, "Lamp", "مصباح", "LIGHT", "9405",
                "CN", "BrandY", "{\"weightKg\":1.2}", null));

        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null)));
        assertTrue(dup.getMessage().contains("该站点已存在"), dup.getMessage());

        // DB CHECK：绕过 Service 直写非法 site_code 必须被拒。
        // MySQL 8.4 把 CHECK 违反（SQLState HY000 / error 3819）映射为 UncategorizedSQLException，
        // 不是 DataIntegrityViolationException；断言放宽到两者公共父类 DataAccessException，语义仍是"被 DB 拒绝"。
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into md_site(site_code,store_id,currency,status) values ('XX',?, 'AED','ACTIVE')", storeId));

        // DB JSON：非法 JSON（error 3140）同样落在 UncategorizedSQLException，故同用 DataAccessException。
        assertThrows(DataAccessException.class,
                () -> jdbc.update("insert into md_sku(sku_code,spec_json,status) values ('SKU-BAD','{oops}','ACTIVE')"));

        // fulfillment_mode 不可变（不可变字段负例）+ Offer 列不写入 + 审计落库。
        long wh = warehouseService.save(new WarehouseInput("WH-MY-1", "AE", "Dubai", "FBN", "FBN", true, null));
        assertThrows(IllegalArgumentException.class, () -> warehouseService.update(wh,
                new WarehouseInput("WH-MY-1", "AE", "Dubai", "FBN", "GLOBAL", true, null)));
        long mapping = partnerSkuService.save(new PartnerSkuInput("P-MY-1", skuId, "AE", null, null, null, null));
        assertNull(jdbc.queryForObject("select live_status from md_partner_sku where id=?", Boolean.class, mapping));
        assertNull(jdbc.queryForObject("select offer_issues from md_partner_sku where id=?", String.class, mapping));
        assertEquals(ae, siteService.get(ae).id());
        assertTrue(jdbc.queryForObject("select count(*) from noon_m2_audit_log where entity_type='STORE' "
                + "and entity_id=? and action='INSERT' and before_json is null", Integer.class, storeId) == 1,
                "新增必须留 before=NULL 的审计");
        assertTrue(jdbc.queryForObject("select count(*) from noon_m2_audit_log "
                + "where entity_type='SITE' and action='INSERT' and operator_id=1", Integer.class) >= 1,
                "审计应记录真实登录人");
    }

    @Test
    void referenceIntegrityAndAuditRollbackOnRealSchema()
    {
        IllegalArgumentException inactivePartner = assertThrows(IllegalArgumentException.class,
                () -> storeService.save(new StoreInput("ST-MY-3", 2L, null, null, null, null)));
        assertTrue(inactivePartner.getMessage().contains("已停用"), inactivePartner.getMessage());

        long storeId = storeService.save(new StoreInput("ST-MY-4", 1L, null, null, null, null));
        long ae = siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));
        storeService.setStatus(storeId, "INACTIVE");
        assertThrows(IllegalArgumentException.class,
                () -> siteService.save(new SiteInput("SA", storeId, null, "SAR", null, null, null)),
                "停用店铺不得再挂站点");
        storeService.setStatus(storeId, "ACTIVE");

        long skuId = skuService.save(new SkuInput("SKU-MY-4", null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-MY-9", skuId, "SA", null, null, null, null)),
                "映射站点必须在 md_site 已维护");

        // 审计回滚：失败写入不得新增审计行。
        int before = jdbc.queryForObject("select count(*) from noon_m2_audit_log", Integer.class);
        assertThrows(IllegalArgumentException.class,
                () -> siteService.update(ae, new SiteInput("SA", storeId, null, "AED", null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-MY-10", 9999L, "AE", null, null, null, null)));
        assertEquals(before, jdbc.queryForObject("select count(*) from noon_m2_audit_log", Integer.class),
                "失败回滚后审计行数不应变化");
    }

    @Test
    void crossSiteWriteIsDeniedOnRealSchema()
    {
        long storeId = storeService.save(new StoreInput("ST-MY-2", 1L, null, null, null, null));
        siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));
        siteService.save(new SiteInput("SA", storeId, null, "SAR", null, null, null));
        long skuId = skuService.save(new SkuInput("SKU-MY-2", null, null, null, null, null, null, null, null, null));

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-MY-2", skuId, "SA", null, null, null, null)));
        assertEquals(1, siteService.list(null, 0, 10).size(), "列表只返回授权站点");
        assertThrows(AccessDeniedException.class, () -> siteService.list("SA", 0, 10));
        // SKU 读需至少一个站点；店铺写仅超管（普通用户有 AE 仍不可写共享店铺）。
        assertEquals(1, skuService.list(null, 0, 10).size());
        assertThrows(AccessDeniedException.class,
                () -> storeService.update(storeId, new StoreInput("ST-MY-2", 1L, null, null, null, null)));
        long aeMapping = partnerSkuService.save(new PartnerSkuInput("P-MY-3", skuId, "AE", null, null, null, null));
        assertTrue(aeMapping > 0);
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

    private static void login(long id)
    {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName("user" + id);
        LoginUser principal = new LoginUser(id, 100L, user, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "", List.of()));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MysqlConfig
    {
        @Bean
        DataSource dataSource()
        {
            DataSource ds = serverDataSource();
            String database = "noon_m2_" + UUID.randomUUID().toString().replace("-", "");
            new JdbcTemplate(ds).execute("create database `" + database + "` character set utf8mb4");
            DATABASE.set(database);
            org.springframework.jdbc.datasource.DriverManagerDataSource configured =
                    (org.springframework.jdbc.datasource.DriverManagerDataSource) ds;
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
        MasterDataAudit masterDataAudit(JdbcTemplate jdbcTemplate) { return new MasterDataAudit(jdbcTemplate); }

        @Bean
        StoreService storeService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, MasterDataAudit audit)
        { return new StoreService(jdbcTemplate, guard, audit); }

        @Bean
        SiteService siteService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, MasterDataAudit audit)
        { return new SiteService(jdbcTemplate, guard, audit); }

        @Bean
        WarehouseService warehouseService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, MasterDataAudit audit)
        { return new WarehouseService(jdbcTemplate, guard, audit); }

        @Bean
        SkuService skuService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, MasterDataAudit audit)
        { return new SkuService(jdbcTemplate, guard, audit); }

        @Bean
        PartnerSkuService partnerSkuService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, MasterDataAudit audit)
        { return new PartnerSkuService(jdbcTemplate, guard, audit); }
    }
}
