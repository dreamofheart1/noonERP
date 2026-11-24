package com.yx.noon.sync;

import java.sql.Timestamp;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Seller Lab 落库单测：FBN/Sales 幂等 upsert、状态更新、站点隔离、不以空当零。 */
class NoonSellerLabSyncServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NoonSellerLabSyncService service;
    private NoonSellerLabClient client;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("create table sys_user (user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.update("insert into sys_user values (1,'0','0'), (2,'0','0')");
        String siteMigration = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/noon_wp1_site_auth.sql"));
        for (String st : siteMigration.split(";"))
            if (!st.isBlank()) jdbc.execute(st.replaceAll("--.*", "").trim());
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) values "
                + "(1,'AE',1,current_timestamp(3)), (1,'SA',1,current_timestamp(3)), (2,'SA',1,current_timestamp(3))");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, partner_sku varchar(128),"
                + "sku_id bigint, site_code varchar(16), business_model varchar(24))");
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64))");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, warehouse_code varchar(64),"
                + "site_code varchar(16), warehouse_name varchar(256), is_fbn int default 0)");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint, warehouse_id bigint, site_code varchar(16),"
                + "qty_available int default 0, qty_unsellable int default 0, qty_in_transit int default 0)");
        jdbc.execute("create table noon_fbn_inventory_snapshot (id bigint auto_increment primary key, site_code varchar(8),"
                + "partner_sku varchar(128), sku varchar(128), warehouse_code varchar(128), saleable int, non_saleable int,"
                + "qty int, captured_at timestamp(3), source varchar(32), synced_at timestamp(3),"
                + "constraint uk_fbn_snap unique(site_code,partner_sku,warehouse_code,captured_at))");
        jdbc.execute("create table noon_sales_item (id bigint auto_increment primary key, site_code varchar(8), item_nr varchar(128),"
                + "partner_sku varchar(128), sku varchar(128), status varchar(64), fulfillment_model varchar(64),"
                + "order_ts timestamp(3), shipment_ts timestamp(3), delivered_ts timestamp(3), price_amount decimal(18,4),"
                + "currency_code char(3), awb_nr varchar(128), source varchar(32), synced_at timestamp(3),"
                + "constraint uk_sales_item unique(site_code,item_nr))");
        jdbc.update("insert into md_sku(id,sku_code) values (1,'SKU-1')");
        jdbc.update("insert into md_partner_sku(partner_sku,sku_id,site_code,business_model) values ('NM1',1,'AE','noon')");
        loginAs(1L);
        service = context.getBean(NoonSellerLabSyncService.class);
        client = context.getBean(NoonSellerLabClient.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    private static void loginAs(Long userId)
    {
        SysUser u = new SysUser(); u.setUserId(userId); u.setUserName("u" + userId);
        LoginUser lu = new LoginUser(userId, 100L, u, java.util.Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(lu, null, List.of()));
    }

    @Test
    void siteWhitelistRejectsUnknownCode()
    {
        assertThrows(IllegalArgumentException.class, () -> client.requireSite("xx"));
        assertThrows(IllegalArgumentException.class, () -> client.requireSite(null));
        assertEquals("AE", client.requireSite("ae"));
        assertEquals("SA", client.requireSite(" SA "));
    }

    @Test
    void fbnUpsertIsIdempotentAndCompareMarksMissingLedger()
    {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        service.persistFbn("AE", List.of(new FbnInventoryRow("AE", "NM1", "Z1", "AUH01", 12, 2, 14)));
        service.persistFbn("AE", List.of(new FbnInventoryRow("AE", "NM1", "Z1", "AUH01", 10, 2, 12)));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_fbn_inventory_snapshot", Integer.class));
        assertEquals(10, jdbc.queryForObject("select saleable from noon_fbn_inventory_snapshot", Integer.class));

        jdbc.update("insert into md_warehouse(id,warehouse_code,site_code,warehouse_name,is_fbn) values "
                + "(1,'FBN-AE','AE','AE FBN',1), (2,'LOCAL-AE','AE','AE Local',0)");

        NoonSellerLabCompare cmp = service.compare("AE", 0, 50);
        assertEquals(1, cmp.rows().size());
        assertNull(cmp.rows().get(0).systemAvailable(), "系统无台账记录必须显示未同步，不得当 0");
        assertEquals("系统无 FBN 仓台账记录（未发生入库/直发收货），不可比较，差异不代表丢货", cmp.rows().get(0).note());

        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,qty_unsellable,qty_in_transit) values (1,1,'AE',8,1,0)");
        // 本地仓 99 不得计入 FBN 对比（复核2）
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,qty_unsellable,qty_in_transit) values (1,2,'AE',99,0,0)");
        NoonSellerLabCompare cmp2 = service.compare("AE", 0, 50);
        assertEquals(8, cmp2.rows().get(0).systemAvailable());
        assertEquals(2, cmp2.rows().get(0).officialSaleable() - cmp2.rows().get(0).systemAvailable());
        assertEquals("", cmp2.rows().get(0).note());
    }

    @Test
    void salesUpsertUpdatesStatusAndKeepsSiteIsolation()
    {
        service.persistSales("AE", List.of(new SalesItemRow("AE", "AEI-1", "NM1", "Z1", "shipped",
                "FBN", "2026-11-01T10:00:00Z", null, null, "12.5", "AED", "AWB1")));
        service.persistSales("AE", List.of(new SalesItemRow("AE", "AEI-1", "NM1", "Z1", "delivered",
                "FBN", "2026-11-01T10:00:00Z", "2026-11-02T10:00:00Z", "2026-11-03T10:00:00Z", "12.5", "AED", "AWB1")));
        service.persistSales("SA", List.of(new SalesItemRow("SA", "AEI-1", "NM1", "Z1", "delivered",
                "FBN", null, null, null, "50", "SAR", "AWB2")));
        assertEquals(2, jdbc.queryForObject("select count(*) from noon_sales_item", Integer.class));
        assertEquals("delivered", jdbc.queryForObject("select status from noon_sales_item where site_code='AE'", String.class));
        assertEquals("SAR", jdbc.queryForObject("select currency_code from noon_sales_item where site_code='SA'", String.class));
        assertEquals(1, service.listSales("AE", 0, 50).rows().size());
        assertThrows(AccessDeniedException.class, () -> { loginAs(2L); service.listSales("AE", 0, 50); });
    }

    @Configuration
    static class TestConfig
    {
        @Bean DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:sellerlab-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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
        @Bean NoonSellerLabClient client() { return org.mockito.Mockito.mock(NoonSellerLabClient.class); }
        @Bean NoonSellerLabSyncService service(NoonSellerLabClient c, JdbcTemplate j, SiteAuthGuard g)
        { return new NoonSellerLabSyncService(c, j, g); }
    }
}
