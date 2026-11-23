package com.yx.noon.masterdata;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * M2 主数据 H2 单测：真 Spring 事务 + H2(MySQL 模式) + 真 MyBatis 站点 Mapper。
 * 覆盖权限（店铺读写规则、SKU 至少一站点/仅超管写）、引用完整性（存在+ACTIVE）、
 * 不可变字段、唯一键转中文、审计同事务与失败回滚。
 * H2 宽松建表（spec_json 文本列、仅唯一键）；CHECK/JSON/表选项由 {@link MySqlMasterDataIT} 在隔离 MySQL 验证。
 */
class MasterDataServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private StoreService storeService;
    private SiteService siteService;
    private WarehouseService warehouseService;
    private SkuService skuService;
    private PartnerSkuService partnerSkuService;

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

        createMasterDataTables();
        storeService = context.getBean(StoreService.class);
        siteService = context.getBean(SiteService.class);
        warehouseService = context.getBean(WarehouseService.class);
        skuService = context.getBean(SkuService.class);
        partnerSkuService = context.getBean(PartnerSkuService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test
    void storePermissionsFollowSiteAndAdminRules()
    {
        login(1L);
        long storeId = newStore("ST-P1", "P1");   // 无站点行的初始化态店铺
        assertEquals("ST-P1", storeService.get(storeId).storeNo(), "超管可读无站点行店铺");
        assertEquals(1, storeService.list(null, 0, 10).size(), "超管列表含无站点行店铺");

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class, () -> storeService.get(storeId),
                "普通用户不得读无站点行店铺");
        assertTrue(storeService.list(null, 0, 10).isEmpty(), "无站点行店铺不进普通用户列表");
        assertThrows(AccessDeniedException.class, () -> storeService.save(
                new StoreInput("ST-P2", 1L, null, null, null, null)), "普通用户不可建店");
        assertThrows(AccessDeniedException.class, () -> storeService.update(storeId,
                new StoreInput("ST-P1", 1L, null, null, null, null)), "普通用户不可改店");
        assertThrows(AccessDeniedException.class, () -> storeService.setStatus(storeId, "INACTIVE"),
                "普通用户不可停用店");

        login(1L);
        siteService.save(new SiteInput("AE", storeId, "UAE", "AED", null, null, null));
        siteService.save(new SiteInput("SA", storeId, "KSA", "SAR", null, null, null));

        login(2L);
        assertNotNull(storeService.get(storeId), "店铺有 AE 站点后，AE 用户可读跨站共享店铺");
        assertEquals(1, storeService.list(null, 0, 10).size());
        assertEquals(1, storeService.list("AE", 0, 10).size());
        assertThrows(AccessDeniedException.class, () -> storeService.list("SA", 0, 10));
        // 有 AE 站点也不能写跨站共享店铺（写仅超管）。
        assertThrows(AccessDeniedException.class, () -> storeService.update(storeId,
                new StoreInput("ST-P1", 1L, "x", null, null, null)));

        login(3L); // 无任何站点
        assertTrue(storeService.list(null, 0, 10).isEmpty());
        assertThrows(AccessDeniedException.class, () -> storeService.get(storeId));
    }

    @Test
    void skuPermissionRequiresSiteToReadAndAdminToWrite()
    {
        login(1L);
        long skuId = skuService.save(new SkuInput("SKU-PERM", null, null, null, null, null, null, null, null, null));
        assertNotNull(skuService.get(skuId));

        login(2L); // 仅 AE
        assertEquals(1, skuService.list("SA", 0, 10).size(), "有授权站点即可读，站点参数被忽略");
        assertNotNull(skuService.get(skuId));
        assertThrows(AccessDeniedException.class, () -> skuService.save(
                new SkuInput("SKU-PERM2", null, null, null, null, null, null, null, null, null)), "SKU 写仅超管");
        assertThrows(AccessDeniedException.class, () -> skuService.setStatus(skuId, "INACTIVE"));

        login(3L); // 无站点
        assertThrows(AccessDeniedException.class, () -> skuService.list(null, 0, 10),
                "登录不等于业务授权，无站点拒绝读 SKU");
        assertThrows(AccessDeniedException.class, () -> skuService.get(skuId));
    }

    @Test
    void storeSiteCrudAndSiteFilteredList()
    {
        login(1L);
        long storeId = newStore("ST-001", "Night Market Trading");
        assertEquals("Night Market Trading", storeService.get(storeId).legalEntity());

        long ae = siteService.save(new SiteInput("AE", storeId, "UAE", "AED",
                new BigDecimal("0.0500"), null, null));
        long sa = siteService.save(new SiteInput("SA", storeId, "KSA", "SAR",
                new BigDecimal("0.1500"), "Asia/Riyadh", "ACTIVE"));
        assertEquals("Asia/Dubai", siteService.get(ae).timezone(), "timezone 空值走 DDL 默认口径");
        assertEquals(0, new BigDecimal("0.0500").compareTo(siteService.get(ae).vatRate()));

        assertEquals(2, siteService.list(null, 0, 10).size());
        assertEquals(1, siteService.list("AE", 0, 10).size());

        storeService.update(storeId, new StoreInput("ST-001", 1L, "Night Market", null, "Night Market LLC", "ACTIVE"));
        assertEquals("Night Market LLC", storeService.get(storeId).legalEntity());
        storeService.setStatus(storeId, "INACTIVE");
        assertEquals("INACTIVE", storeService.get(storeId).status());
        assertNotNull(storeService.get(storeId), "停用后仍可读，不存在物理删除");
        siteService.setStatus(sa, "INACTIVE");
        assertEquals("INACTIVE", siteService.get(sa).status());
    }

    @Test
    void warehouseAndPartnerSkuCrud()
    {
        login(1L);
        long storeId = newStore("ST-002", "Souq Plus");
        siteService.save(new SiteInput("AE", storeId, "UAE", "AED", new BigDecimal("0.05"), null, null));
        long skuId = skuService.save(new SkuInput("SKU-1", "SPU-1", "Desk Lamp", "مصباح", "LIGHT",
                "9405", "CN", "BrandX", "{\"weightKg\":1.2}", null));

        long wh = warehouseService.save(new WarehouseInput("WH-AE-1", "AE", "Dubai FBN",
                "FBN", "FBN", true, null));
        WarehouseRow row = warehouseService.get(wh);
        assertEquals("FBN", row.fulfillmentMode());
        assertTrue(row.isFbn());
        assertEquals(1, warehouseService.list("AE", 0, 10).size());

        long mapping = partnerSkuService.save(new PartnerSkuInput("P-SKU-1", skuId, "AE", null,
                "OFFER-1", "6291041500213", null));
        PartnerSkuRow mapped = partnerSkuService.get(mapping);
        assertEquals("noon", mapped.businessModel(), "业务模式空值默认 noon");
        assertEquals(skuId, mapped.skuId());
        assertEquals(1, partnerSkuService.list(null, 0, 10).size());
    }

    @Test
    void uniquenessConflictsAreTranslated()
    {
        login(1L);
        long storeId = newStore("ST-003", "A");
        siteService.save(new SiteInput("AE", storeId, "UAE", "AED", null, null, null));
        long skuId = skuService.save(new SkuInput("SKU-U", null, null, null, null, null, null, null, null, null));
        long wh = warehouseService.save(new WarehouseInput("WH-U", "AE", null, "LOCAL", null, false, null));

        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> storeService.save(new StoreInput("ST-003", 1L, null, null, null, null)));
        assertTrue(e1.getMessage().contains("店铺编号"), e1.getMessage());

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null)));
        assertTrue(e2.getMessage().contains("该站点已存在"), e2.getMessage());

        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.save(new WarehouseInput("WH-U", "AE", null, "3PL", null, false, null)));
        assertTrue(e3.getMessage().contains("仓库编码"), e3.getMessage());

        IllegalArgumentException e4 = assertThrows(IllegalArgumentException.class,
                () -> skuService.save(new SkuInput("SKU-U", null, null, null, null, null, null, null, null, null)));
        assertTrue(e4.getMessage().contains("SKU 编码"), e4.getMessage());

        partnerSkuService.save(new PartnerSkuInput("P-1", skuId, "AE", "noon", null, null, null));
        IllegalArgumentException e5 = assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-1", skuId, "AE", "NOON", null, null, null)));
        assertTrue(e5.getMessage().contains("Partner SKU 已存在"), e5.getMessage());
        partnerSkuService.save(new PartnerSkuInput("P-1", skuId, "AE", "supermall", null, null, null));
        assertEquals(wh, warehouseService.get(wh).id());
    }

    @Test
    void referenceIntegrityChecksExistenceAndActiveStatus()
    {
        login(1L);
        // store → partner：不存在 / INACTIVE 均拒绝。
        IllegalArgumentException noPartner = assertThrows(IllegalArgumentException.class,
                () -> storeService.save(new StoreInput("ST-R1", 999L, null, null, null, null)));
        assertTrue(noPartner.getMessage().contains("不存在"), noPartner.getMessage());
        IllegalArgumentException inactivePartner = assertThrows(IllegalArgumentException.class,
                () -> storeService.save(new StoreInput("ST-R1", 2L, null, null, null, null)));
        assertTrue(inactivePartner.getMessage().contains("已停用"), inactivePartner.getMessage());

        // site → store：不存在 / INACTIVE 均拒绝。
        long storeId = newStore("ST-R2", "B");
        IllegalArgumentException noStore = assertThrows(IllegalArgumentException.class,
                () -> siteService.save(new SiteInput("AE", 9999L, null, "AED", null, null, null)));
        assertTrue(noStore.getMessage().contains("店铺不存在"), noStore.getMessage());
        storeService.setStatus(storeId, "INACTIVE");
        IllegalArgumentException inactiveStore = assertThrows(IllegalArgumentException.class,
                () -> siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null)));
        assertTrue(inactiveStore.getMessage().contains("已停用"), inactiveStore.getMessage());
        storeService.setStatus(storeId, "ACTIVE");
        long ae = siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));

        // warehouse → site：未维护 / INACTIVE 均拒绝。
        IllegalArgumentException noSite = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.save(new WarehouseInput("WH-X", "SA", null, "LOCAL", null, false, null)));
        assertTrue(noSite.getMessage().contains("站点未维护"), noSite.getMessage());
        siteService.setStatus(ae, "INACTIVE");
        IllegalArgumentException inactiveSite = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.save(new WarehouseInput("WH-Y", "AE", null, "LOCAL", null, false, null)));
        assertTrue(inactiveSite.getMessage().contains("已停用"), inactiveSite.getMessage());
        siteService.setStatus(ae, "ACTIVE");
        IllegalArgumentException badCode = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.save(new WarehouseInput("WH-Z", "XX", null, "LOCAL", null, false, null)));
        assertTrue(badCode.getMessage().contains("AE/SA"), badCode.getMessage());

        // partner_sku → sku / site：不存在或 INACTIVE 均拒绝。
        long skuId = skuService.save(new SkuInput("SKU-R", null, null, null, null, null, null, null, null, null));
        IllegalArgumentException missingSku = assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-R", 8888L, "AE", null, null, null, null)));
        assertTrue(missingSku.getMessage().contains("SKU 不存在"), missingSku.getMessage());
        skuService.setStatus(skuId, "INACTIVE");
        IllegalArgumentException inactiveSku = assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-R", skuId, "AE", null, null, null, null)));
        assertTrue(inactiveSku.getMessage().contains("已停用"), inactiveSku.getMessage());
        IllegalArgumentException noMappingSite = assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-S", skuId, "SA", null, null, null, null)));
        assertTrue(noMappingSite.getMessage().contains("站点未维护"), noMappingSite.getMessage());
    }

    @Test
    void fulfillmentModeIsImmutableAndFbnMustMatchType()
    {
        login(1L);
        long storeId = newStore("ST-005", "C");
        siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));
        long wh = warehouseService.save(new WarehouseInput("WH-F", "AE", null, "FBN", "GLOBAL", true, null));

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.update(wh, new WarehouseInput("WH-F", "AE", null, "FBN", "FBP", true, null)));
        assertTrue(changed.getMessage().contains("不可变更"), changed.getMessage());
        warehouseService.update(wh, new WarehouseInput("WH-F", "AE", "Renamed", "FBN", null, true, null));
        assertEquals("Renamed", warehouseService.get(wh).warehouseName());
        assertEquals("GLOBAL", warehouseService.get(wh).fulfillmentMode());

        IllegalArgumentException wrongFbn = assertThrows(IllegalArgumentException.class,
                () -> warehouseService.save(new WarehouseInput("WH-L", "AE", null, "LOCAL", null, true, null)));
        assertTrue(wrongFbn.getMessage().contains("isFbn"), wrongFbn.getMessage());
    }

    @Test
    void siteCodeAndStoreOwnershipAreImmutable()
    {
        login(1L);
        long storeA = newStore("ST-007", "E");
        long storeB = newStore("ST-008", "F");
        long ae = siteService.save(new SiteInput("AE", storeA, "UAE", "AED", null, null, null));

        IllegalArgumentException codeChanged = assertThrows(IllegalArgumentException.class,
                () -> siteService.update(ae, new SiteInput("SA", storeA, null, "AED", null, null, null)));
        assertTrue(codeChanged.getMessage().contains("站点编码创建后不可变更"), codeChanged.getMessage());

        IllegalArgumentException storeChanged = assertThrows(IllegalArgumentException.class,
                () -> siteService.update(ae, new SiteInput(null, storeB, null, "AED", null, null, null)));
        assertTrue(storeChanged.getMessage().contains("店铺归属创建后不可变更"), storeChanged.getMessage());

        siteService.update(ae, new SiteInput(null, null, "UAE", "AED",
                new BigDecimal("0.0500"), "Asia/Dubai", "ACTIVE"));
        assertEquals("AE", siteService.get(ae).siteCode());
        assertEquals(storeA, siteService.get(ae).storeId());
        assertEquals(0, new BigDecimal("0.0500").compareTo(siteService.get(ae).vatRate()));
    }

    @Test
    void crossSiteAccessIsDeniedAndEmptyGrantYieldsZeroRows()
    {
        login(1L);
        long storeId = newStore("ST-006", "D");
        long ae = siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));
        long sa = siteService.save(new SiteInput("SA", storeId, null, "SAR", null, null, null));
        long skuId = skuService.save(new SkuInput("SKU-S", null, null, null, null, null, null, null, null, null));

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class, () -> siteService.get(sa));
        assertThrows(AccessDeniedException.class, () -> siteService.list("SA", 0, 10));
        assertThrows(AccessDeniedException.class,
                () -> warehouseService.save(new WarehouseInput("WH-SA", "SA", null, "LOCAL", null, false, null)));
        assertThrows(AccessDeniedException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-SA", skuId, "SA", null, null, null, null)));
        assertEquals(1, siteService.list(null, 0, 10).size(), "只看到 AE");
        assertEquals(1, storeService.list(null, 0, 10).size(), "店铺经站点关联后可见");
        assertEquals("AE", siteService.get(ae).siteCode(), "授权站点详情可读");

        login(3L); // 无授权
        assertTrue(storeService.list(null, 0, 10).isEmpty(), "空授权必须零行");
        assertTrue(siteService.list(null, 0, 10).isEmpty());
        assertTrue(partnerSkuService.list(null, 0, 10).isEmpty());
        assertThrows(AccessDeniedException.class, () -> skuService.list("SA", 0, 10), "SKU 读需至少一个站点");
    }

    @Test
    void offerColumnsStayUntouchedAndInvalidJsonRejected()
    {
        login(1L);
        long storeId = newStore("ST-009", "G");
        siteService.save(new SiteInput("AE", storeId, null, "AED", null, null, null));
        long skuId = skuService.save(new SkuInput("SKU-O", null, null, null, null, null, null, null,
                "{\"lengthCm\":30}", null));
        IllegalArgumentException badJson = assertThrows(IllegalArgumentException.class,
                () -> skuService.save(new SkuInput("SKU-O2", null, null, null, null, null, null, null,
                        "{not-json}", null)));
        assertTrue(badJson.getMessage().contains("合法 JSON"), badJson.getMessage());

        long mapping = partnerSkuService.save(new PartnerSkuInput("P-O", skuId, "AE", null, null, null, null));
        assertNull(jdbc.queryForObject("select live_status from md_partner_sku where id=?", Boolean.class, mapping),
                "Offer 回读列不得由本模块写入");
        assertNull(jdbc.queryForObject("select offer_issues from md_partner_sku where id=?", String.class, mapping));
        assertNull(jdbc.queryForObject("select synced_at from md_partner_sku where id=?", java.sql.Timestamp.class, mapping));
        assertEquals("{\"lengthCm\":30}", skuService.get(skuId).specJson());
    }

    @Test
    void auditRecordsBeforeAfterForAllEntitiesAndRollsBackOnFailure()
    {
        login(1L);
        long storeId = newStore("ST-A1", "Audit Store");
        siteService.save(new SiteInput("AE", storeId, "UAE", "AED", null, null, null));
        siteService.update(siteService.list("AE", 0, 1).get(0).id(),
                new SiteInput("AE", storeId, "UAE", "AED", new BigDecimal("0.0500"), null, null));
        siteService.setStatus(siteService.list("AE", 0, 1).get(0).id(), "ACTIVE");
        long skuId = skuService.save(new SkuInput("SKU-A1", null, null, null, null, null, null, null, null, null));
        skuService.setStatus(skuId, "INACTIVE");
        skuService.setStatus(skuId, "ACTIVE");
        long wh = warehouseService.save(new WarehouseInput("WH-A1", "AE", null, "FBN", "FBN", true, null));
        warehouseService.setStatus(wh, "INACTIVE");
        long mapping = partnerSkuService.save(new PartnerSkuInput("P-A1", skuId, "AE", null, null, null, null));
        partnerSkuService.setStatus(mapping, "INACTIVE");
        storeService.update(storeId, new StoreInput("ST-A1", 1L, "New Name", null, null, null));
        storeService.setStatus(storeId, "INACTIVE");

        // 5 类实体各自有审计；新增 before 为 NULL，更新 before/after 均在；操作人取真实登录人。
        for (String entity : new String[]{StoreService.ENTITY, SiteService.ENTITY, WarehouseService.ENTITY,
                SkuService.ENTITY, PartnerSkuService.ENTITY})
        {
            assertTrue(jdbc.queryForObject("select count(*) from noon_m2_audit_log where entity_type=?",
                    Integer.class, entity) >= 2, "缺少审计: " + entity);
            assertTrue(jdbc.queryForObject("select max(operator_id) from noon_m2_audit_log where entity_type=?",
                    Long.class, entity) == 1L, "操作人应为登录超管: " + entity);
            assertTrue(jdbc.queryForObject("select count(*) from noon_m2_audit_log "
                    + "where entity_type=? and action='INSERT' and before_json is null", Integer.class, entity) >= 1);
            assertTrue(jdbc.queryForObject("select count(*) from noon_m2_audit_log "
                    + "where entity_type=? and before_json is not null and after_json is not null",
                    Integer.class, entity) >= 1, "缺少 before/after: " + entity);
        }
        String afterJson = jdbc.queryForObject("select after_json from noon_m2_audit_log "
                + "where entity_type='STORE' and action='UPDATE' order by id desc limit 1", String.class);
        assertTrue(afterJson.contains("\"nameEn\":\"New Name\""), afterJson);
        assertEquals("user1", jdbc.queryForObject("select operator_name from noon_m2_audit_log "
                + "where entity_type='STORE' order by id limit 1", String.class));

        // 失败回滚：业务异常不产生审计行（同事务）。
        int beforeCount = auditCount();
        long aeSite = siteService.list("AE", 0, 1).get(0).id();
        assertThrows(IllegalArgumentException.class,
                () -> siteService.update(aeSite, new SiteInput("SA", storeId, null, "AED", null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> skuService.save(new SkuInput("SKU-A1", null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> partnerSkuService.save(new PartnerSkuInput("P-A1", skuId, "AE", "noon", null, null, null)));
        assertEquals(beforeCount, auditCount(), "失败的写入/校验不得留下审计");
    }

    // ------------------------------------------------- v3 分页/搜索（PageResult + XxxQuery）

    @Test
    void storeQueryFiltersHitMissAndRealTotal()
    {
        login(1L);
        newStore("ST-NIGHT-1", "Night Market");
        newStore("ST-NIGHT-2", "Night Market FZE");
        newStore("ST-SOUQ-1", "Souq Plus");

        PageResult<StoreRow> all = storeService.list(null, 0, 10, null);
        assertEquals(3, all.total(), "无筛选时 total 为全部行数");
        assertEquals(3, all.rows().size());

        PageResult<StoreRow> byCode = storeService.list(null, 0, 10, new StoreQuery("  NIGHT ", null, null));
        assertEquals(2, byCode.total(), "storeNo 模糊命中且大小写不敏感、去空白");
        assertEquals(2, byCode.rows().size());

        PageResult<StoreRow> byName = storeService.list(null, 0, 10, new StoreQuery(null, " souq ", null));
        assertEquals(1, byName.total(), "nameEn 模糊命中");
        assertEquals("ST-SOUQ-1", byName.rows().get(0).storeNo());

        PageResult<StoreRow> combined = storeService.list(null, 0, 10, new StoreQuery("ST-NIGHT", "fze", null));
        assertEquals(1, combined.total(), "多个条件按 AND 组合");
        assertEquals("ST-NIGHT-2", combined.rows().get(0).storeNo());

        PageResult<StoreRow> miss = storeService.list(null, 0, 10, new StoreQuery("ZZZ", null, null));
        assertEquals(0, miss.total(), "未命中时 total=0 而非全表数");
        assertTrue(miss.rows().isEmpty());

        // total 必须是筛选后的真实总数：limit 截断 rows，但不能截断 total。
        PageResult<StoreRow> truncated = storeService.list(null, 0, 1, new StoreQuery("ST-NIGHT", null, null));
        assertEquals(1, truncated.rows().size());
        assertEquals(2, truncated.total(), "total 不受 LIMIT 影响");

        long souqId = storeService.list(null, 0, 10, new StoreQuery("ST-SOUQ-1", null, null)).rows().get(0).id();
        storeService.setStatus(souqId, "INACTIVE");
        assertEquals(1, storeService.list(null, 0, 10, new StoreQuery(null, null, "inactive")).total(),
                "status 精确匹配且大小写不敏感");
        assertThrows(IllegalArgumentException.class,
                () -> storeService.list(null, 0, 10, new StoreQuery(null, null, "DELETED")),
                "非法 status 按 MasterDataRules.status 拒绝");
    }

    @Test
    void storePagingWalksWithoutDuplicatesOrGaps()
    {
        login(1L);
        for (int i = 1; i <= 7; i++) newStore("PG-0" + i, "Paged Store " + i);

        List<Long> expected = ids(storeService.list(null, 0, 50,
                new StoreQuery("pg-", null, null)).rows(), StoreRow::id);
        assertEquals(7, expected.size());

        PageResult<StoreRow> page = storeService.list(null, 0, 2, new StoreQuery("pg-", null, null));
        assertEquals(7, page.total(), "每页条数不改变 total");
        assertPageWalk(expected, 2, (offset, size) -> ids(storeService.list(null, offset, size,
                new StoreQuery("pg-", null, null)).rows(), StoreRow::id));
    }

    @Test
    void storeNonAdminFilterAndTotalRespectSiteAuthorization()
    {
        login(1L);
        long storeAe = newStore("ST-AE-A", "Shared Alpha");
        long storeSa = newStore("ST-SA-B", "Shared Alpha");
        long storeAe2 = newStore("ST-AE-C", "Second AE");
        long storeNone = newStore("ST-NONE-D", "No Site Store");
        siteService.save(new SiteInput("AE", storeAe, null, "AED", null, null, null));
        siteService.save(new SiteInput("AE", storeAe2, null, "AED", null, null, null));
        siteService.save(new SiteInput("SA", storeSa, null, "SAR", null, null, null));

        assertEquals(4, storeService.list(null, 0, 10, null).total(), "超管看到全部（含无站点行）");
        assertEquals(2, storeService.list(null, 0, 10, new StoreQuery(null, "shared alpha", null)).total(),
                "超管按名称筛选跨站同名店铺");

        login(2L); // 仅 AE
        PageResult<StoreRow> aeAll = storeService.list(null, 0, 10, null);
        assertEquals(2, aeAll.total(), "普通用户 total 只统计授权站点命中的店铺");
        assertEquals(2, aeAll.rows().size());
        assertTrue(aeAll.rows().stream().noneMatch(r -> r.id() == storeNone || r.id() == storeSa));

        PageResult<StoreRow> aeShared = storeService.list(null, 0, 10, new StoreQuery(null, "shared alpha", null));
        assertEquals(1, aeShared.total(), "同名跨站店铺只统计授权站点内的一条");
        assertEquals(storeAe, aeShared.rows().get(0).id());

        assertEquals(0, storeService.list(null, 0, 10, new StoreQuery("ST-SA-B", null, null)).total(),
                "未授权站点店铺即使命中条件也不计入 total");
        assertEquals(2, storeService.list("AE", 0, 10, new StoreQuery("ST-AE", null, null)).total(),
                "入参 siteCode 作为店铺站点过滤");

        login(3L); // 无授权
        PageResult<StoreRow> zero = storeService.list(null, 0, 10, new StoreQuery("ST-AE-A", null, null));
        assertEquals(0, zero.total(), "空授权 total=0");
        assertTrue(zero.rows().isEmpty(), "空授权零行且不因筛选条件绕开授权");
    }

    @Test
    void siteQueryUsesAuthGateAndQuerySiteIntersection()
    {
        login(1L);
        long[] stores = {newStore("ST-S-1", "S1"), newStore("ST-S-2", "S2"), newStore("ST-S-3", "S3")};
        for (long store : stores)
        {
            siteService.save(new SiteInput("AE", store, null, "AED", null, null, null));
            siteService.save(new SiteInput("SA", store, null, "SAR", null, null, null));
        }

        PageResult<SiteRow> ae = siteService.list(null, 0, 10, new SiteQuery("AE", null, null));
        assertEquals(3, ae.total(), "Query.siteCode 作为站点筛选");
        assertEquals(3, ae.rows().size());

        PageResult<SiteRow> oneStore = siteService.list(null, 0, 10, new SiteQuery(null, stores[0], null));
        assertEquals(2, oneStore.total(), "storeId 精确匹配");
        assertTrue(oneStore.rows().stream().allMatch(r -> r.storeId() == stores[0]));

        assertEquals(0, siteService.list(null, 0, 10, new SiteQuery(null, 9999L, null)).total(),
                "storeId 未命中 total=0");
        assertEquals(6, siteService.list(null, 0, 10, null).total(), "无筛选 total 为授权内全部站点");

        List<Long> expected = ids(ae.rows(), SiteRow::id);
        assertPageWalk(expected, 2, (offset, size) -> ids(siteService.list(null, offset, size,
                new SiteQuery("AE", null, null)).rows(), SiteRow::id));

        login(2L); // 仅 AE，拿 SA 作为条件不能越权读取
        PageResult<SiteRow> conflict = siteService.list(null, 0, 10, new SiteQuery("SA", null, null));
        assertEquals(0, conflict.total(), "条件站点与授权闸门交集为空即零行");
        assertTrue(conflict.rows().isEmpty());

        PageResult<SiteRow> sameSite = siteService.list("AE", 0, 10, new SiteQuery("AE", null, null));
        assertEquals(3, sameSite.total(), "入参与 Query 同一站点不重复绑定、不产生冲突条件");
        assertEquals(3, sameSite.rows().size());

        login(3L);
        assertEquals(0, siteService.list(null, 0, 10, new SiteQuery("AE", null, null)).total(), "空授权零行");
    }

    @Test
    void warehouseQueryPagingAndCrossSiteScope()
    {
        login(1L);
        long store = newStore("ST-W-1", "W");
        siteService.save(new SiteInput("AE", store, null, "AED", null, null, null));
        siteService.save(new SiteInput("SA", store, null, "SAR", null, null, null));
        for (int i = 1; i <= 5; i++)
            warehouseService.save(new WarehouseInput("WH-A-" + i, "AE", "AE FBN " + i, "LOCAL", null, false, null));
        for (int i = 1; i <= 2; i++)
            warehouseService.save(new WarehouseInput("WH-S-" + i, "SA", "SA FBN " + i, "LOCAL", null, false, null));

        PageResult<WarehouseRow> aeRows = warehouseService.list(null, 0, 10,
                new WarehouseQuery(" wh-a ", null, null));
        assertEquals(5, aeRows.total(), "warehouseCode 模糊 + 大小写不敏感");
        assertEquals(5, aeRows.rows().size());

        PageResult<WarehouseRow> truncated = warehouseService.list(null, 0, 3,
                new WarehouseQuery("WH-", null, null));
        assertEquals(3, truncated.rows().size());
        assertEquals(7, truncated.total(), "total 为筛选后总数，不受 LIMIT 截断");

        assertEquals(0, warehouseService.list(null, 0, 10,
                new WarehouseQuery("NOPE", null, null)).total(), "未命中 total=0");

        List<Long> expected = ids(aeRows.rows(), WarehouseRow::id);
        assertPageWalk(expected, 2, (offset, size) -> ids(warehouseService.list(null, offset, size,
                new WarehouseQuery("WH-A", null, null)).rows(), WarehouseRow::id));

        login(2L); // 仅 AE
        assertEquals(5, warehouseService.list(null, 0, 10, null).total(), "普通用户只统计授权站点");
        assertEquals(0, warehouseService.list(null, 0, 10,
                new WarehouseQuery(null, "SA", null)).total(), "条件站点不在授权内即零行");
        assertEquals(5, warehouseService.list("AE", 0, 10,
                new WarehouseQuery(null, "AE", null)).total(), "重复站点条件不冲突");

        login(3L);
        assertEquals(0, warehouseService.list(null, 0, 10,
                new WarehouseQuery("WH-A", null, null)).total(), "空授权 total=0");

        login(4L); // AE+SA 两站点授权：验证 IN 多占位符与 LIMIT/OFFSET 参数顺序
        PageResult<WarehouseRow> both = warehouseService.list(null, 0, 10, new WarehouseQuery("WH-", null, null));
        assertEquals(7, both.total(), "双站点授权覆盖 AE/SA 全部仓库");
        List<Long> bothIds = ids(both.rows(), WarehouseRow::id);
        assertPageWalk(bothIds, 3, (offset, size) -> ids(warehouseService.list(null, offset, size,
                new WarehouseQuery("WH-", null, null)).rows(), WarehouseRow::id));
    }

    @Test
    void skuQueryPagingAndIgnoresSiteCodeFilter()
    {
        login(1L);
        for (int i = 1; i <= 5; i++)
            skuService.save(new SkuInput("SKU-Q-" + i, null, "Desk Lamp " + i, null, "LIGHT",
                    null, null, i % 2 == 0 ? "BrandY" : "BrandX", null, null));

        PageResult<SkuRow> byCode = skuService.list("SA", 0, 10, new SkuQuery(" sku-q ", null, null, null));
        assertEquals(5, byCode.total(), "siteCode 被忽略，skuCode 模糊命中");
        assertEquals(5, byCode.rows().size());

        assertEquals(2, skuService.list(null, 0, 10, new SkuQuery(null, null, "brandy", null)).total(),
                "brand 模糊命中");
        assertEquals(1, skuService.list(null, 0, 10,
                new SkuQuery(null, "lamp 1", "brandx", null)).total(), "多条件 AND");
        assertEquals(0, skuService.list(null, 0, 10, new SkuQuery(null, "phone", null, null)).total(),
                "未命中 total=0");

        PageResult<SkuRow> truncated = skuService.list(null, 0, 4, new SkuQuery("SKU-Q", null, null, null));
        assertEquals(4, truncated.rows().size());
        assertEquals(5, truncated.total(), "total 不受 LIMIT 截断");

        List<Long> expected = ids(skuService.list(null, 0, 50, new SkuQuery("SKU-Q", null, null, null)).rows(),
                SkuRow::id);
        assertPageWalk(expected, 2, (offset, size) -> ids(skuService.list(null, offset, size,
                new SkuQuery("SKU-Q", null, null, null)).rows(), SkuRow::id));

        login(2L); // 仅 AE，仍可读全局 SKU
        assertEquals(5, skuService.list("SA", 0, 10, new SkuQuery("SKU-Q", null, null, null)).total());

        login(3L); // 空授权：沿用既有规则（拒绝而非零行），与 3 参重载一致
        assertThrows(AccessDeniedException.class,
                () -> skuService.list(null, 0, 10, new SkuQuery("SKU-Q", null, null, null)));
    }

    @Test
    void partnerSkuQueryPagingAndSiteScope()
    {
        login(1L);
        long store = newStore("ST-PS-1", "PS");
        siteService.save(new SiteInput("AE", store, null, "AED", null, null, null));
        siteService.save(new SiteInput("SA", store, null, "SAR", null, null, null));
        long sku1 = skuService.save(new SkuInput("SKU-PS-1", null, null, null, null, null, null, null, null, null));
        long sku2 = skuService.save(new SkuInput("SKU-PS-2", null, null, null, null, null, null, null, null, null));
        for (int i = 1; i <= 5; i++)
            partnerSkuService.save(new PartnerSkuInput("P-A-" + i, i <= 3 ? sku1 : sku2, "AE", null, null, null, null));
        partnerSkuService.save(new PartnerSkuInput("P-S-1", sku1, "SA", null, null, null, null));

        PageResult<PartnerSkuRow> aeRows = partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery(" p-a ", null, null, null));
        assertEquals(5, aeRows.total(), "partnerSku 模糊命中");
        assertEquals(5, aeRows.rows().size());

        assertEquals(4, partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery(null, sku1, null, null)).total(), "skuId 精确匹配（含 SA 站点的 1 条）");
        assertEquals(0, partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery("P-S-1", null, "AE", null)).total(), "条件站点与记录站点取交集");
        assertEquals(1, partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery(null, null, "SA", null)).total(), "Query.siteCode 单站筛选");

        PageResult<PartnerSkuRow> truncated = partnerSkuService.list(null, 0, 2,
                new PartnerSkuQuery("P-", null, null, null));
        assertEquals(2, truncated.rows().size());
        assertEquals(6, truncated.total());

        List<Long> expected = ids(aeRows.rows(), PartnerSkuRow::id);
        assertPageWalk(expected, 2, (offset, size) -> ids(partnerSkuService.list(null, offset, size,
                new PartnerSkuQuery("P-A", null, null, null)).rows(), PartnerSkuRow::id));

        login(2L); // 仅 AE
        assertEquals(5, partnerSkuService.list(null, 0, 10, null).total(), "普通用户只统计授权站点映射");
        assertEquals(0, partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery("P-S", null, null, null)).total(), "未授权站点映射不计入 total");

        login(3L);
        PageResult<PartnerSkuRow> zero = partnerSkuService.list(null, 0, 10,
                new PartnerSkuQuery("P-A", null, null, null));
        assertEquals(0, zero.total(), "空授权 total=0");
        assertTrue(zero.rows().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<Long> ids(List<T> rows, java.util.function.Function<T, Long> id)
    {
        return rows.stream().map(id).toList();
    }

    /** 按 pageSize 逐页取 id，要求与完整列表顺序一致：无重复、无遗漏、每页条数正确。 */
    private static void assertPageWalk(List<Long> expected, int pageSize,
                                       java.util.function.BiFunction<Integer, Integer, List<Long>> page)
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

    private int auditCount()
    {
        return jdbc.queryForObject("select count(*) from noon_m2_audit_log", Integer.class);
    }

    /** 测试用建店：nameEn 与 legalEntity 同值，便于验证名称模糊搜索且不影响既有断言。 */
    private long newStore(String storeNo, String name)
    {
        return storeService.save(new StoreInput(storeNo, 1L, name, null, name, null));
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
     * H2 宽松建表：列名与 noon_m2_masterdata.sql 对齐；spec_json/审计 JSON 用文本列，
     * 唯一键命名与 MySQL 一致便于验证"冲突转中文异常"；CHECK/JSON/表选项由隔离 MySQL IT 验证。
     */
    private void createMasterDataTables()
    {
        jdbc.execute("create table md_partner_account (id bigint auto_increment primary key, "
                + "project_code varchar(64) not null, account_name varchar(128), env varchar(16) not null default 'SANDBOX', "
                + "status varchar(16) not null default 'ACTIVE', constraint uk_project unique(project_code))");
        jdbc.update("insert into md_partner_account(project_code,account_name,status) "
                + "values ('PRJ-1','Partner One','ACTIVE'), ('PRJ-2','Partner Two','INACTIVE')");
        jdbc.execute("create table md_store (id bigint auto_increment primary key, store_no varchar(64) not null, "
                + "partner_id bigint not null, name_en varchar(256), name_ar varchar(256), legal_entity varchar(256), "
                + "status varchar(16) not null default 'ACTIVE', created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_store_no unique(store_no))");
        jdbc.execute("create table md_site (id bigint auto_increment primary key, site_code varchar(16) not null, "
                + "store_id bigint not null, country varchar(64), currency char(3) not null, "
                + "vat_rate decimal(6,4) default 0, timezone varchar(64) default 'Asia/Dubai', "
                + "status varchar(16) not null default 'ACTIVE', created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_store_site unique(store_id, site_code))");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, warehouse_code varchar(64) not null, "
                + "site_code varchar(16) not null, warehouse_name varchar(256), warehouse_type varchar(24) not null, "
                + "fulfillment_mode varchar(24) not null default 'FBN', is_fbn tinyint not null default 0, "
                + "status varchar(16) not null default 'ACTIVE', created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_wh_code unique(warehouse_code, site_code))");
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "spu_id varchar(64), name_en varchar(512), name_ar varchar(512), category_code varchar(64), "
                + "hs_code varchar(32), origin_country varchar(32), brand varchar(128), image_url varchar(512), spec_json varchar(4000), "
                + "status varchar(16) not null default 'ACTIVE', created_at timestamp(3) not null default current_timestamp(3), "
                + "updated_at timestamp(3) not null default current_timestamp(3), constraint uk_sku_code unique(sku_code))");
        jdbc.execute("create table md_partner_sku (id bigint auto_increment primary key, partner_sku varchar(128) not null, "
                + "sku_id bigint not null, site_code varchar(16) not null, business_model varchar(24) not null default 'noon', "
                + "offer_code varchar(128), barcode varchar(128), noon_sku varchar(128), image_url varchar(512), "
                + "live_status tinyint default null, offer_issues varchar(4000), "
                + "status varchar(16) not null default 'ACTIVE', synced_at timestamp(3), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_partner_sku unique(partner_sku, site_code, business_model))");
        jdbc.execute("create table noon_m2_audit_log (id bigint auto_increment primary key, "
                + "entity_type varchar(32) not null, entity_id bigint not null, action varchar(16) not null, "
                + "operator_id bigint, operator_name varchar(64), before_json varchar(4000), after_json varchar(4000), "
                + "changed_at timestamp(3) not null)");
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
