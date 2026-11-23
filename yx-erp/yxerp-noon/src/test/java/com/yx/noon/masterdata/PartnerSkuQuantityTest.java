package com.yx.noon.masterdata;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.security.site.SiteAuthGuard;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partner SKU 展开数量分布 H2 单测：真站点闸门 + 真实 SQL 口径。
 * 覆盖：共享映射汇总标注、四阶段互斥计数、单据明细、站点隔离、无来源阶段待接入。
 */
class PartnerSkuQuantityTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PartnerSkuQuantityService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        String siteMigration = java.nio.file.Files.readString(
                java.nio.file.Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String statement : siteMigration.split(";"))
            if (!statement.isBlank()) jdbc.execute(statement.replaceAll("--.*", "").trim());
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) values "
                + "(1,'AE',1,current_timestamp(3)), (1,'SA',1,current_timestamp(3)), (2,'SA',1,current_timestamp(3))");

        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null)");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, partner_sku varchar(128) not null, "
                + "sku_id bigint not null, site_code varchar(16) not null, business_model varchar(24) default 'noon')");
        jdbc.execute("create table pur_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "site_code varchar(16) not null, status varchar(24) not null)");
        jdbc.execute("create table pur_order_item (id bigint auto_increment primary key, pur_order_id bigint not null, "
                + "sku_id bigint not null, qty_ordered int not null, qty_received int not null default 0)");
        jdbc.execute("create table log_asn (id bigint auto_increment primary key, asn_no varchar(64) not null, "
                + "site_code varchar(16) not null, status varchar(24) not null)");
        jdbc.execute("create table log_asn_item (id bigint auto_increment primary key, asn_id bigint not null, "
                + "sku_id bigint not null, qty_shipped int not null, qty_received int default 0)");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, warehouse_code varchar(64) not null, "
                + "site_code varchar(16) not null, warehouse_name varchar(256), is_fbn smallint not null default 0)");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint not null, "
                + "warehouse_id bigint not null, site_code varchar(16) not null, qty_available int not null default 0)");

        jdbc.update("insert into md_sku(id,sku_code) values (1,'SKU-A'), (2,'SKU-B')");
        // 同站点两条映射指向同一 SKU；另一站点一条映射
        jdbc.update("insert into md_partner_sku(id,partner_sku,sku_id,site_code) values "
                + "(1,'NM000003',1,'AE'), (2,'YXSS000195',1,'AE'), (3,'SA-001',2,'SA')");
        // 采购：只有已确认/部分收货且未收齐的计入
        jdbc.update("insert into pur_order(id,order_no,site_code,status) values "
                + "(1,'PO-CONFIRMED','AE','CONFIRMED'), (2,'PO-DRAFT','AE','DRAFT'), "
                + "(3,'PO-RECEIVED','AE','RECEIVED')");
        jdbc.update("insert into pur_order_item(id,pur_order_id,sku_id,qty_ordered,qty_received) values "
                + "(1,1,1,10,3), (2,2,1,5,0), (3,3,1,8,8)");
        // 在途：仅 SEALED 且已发未收
        jdbc.update("insert into log_asn(id,asn_no,site_code,status) values "
                + "(1,'ASN-SEALED','AE','SEALED'), (2,'ASN-DRAFT','AE','DRAFT'), (3,'ASN-RECEIVED','AE','RECEIVED')");
        jdbc.update("insert into log_asn_item(id,asn_id,sku_id,qty_shipped,qty_received) values "
                + "(1,1,1,4,1), (2,2,1,2,0), (3,3,1,5,5)");
        jdbc.update("insert into md_warehouse(id,warehouse_code,site_code,warehouse_name,is_fbn) values "
                + "(1,'FBN-AE','AE','AE FBN',1), (2,'LOCAL-AE','AE','AE Local',0), (3,'FBN-SA','SA','SA FBN',1)");
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values "
                + "(1,1,'AE',6), (1,2,'AE',2), (2,3,'SA',5)");

        loginAs(1L); // 超管，AE+SA 都授权
        service = context.getBean(PartnerSkuQuantityService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    private static void loginAs(Long userId)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName(userId == 1 ? "admin" : "sa-only");
        LoginUser loginUser = new LoginUser(userId, 100L, user, java.util.Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    private static int qty(PartnerSkuQuantityBreakdown b, String stage)
    {
        return b.stages().stream().filter(s -> s.stage().equals(stage)).findFirst().orElseThrow().qty();
    }

    @Test
    void stagesUseRealEventsAndSharedMappingIsExplicit()
    {
        PartnerSkuQuantityBreakdown b = service.breakdown(1);
        assertEquals("AE", b.siteCode());
        assertEquals(1L, b.skuId());
        assertEquals(2, b.sharedMappingCount());
        assertTrue(b.scopeNote().contains("汇总"));
        assertTrue(b.scopeNote().contains("非本映射独有"));

        assertEquals(7, qty(b, "PURCHASING"));   // 10-3，DRAFT/RECEIVED 不计
        assertEquals(3, qty(b, "IN_TRANSIT"));   // 4-1，DRAFT/RECEIVED 不计
        assertEquals(6, qty(b, "FBN_AVAILABLE"));
        assertEquals(2, qty(b, "LOCAL_AVAILABLE"));
        assertEquals(1, b.stages().stream().filter(s -> s.stage().equals("PURCHASING"))
                .findFirst().orElseThrow().docs().size());

        assertEquals(1, b.pendingStages().size());
        assertEquals("NOON_FBN_LIVE", b.pendingStages().get(0).stage());
        assertTrue(b.pendingStages().get(0).reason().contains("未接入"));
    }

    @Test
    void siteScopeIsEnforced()
    {
        PartnerSkuQuantityBreakdown sa = service.breakdown(3);
        assertEquals("SA", sa.siteCode());
        assertEquals(0, qty(sa, "PURCHASING"));
        assertEquals(0, qty(sa, "IN_TRANSIT"));
        assertEquals(5, qty(sa, "FBN_AVAILABLE")); // 不串到 AE 仓

        loginAs(2L); // 仅 SA 授权
        assertThrows(AccessDeniedException.class, () -> service.breakdown(1));
        assertEquals("SA", service.breakdown(3).siteCode());
    }

    @Test
    void unknownMappingIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> service.breakdown(999));
    }

    @Configuration
    static class TestConfig
    {
        @Bean
        DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:partner-qty-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
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
        PartnerSkuQuantityService partnerSkuQuantityService(JdbcTemplate jdbc, SiteAuthGuard guard)
        {
            return new PartnerSkuQuantityService(jdbc, guard);
        }
    }
}
