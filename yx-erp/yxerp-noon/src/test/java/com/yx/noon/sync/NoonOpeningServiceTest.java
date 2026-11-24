package com.yx.noon.sync;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 期初库存隔离单测：generate 只读、confirm 才建账、幂等、零成本拒绝、重叠只标注。 */
class NoonOpeningServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NoonOpeningService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0')");
        String siteMigration = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String st : siteMigration.split(";")) if (!st.isBlank()) jdbc.execute(st.replaceAll("--.*", "").trim());
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) values (1,'AE',1,current_timestamp(3))");

        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64))");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, partner_sku varchar(128), sku_id bigint, site_code varchar(16), business_model varchar(24))");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, warehouse_code varchar(64), site_code varchar(16), warehouse_name varchar(256), is_fbn int default 0)");
        jdbc.execute("create table inv_batch (id bigint auto_increment primary key, batch_no varchar(64) not null, sku_id bigint, warehouse_id bigint, site_code varchar(16), received_at timestamp(3), qty_in int, qty_remaining int, unit_cost decimal(18,4), currency char(3), fx_rate decimal(18,8), source_type varchar(24), status varchar(16))");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint, warehouse_id bigint, site_code varchar(16), qty_available int default 0, qty_unsellable int default 0, qty_in_transit int default 0)");
        jdbc.execute("create table noon_fbn_inventory_snapshot (id bigint auto_increment primary key, site_code varchar(8), partner_sku varchar(128), sku varchar(128), warehouse_code varchar(128), saleable int, non_saleable int, qty int, captured_at timestamp(3), source varchar(32), synced_at timestamp(3))");
        jdbc.execute("create table noon_sales_link (id bigint auto_increment primary key, site_code varchar(8), item_nr varchar(128), sal_order_id bigint, sal_order_item_id bigint, status varchar(32), delivered_at timestamp(3), synced_at timestamp(3))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, sku_id bigint, sal_order_id bigint)");
        jdbc.execute("create table noon_opening_proposal (id bigint auto_increment primary key, proposal_no varchar(64), site_code varchar(8), partner_sku varchar(128), sku_id bigint, warehouse_id bigint, qty_saleable int, qty_non_saleable int, snapshot_at timestamp(3), overlap_note varchar(512), status varchar(24), cost_status varchar(24), unit_cost decimal(18,4), confirmed_by varchar(64), confirmed_at timestamp(3), source varchar(32), created_at timestamp(3))");
        jdbc.execute("create table noon_opening_audit (id bigint auto_increment primary key, proposal_no varchar(64), site_code varchar(8), action varchar(24), operator varchar(64), detail_json varchar(1000), created_at timestamp(3))");

        jdbc.update("insert into md_sku(id,sku_code) values (1,'SKU-1')");
        jdbc.update("insert into md_partner_sku(partner_sku,sku_id,site_code,business_model) values ('NM1',1,'AE','noon')");
        jdbc.update("insert into md_warehouse(id,warehouse_code,site_code,is_fbn) values (1,'FBN-AE','AE',1)");
        jdbc.update("insert into noon_fbn_inventory_snapshot(site_code,partner_sku,sku,warehouse_code,saleable,non_saleable,qty,captured_at) values ('AE','NM1','Z1','FBN-AE',10,2,12,?)",
                java.sql.Timestamp.valueOf(LocalDateTime.of(2026,11,20,0,0)));

        SysUser u = new SysUser(); u.setUserId(1L); u.setUserName("admin");
        LoginUser lu = new LoginUser(1L, 100L, u, java.util.Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(lu, null, List.of()));
        service = context.getBean(NoonOpeningService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void generateIsReadOnlyAndMarksOverlap()
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (1,1,'AE',99)");
        List<OpeningProposalRow> rows = service.generate("AE", "P1", "2026-11-23");
        assertEquals(1, rows.size());
        assertEquals(10, rows.get(0).qtySaleable());
        assertTrue(rows.get(0).overlapNote().contains("已有 FBN 台账"));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch", Integer.class), "generate 不得建批");
        assertEquals(99, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class), "generate 不得改库存");
    }

    @Test
    void confirmRequiresRealCostAndIsIdempotent()
    {
        service.generate("AE", "P1", "2026-11-23");
        assertThrows(IllegalArgumentException.class, () -> service.confirm("AE", "P1", "admin", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.confirm("AE", "P1", "admin", null));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));

        assertEquals(1, service.confirm("AE", "P1", "admin", new BigDecimal("12.5")));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
        assertEquals(10, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_opening_audit", Integer.class));

        // 重复确认：PENDING 已无行，不再建账
        assertEquals(0, service.confirm("AE", "P1", "admin", new BigDecimal("12.5")));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch", Integer.class));
        assertEquals(10, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
    }

    @Configuration
    static class TestConfig
    {
        @Bean DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:opening-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }
        @Bean SysUserSiteMapper mapper(DataSource ds) throws Exception
        {
            SqlSessionFactoryBean f = new SqlSessionFactoryBean(); f.setDataSource(ds);
            f.setMapperLocations(new ClassPathResource("mapper/system/SysUserSiteMapper.xml"));
            return new SqlSessionTemplate(f.getObject()).getMapper(SysUserSiteMapper.class);
        }
        @Bean SiteAuthGuard guard(SysUserSiteMapper m) { return new SiteAuthGuard(m); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean NoonOpeningService openingService(JdbcTemplate j, SiteAuthGuard g) { return new NoonOpeningService(j, g); }
    }
}
