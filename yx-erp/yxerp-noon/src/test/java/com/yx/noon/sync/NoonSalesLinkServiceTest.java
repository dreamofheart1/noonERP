package com.yx.noon.sync;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 销售联动与退货证据闸门单测：不伪造订单、不自动扣减、缺证据不加库存。 */
class NoonSalesLinkServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NoonSalesLinkService service;

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
        jdbc.execute("create table noon_sales_item (id bigint auto_increment primary key, site_code varchar(8), item_nr varchar(128), partner_sku varchar(128), sku varchar(128), status varchar(64), fulfillment_model varchar(64), order_ts timestamp(3), shipment_ts timestamp(3), delivered_ts timestamp(3), price_amount decimal(18,4), currency_code char(3), awb_nr varchar(128), source varchar(32), synced_at timestamp(3))");
        jdbc.execute("create table noon_sales_link (id bigint auto_increment primary key, site_code varchar(8), item_nr varchar(128), sal_order_id bigint, sal_order_item_id bigint, status varchar(32), delivered_at timestamp(3), synced_at timestamp(3), constraint uk_sales_link unique(site_code,item_nr))");
        jdbc.execute("create table sal_order_item (id bigint auto_increment primary key, sku_id bigint, sal_order_id bigint)");
        jdbc.execute("create table noon_return_evidence (id bigint auto_increment primary key, site_code varchar(8), item_nr varchar(128), source_return_no varchar(128), status varchar(32), evidence_note varchar(512), synced_at timestamp(3))");

        SysUser u = new SysUser(); u.setUserId(1L); u.setUserName("admin");
        LoginUser lu = new LoginUser(1L, 100L, u, java.util.Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(lu, null, List.of()));
        service = context.getBean(NoonSalesLinkService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void deliveredWithoutOrderLinkIsQuarantined()
    {
        jdbc.update("insert into noon_sales_item(site_code,item_nr,status) values ('AE','AEI-1','Delivered')");
        List<SalesLinkRow> rows = service.refresh("AE");
        assertEquals(1, rows.size());
        assertEquals("NO_ORDER_LINK", rows.get(0).decision());
        assertTrue(rows.get(0).note().contains("禁止臆造"));
        assertEquals(null, jdbc.queryForObject("select sal_order_item_id from noon_sales_link where item_nr='AEI-1'", Long.class));
    }

    @Test
    void stateRegressionKeepsDeliveredFact()
    {
        jdbc.update("insert into noon_sales_item(site_code,item_nr,status) values ('AE','AEI-2','shipped')");
        jdbc.update("insert into noon_sales_link(site_code,item_nr,sal_order_item_id,status) values ('AE','AEI-2',7,'READY_FOR_FIFO')");
        List<SalesLinkRow> rows = service.refresh("AE");
        assertEquals("STATE_REGRESSION", rows.get(0).decision());
        assertEquals("READY_FOR_FIFO", jdbc.queryForObject("select status from noon_sales_link where item_nr='AEI-2'", String.class));
    }

    @Test
    void returnWithoutEvidenceStaysPending()
    {
        assertEquals(0, service.registerReturn("AE", "AEI-1", "RET-1", false, null));
        assertEquals("INSUFFICIENT_EVIDENCE", jdbc.queryForObject("select status from noon_return_evidence", String.class));
        assertEquals(1, service.registerReturn("AE", "AEI-1", "RET-2", true, "平台确认已重新入仓且可售"));
        assertEquals(2, jdbc.queryForObject("select count(*) from noon_return_evidence", Integer.class));
        assertEquals("VERIFIED", jdbc.queryForObject("select status from noon_return_evidence where source_return_no='RET-2'", String.class));
    }

    @Configuration
    static class TestConfig
    {
        @Bean DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:saleslink-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
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
        @Bean NoonSalesLinkService salesLinkService(JdbcTemplate j, SiteAuthGuard g) { return new NoonSalesLinkService(j, g); }
    }
}
