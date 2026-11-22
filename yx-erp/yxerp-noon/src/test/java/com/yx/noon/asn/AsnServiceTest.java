package com.yx.noon.asn;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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

import com.yx.common.core.domain.entity.SysUser;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.inventory.InventoryAudit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 第三轮第三批 ASN 收货闭环 H2 单测（真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper +
 * 真 {@link SiteAuthGuard}）。
 *
 * <p>覆盖契约 §8：create/seal/receive 全链路守恒、幂等三态（首次/DONE 重放/内容冲突/PENDING）、
 * 跨动作隔离、DONE 重放零写入、shortEventId 缺失拒绝、按行分批收货、授权边界（无站点/跨站/
 * FBN 仓非本站点/非 FBN 仓/停用）、源批次并发扣减、失败回滚无 PENDING 残留。
 *
 * <p>H2 建表为宽松 DDL（不含 CHECK/JSON），真实 DDL 的 CHECK/并发与端到端由 {@code MySqlAsnIT} 验证。
 */
class AsnServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AsnService service;

    @BeforeEach
    void setUp() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
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
        service = context.getBean(AsnService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ------------------------------------------------------ create：只登记不动库存

    @Test
    void createRegistersDraftWithoutTouchingStock()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-AE", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-AE", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-C1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-C1", 10, "12.0000", LocalDateTime.of(2026, 6, 1, 9, 0));
        seedLedger(sku, wh, "AE", 10);

        String requestId = rid();
        List<AsnCreateLine> lines = List.of(new AsnCreateLine(sku, "B:SRC-C1", 8, null));
        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-AE", requestId, null, lines));
        assertFalse(created.replayed());
        assertEquals("CREATE", created.action());
        assertTrue(created.childBatchIds().isEmpty());
        assertTrue(created.asnNo().matches("ASN:AE:[0-9A-F]{8}"), created.asnNo());

        assertEquals("DRAFT", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals("AE", jdbc.queryForObject("select site_code from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals("WH-FBN-AE", jdbc.queryForObject("select warehouse_code from log_asn where id=?",
                String.class, created.asnId()));
        Map<String, Object> item = jdbc.queryForMap("select sku_id,batch_no,qty_shipped,qty_received,"
                + "diff_qty,partner_sku from log_asn_item where asn_id=?", created.asnId());
        assertEquals(sku, ((Number) item.get("sku_id")).longValue());
        assertEquals("B:SRC-C1", item.get("batch_no"));
        assertEquals(8, ((Number) item.get("qty_shipped")).intValue());
        assertEquals(0, ((Number) item.get("qty_received")).intValue());
        assertEquals(0, ((Number) item.get("diff_qty")).intValue());
        assertNull(item.get("partner_sku"));

        // create 不得动批次/台账/流水/消耗。
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-C1'",
                Integer.class), "create 不扣批次");
        assertEquals(10, ledgerAvailable(sku, wh), "create 不动源仓可用");
        assertEquals(0, count("inv_stock_move"), "create 不写流水");
        assertEquals(0, count("inv_batch where batch_no like 'B:ASN:%'"), "create 不生成子批次");
        assertEquals(0, count("inv_batch_consumption"));

        // 审计 + 幂等 DONE + asn_id 回填 + 指纹。
        Map<String, Object> auditRow = jdbc.queryForMap("select biz_type,action,biz_id,operator_id "
                + "from noon_m4_inventory_audit");
        assertEquals("ASN", auditRow.get("biz_type"));
        assertEquals("CREATE", auditRow.get("action"));
        assertEquals(String.valueOf(created.asnId()), auditRow.get("biz_id"));
        assertEquals(1L, ((Number) auditRow.get("operator_id")).longValue());
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_asn_request "
                + "where request_id=? and action='CREATE'", String.class, requestId));
        assertEquals(created.asnId(), jdbc.queryForObject("select asn_id from noon_m4_asn_request "
                + "where request_id=? and action='CREATE'", Long.class, requestId));
        assertEquals(AsnRules.createFingerprint("AE", "WH-FBN-AE", lines),
                jdbc.queryForObject("select content_fingerprint from noon_m4_asn_request "
                        + "where request_id=? and action='CREATE'", String.class, requestId));

        // list/get 反映登记结果。
        PageResult<AsnRow> page = service.list("AE", 0, 20);
        assertEquals(1, page.total());
        assertEquals(created.asnId(), page.rows().get(0).asnId());
        assertEquals(1, page.rows().get(0).itemCount());
        assertEquals(8, page.rows().get(0).qtyShippedTotal());
        assertEquals(0, page.rows().get(0).qtyReceivedTotal());
        AsnDetail detail = service.get(created.asnId());
        assertEquals("DRAFT", detail.head().status());
        assertEquals("LOCAL_BATCH", detail.head().sourceMode(), "旧调用默认本地批次模式");
        assertNull(detail.items().get(0).childBatchId(), "未封单无子批次");
    }

    @Test
    void createValidatesRowsAndFbnWarehouse()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-V1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-V1", "AE", "FBN", 1, "ACTIVE");
        newWarehouse("WH-LOCAL-V1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-V1-OFF", "AE", "FBN", 1, "INACTIVE");
        newWarehouse("WH-FBN-V1-SA", "SA", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-V1", "ACTIVE");
        long skuOff = newSku("SKU-ASN-V1-OFF", "INACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-V1", 10, "5.0000", LocalDateTime.of(2026, 6, 2, 9, 0));
        newBatch(sku, wh, "AE", "B:SRC-V1-CLOSED", 1, "5.0000", LocalDateTime.of(2026, 6, 2, 9, 0));
        jdbc.update("update inv_batch set qty_remaining=0, status='CLOSED' where batch_no='B:SRC-V1-CLOSED'");

        // 非 FBN 仓 → 400（不得把本地仓当 FBN 目标）。
        IllegalArgumentException notFbnErr = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-LOCAL-V1", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, null)))));
        assertTrue(notFbnErr.getMessage().contains("不是 FBN 仓"), notFbnErr.getMessage());
        // FBN 仓停用 → 400。
        IllegalArgumentException offErr = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-V1-OFF", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, null)))));
        assertTrue(offErr.getMessage().contains("已停用"), offErr.getMessage());
        // FBN 仓不存在 → 400。
        IllegalArgumentException missingErr = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-NOPE", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, null)))));
        assertTrue(missingErr.getMessage().contains("不存在"), missingErr.getMessage());
        // FBN 仓属于其它站点：按 (code, site) 解析不到本站点 → 400（不跨站找仓）。
        IllegalArgumentException crossFbn = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-V1-SA", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, null)))));
        assertTrue(crossFbn.getMessage().contains("不存在"), crossFbn.getMessage());

        // 跨站源批次（批次在 SA）→ 400。
        long whSa = newWarehouse("WH-ASN-SA", "SA", "LOCAL", 0, "ACTIVE");
        newBatch(sku, whSa, "SA", "B:SRC-V1-SA", 5, "5.0000", LocalDateTime.of(2026, 6, 2, 9, 0));
        IllegalArgumentException crossBatch = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-V1", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1-SA", 1, null)))));
        assertTrue(crossBatch.getMessage().contains("站点"), crossBatch.getMessage());

        // 批次关批 / SKU 停用 / 批次 SKU 不符 / 超剩余 / 重复行 / qty=0 / 无行 / null 入参。
        assertThrows(IllegalArgumentException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-V1", rid(), null, List.of(new AsnCreateLine(sku, "B:SRC-V1-CLOSED", 1, null)))));
        assertThrows(IllegalArgumentException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-V1", rid(), null, List.of(new AsnCreateLine(skuOff, "B:SRC-V1", 1, null)))));
        assertThrows(IllegalArgumentException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-V1", rid(), null, List.of(new AsnCreateLine(sku + 999L, "B:SRC-V1", 1, null)))));
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-V1", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 11, null)))));
        assertTrue(over.getMessage().contains("超过源批次剩余量"), over.getMessage());
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-V1", rid(), null,
                        List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, null),
                                new AsnCreateLine(sku, "B:SRC-V1", 2, null)))));
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-V1", rid(), null, List.of(new AsnCreateLine(sku, "B:SRC-V1", 0, null)))));
        assertThrows(IllegalArgumentException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-V1", rid(), null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.create(null));

        // 被拒请求零写入（幂等表/头/行/审计全空）。
        assertEquals(0, count("log_asn"));
        assertEquals(0, count("log_asn_item"));
        assertEquals(0, count("noon_m4_asn_request"));

        // 合法建单仍可通过，partnerSku trim 后落库。
        assertNotNull(service.create(new AsnCreateInput("AE", "WH-FBN-V1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-V1", 1, "  PARTNER-1  ")))));
        assertEquals("PARTNER-1", jdbc.queryForObject("select partner_sku from log_asn_item", String.class));
    }

    // ------------------------------------------------------ seal：拆批血缘 + 台账 + 流水

    @Test
    void sealSplitsBatchWithLineageLedgerAndAsnOut()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-S1", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-S1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-S1", "ACTIVE");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 1, 5, 8, 30, 15);
        long sourceBatch = newBatch(sku, wh, "AE", "B:SRC-S1", 10, "12.3400", receivedAt);
        seedLedger(sku, wh, "AE", 10);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-S1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-S1", 10, "P-1"))));
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());

        AsnResult sealed = service.seal(new AsnSealInput(created.asnId(), rid(), null));
        assertFalse(sealed.replayed());
        assertEquals("SEAL", sealed.action());
        assertEquals(1, sealed.childBatchIds().size());
        long child = sealed.childBatchIds().get(0);

        // 头：DRAFT → SEALED + sealed_at。
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertNotNull(jdbc.queryForObject("select sealed_at from log_asn where id=?", Timestamp.class,
                created.asnId()));

        // 源批次扣满关批；子批次血缘/状态/成本继承。
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, sourceBatch));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where id=? and batch_no like 'B:ASN:%' "
                        + "and source_type='TRANSFER_SPLIT' and status='IN_TRANSIT' and parent_batch_id=? "
                        + "and split_seq=1 and split_reason='ASN_TRANSIT' and asn_no=? and qty_in=10 "
                        + "and qty_remaining=10 and warehouse_id=? and site_code='AE' and transfer_no is null",
                Integer.class, child, sourceBatch, created.asnNo(), fbn), "子批次血缘/状态落库体");
        assertEquals(0, new BigDecimal("12.3400").compareTo(jdbc.queryForObject(
                "select unit_cost from inv_batch where id=?", BigDecimal.class, child)));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch c join inv_batch p "
                        + "on p.id=c.parent_batch_id where c.id=? and c.received_at=p.received_at "
                        + "and c.currency=p.currency and c.fx_rate=p.fx_rate",
                Integer.class, child), "成本/币种/fx/received_at 继承源批次");

        // 台账双边：源仓 available −10 → 0；FBN 仓 in_transit +10。
        assertEquals(0, ledgerAvailable(sku, wh));
        assertEquals(10, ledgerInTransit(sku, fbn));
        assertEquals(0, ledgerAvailable(sku, fbn));

        // 流水 ASN_OUT(−10, 源仓, ref=asnNo)。
        Map<String, Object> move = jdbc.queryForMap("select sku_id,warehouse_id,batch_id,change_type,"
                + "qty_delta,ref_doc_type,ref_doc_no,operator_id from inv_stock_move");
        assertEquals(sku, ((Number) move.get("sku_id")).longValue());
        assertEquals(wh, ((Number) move.get("warehouse_id")).longValue());
        assertEquals(child, ((Number) move.get("batch_id")).longValue());
        assertEquals("ASN_OUT", move.get("change_type"));
        assertEquals(-10, ((Number) move.get("qty_delta")).intValue());
        assertEquals("ASN", move.get("ref_doc_type"));
        assertEquals(created.asnNo(), move.get("ref_doc_no"));
        assertEquals(1L, ((Number) move.get("operator_id")).longValue());

        // 审计 SEAL + 幂等 DONE。
        assertEquals(1, count("noon_m4_inventory_audit where action='SEAL'"));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_asn_request where action='SEAL'",
                String.class));

        // 详情：行带子批次血缘。
        AsnDetail detail = service.get(created.asnId());
        assertEquals(itemId, detail.items().get(0).id());
        assertEquals(child, detail.items().get(0).childBatchId());
        assertEquals("IN_TRANSIT", detail.items().get(0).childStatus());
        assertEquals(1, detail.items().get(0).childSplitSeq());
    }

    @Test
    void sealRequiresDraftAndSplitsSeqPerParent()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-S2", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-S2", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-S2", "ACTIVE");
        long parent = newBatch(sku, wh, "AE", "B:SRC-S2", 30, "3.0000", LocalDateTime.of(2026, 6, 3, 9, 0));
        seedLedger(sku, wh, "AE", 30);

        // 两张 ASN 同父批次：split_seq 必须递增（1、2）。
        AsnResult a = service.create(new AsnCreateInput("AE", "WH-FBN-S2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-S2", 6, null))));
        AsnResult b = service.create(new AsnCreateInput("AE", "WH-FBN-S2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-S2", 4, null))));
        long childA = service.seal(new AsnSealInput(a.asnId(), rid(), null)).childBatchIds().get(0);
        long childB = service.seal(new AsnSealInput(b.asnId(), rid(), null)).childBatchIds().get(0);
        assertEquals(1, jdbc.queryForObject("select split_seq from inv_batch where id=?", Integer.class, childA));
        assertEquals(2, jdbc.queryForObject("select split_seq from inv_batch where id=?", Integer.class, childB));
        assertEquals(20, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, parent), "30 − 6 − 4 = 20");

        // 已 SEALED 再封单（新 requestId）→ 400，零写入。
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> service.seal(new AsnSealInput(a.asnId(), rid(), null)));
        assertTrue(again.getMessage().contains("仅 DRAFT"), again.getMessage());
        assertEquals(2, count("inv_batch where batch_no like 'B:ASN:%'"), "被拒封单不得生成子批次");

        // 剩余 20，两张 ASN 都计划 15：先封一张后剩 5（仍 OPEN），另一张必须被剩余量拒绝并整体回滚。
        AsnResult c = service.create(new AsnCreateInput("AE", "WH-FBN-S2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-S2", 15, null))));
        AsnResult d = service.create(new AsnCreateInput("AE", "WH-FBN-S2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-S2", 15, null))));
        assertEquals(1, service.seal(new AsnSealInput(c.asnId(), rid(), null)).childBatchIds().size());
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.seal(new AsnSealInput(d.asnId(), rid(), null)));
        assertTrue(over.getMessage().contains("超过源批次剩余量"), over.getMessage());
        assertEquals(5, jdbc.queryForObject("select qty_remaining from inv_batch where id=? and status='OPEN'",
                Integer.class, parent), "被拒封单不得扣减源批次");
        assertEquals("DRAFT", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, d.asnId()), "被拒封单保持 DRAFT");
        assertEquals(3, count("inv_batch where batch_no like 'B:ASN:%'"));
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"), "失败封单不得残留 PENDING");
        assertEquals(0, ledgerInTransit(sku, wh), "源仓在途始终为 0");
    }

    // ------------------------------------------------------ receive：在途转可用 + 短少

    @Test
    void receiveConvergesChildLedgerAndRecordsShort()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-R1", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-R1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-R1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-R1", 10, "12.3400", LocalDateTime.of(2026, 6, 4, 9, 0));
        seedLedger(sku, wh, "AE", 10);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-R1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-R1", 10, null))));
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());
        long child = service.seal(new AsnSealInput(created.asnId(), rid(), null)).childBatchIds().get(0);

        String shortEvent = "short-r1";
        AsnResult received = service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemId, 8, shortEvent))));
        assertFalse(received.replayed());
        assertEquals("RECEIVE", received.action());
        assertEquals(1, received.childBatchIds().size());

        assertEquals(8, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child));
        assertEquals(8, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, child));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child), "实收 >0 在途转 OPEN");
        assertEquals(0, ledgerInTransit(sku, fbn), "FBN 仓在途清零");
        assertEquals(8, ledgerAvailable(sku, fbn), "FBN 仓可用 +8");
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));

        assertEquals(8, jdbc.queryForObject("select qty_received from log_asn_item where id=?",
                Integer.class, itemId));
        assertEquals(2, jdbc.queryForObject("select diff_qty from log_asn_item where id=?",
                Integer.class, itemId));

        // ASN_IN(+8, FBN 仓, 批次=子批次)。
        Map<String, Object> inc = jdbc.queryForMap("select sku_id,warehouse_id,batch_id,change_type,"
                + "qty_delta,ref_doc_no,ref_doc_type from inv_stock_move where change_type='ASN_IN'");
        assertEquals(sku, ((Number) inc.get("sku_id")).longValue());
        assertEquals(fbn, ((Number) inc.get("warehouse_id")).longValue());
        assertEquals(child, ((Number) inc.get("batch_id")).longValue());
        assertEquals(8, ((Number) inc.get("qty_delta")).intValue());
        assertEquals(created.asnNo(), inc.get("ref_doc_no"));
        assertEquals("ASN", inc.get("ref_doc_type"));

        // ASN_SHORT 消耗：事件键/成本/金额/期间/sign。
        Map<String, Object> shortRow = jdbc.queryForMap("select biz_event_key,source_event_id,batch_id,qty,"
                + "unit_cost,amount,move_type,sign,period,sal_order_item_id from inv_batch_consumption");
        assertEquals("ASNSHORT:" + itemId + ":" + shortEvent, shortRow.get("biz_event_key"));
        assertEquals(shortEvent, shortRow.get("source_event_id"));
        assertEquals(child, ((Number) shortRow.get("batch_id")).longValue());
        assertEquals(2, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) shortRow.get("unit_cost")));
        assertEquals(0, new BigDecimal("24.6800").compareTo((BigDecimal) shortRow.get("amount")));
        assertEquals("ASN_SHORT", shortRow.get("move_type"));
        assertEquals(1, ((Number) shortRow.get("sign")).intValue());
        assertTrue(String.valueOf(shortRow.get("period")).matches("\\d{4}-\\d{2}"));
        assertNull(shortRow.get("sal_order_item_id"));

        // 全链路守恒：源批次 0 + 子批次 8 + 短少 2 = 初始 10。
        assertEquals(10, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch "
                        + "where batch_no='B:SRC-R1') + (select coalesce(qty_remaining,0) from inv_batch where id=?) "
                        + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='ASN_SHORT')",
                Integer.class, child));

        assertEquals(1, count("noon_m4_inventory_audit where action='RECEIVE'"));
    }

    @Test
    void receiveZeroShortageClosesChildWithoutInboundMove()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-R2", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-R2", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-R2", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-R2", 5, "7.0000", LocalDateTime.of(2026, 6, 4, 10, 0));
        seedLedger(sku, wh, "AE", 5);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-R2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-R2", 5, null))));
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());
        long child = service.seal(new AsnSealInput(created.asnId(), rid(), null)).childBatchIds().get(0);

        service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemId, 0, "full-short"))));
        assertEquals(5, jdbc.queryForObject("select qty_in from inv_batch where id=?",
                Integer.class, child), "实收 0 保留 qty_in = 发运量（守 CHECK qty_in>0）");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, child));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(0, ledgerInTransit(sku, fbn));
        assertEquals(0, ledgerAvailable(sku, fbn));
        assertEquals(0, count("inv_stock_move where change_type='ASN_IN'"), "实收 0 不写 ASN_IN（守 qty_delta<>0）");
        assertEquals(5, jdbc.queryForObject("select qty from inv_batch_consumption where move_type='ASN_SHORT'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("select qty_received from log_asn_item where id=?",
                Integer.class, itemId));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
    }

    @Test
    void receiveSupportsStagedLinesAndRejectsSecondReceiveOfSameLine()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-R3", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-R3", "AE", "FBN", 1, "ACTIVE");
        long skuA = newSku("SKU-ASN-R3-A", "ACTIVE");
        long skuB = newSku("SKU-ASN-R3-B", "ACTIVE");
        newBatch(skuA, wh, "AE", "B:SRC-R3-A", 6, "10.0000", LocalDateTime.of(2026, 6, 5, 9, 0));
        newBatch(skuB, wh, "AE", "B:SRC-R3-B", 4, "9.0000", LocalDateTime.of(2026, 6, 5, 9, 0));
        seedLedger(skuA, wh, "AE", 6);
        seedLedger(skuB, wh, "AE", 4);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-R3", rid(), null,
                List.of(new AsnCreateLine(skuA, "B:SRC-R3-A", 6, null),
                        new AsnCreateLine(skuB, "B:SRC-R3-B", 4, null))));
        List<AsnItemRow> items = service.get(created.asnId()).items();
        long itemA = items.get(0).id();
        long itemB = items.get(1).id();
        service.seal(new AsnSealInput(created.asnId(), rid(), null));
        assertEquals(2, count("inv_batch where batch_no like 'B:ASN:%'"));

        // 第一批只收 A（6 全收）：头仍是 SEALED，B 仍在途。
        service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemA, 6, null))));
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()), "按行分批时头保持 SEALED");
        assertEquals(6, ledgerAvailable(skuA, fbn));
        assertEquals(4, ledgerInTransit(skuB, fbn));

        // 同一行再收（新 requestId）→ 400，零写入。
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemA, 6, null)))));
        assertTrue(twice.getMessage().contains("已收货"), twice.getMessage());
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"), "重复收获败不得留 PENDING");

        // 第二批收 B（3/4，短少 1）→ 整单 RECEIVED，守恒。
        service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemB, 3, "short-r3"))));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        assertEquals(3, ledgerAvailable(skuB, fbn));
        assertEquals(0, ledgerInTransit(skuB, fbn));
        assertEquals(1, jdbc.queryForObject("select diff_qty from log_asn_item where id=?",
                Integer.class, itemB));
        assertEquals(2, count("inv_stock_move where change_type='ASN_IN'"));
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"));

        // 收完后整单再收（新 requestId）→ 400 仅 SEALED。
        IllegalArgumentException closed = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemB, 1, null)))));
        assertTrue(closed.getMessage().contains("仅 SEALED"), closed.getMessage());
    }

    // ------------------------------------------------------ 幂等三态 / 跨动作隔离 / 零写入

    @Test
    void idempotencyReplayConflictPendingAndCrossActionIsolation()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-I1", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-I1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-I1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-I1", 10, "4.0000", LocalDateTime.of(2026, 6, 6, 9, 0));
        seedLedger(sku, wh, "AE", 10);

        String key = rid();
        List<AsnCreateLine> lines = List.of(new AsnCreateLine(sku, "B:SRC-I1", 5, null));
        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-I1", key, null, lines));
        int heads = count("log_asn");
        int items = count("log_asn_item");
        int audits = count("noon_m4_inventory_audit");
        int requests = count("noon_m4_asn_request");

        // ① 同键同内容 CREATE → replayed，零写入。
        AsnResult replayCreate = service.create(new AsnCreateInput("AE", "WH-FBN-I1", key, null, lines));
        assertTrue(replayCreate.replayed());
        assertEquals(created.asnId(), replayCreate.asnId());
        assertEquals(created.asnNo(), replayCreate.asnNo());
        assertEquals("CREATE", replayCreate.action());
        assertEquals(heads, count("log_asn"));
        assertEquals(items, count("log_asn_item"));
        assertEquals(audits, count("noon_m4_inventory_audit"));
        assertEquals(requests, count("noon_m4_asn_request"));

        // ② 同键换内容 → 400（指纹含明细），不新增数据。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-I1", key, null,
                        List.of(new AsnCreateLine(sku, "B:SRC-I1", 6, null)))));
        assertTrue(changed.getMessage().contains("内容与原请求不一致"), changed.getMessage());
        assertEquals(heads, count("log_asn"));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-I1'",
                Integer.class), "内容冲突不得产生库存副作用");

        // ③ PENDING（同键同内容）→ 400「处理中」，不重复执行。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_asn_request(request_id,action,asn_id,site_code,content_fingerprint,"
                        + "status) values (?,?,?,?,?, 'PENDING')",
                pendingKey, "CREATE", 0L, "AE",
                AsnRules.createFingerprint("AE", "WH-FBN-I1", List.of(new AsnCreateLine(sku, "B:SRC-I1", 1, null))));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-I1", pendingKey, null,
                        List.of(new AsnCreateLine(sku, "B:SRC-I1", 1, null)))));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());

        // ④ 跨动作隔离：CREATE 的 requestId 直接用于 SEAL 是独立新键，必须成功并新增一行。
        String sharedKey = rid();
        AsnResult created2 = service.create(new AsnCreateInput("AE", "WH-FBN-I1", sharedKey, null,
                List.of(new AsnCreateLine(sku, "B:SRC-I1", 3, null))));
        int beforeSeal = count("noon_m4_asn_request");
        AsnResult sealed = service.seal(new AsnSealInput(created2.asnId(), sharedKey, null));
        assertFalse(sealed.replayed(), "同一 requestId 跨动作是新键");
        assertEquals(beforeSeal + 1, count("noon_m4_asn_request"));
        assertEquals(2, jdbc.queryForObject("select count(*) from noon_m4_asn_request where request_id=?",
                Integer.class, sharedKey), "CREATE/SEAL 各一行（动作级隔离）");
        assertFalse(AsnRules.createFingerprint("AE", "WH-FBN-I1",
                        List.of(new AsnCreateLine(sku, "B:SRC-I1", 3, null)))
                        .equals(jdbc.queryForObject("select content_fingerprint from noon_m4_asn_request "
                                + "where request_id=? and action='SEAL'", String.class, sharedKey)),
                "SEAL 指纹与 CREATE 指纹不同（动作前缀隔离）");

        // ⑤ SEAL DONE 重放零写入。
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created2.asnId());
        int batches = count("inv_batch");
        int moves = count("inv_stock_move");
        AsnResult replaySeal = service.seal(new AsnSealInput(created2.asnId(), sharedKey, null));
        assertTrue(replaySeal.replayed());
        assertEquals(sealed.childBatchIds(), replaySeal.childBatchIds());
        assertEquals(batches, count("inv_batch"), "封单重放不得再拆批");
        assertEquals(moves, count("inv_stock_move"), "封单重放不得再写流水");
        assertEquals(3, ledgerInTransit(sku, fbn), "封单重放不得再动台账");

        // ⑥ RECEIVE DONE 重放零写入。
        String receiveKey = rid();
        service.receive(new AsnReceiveInput(created2.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(itemId, 2, "shift-1"))));
        int consumptions = count("inv_batch_consumption");
        AsnResult replayReceive = service.receive(new AsnReceiveInput(created2.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(itemId, 2, "shift-1"))));
        assertTrue(replayReceive.replayed());
        assertEquals(consumptions, count("inv_batch_consumption"), "收货重放不得双记短少");
        assertEquals(0, ledgerInTransit(sku, fbn));
        assertEquals(2, ledgerAvailable(sku, fbn));

        // ⑦ 收货 DONE 后同键换 shortEventId → 400（指纹含事件号，防换键重记短少）。
        IllegalArgumentException changedEvent = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created2.asnId(), receiveKey, null,
                        List.of(new AsnReceiveLine(itemId, 2, "shift-2")))));
        assertTrue(changedEvent.getMessage().contains("内容与原请求不一致"), changedEvent.getMessage());

        // ⑧ 收满后整单 RECEIVED，同键重放仍返回首次结果（不因状态闸门被误拒）。
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created2.asnId()));
        assertTrue(service.receive(new AsnReceiveInput(created2.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(itemId, 2, "shift-1")))).replayed());
    }

    @Test
    void failedTransactionLeavesNoPendingAndSameKeyCanRetry()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-F1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-F1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-F1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-F1", 5, "2.0000", LocalDateTime.of(2026, 6, 7, 9, 0));
        seedLedger(sku, wh, "AE", 5);

        String key = rid();
        List<AsnCreateLine> lines = List.of(new AsnCreateLine(sku, "B:SRC-F1", 5, null));

        // 占位后事务中途失败（审计表缺失）→ PENDING 与半张单一起回滚。
        jdbc.execute("drop table noon_m4_inventory_audit");
        assertThrows(RuntimeException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-F1", key, null, lines)));
        assertEquals(0, count("noon_m4_asn_request"), "失败事务的 PENDING 占位必须回滚");
        assertEquals(0, count("log_asn"), "失败事务不得留下半张 ASN");
        assertEquals(0, count("log_asn_item"));

        createAuditTable();
        AsnResult retried = service.create(new AsnCreateInput("AE", "WH-FBN-F1", key, null, lines));
        assertFalse(retried.replayed(), "回滚后同键重试是首次成功");
        assertEquals(1, count("log_asn"));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_asn_request where request_id=?",
                String.class, key));
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"));
    }

    // ------------------------------------------------------ 短少事件号

    @Test
    void shortEventIdRequiredAndDuplicateEventBlocked()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-H1", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-H1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-H1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-H1", 4, "6.0000", LocalDateTime.of(2026, 6, 8, 9, 0));
        seedLedger(sku, wh, "AE", 4);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-H1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-H1", 4, null))));
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());
        long child = service.seal(new AsnSealInput(created.asnId(), rid(), null)).childBatchIds().get(0);

        // 短少（4 发 3 收）缺 shortEventId → 400 且零写入。
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemId, 3, null)))));
        assertTrue(missing.getMessage().contains("shortEventId"), missing.getMessage());
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child), "缺事件号不得收敛子批次");
        assertEquals(4, ledgerInTransit(sku, fbn));
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals("SEALED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));

        // 非法格式 → 400。
        IllegalArgumentException badFormat = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemId, 3, "bad:event")))));
        assertTrue(badFormat.getMessage().contains("格式无效"), badFormat.getMessage());

        // 预置同事件键流水（模拟另一路径已过账）→ 唯一键拦截整单回滚。
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('AS:MANUAL',?,?,?,1,'6.0000','6.0000',now(3),'ASN_SHORT',1,'2026-06',0)",
                "ASNSHORT:" + itemId + ":dup-event", child, sku);
        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                        List.of(new AsnReceiveLine(itemId, 3, "dup-event")))));
        assertTrue(duplicated.getMessage().contains("短少事件已过账"), duplicated.getMessage());
        assertEquals(1, count("inv_batch_consumption"), "唯一键拦截后不得双计");
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child), "整单回滚子批次仍在途");
        assertEquals(4, ledgerInTransit(sku, fbn));
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"));

        // 无短少（4 收 4）不需要 shortEventId，且不新增消耗。
        service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(itemId, 4, null))));
        assertEquals(1, count("inv_batch_consumption"), "仅剩预置手工行");
        assertEquals(4, ledgerAvailable(sku, fbn));
        assertEquals(0, ledgerInTransit(sku, fbn));
    }

    // ------------------------------------------------------ 授权边界

    @Test
    void authorizationFollowsAsnSiteAndFbnWarehouse()
    {
        long wh = newWarehouse("WH-ASN-A1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-A1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-A1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-A1", 10, "3.0000", LocalDateTime.of(2026, 6, 9, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        long whSa = newWarehouse("WH-ASN-A1-SA", "SA", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-A1-SA", "SA", "FBN", 1, "ACTIVE");
        newBatch(sku, whSa, "SA", "B:SRC-A1-SA", 5, "3.0000", LocalDateTime.of(2026, 6, 9, 9, 0));
        seedLedger(sku, whSa, "SA", 5);

        // ① 无任何站点授权 → 403，零写入。
        login(3L);
        assertThrows(AccessDeniedException.class, () -> service.create(new AsnCreateInput("AE",
                "WH-FBN-A1", rid(), null, List.of(new AsnCreateLine(sku, "B:SRC-A1", 2, null)))));
        assertEquals(0, count("noon_m4_asn_request"));
        assertEquals(0, service.list(null, 0, 20).total(), "空授权列表零行");

        // ② 仅 AE 授权：AE 单可建；SA 单 → 403（站点授权先于一切）。
        AsnResult ae = null;
        AsnResult sa = null;
        login(2L);
        ae = service.create(new AsnCreateInput("AE", "WH-FBN-A1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-A1", 2, null))));
        assertThrows(AccessDeniedException.class, () -> service.create(new AsnCreateInput("SA",
                "WH-FBN-A1-SA", rid(), null, List.of(new AsnCreateLine(sku, "B:SRC-A1-SA", 1, null)))));
        assertEquals(1, count("noon_m4_asn_request"), "越权站点不得写幂等行");

        // ③ 超管可建 SA 单；仅 AE 用户 get/resultOf SA 单 → 403。
        login(1L);
        sa = service.create(new AsnCreateInput("SA", "WH-FBN-A1-SA", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-A1-SA", 1, null))));
        login(2L);
        AsnResult finalSa = sa;
        assertThrows(AccessDeniedException.class, () -> service.get(finalSa.asnId()));
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 20));
        assertEquals(1, service.list(null, 0, 20).total(), "AE 用户全站列表只含 AE 单");
        assertEquals(0, service.list(null, 0, 20).rows().stream()
                .filter(row -> "SA".equals(row.siteCode())).count(), "列表不得泄漏 SA 单");

        // ④ AE 用户封 AE 单成功；AE+SA 用户可读 SA 单。
        AsnResult sealed = service.seal(new AsnSealInput(ae.asnId(), rid(), null));
        assertEquals(1, sealed.childBatchIds().size());
        login(4L);
        assertEquals("DRAFT", service.get(finalSa.asnId()).head().status());

        // ⑤ 授权撤销后同键重放/查询 → 403（重放前先授权）。
        String sealKey = rid();
        login(1L);
        service.seal(new AsnSealInput(finalSa.asnId(), sealKey, null));
        jdbc.update("delete from sys_user_site where user_id=4 and site_code='SA'");
        login(4L);
        assertThrows(AccessDeniedException.class,
                () -> service.seal(new AsnSealInput(finalSa.asnId(), sealKey, null)),
                "授权撤销后同键重放必须 403");
        assertThrows(AccessDeniedException.class, () -> service.resultOf(sealKey));
        assertThrows(AccessDeniedException.class, () -> service.get(finalSa.asnId()));
    }

    // ------------------------------------------------------ 源批次并发扣减

    @Test
    void concurrentSealOfSameSourceBatchKeepsConservation() throws Exception
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-C1", "AE", "LOCAL", 0, "ACTIVE");
        long fbn = newWarehouse("WH-FBN-C1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-C1-CON", "ACTIVE");
        long parent = newBatch(sku, wh, "AE", "B:SRC-C1-CON", 10, "5.0000",
                LocalDateTime.of(2026, 6, 10, 9, 0));
        seedLedger(sku, wh, "AE", 10);

        AsnResult a = service.create(new AsnCreateInput("AE", "WH-FBN-C1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-C1-CON", 10, null))));
        AsnResult b = service.create(new AsnCreateInput("AE", "WH-FBN-C1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-C1-CON", 10, null))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<String> first = pool.submit(() -> sealAttempt(a.asnId(), ready, go));
        Future<String> second = pool.submit(() -> sealAttempt(b.asnId(), ready, go));
        assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程未就绪");
        go.countDown();
        List<String> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, results.stream().filter("FRESH"::equals).count(),
                "同一源批次并发只允许一次扣减成功: " + results);
        assertEquals(1, results.stream().filter(r -> r.startsWith("REJECT:") || "REPLAYED".equals(r)).count(),
                "另一个必须被剩余量/并发 CAS 拒绝: " + results);
        assertEquals(1, count("inv_batch where batch_no like 'B:ASN:%'"), "只允许一个在途子批次");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, parent), "源批次只扣一次（扣到 0）");
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, parent));
        assertEquals(0, ledgerAvailable(sku, wh), "源仓可用只扣一次");
        assertEquals(10, ledgerInTransit(sku, fbn), "FBN 仓在途只加一次");
        assertEquals(1, count("inv_stock_move where change_type='ASN_OUT'"), "只允许一条 ASN_OUT");
        assertEquals(1, count("noon_m4_inventory_audit where action='SEAL'"));
        assertEquals(0, count("noon_m4_asn_request where status='PENDING'"), "不得残留 PENDING");
    }

    private String sealAttempt(long asnId, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException
    {
        login(1L);
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        try
        {
            return tx.execute(status ->
                    service.seal(new AsnSealInput(asnId, rid(), null)).replayed() ? "REPLAYED" : "FRESH");
        }
        catch (RuntimeException e)
        {
            return "REJECT:" + e.getMessage();
        }
        finally
        {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------ 结果查询 / 列表 / 详情

    @Test
    void resultOfRequiresSiteAndPrefersLatestAction()
    {
        login(1L);
        long wh = newWarehouse("WH-ASN-Q1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-Q1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-Q1", "ACTIVE");
        newBatch(sku, wh, "AE", "B:SRC-Q1", 10, "2.0000", LocalDateTime.of(2026, 6, 12, 9, 0));
        seedLedger(sku, wh, "AE", 10);

        String sharedKey = rid();
        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-Q1", sharedKey, null,
                List.of(new AsnCreateLine(sku, "B:SRC-Q1", 4, null))));
        long itemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());

        // CREATE DONE：命中 → replayed=true, action=CREATE。
        AsnResult hitCreate = service.resultOf(sharedKey);
        assertNotNull(hitCreate);
        assertTrue(hitCreate.replayed());
        assertEquals("CREATE", hitCreate.action());
        assertEquals(created.asnId(), hitCreate.asnId());

        // 同一 requestId 的 SEAL 完成后：resultOf 取最新动作（SEAL）。
        service.seal(new AsnSealInput(created.asnId(), sharedKey, null));
        AsnResult hitSeal = service.resultOf(sharedKey);
        assertEquals("SEAL", hitSeal.action(), "同键多动作时取最新动作结果");
        assertFalse(hitSeal.childBatchIds().isEmpty());

        // RECEIVE 完成后取 RECEIVE。
        String receiveKey = rid();
        service.receive(new AsnReceiveInput(created.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(itemId, 4, null))));
        assertEquals("RECEIVE", service.resultOf(receiveKey).action());

        // PENDING / 非法键 / 不存在 → null。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_asn_request(request_id,action,asn_id,site_code,content_fingerprint,"
                        + "status) values (?,?,?,?,?, 'PENDING')",
                pendingKey, "SEAL", created.asnId(), "AE", "a".repeat(64));
        assertNull(service.resultOf(pendingKey));
        assertNull(service.resultOf("bad key!"));
        assertNull(service.resultOf(rid()));

        // 授权：仅 AE 用户命中 AE 结果可读；越权站点 403。
        login(2L);
        assertEquals(created.asnId(), service.resultOf(sharedKey).asnId());
        assertEquals("RECEIVED", service.get(created.asnId()).head().status());
    }

    @Test
    void listAndGetFilterBySite()
    {
        long whAe = newWarehouse("WH-ASN-L1", "AE", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-L1", "AE", "FBN", 1, "ACTIVE");
        long whSa = newWarehouse("WH-ASN-L2", "SA", "LOCAL", 0, "ACTIVE");
        newWarehouse("WH-FBN-L2", "SA", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-L1", "ACTIVE");
        newBatch(sku, whAe, "AE", "B:SRC-L1", 5, "1.0000", LocalDateTime.of(2026, 6, 11, 9, 0));
        newBatch(sku, whSa, "SA", "B:SRC-L2", 5, "1.0000", LocalDateTime.of(2026, 6, 11, 9, 0));

        login(1L);
        assertNotNull(service.create(new AsnCreateInput("AE", "WH-FBN-L1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-L1", 2, null)))));
        assertNotNull(service.create(new AsnCreateInput("SA", "WH-FBN-L2", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-L2", 2, null)))));
        assertNotNull(service.create(new AsnCreateInput("AE", "WH-FBN-L1", rid(), null,
                List.of(new AsnCreateLine(sku, "B:SRC-L1", 1, null)))));
        assertEquals(1, service.list("AE", 0, 1).rows().size());

        // 超管：单站过滤 + 全站 + 分页 total 与分页无关。
        assertEquals(2, service.list("AE", 1, 20).total());
        assertEquals(1, service.list("AE", 1, 20).rows().size());
        assertEquals(3, service.list(null, 0, 20).total());
        assertEquals(3, service.list(null, 1, 1).total(), "total 与分页无关");
        assertEquals(1, service.list(null, 1, 1).rows().size());

        // 仅 AE：全站也只有 AE 的单。
        login(2L);
        assertEquals(2, service.list(null, 0, 20).total());
        assertEquals("AE", service.list(null, 0, 20).rows().get(0).siteCode());

        // 无站点：零行（空授权必须零行，不得省略 WHERE）。
        login(3L);
        assertEquals(0, service.list(null, 0, 20).total());
        assertEquals(0, service.list(null, 0, 20).rows().size());

        // 非法站点/负数 offset/非法 limit 收敛。
        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.list("XX", 0, 20));
        assertThrows(IllegalArgumentException.class, () -> service.list("AE", -1, 20));
        assertEquals(2, service.list("AE", 0, 0).rows().size(), "非法 limit 收敛为默认 20");
        assertThrows(IllegalArgumentException.class, () -> service.get(-1L));
    }

    // ------------------------------------------------------ M6 采购直发 FBN

    @Test
    void directPurchaseCreatesSealReceivesWithReceiptCostAndNoLocalStock()
    {
        login(1L);
        long fbn = newWarehouse("WH-FBN-D1", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-D1", "ACTIVE");
        long order = newPurchaseOrder("AE");
        long poItem = newPurchaseItem(order, sku, 10, 10, "12.0000");
        LocalDateTime firstReceivedAt = LocalDateTime.of(2026, 7, 1, 9, 0, 12);
        LocalDateTime secondReceivedAt = LocalDateTime.of(2026, 7, 2, 9, 30, 45);
        newReceipt(order, poItem, 4, "12.0000", firstReceivedAt);
        newReceipt(order, poItem, 6, "13.0000", secondReceivedAt);

        AsnResult created = service.create(new AsnCreateInput("AE", "WH-FBN-D1", rid(), null,
                List.of(new AsnCreateLine(sku, null, 10, "P-D1", poItem)), "DIRECT_PURCHASE"));
        assertFalse(created.replayed());
        assertEquals("DIRECT_PURCHASE", jdbc.queryForObject("select source_mode from log_asn where id=?",
                String.class, created.asnId()));
        Map<String, Object> item = jdbc.queryForMap("select id,sku_id,pur_order_item_id,batch_no,"
                + "qty_shipped,qty_received,diff_qty,partner_sku from log_asn_item where asn_id=?",
                created.asnId());
        long asnItemId = ((Number) item.get("id")).longValue();
        assertEquals(sku, ((Number) item.get("sku_id")).longValue());
        assertEquals(poItem, ((Number) item.get("pur_order_item_id")).longValue(), "直发写采购明细来源");
        assertEquals(10, ((Number) item.get("qty_shipped")).intValue());
        assertEquals(0, ((Number) item.get("qty_received")).intValue());
        assertEquals("P-D1", item.get("partner_sku"));
        String parentBatchNo = (String) item.get("batch_no");
        assertTrue(parentBatchNo.matches("DP:" + poItem + ":[0-9a-f]{8}"), parentBatchNo);

        // 读路径回显来源模式与采购明细（前端列表/详情依赖）。
        assertEquals("DIRECT_PURCHASE", service.get(created.asnId()).head().sourceMode());
        assertEquals(poItem, service.get(created.asnId()).items().get(0).purOrderItemId());
        assertEquals("DIRECT_PURCHASE", service.list("AE", 0, 20).rows().get(0).sourceMode());

        // create 零副作用：不建批次、不动台账、不写流水、不扣任何仓。
        assertEquals(0, count("inv_batch"), "直发建单不建任何批次");
        assertEquals(0, count("inv_stock_ledger"), "直发建单不动台账");
        assertEquals(0, count("inv_stock_move"), "直发建单不写流水");
        assertEquals(2, count("noon_m3_receipt"), "不伪造采购收货记录");

        // seal：FBN 仓建 DIRECT_PURCHASE/CLOSED 成本父批次（剩余 0）+ 在途子批次；只加在途，不写 ASN_OUT。
        AsnResult sealed = service.seal(new AsnSealInput(created.asnId(), rid(), null));
        assertEquals(1, sealed.childBatchIds().size());
        long child = sealed.childBatchIds().get(0);
        long parent = jdbc.queryForObject("select parent_batch_id from inv_batch where id=?", Long.class, child);
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where id=? and batch_no=? "
                        + "and source_type='DIRECT_PURCHASE' and status='CLOSED' and qty_in=10 "
                        + "and qty_remaining=0 and pur_order_item_id=? and warehouse_id=? and site_code='AE' "
                        + "and parent_batch_id is null",
                Integer.class, parent, parentBatchNo, poItem, fbn), "直发父批次成本载体落库体");
        // 成本取该明细<最早一笔>收货的 unit_cost_cny（v1 口径，契约 §5.1 待定严格 FIFO 绑定）。
        assertEquals(0, new BigDecimal("12.0000").compareTo(jdbc.queryForObject(
                "select unit_cost from inv_batch where id=?", BigDecimal.class, parent)));
        assertEquals("CNY", jdbc.queryForObject("select currency from inv_batch where id=?",
                String.class, parent));
        assertEquals(0, BigDecimal.ONE.compareTo(jdbc.queryForObject("select fx_rate from inv_batch where id=?",
                BigDecimal.class, parent)));
        assertEquals(Timestamp.valueOf(firstReceivedAt), jdbc.queryForObject(
                "select received_at from inv_batch where id=?", Timestamp.class, parent));

        assertEquals(1, jdbc.queryForObject("select count(*) from inv_batch where id=? "
                        + "and source_type='TRANSFER_SPLIT' and status='IN_TRANSIT' and parent_batch_id=? "
                        + "and split_seq=1 and split_reason='ASN_TRANSIT' and asn_no=? and qty_in=10 "
                        + "and qty_remaining=10 and warehouse_id=? and site_code='AE'",
                Integer.class, child, parent, created.asnNo(), fbn), "在途子批次血缘");
        assertEquals(10, ledgerInTransit(sku, fbn), "FBN 仓在途 +10（只加在途）");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_move", Integer.class),
                "直发封单不写 ASN_OUT（无源仓出库）");
        assertEquals(0, ledgerAvailable(sku, fbn), "封单不产生可用（父批次 CLOSED 不虚增可用）");

        // receive 9（短少 1）：子批次 OPEN，FBN 可用 9，在途 0，短少按收货成本记账。
        service.receive(new AsnReceiveInput(created.asnId(), rid(), null,
                List.of(new AsnReceiveLine(asnItemId, 9, "short-d1"))));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(9, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, child));
        assertEquals(0, ledgerInTransit(sku, fbn));
        assertEquals(9, ledgerAvailable(sku, fbn), "可用 = 实收 9（父批次不产生可用）");
        assertEquals("RECEIVED", jdbc.queryForObject("select status from log_asn where id=?",
                String.class, created.asnId()));
        Map<String, Object> shortRow = jdbc.queryForMap("select biz_event_key,qty,unit_cost,amount "
                + "from inv_batch_consumption where move_type='ASN_SHORT'");
        assertEquals("ASNSHORT:" + asnItemId + ":short-d1", shortRow.get("biz_event_key"));
        assertEquals(1, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.0000").compareTo((BigDecimal) shortRow.get("unit_cost")),
                "短少成本继承收货成本");
        assertEquals(0, new BigDecimal("12.0000").compareTo((BigDecimal) shortRow.get("amount")));

        // 父批次仍 CLOSED/剩余 0，不参与 FIFO；守恒：子批次 9 + 短少 1 = 发运 10（= 已收货 10）。
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, parent));
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, parent));
        assertEquals(10, jdbc.queryForObject("select (select coalesce(qty_remaining,0) from inv_batch "
                        + "where id=?) + (select coalesce(sum(qty),0) from inv_batch_consumption "
                        + "where move_type='ASN_SHORT')", Integer.class, child), "子批次剩余 + 短少 = 收货量");
    }

    @Test
    void directPurchaseRejectsUnreceivedOverShipCrossSiteAndBadInput()
    {
        login(1L);
        newWarehouse("WH-FBN-D2", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-D2", "ACTIVE");
        long otherSku = newSku("SKU-ASN-D2-OTHER", "ACTIVE");
        long orderAe = newPurchaseOrder("AE");
        long unreceived = newPurchaseItem(orderAe, sku, 5, 0, "4.0000");
        long received5 = newPurchaseItem(orderAe, sku, 5, 5, "4.0000");
        newReceipt(orderAe, received5, 5, "4.0000", LocalDateTime.of(2026, 7, 3, 9, 0));
        long orderSa = newPurchaseOrder("SA");
        long crossSite = newPurchaseItem(orderSa, sku, 5, 5, "4.0000");
        newReceipt(orderSa, crossSite, 5, "4.0000", LocalDateTime.of(2026, 7, 3, 9, 0));

        // 未收货 → 400（D1：直发不绕开采购收货）。
        IllegalArgumentException noReceipt = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, unreceived)), "DIRECT_PURCHASE")));
        assertTrue(noReceipt.getMessage().contains("尚未收货"), noReceipt.getMessage());
        // 超发 → 400（已收 5，本次 6）。
        IllegalArgumentException overReceived = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 6, null, received5)), "DIRECT_PURCHASE")));
        assertTrue(overReceived.getMessage().contains("超过可发量"), overReceived.getMessage());
        // 跨站采购明细 → 400。
        IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, crossSite)), "DIRECT_PURCHASE")));
        assertTrue(cross.getMessage().contains("不一致"), cross.getMessage());
        // 采购明细不存在 / 缺 purOrderItemId / SKU 不符 / 非法来源模式 → 400。
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, 99999L)), "DIRECT_PURCHASE")));
        IllegalArgumentException missingPo = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, null)), "DIRECT_PURCHASE")));
        assertTrue(missingPo.getMessage().contains("purOrderItemId"), missingPo.getMessage());
        IllegalArgumentException skuMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(otherSku, null, 1, null, received5)), "DIRECT_PURCHASE")));
        assertTrue(skuMismatch.getMessage().contains("SKU"), skuMismatch.getMessage());
        IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, received5)), "DIRECT")));
        assertTrue(badMode.getMessage().contains("sourceMode"), badMode.getMessage());
        // 被拒请求零写入。
        assertEquals(0, count("log_asn"));
        assertEquals(0, count("log_asn_item"));
        assertEquals(0, count("noon_m4_asn_request"));

        // 分批发运：已收 5，第一张 3 + 第二张 2 可建；第三张任何量都超发。
        AsnResult first = service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                List.of(new AsnCreateLine(sku, null, 3, null, received5)), "DIRECT_PURCHASE"));
        AsnResult second = service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                List.of(new AsnCreateLine(sku, null, 2, null, received5)), "DIRECT_PURCHASE"));
        assertNotNull(first);
        assertNotNull(second);
        IllegalArgumentException third = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, received5)), "DIRECT_PURCHASE")));
        assertTrue(third.getMessage().contains("超过可发量"), third.getMessage());
        assertEquals(2, count("log_asn"), "超发被拒不得建第三张单");

        // 同单内重复采购明细 → 400。
        IllegalArgumentException dupPo = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D2", rid(), null,
                        List.of(new AsnCreateLine(sku, null, 1, null, received5),
                                new AsnCreateLine(sku, null, 1, null, received5)), "DIRECT_PURCHASE")));
        assertTrue(dupPo.getMessage().contains("重复"), dupPo.getMessage());
    }

    @Test
    void directPurchaseActionsAreIdempotentAndFingerprintStaysCompatibleForLocal()
    {
        login(1L);
        long fbn = newWarehouse("WH-FBN-D3", "AE", "FBN", 1, "ACTIVE");
        long sku = newSku("SKU-ASN-D3", "ACTIVE");
        long order = newPurchaseOrder("AE");
        long poItem = newPurchaseItem(order, sku, 8, 8, "5.5000");
        newReceipt(order, poItem, 8, "5.5000", LocalDateTime.of(2026, 7, 4, 9, 0));

        // 指纹兼容：本地模式 3 参与 4 参（LOCAL_BATCH）逐字节一致，直发口径不同。
        List<AsnCreateLine> localLines = List.of(new AsnCreateLine(sku, "B:SRC-D3", 2, null));
        assertEquals(AsnRules.createFingerprint("AE", "WH-FBN-D3", localLines),
                AsnRules.createFingerprint("AE", "WH-FBN-D3", "LOCAL_BATCH", localLines),
                "本地模式指纹必须与旧版一致");
        assertFalse(AsnRules.createFingerprint("AE", "WH-FBN-D3", localLines).equals(
                        AsnRules.createFingerprint("AE", "WH-FBN-D3", "DIRECT_PURCHASE",
                                List.of(new AsnCreateLine(sku, null, 2, null, poItem)))),
                "直发指纹含来源模式，不得与本地撞串");

        String key = rid();
        AsnCreateInput input = new AsnCreateInput("AE", "WH-FBN-D3", key, null,
                List.of(new AsnCreateLine(sku, null, 8, null, poItem)), "DIRECT_PURCHASE");
        AsnResult created = service.create(input);
        int requests = count("noon_m4_asn_request");
        // ① CREATE 同键同内容 → 重放零写入。
        AsnResult replayCreate = service.create(input);
        assertTrue(replayCreate.replayed());
        assertEquals(created.asnId(), replayCreate.asnId());
        assertEquals(requests, count("noon_m4_asn_request"));
        // ② 同键改量 → 400。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new AsnCreateInput("AE", "WH-FBN-D3", key, null,
                        List.of(new AsnCreateLine(sku, null, 7, null, poItem)), "DIRECT_PURCHASE")));
        assertTrue(changed.getMessage().contains("内容与原请求不一致"), changed.getMessage());

        long asnItemId = jdbc.queryForObject("select id from log_asn_item where asn_id=?",
                Long.class, created.asnId());
        String sealKey = rid();
        AsnResult sealed = service.seal(new AsnSealInput(created.asnId(), sealKey, null));
        int batches = count("inv_batch");
        // ③ SEAL 重放：不再建父/子批次、不再加在途。
        AsnResult replaySeal = service.seal(new AsnSealInput(created.asnId(), sealKey, null));
        assertTrue(replaySeal.replayed());
        assertEquals(sealed.childBatchIds(), replaySeal.childBatchIds());
        assertEquals(batches, count("inv_batch"));
        assertEquals(8, ledgerInTransit(sku, fbn), "在途只加一次");
        assertEquals(0, ledgerAvailable(sku, fbn));
        assertEquals(0, count("inv_stock_move where change_type='ASN_OUT'"));

        // ④ RECEIVE 重放：不双加可用、不双记短少。
        String receiveKey = rid();
        service.receive(new AsnReceiveInput(created.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(asnItemId, 8, null))));
        int consumptions = count("inv_batch_consumption");
        AsnResult replayReceive = service.receive(new AsnReceiveInput(created.asnId(), receiveKey, null,
                List.of(new AsnReceiveLine(asnItemId, 8, null))));
        assertTrue(replayReceive.replayed());
        assertEquals(consumptions, count("inv_batch_consumption"));
        assertEquals(8, ledgerAvailable(sku, fbn), "可用只加一次");
        assertEquals(0, ledgerInTransit(sku, fbn));

        // 父批次始终 CLOSED/剩余 0：可用只来自 OPEN 子批次，FIFO 不会选中父批次。
        assertEquals(1, count("inv_batch where source_type='DIRECT_PURCHASE' and status='CLOSED' "
                + "and qty_remaining=0"));
        assertEquals(8, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch "
                        + "where status='OPEN' and warehouse_id=? and sku_id=?", Integer.class, fbn, sku),
                "OPEN 批次合计 = 实收 8");
        assertEquals(8, ledgerAvailable(sku, fbn), "台账可用 = OPEN 批次合计（父批次不虚增）");
    }

    // ------------------------------------------------------------------ helpers

    private long newSku(String code, String status)
    {
        jdbc.update("insert into md_sku(sku_code,status) values (?,?)", code, status);
        return jdbc.queryForObject("select id from md_sku where sku_code=?", Long.class, code);
    }

    private long newWarehouse(String code, String site, String type, int isFbn, String status)
    {
        jdbc.update("insert into md_warehouse(warehouse_code,site_code,warehouse_type,is_fbn,status) "
                + "values (?,?,?,?,?)", code, site, type, isFbn, status);
        return jdbc.queryForObject("select id from md_warehouse where warehouse_code=? and site_code=?",
                Long.class, code, site);
    }

    private long newBatch(long skuId, long warehouseId, String site, String batchNo, int qty, String unitCost,
                          LocalDateTime receivedAt)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                        + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                        + "split_reason,transfer_no,asn_no,status) "
                        + "values (?,?,NULL,?,?,?,?,?,?,'CNY',1,'PURCHASE',NULL,0,NULL,NULL,NULL,'OPEN')",
                batchNo, skuId, warehouseId, site, Timestamp.valueOf(receivedAt), qty, qty, unitCost);
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(long skuId, long warehouseId, String site, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable) values (?,?,?,?,0,0,0)",
                skuId, warehouseId, site, available);
    }

    private int ledgerAvailable(long skuId, long warehouseId)
    {
        return jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuId, warehouseId);
    }

    private int ledgerInTransit(long skuId, long warehouseId)
    {
        return jdbc.queryForObject("select qty_in_transit from inv_stock_ledger "
                + "where sku_id=? and warehouse_id=?", Integer.class, skuId, warehouseId);
    }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** 一次性幂等键（模拟前端 crypto.randomUUID()），长度 38 位，符合 8–64 与 [A-Za-z0-9_-]。 */
    private static String rid()
    {
        return "r-" + UUID.randomUUID();
    }

    // ------------------------------------------------------ M6 采购/收货来源造数

    private long newPurchaseOrder(String site)
    {
        String orderNo = "PO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into pur_order(order_no,site_code,status) values (?,?, 'RECEIVED')", orderNo, site);
        return jdbc.queryForObject("select id from pur_order where order_no=?", Long.class, orderNo);
    }

    private long newPurchaseItem(long purOrderId, long skuId, int qtyOrdered, int qtyReceived, String unitPrice)
    {
        jdbc.update("insert into pur_order_item(pur_order_id,sku_id,qty_ordered,qty_received,unit_price,"
                        + "currency,fx_rate) values (?,?,?,?,?, 'CNY', 1)",
                purOrderId, skuId, qtyOrdered, qtyReceived, unitPrice);
        return jdbc.queryForObject("select id from pur_order_item where pur_order_id=? order by id desc limit 1",
                Long.class, purOrderId);
    }

    private void newReceipt(long purOrderId, long purOrderItemId, int qty, String unitCostCny,
                            LocalDateTime receivedAt)
    {
        jdbc.update("insert into noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at) "
                        + "values (?,?,?,?,?)",
                purOrderId, purOrderItemId, qty, unitCostCny, Timestamp.valueOf(receivedAt));
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
     * H2 宽松建表：列名与 schema v0.5 / {@code noon_m4_asn.sql} 对齐；JSON 用文本列、不含 CHECK
     * （CHECK 与真库并发由 {@code MySqlAsnIT} 验证）。
     */
    private void createTables()
    {
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "name_en varchar(512), status varchar(16) not null default 'ACTIVE', "
                + "constraint uk_sku_code unique(sku_code))");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, "
                + "warehouse_code varchar(64) not null, site_code varchar(16) not null, "
                + "warehouse_name varchar(256), warehouse_type varchar(24) not null default 'LOCAL', "
                + "fulfillment_mode varchar(24) not null default 'FBN', is_fbn tinyint not null default 0, "
                + "status varchar(16) not null default 'ACTIVE', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_wh_code unique(warehouse_code,site_code))");
        jdbc.execute("create table inv_batch (id bigint auto_increment primary key, batch_no varchar(64) not null, "
                + "sku_id bigint not null, pur_order_item_id bigint, warehouse_id bigint not null, "
                + "site_code varchar(16) not null, received_at timestamp(3) not null, qty_in int not null, "
                + "qty_remaining int not null, unit_cost decimal(18,4) not null, "
                + "currency char(3) not null default 'CNY', fx_rate decimal(18,8), "
                + "source_type varchar(24) not null default 'PURCHASE', parent_batch_id bigint, "
                + "split_seq int not null default 0, split_reason varchar(24), transfer_no varchar(64), "
                + "asn_no varchar(64), status varchar(16) not null default 'OPEN', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_batch_no unique(batch_no))");
        jdbc.execute("create table inv_stock_ledger (id bigint auto_increment primary key, sku_id bigint not null, "
                + "warehouse_id bigint not null, site_code varchar(16) not null, "
                + "qty_available int not null default 0, qty_in_transit int not null default 0, "
                + "qty_reserved int not null default 0, qty_unsellable int not null default 0, "
                + "updated_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_sku_wh unique(sku_id,warehouse_id))");
        jdbc.execute("create table inv_stock_move (id bigint auto_increment primary key, move_no varchar(64) not null, "
                + "sku_id bigint not null, warehouse_id bigint not null, batch_id bigint, "
                + "change_type varchar(32) not null, qty_delta int not null, ref_doc_type varchar(32), "
                + "ref_doc_no varchar(64), operator_id bigint, raw_payload varchar(4000), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_move_no unique(move_no))");
        jdbc.execute("create table inv_batch_consumption (id bigint auto_increment primary key, "
                + "consumption_no varchar(64) not null, biz_event_key varchar(160) not null, "
                + "source_event_id varchar(128), batch_id bigint not null, sal_order_item_id bigint, "
                + "sku_id bigint not null, qty int not null, unit_cost decimal(18,4) not null, "
                + "amount decimal(18,4) not null, consumed_at timestamp(3) not null, "
                + "move_type varchar(24) not null, sign int not null, period char(7), "
                + "cross_period int not null default 0, raw_payload varchar(4000), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_consumption_no unique(consumption_no), "
                + "constraint uk_consume_biz_event unique(biz_event_key,batch_id))");
        jdbc.execute("create table log_asn (id bigint auto_increment primary key, asn_no varchar(64) not null, "
                + "site_code varchar(16) not null, warehouse_code varchar(64) not null, "
                + "status varchar(24) not null default 'DRAFT', sealed_at timestamp(3), slot_date date, "
                + "carrier varchar(128), tracking_no varchar(128), "
                + "source varchar(24) not null default 'MANUAL', "
                + "source_mode varchar(24) not null default 'LOCAL_BATCH', raw_payload varchar(4000), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "updated_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_asn_no unique(asn_no))");
        jdbc.execute("create table log_asn_item (id bigint auto_increment primary key, asn_id bigint not null, "
                + "sku_id bigint not null, pur_order_item_id bigint, partner_sku varchar(128), "
                + "batch_no varchar(64) not null, "
                + "qty_shipped int not null, qty_received int default 0, diff_qty int default 0, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        // M6 §2 直发依赖的采购/收货来源表（宽松 DDL，CHECK 由 MySqlAsnIT 真库验证）。
        jdbc.execute("create table pur_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "supplier_id bigint, site_code varchar(16) not null, currency char(3) not null default 'CNY', "
                + "total_amount decimal(18,4) default 0, status varchar(24) not null default 'DRAFT', "
                + "source varchar(24) default 'MANUAL', source_ref_no varchar(64), operator_id bigint, "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_pur_no unique(order_no))");
        jdbc.execute("create table pur_order_item (id bigint auto_increment primary key, "
                + "pur_order_id bigint not null, sku_id bigint not null, qty_ordered int not null, "
                + "qty_received int not null default 0, unit_price decimal(18,4) not null, "
                + "currency char(3) not null default 'CNY', fx_rate decimal(18,8), "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m3_receipt (id bigint auto_increment primary key, "
                + "order_id bigint not null, order_item_id bigint not null, qty int not null, "
                + "unit_cost_cny decimal(18,4) not null, received_at timestamp(3) not null, operator_id bigint)");
        jdbc.execute("create table noon_m4_asn_request (request_id varchar(64) not null, "
                + "action varchar(16) not null, asn_id bigint not null, site_code varchar(16) not null, "
                + "operator_id bigint, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', result_json varchar(10000), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "primary key(request_id,action))");
        createAuditTable();
    }

    /** 审计表单独抽出：回滚用例会临时 drop 再复原，模拟“占位后事务中途失败”。 */
    private void createAuditTable()
    {
        jdbc.execute("create table noon_m4_inventory_audit (id bigint auto_increment primary key, "
                + "biz_type varchar(32) not null, biz_id varchar(64), action varchar(32) not null, "
                + "detail varchar(1024), operator_id bigint, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
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
        InventoryAudit inventoryAudit(JdbcTemplate jdbcTemplate) { return new InventoryAudit(jdbcTemplate); }

        @Bean
        AsnService asnService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new AsnService(jdbcTemplate, guard, audit);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm)
        {
            return new TransactionTemplate(tm);
        }
    }
}
