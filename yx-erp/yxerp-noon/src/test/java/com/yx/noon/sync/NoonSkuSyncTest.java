package com.yx.noon.sync;

import java.util.List;
import java.util.UUID;

import com.yx.noon.masterdata.MasterDataAudit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noon SKU 同步 H2 单测：CSV 解析（含站点过滤/去重）与落库幂等。
 * 覆盖：首次新增、重复同步只更新、中文名/状态/类目不被平台覆盖、Offer 回读列真实写入。
 */
class NoonSkuSyncTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private NoonSkuSyncWriter writer;

    @BeforeEach
    void setUp()
    {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "spu_id varchar(64), name_en varchar(512), name_ar varchar(512), category_code varchar(64), "
                + "hs_code varchar(32), origin_country varchar(32), brand varchar(128), spec_json varchar(4000), "
                + "status varchar(16) not null default 'ACTIVE', constraint uk_sku_code unique(sku_code))");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, partner_sku varchar(128) not null, "
                + "sku_id bigint not null, site_code varchar(16) not null, business_model varchar(24) not null default 'noon', "
                + "offer_code varchar(128), barcode varchar(128), noon_sku varchar(128), live_status boolean, "
                + "offer_issues varchar(4000), status varchar(16) not null default 'ACTIVE', synced_at timestamp(3), "
                + "constraint uk_partner_sku unique(partner_sku, site_code, business_model))");
        jdbc.execute("create table noon_m2_audit_log (id bigint auto_increment primary key, "
                + "entity_type varchar(32) not null, entity_id bigint not null, action varchar(16) not null, "
                + "operator_id bigint, operator_name varchar(64), before_json varchar(4000), after_json varchar(4000), "
                + "changed_at timestamp(3) not null)");
        writer = context.getBean(NoonSkuSyncWriter.class);
    }

    @AfterEach
    void tearDown()
    {
        if (context != null) context.close();
    }

    @Test
    void parseCsvFiltersLocationAndDeduplicates()
    {
        String csv = "location,sku,partner_sku,partner_barcode\n"
                + "AE,Z111A-1,NM000003,NM000003\n"
                + "AE,Z111A-1,NM000003,NM000003\n"
                + "SA,Z222A-1,NM000004,NM000004\n"
                + "AE,N38692941A,YXSS000195,YXSS000195\n";
        List<NoonCatalogSku> rows = NoonApiClient.parseCatalogCsv(csv);
        assertEquals(2, rows.size());
        assertEquals("NM000003", rows.get(0).partnerSku());
        assertEquals("Z111A-1", rows.get(0).noonSku());
        assertEquals("YXSS000195", rows.get(1).partnerSku());
    }

    @Test
    void persistIsIdempotentAndPreservesManuallyMaintainedFields()
    {
        NoonOfferInfo offer = new NoonOfferInfo("NM000003", "Z111A-1", "NM000003",
                "Car Phone Holder", "Generic", "offer-1", true, true, "[]", "19.9", "AED");

        NoonSkuSyncWriter.Counters first = writer.persist("AE", List.of(offer));
        assertEquals(1, first.skuCreated());
        assertEquals(1, first.mappingCreated());

        long skuId = jdbc.queryForObject("select sku_id from md_partner_sku where partner_sku='NM000003'", Long.class);
        jdbc.update("update md_sku set name_ar=?,category_code=?,status='INACTIVE',hs_code='HS1' where id=?",
                "هاتف", "AUTO", skuId);

        NoonOfferInfo updated = new NoonOfferInfo("NM000003", "Z111A-1", "NM000003",
                "Car Phone Holder Pro", "Generic", "offer-2", true, false,
                "[{\"reason\":\"Stock Issues\"}]", "21.9", "AED");
        NoonSkuSyncWriter.Counters second = writer.persist("AE", List.of(updated));
        assertEquals(0, second.skuCreated());
        assertEquals(1, second.skuUpdated());
        assertEquals(0, second.mappingCreated());
        assertEquals(1, second.mappingUpdated());

        assertEquals(1, jdbc.queryForObject("select count(*) from md_sku", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from md_partner_sku", Integer.class));
        assertEquals("Car Phone Holder Pro", jdbc.queryForObject("select name_en from md_sku where id=?", String.class, skuId));
        assertEquals("هاتف", jdbc.queryForObject("select name_ar from md_sku where id=?", String.class, skuId));
        assertEquals("AUTO", jdbc.queryForObject("select category_code from md_sku where id=?", String.class, skuId));
        assertEquals("INACTIVE", jdbc.queryForObject("select status from md_sku where id=?", String.class, skuId));
        assertEquals("HS1", jdbc.queryForObject("select hs_code from md_sku where id=?", String.class, skuId));

        assertEquals("offer-2", jdbc.queryForObject("select offer_code from md_partner_sku where id=?", String.class, 1));
        assertEquals("Z111A-1", jdbc.queryForObject("select noon_sku from md_partner_sku where id=?", String.class, 1));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("select live_status from md_partner_sku where id=?", Boolean.class, 1));
        assertTrue(jdbc.queryForObject("select offer_issues from md_partner_sku where id=?", String.class, 1)
                .contains("Stock Issues"));
        assertNotNull(jdbc.queryForObject("select synced_at from md_partner_sku where id=?", Object.class, 1));
        assertEquals(4, jdbc.queryForObject("select count(*) from noon_m2_audit_log", Integer.class));
    }

    @Test
    void rejectsTooLongPartnerSkuAndRollsBackBatch()
    {
        String tooLong = "X".repeat(65);
        NoonOfferInfo bad = new NoonOfferInfo(tooLong, "Z999-1", "BC", "Bad", "Generic",
                "offer-9", true, true, "[]", "1", "AED");
        assertThrows(IllegalArgumentException.class, () -> writer.persist("AE", List.of(bad)));
        assertEquals(0, jdbc.queryForObject("select count(*) from md_sku", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m2_audit_log", Integer.class));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig
    {
        @Bean
        javax.sql.DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:noon-sync-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(javax.sql.DataSource ds)
        {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource ds) { return new JdbcTemplate(ds); }

        @Bean
        MasterDataAudit masterDataAudit(JdbcTemplate jdbc) { return new MasterDataAudit(jdbc); }

        @Bean
        NoonSkuSyncWriter noonSkuSyncWriter(JdbcTemplate jdbc, MasterDataAudit audit)
        {
            return new NoonSkuSyncWriter(jdbc, audit);
        }
    }
}
