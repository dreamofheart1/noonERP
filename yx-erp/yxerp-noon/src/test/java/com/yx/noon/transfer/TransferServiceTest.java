package com.yx.noon.transfer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 第二轮调拨 H2 单测（真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper + 真 SiteAuthGuard）。
 *
 * <p>覆盖契约 §5：授权（无站点 403 / 跨站 403 / 两仓站点不一致 400 / 仓库非 ACTIVE 400 / 自报站点 400）、
 * 拆批守恒（超源批次剩余 400、源批次扣减与关闭、血缘字段、split_seq 递增）、台账双边、收货
 * （在途→OPEN、在途减可用增、短少 TRANSFER_SHORT、diff 回写、TRANSFER_IN 流水、零收货边界）、
 * 幂等（ship/receive 各自重放、内容冲突、动作冲突、PENDING 并发、失败回滚可重试）、resultOf 两态与越权、
 * 总量守恒、防伪造。
 *
 * <p>H2 建表为宽松 DDL（不含 CHECK/JSON），真实 DDL 的 CHECK/并发由 {@link MySqlTransferIT} 验证。
 */
class TransferServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TransferService service;

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

        createTables();
        service = context.getBean(TransferService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ---------------------------------------------------------- 授权（两仓 + 同站）

    @Test
    void shipAuthorizationFollowsBothWarehousesAndSameSiteRule()
    {
        long whAeA = newWarehouse("WH-TR-AE-A", "AE", "ACTIVE");
        long whAeB = newWarehouse("WH-TR-AE-B", "AE", "ACTIVE");
        long whSa = newWarehouse("WH-TR-SA", "SA", "ACTIVE");
        long whOff = newWarehouse("WH-TR-OFF", "AE", "INACTIVE");
        long sku = newSku("SKU-TR-AUTH");
        long batch = newBatch(sku, whAeA, "AE", "B:SRC-AUTH", 10, "10.0000", LocalDateTime.of(2026, 3, 1, 9, 0));
        seedLedger(sku, whAeA, "AE", 10);

        long transferAe = newTransfer("TR-AUTH-AE", whAeA, whAeB);
        long itemAe = newTransferItem(transferAe, sku, "B:SRC-AUTH", 10);

        // 1) 无任何站点授权 → 403，不写任何数据。
        login(3L);
        assertThrows(AccessDeniedException.class, () -> service.ship(new TransferShipInput(transferAe, whAeA,
                whAeB, "AE", List.of(new TransferShipLine(itemAe, 2)), rid())), "无站点必须 403");
        assertEquals(0, count("noon_m4_transfer_request"));

        // 2) 仅 AE 授权用户可发 AE 单（授权按仓库真实站点，不看请求体）。
        login(2L);
        assertFalse(service.ship(new TransferShipInput(transferAe, whAeA, whAeB, "AE",
                List.of(new TransferShipLine(itemAe, 2)), rid())).replayed());
        assertEquals(1, count("noon_m4_transfer_request"));

        // 3) 跨站：SA 调拨单，AE 用户 → 403（目标仓站点未授权）。
        long batchSa = newBatch(sku, whSa, "SA", "B:SRC-SA", 5, "10.0000", LocalDateTime.of(2026, 3, 1, 9, 0));
        seedLedger(sku, whSa, "SA", 5);
        long whSa2 = newWarehouse("WH-TR-SA-2", "SA", "ACTIVE");
        long transferSa = newTransfer("TR-AUTH-SA", whSa, whSa2);
        long itemSa = newTransferItem(transferSa, sku, "B:SRC-SA", 5);
        assertThrows(AccessDeniedException.class, () -> service.ship(new TransferShipInput(transferSa, whSa,
                whSa2, "SA", List.of(new TransferShipLine(itemSa, 1)), rid())), "跨站必须 403");
        assertEquals(1, count("noon_m4_transfer_request"), "越权不得写幂等行");

        // 4) 两仓站点不一致 → 400（即使超管也不允许跨站调拨）。
        login(1L);
        long transferCross = newTransfer("TR-AUTH-CROSS", whAeA, whSa);
        long itemCross = newTransferItem(transferCross, sku, "B:SRC-AUTH", 10);
        IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferCross, whAeA, whSa, null,
                        List.of(new TransferShipLine(itemCross, 1)), rid())));
        assertTrue(cross.getMessage().contains("跨站调拨"), cross.getMessage());

        // 5) 仓库停用 → 400；仓库不存在 → 400；源/目标仓相同 → 400。
        long transferOff = newTransfer("TR-AUTH-OFF", whOff, whAeB);
        long itemOff = newTransferItem(transferOff, sku, "B:SRC-AUTH", 1);
        IllegalArgumentException inactive = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferOff, whOff, whAeB, null,
                        List.of(new TransferShipLine(itemOff, 1)), rid())));
        assertTrue(inactive.getMessage().contains("已停用"), inactive.getMessage());

        long transferMissing = newTransfer("TR-AUTH-MISS", 9999L, whAeB);
        long itemMissing = newTransferItem(transferMissing, sku, "B:SRC-AUTH", 1);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferMissing, 9999L, whAeB, null,
                        List.of(new TransferShipLine(itemMissing, 1)), rid())));
        assertTrue(missing.getMessage().contains("仓库不存在"), missing.getMessage());

        long transferSame = newTransfer("TR-AUTH-SAME", whAeA, whAeA);
        long itemSame = newTransferItem(transferSame, sku, "B:SRC-AUTH", 1);
        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferSame, whAeA, whAeA, null,
                        List.of(new TransferShipLine(itemSame, 1)), rid())));
        assertTrue(same.getMessage().contains("不能相同"), same.getMessage());

        // 6) 请求体 siteCode 与源仓不一致 → 400（不参与授权，禁止自报站点绕过）。
        long transferSite = newTransfer("TR-AUTH-SITE", whAeA, whAeB);
        long itemSite = newTransferItem(transferSite, sku, "B:SRC-AUTH", 1);
        IllegalArgumentException siteMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferSite, whAeA, whAeB, "SA",
                        List.of(new TransferShipLine(itemSite, 1)), rid())));
        assertTrue(siteMismatch.getMessage().contains("不一致"), siteMismatch.getMessage());

        // 7) 请求体仓库 id 与调拨单真实源仓不一致 → 400（防伪造）。
        IllegalArgumentException warehouseMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferSite, whAeB, whAeB, null,
                        List.of(new TransferShipLine(itemSite, 1)), rid())));
        assertTrue(warehouseMismatch.getMessage().contains("真实源仓"), warehouseMismatch.getMessage());

        // 8) 不存在的调拨单 → 400。
        IllegalArgumentException noTransfer = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(999999L, whAeA, whAeB, null,
                        List.of(new TransferShipLine(itemAe, 1)), rid())));
        assertTrue(noTransfer.getMessage().contains("调拨单不存在"), noTransfer.getMessage());

        assertEquals(1, count("inv_batch where batch_no like 'B:TR:%'"), "被拒请求不得生成子批次");
        assertEquals(1, count("inv_stock_move"), "被拒请求不得写流水");
        assertEquals(1, count("noon_m4_transfer_request"), "被拒请求不得写幂等行");
    }

    // ---------------------------------------------------------- 拆批血缘 + 台账双边

    @Test
    void shipSplitsBatchWithLineageLedgerMovesAndSplitSeq()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-FROM", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-TO", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-SPLIT");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 1, 5, 8, 30, 15);
        long sourceBatch = newBatch(sku, fromWh, "AE", "B:SRC-SPLIT", 10, "12.3400", receivedAt);
        seedLedger(sku, fromWh, "AE", 10);

        // ① 首单发 3：源批次 10→7，子批次 seq=1。
        long transfer1 = newTransfer("TR-SPLIT-1", fromWh, toWh);
        long item1 = newTransferItem(transfer1, sku, "B:SRC-SPLIT", 10);
        TransferResult ship1 = service.ship(new TransferShipInput(transfer1, fromWh, toWh, "AE",
                List.of(new TransferShipLine(item1, 3)), rid()));
        assertFalse(ship1.replayed());
        assertEquals("SHIP", ship1.action());
        assertEquals(1, ship1.childBatchIds().size());

        long child1 = ship1.childBatchIds().get(0);
        Map<String, Object> child = jdbc.queryForMap("select batch_no,sku_id,warehouse_id,site_code,received_at,"
                + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                + "split_reason,transfer_no,status from inv_batch where id=?", child1);
        assertTrue(String.valueOf(child.get("batch_no")).startsWith("B:TR:"), String.valueOf(child.get("batch_no")));
        assertEquals(sku, ((Number) child.get("sku_id")).longValue());
        assertEquals(toWh, ((Number) child.get("warehouse_id")).longValue(), "子批次归属目标仓");
        assertEquals("AE", child.get("site_code"));
        assertEquals(receivedAt, ((Timestamp) child.get("received_at")).toLocalDateTime(),
                "FIFO 顺序继承父批次原始入库时刻");
        assertEquals(3, ((Number) child.get("qty_in")).intValue());
        assertEquals(3, ((Number) child.get("qty_remaining")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) child.get("unit_cost")), "成本继承不重估");
        assertEquals("CNY", child.get("currency"));
        assertEquals(0, new BigDecimal("1").compareTo((BigDecimal) child.get("fx_rate")));
        assertEquals("TRANSFER_SPLIT", child.get("source_type"));
        assertEquals(sourceBatch, ((Number) child.get("parent_batch_id")).longValue(), "血缘父批次");
        assertEquals(1, ((Number) child.get("split_seq")).intValue());
        assertEquals("TRANSFER", child.get("split_reason"));
        assertEquals("TR-SPLIT-1", child.get("transfer_no"));
        assertEquals("IN_TRANSIT", child.get("status"));

        // 源批次扣减 10→7，未扣满仍 OPEN。
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?", String.class, sourceBatch));
        // 台账双边：源仓可用 10→7，目标仓在途 0→3（新行）。
        assertEquals(7, ledgerAvailable(sku, fromWh));
        assertEquals(0, ledgerAvailable(sku, toWh), "目标仓可用不动");
        assertEquals(3, ledgerInTransit(sku, toWh));
        assertEquals(0, ledgerInTransit(sku, fromWh), "源仓在途不动");
        // 流水 TRANSFER_OUT：-3、源仓、引用调拨单号、指向子批次。
        Map<String, Object> outMove = jdbc.queryForMap("select move_no,sku_id,warehouse_id,batch_id,"
                + "change_type,qty_delta,ref_doc_type,ref_doc_no,operator_id from inv_stock_move");
        assertTrue(String.valueOf(outMove.get("move_no")).startsWith("TM:"), String.valueOf(outMove.get("move_no")));
        assertEquals("TRANSFER_OUT", outMove.get("change_type"));
        assertEquals(-3, ((Number) outMove.get("qty_delta")).intValue());
        assertEquals(fromWh, ((Number) outMove.get("warehouse_id")).longValue());
        assertEquals(child1, ((Number) outMove.get("batch_id")).longValue());
        assertEquals("TRANSFER", outMove.get("ref_doc_type"));
        assertEquals("TR-SPLIT-1", outMove.get("ref_doc_no"));
        assertEquals(1L, ((Number) outMove.get("operator_id")).longValue());
        // 调拨单状态机 + 行量收敛。
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer1));
        assertNotNull(jdbc.queryForObject("select shipped_at from inv_transfer where id=?",
                Timestamp.class, transfer1));
        assertEquals(3, jdbc.queryForObject("select qty_shipped from inv_transfer_item where id=?",
                Integer.class, item1), "行量收敛为实际发货量");
        // 审计 biz_type=TRANSFER、action=SHIP。
        assertEquals(1, count("noon_m4_inventory_audit"));
        Map<String, Object> audit = jdbc.queryForMap("select biz_type,biz_id,action from noon_m4_inventory_audit");
        assertEquals("TRANSFER", audit.get("biz_type"));
        assertEquals(String.valueOf(transfer1), audit.get("biz_id"));
        assertEquals("SHIP", audit.get("action"));

        // ② 同父第二单发 3：split_seq=2，剩余 7→4。
        long transfer2 = newTransfer("TR-SPLIT-2", fromWh, toWh);
        long item2 = newTransferItem(transfer2, sku, "B:SRC-SPLIT", 5);
        long child2 = service.ship(new TransferShipInput(transfer2, fromWh, toWh, null,
                List.of(new TransferShipLine(item2, 3)), rid())).childBatchIds().get(0);
        assertEquals(2, jdbc.queryForObject("select split_seq from inv_batch where id=?", Integer.class, child2),
                "同父拆批 split_seq 递增");
        assertEquals(4, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));
        assertEquals(4, ledgerAvailable(sku, fromWh), "源仓可用 10-3-3=4");
        assertEquals(6, ledgerInTransit(sku, toWh), "目标仓在途累加 3+3");
        assertEquals(2, count("inv_stock_ledger"), "源仓一行（种子）+ 目标仓一行（新建）");

        // ③ 超额发货（剩余 4，发 5）→ 400，不拆批、不改台账。
        long transfer3 = newTransfer("TR-SPLIT-3", fromWh, toWh);
        long item3 = newTransferItem(transfer3, sku, "B:SRC-SPLIT", 5);
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transfer3, fromWh, toWh, null,
                        List.of(new TransferShipLine(item3, 5)), rid())));
        assertTrue(over.getMessage().contains("超过来源批次剩余量"), over.getMessage());
        assertTrue(over.getMessage().contains("剩余=4"), over.getMessage());
        assertEquals(2, count("inv_batch where batch_no like 'B:TR:%'"), "超额失败不得生成子批次");
        assertEquals(4, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch), "超额失败不得扣减源批次");
        assertEquals(4, ledgerAvailable(sku, fromWh), "超额失败不得扣减台账");

        // ④ 计划量闸门：单行计划 2，发 3 → 400（小于剩余也不允许超计划）。
        long transferPlan = newTransfer("TR-SPLIT-PLAN", fromWh, toWh);
        long itemPlan = newTransferItem(transferPlan, sku, "B:SRC-SPLIT", 2);
        IllegalArgumentException overPlan = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferPlan, fromWh, toWh, null,
                        List.of(new TransferShipLine(itemPlan, 3)), rid())));
        assertTrue(overPlan.getMessage().contains("超过调拨单行计划量"), overPlan.getMessage());

        // ⑤ 发满剩余 4 → 源批次 CLOSED、seq=3、目标仓在途 10。
        long child3 = service.ship(new TransferShipInput(transfer3, fromWh, toWh, null,
                List.of(new TransferShipLine(item3, 4)), rid())).childBatchIds().get(0);
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, sourceBatch));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, sourceBatch), "扣到 0 置 CLOSED");
        assertEquals(3, jdbc.queryForObject("select split_seq from inv_batch where id=?", Integer.class, child3));
        assertEquals(0, ledgerAvailable(sku, fromWh));
        assertEquals(10, ledgerInTransit(sku, toWh));
        // 已 CLOSED 的批次不可再拆（另一张单）→ 400。
        long transferClosed = newTransfer("TR-SPLIT-CLOSED", fromWh, toWh);
        long itemClosed = newTransferItem(transferClosed, sku, "B:SRC-SPLIT", 1);
        IllegalArgumentException closed = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transferClosed, fromWh, toWh, null,
                        List.of(new TransferShipLine(itemClosed, 1)), rid())));
        assertTrue(closed.getMessage().contains("不可拆批出库"), closed.getMessage());
    }

    // ---------------------------------------------------------- 收货 + 短少 + 分批

    @Test
    void receiveTurnsTransitIntoOpenRecordsShortageAndStagedReceive()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-RF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-RT", "AE", "ACTIVE");
        long sku1 = newSku("SKU-TR-R1");
        long sku2 = newSku("SKU-TR-R2");
        LocalDateTime receivedAt = LocalDateTime.of(2026, 2, 1, 10, 0);
        long batch1 = newBatch(sku1, fromWh, "AE", "B:SRC-R1", 10, "12.3400", receivedAt);
        long batch2 = newBatch(sku2, fromWh, "AE", "B:SRC-R2", 6, "20.0000", receivedAt);
        seedLedger(sku1, fromWh, "AE", 10);
        seedLedger(sku2, fromWh, "AE", 6);

        long transfer = newTransfer("TR-RECV-1", fromWh, toWh);
        long item1 = newTransferItem(transfer, sku1, "B:SRC-R1", 10);
        long item2 = newTransferItem(transfer, sku2, "B:SRC-R2", 6);
        TransferResult ship = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item1, 10), new TransferShipLine(item2, 6)), rid()));
        assertEquals(2, ship.childBatchIds().size());

        // ① 先收第 1 行 8 件（短少 2）→ 整单仍未收齐，保持 IN_TRANSIT。
        String shortEvent1 = "short-evt-1";
        String recvKey1 = rid();
        TransferResult recv1 = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item1, 8, shortEvent1)), recvKey1));
        assertFalse(recv1.replayed());
        assertEquals("RECEIVE", recv1.action());
        assertEquals(1, recv1.childBatchIds().size());
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer), "仍有行在途，整单保持 IN_TRANSIT");

        long child1 = recv1.childBatchIds().get(0);
        assertEquals(8, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child1));
        assertEquals(8, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, child1));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?", String.class, child1),
                "实收 >0 在途转 OPEN");
        assertEquals(8, jdbc.queryForObject("select qty_received from inv_transfer_item where id=?",
                Integer.class, item1));
        assertEquals(2, jdbc.queryForObject("select diff_qty from inv_transfer_item where id=?",
                Integer.class, item1), "diff_qty 显式回写");

        // 台账：目标仓在途 16→6，可用 0→8。
        assertEquals(6, ledgerInTransit(sku1, toWh) + ledgerInTransit(sku2, toWh));
        assertEquals(8, ledgerAvailable(sku1, toWh));
        assertEquals(0, ledgerAvailable(sku2, toWh));

        // 短少流水：TRANSFER_SHORT / sign=1 / qty=2 / 成本继承 / amount=24.68 / 事件键 TSHORT:itemId:event。
        assertEquals(1, count("inv_batch_consumption"));
        Map<String, Object> shortRow = jdbc.queryForMap("select consumption_no,biz_event_key,source_event_id,"
                + "batch_id,sku_id,qty,unit_cost,amount,move_type,sign,period,cross_period,sal_order_item_id "
                + "from inv_batch_consumption");
        assertTrue(String.valueOf(shortRow.get("consumption_no")).startsWith("TS:"),
                String.valueOf(shortRow.get("consumption_no")));
        assertEquals("TSHORT:" + item1 + ":" + shortEvent1, shortRow.get("biz_event_key"));
        assertEquals(shortEvent1, shortRow.get("source_event_id"));
        assertEquals(child1, ((Number) shortRow.get("batch_id")).longValue());
        assertEquals(sku1, ((Number) shortRow.get("sku_id")).longValue());
        assertEquals(2, ((Number) shortRow.get("qty")).intValue());
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) shortRow.get("unit_cost")));
        assertEquals(0, new BigDecimal("24.6800").compareTo((BigDecimal) shortRow.get("amount")));
        assertEquals("TRANSFER_SHORT", shortRow.get("move_type"));
        assertEquals(1, ((Number) shortRow.get("sign")).intValue());
        assertNull(shortRow.get("sal_order_item_id"), "调拨短少不绑定销售订单行");
        assertTrue(String.valueOf(shortRow.get("period")).matches("\\d{4}-\\d{2}"),
                String.valueOf(shortRow.get("period")));

        // TRANSFER_IN 流水：+8、目标仓、引用调拨单号。
        Map<String, Object> inMove = jdbc.queryForMap("select change_type,qty_delta,warehouse_id,batch_id,"
                + "ref_doc_no from inv_stock_move where change_type='TRANSFER_IN'");
        assertEquals(8, ((Number) inMove.get("qty_delta")).intValue());
        assertEquals(toWh, ((Number) inMove.get("warehouse_id")).longValue());
        assertEquals(child1, ((Number) inMove.get("batch_id")).longValue());
        assertEquals("TR-RECV-1", inMove.get("ref_doc_no"));
        assertEquals(1, count("inv_stock_move where change_type='TRANSFER_IN'"), "只有已收行写 TRANSFER_IN");

        // ② 同 requestId 重放 → replayed=true，子批次/台账/短少/流水/审计零增长。
        int auditRows = count("noon_m4_inventory_audit");
        TransferResult replay = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item1, 8, shortEvent1)), recvKey1));
        assertTrue(replay.replayed(), "同 requestId 重放必须 replayed");
        assertEquals(8, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child1),
                "重放不得改子批次");
        assertEquals(1, count("inv_batch_consumption"), "重放不得重复记短少");
        assertEquals(8, ledgerAvailable(sku1, toWh));
        assertEquals(auditRows, count("noon_m4_inventory_audit"), "重放不得写审计");

        // ③ 不同 requestId 对同一行再收货 → 400「已收货」，不双计。
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item1, 2, "short-evt-x")), rid())));
        assertTrue(again.getMessage().contains("已收货"), again.getMessage());
        assertEquals(6, ledgerInTransit(sku1, toWh) + ledgerInTransit(sku2, toWh), "在途未变");

        // ④ 收第 2 行全部 6 件（无短少）→ 整单 RECEIVED。
        TransferResult recv2 = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item2, 6, null)), rid()));
        assertFalse(recv2.replayed());
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer), "全部收齐置 RECEIVED");
        assertNotNull(jdbc.queryForObject("select received_at from inv_transfer where id=?",
                Timestamp.class, transfer));
        assertEquals(0, ledgerInTransit(sku1, toWh) + ledgerInTransit(sku2, toWh), "在途清空");
        assertEquals(6, ledgerAvailable(sku2, toWh));
        assertEquals(4, count("inv_batch"), "2 个源批次 + 2 个子批次");
        assertEquals(1, count("inv_batch_consumption"), "无短少的行不记 TRANSFER_SHORT");
        assertEquals(3, count("noon_m4_inventory_audit"), "SHIP + 2 次 RECEIVE（重放不写）");
    }

    // ---------------------------------------------------------- 收货边界（零收货/缺事件号）

    @Test
    void receiveEdgesZeroReceivedMissingShortEventAndUnshippedItem()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-EF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-ET", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-EDGE");

        // 未发货就收货 → 400（状态闸门）。
        newBatch(sku, fromWh, "AE", "B:SRC-E1", 5, "10.0000", LocalDateTime.of(2026, 2, 2, 10, 0));
        seedLedger(sku, fromWh, "AE", 5);
        long draft = newTransfer("TR-EDGE-DRAFT", fromWh, toWh);
        long draftItem = newTransferItem(draft, sku, "B:SRC-E1", 5);
        IllegalArgumentException wrongState = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(draft, toWh,
                        List.of(new TransferReceiveLine(draftItem, 5, null)), rid())));
        assertTrue(wrongState.getMessage().contains("不可收货"), wrongState.getMessage());

        // 发货后：缺失 shortEventId 的短少 → 400；超发货量 → 400；收货量=发货量 无短少成功。
        long transfer = newTransfer("TR-EDGE-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-E1", 5);
        service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 5)), rid()));

        IllegalArgumentException missingEvent = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 3, " ")), rid())));
        assertTrue(missingEvent.getMessage().contains("shortEventId"), missingEvent.getMessage());
        assertEquals(0, count("inv_batch_consumption"), "缺事件号不得记账");

        IllegalArgumentException overReceive = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 6, null)), rid())));
        assertTrue(overReceive.getMessage().contains("超过发货量"), overReceive.getMessage());

        // 收货量 = 0（整行短少）：子批次 qty_in 保留发货量、remaining=0、CLOSED；无 TRANSFER_IN 流水。
        TransferResult zero = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item, 0, "short-all")), rid()));
        long child = zero.childBatchIds().get(0);
        assertEquals(5, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child),
                "零收货保留历史发货量（守 chk_batch_qty qty_in>0）");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, child));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?", String.class, child));
        assertEquals(5, jdbc.queryForObject("select qty from inv_batch_consumption", Integer.class), "全量短少");
        assertEquals(0, ledgerAvailable(sku, toWh));
        assertEquals(0, ledgerInTransit(sku, toWh));
        assertEquals(0, count("inv_stock_move where change_type='TRANSFER_IN'"),
                "实收 0 不写 qty_delta=0 流水（真 DDL chk_move_delta 禁止 0）");
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer));

        // 已收货行再用新 requestId 收同一行 → 400（子批次已离开 IN_TRANSIT）。
        IllegalArgumentException already = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 5, null)), rid())));
        assertTrue(already.getMessage().contains("已收货") || already.getMessage().contains("不可收货"),
                already.getMessage());
    }

    // ---------------------------------------------------------- 幂等（ship）

    @Test
    void shipIdempotencyReplayConflictPendingAndRetryAfterRollback()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-IF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-IT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-IDEM");
        long batch = newBatch(sku, fromWh, "AE", "B:SRC-IDEM", 10, "10.0000", LocalDateTime.of(2026, 3, 3, 9, 0));
        seedLedger(sku, fromWh, "AE", 10);
        long transfer = newTransfer("TR-IDEM-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-IDEM", 10);
        String requestId = rid();

        TransferResult first = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 4)), requestId));
        assertFalse(first.replayed());
        int batches = count("inv_batch");
        int moves = count("inv_stock_move");
        int audits = count("noon_m4_inventory_audit");

        // ① 同键同内容 → replayed=true，零写入。
        TransferResult replay = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 4)), requestId));
        assertTrue(replay.replayed());
        assertEquals(first.transferId(), replay.transferId());
        assertEquals(first.transferNo(), replay.transferNo());
        assertEquals(first.childBatchIds(), replay.childBatchIds());
        assertEquals("SHIP", replay.action());
        assertEquals(batches, count("inv_batch"), "重放不得新增子批次");
        assertEquals(moves, count("inv_stock_move"), "重放不得写流水");
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不得写审计");
        assertEquals(6, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "重放不得再扣源批次");
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_transfer_request where request_id=? and status='DONE'",
                Integer.class, requestId));

        // ② 同键不同数量 → 400「内容不一致」。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                        List.of(new TransferShipLine(item, 5)), requestId)));
        assertTrue(changed.getMessage().contains("内容与原请求不一致"), changed.getMessage());

        // ③ 内容冲突不落库：DONE 行仍在，库存不变。
        assertEquals(6, jdbc.queryForObject("select qty_remaining from inv_batch where id=?", Integer.class, batch));

        // ④ PENDING 并发占位（同键同内容）→ 400「处理中」。
        long other = newTransfer("TR-IDEM-2", fromWh, toWh);
        long otherItem = newTransferItem(other, sku, "B:SRC-IDEM", 3);
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_transfer_request(request_id,transfer_id,content_fingerprint,status) "
                        + "values (?,?,?,'PENDING')", pendingKey, other,
                TransferRules.shipFingerprint(other, List.of(new TransferShipLine(otherItem, 2))));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(other, fromWh, toWh, null,
                        List.of(new TransferShipLine(otherItem, 2)), pendingKey)));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());

        // ⑤ 失败回滚：超剩余的发货失败后，PENDING 占位消失，同键可安全重试成功。
        String retryKey = rid();
        long third = newTransfer("TR-IDEM-3", fromWh, toWh);
        long thirdItem = newTransferItem(third, sku, "B:SRC-IDEM", 10);
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(third, fromWh, toWh, null,
                        List.of(new TransferShipLine(thirdItem, 9)), retryKey)));
        assertTrue(over.getMessage().contains("超过来源批次剩余量"), over.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_transfer_request where request_id=?",
                Integer.class, retryKey), "失败事务的 PENDING 占位必须回滚");
        assertEquals(batches, count("inv_batch"));
        assertEquals(moves, count("inv_stock_move"));

        TransferResult retried = service.ship(new TransferShipInput(third, fromWh, toWh, null,
                List.of(new TransferShipLine(thirdItem, 6)), retryKey));
        assertFalse(retried.replayed(), "重试是首次成功");
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_request "
                + "where request_id=?", String.class, retryKey));
    }

    @Test
    void concurrentSameShipRequestIdSplitsOnce() throws Exception
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-CONC");
        long batch = newBatch(sku, fromWh, "AE", "B:SRC-CONC", 10, "10.0000", LocalDateTime.of(2026, 3, 4, 9, 0));
        seedLedger(sku, fromWh, "AE", 10);
        long transfer = newTransfer("TR-CONC-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-CONC", 10);
        String requestId = rid();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> attempt = () ->
        {
            login(1L);
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            try
            {
                TransferResult result = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                        List.of(new TransferShipLine(item, 3)), requestId));
                return result.replayed() ? "REPLAYED" : "FRESH";
            }
            catch (RuntimeException e)
            {
                return "REJECT:" + e.getClass().getSimpleName() + ":" + e.getMessage();
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        };
        Future<String> a = pool.submit(attempt);
        Future<String> b = pool.submit(attempt);
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<String> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, results.stream().filter("FRESH"::equals).count(), "并发同键只能一次真实拆批: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, count("inv_batch where batch_no like 'B:TR:%'"), "只允许一个在途子批次");
        assertEquals(7, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "源批次只扣一次");
        assertEquals(7, ledgerAvailable(sku, fromWh), "源仓可用只扣一次");
        assertEquals(3, ledgerInTransit(sku, toWh), "目标仓在途只加一次");
        assertEquals(1, count("inv_stock_move"), "只允许一条 TRANSFER_OUT 流水");
        assertEquals(1, count("noon_m4_inventory_audit"));
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_request "
                + "where request_id=?", String.class, requestId));
    }

    // ---------------------------------------------------------- 幂等（receive 动作冲突）

    @Test
    void shipRequestIdCannotBeReusedForReceive()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-AF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-AT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-ACT");
        newBatch(sku, fromWh, "AE", "B:SRC-ACT", 5, "10.0000", LocalDateTime.of(2026, 3, 5, 9, 0));
        seedLedger(sku, fromWh, "AE", 5);
        long transfer = newTransfer("TR-ACT-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-ACT", 5);
        String shipKey = rid();
        service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 5)), shipKey));

        // 同一 requestId 用于 receive（数量相同也拒绝：动作进入指纹）。
        IllegalArgumentException actionConflict = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 5, null)), shipKey)));
        assertTrue(actionConflict.getMessage().contains("内容与原请求不一致"), actionConflict.getMessage());
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer), "动作冲突不得推进状态");

        // 正常 receive 的另一键重放：replayed=true 并返回 RECEIVE 结果。
        String recvKey = rid();
        TransferResult recv = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item, 5, null)), recvKey));
        TransferResult recvReplay = service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item, 5, null)), recvKey));
        assertTrue(recvReplay.replayed());
        assertEquals("RECEIVE", recvReplay.action());
        assertEquals(recv.childBatchIds(), recvReplay.childBatchIds());
        assertEquals(5, ledgerAvailable(sku, toWh), "重放不得重复累加");
    }

    // ---------------------------------------------------------- resultOf

    @Test
    void resultOfIsReadOnlySiteAuthorizedAndTwoState()
    {
        login(1L);
        long fromWhAe = newWarehouse("WH-TR-RSA", "AE", "ACTIVE");
        long toWhAe = newWarehouse("WH-TR-RSB", "AE", "ACTIVE");
        long fromWhSa = newWarehouse("WH-TR-RSC", "SA", "ACTIVE");
        long toWhSa = newWarehouse("WH-TR-RSD", "SA", "ACTIVE");
        long sku = newSku("SKU-TR-RES");
        newBatch(sku, fromWhAe, "AE", "B:SRC-RES-AE", 10, "10.0000", LocalDateTime.of(2026, 3, 6, 9, 0));
        newBatch(sku, fromWhSa, "SA", "B:SRC-RES-SA", 10, "10.0000", LocalDateTime.of(2026, 3, 6, 9, 0));
        seedLedger(sku, fromWhAe, "AE", 10);
        seedLedger(sku, fromWhSa, "SA", 10);

        long transferAe = newTransfer("TR-RES-AE", fromWhAe, toWhAe);
        long itemAe = newTransferItem(transferAe, sku, "B:SRC-RES-AE", 5);
        String keyAe = rid();
        TransferResult first = service.ship(new TransferShipInput(transferAe, fromWhAe, toWhAe, null,
                List.of(new TransferShipLine(itemAe, 5)), keyAe));
        int batches = count("inv_batch");

        TransferResult queried = service.resultOf(keyAe);
        assertNotNull(queried);
        assertTrue(queried.replayed());
        assertEquals(first.transferId(), queried.transferId());
        assertEquals(first.childBatchIds(), queried.childBatchIds());
        assertEquals("SHIP", queried.action());
        assertEquals(batches, count("inv_batch"), "resultOf 只读");
        assertEquals(1, count("noon_m4_inventory_audit"), "resultOf 不写审计");

        // 不存在/非法/PENDING → null。
        assertNull(service.resultOf("r-" + UUID.randomUUID()));
        assertNull(service.resultOf(null));
        assertNull(service.resultOf(""));
        assertNull(service.resultOf("short"));
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_transfer_request(request_id,transfer_id,content_fingerprint,status) "
                        + "values (?,?,?,'PENDING')", pendingKey, transferAe,
                TransferRules.shipFingerprint(transferAe, List.of(new TransferShipLine(itemAe, 1))));
        assertNull(service.resultOf(pendingKey), "PENDING 无结果可展示");

        // SA 单的键：AE 用户 403、无站点用户 403、超管可见。
        long transferSa = newTransfer("TR-RES-SA", fromWhSa, toWhSa);
        long itemSa = newTransferItem(transferSa, sku, "B:SRC-RES-SA", 4);
        String keySa = rid();
        service.ship(new TransferShipInput(transferSa, fromWhSa, toWhSa, null,
                List.of(new TransferShipLine(itemSa, 4)), keySa));

        login(2L);
        assertThrows(AccessDeniedException.class, () -> service.resultOf(keySa), "跨站查结果 403");
        assertNotNull(service.resultOf(keyAe), "AE 用户可查 AE 单结果");
        login(3L);
        assertThrows(AccessDeniedException.class, () -> service.resultOf(keyAe), "无站点查结果 403");
    }

    // ---------------------------------------------------------- 台账不足拒绝（双边）

    @Test
    void ledgerInsufficiencyRejectsShipAndReceiveWithRollback()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-LF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-LT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-LEDGER");
        long batch = newBatch(sku, fromWh, "AE", "B:SRC-LEDGER", 10, "10.0000",
                LocalDateTime.of(2026, 8, 1, 9, 0));
        seedLedger(sku, fromWh, "AE", 5); // 批次剩余 10 但台账可用仅 5（数据不一致）
        long transfer = newTransfer("TR-LEDGER-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-LEDGER", 10);

        // 发货 6 > 台账可用 5 → 400，子批次/批次/台账全部回滚。
        IllegalArgumentException shortLedger = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                        List.of(new TransferShipLine(item, 6)), rid())));
        assertTrue(shortLedger.getMessage().contains("可用量不足"), shortLedger.getMessage());
        assertEquals(0, count("inv_batch where batch_no like 'B:TR:%'"), "失败不得留下子批次");
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "失败不得扣批次");
        assertEquals(5, ledgerAvailable(sku, fromWh), "失败不得扣台账");
        assertEquals(0, count("noon_m4_transfer_request"));

        // 发货 4（<= 可用 5）成功；随后人为把目标仓在途改为 2 模拟不一致，收货 4 → 400 在途不足。
        TransferResult ok = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 4)), rid()));
        assertEquals(1, ledgerAvailable(sku, fromWh));
        assertEquals(4, ledgerInTransit(sku, toWh));
        jdbc.update("update inv_stock_ledger set qty_in_transit=2 where sku_id=? and warehouse_id=?",
                sku, toWh);
        IllegalArgumentException shortTransit = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 4, null)), rid())));
        assertTrue(shortTransit.getMessage().contains("在途量不足"), shortTransit.getMessage());
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, ok.childBatchIds().get(0)), "失败事务回滚，子批次仍在途");
        assertEquals(0, ledgerAvailable(sku, toWh), "失败不得加可用");
        assertEquals(2, ledgerInTransit(sku, toWh));
    }

    // ---------------------------------------------------------- 短少唯一键拦截

    @Test
    void duplicateShortEventIsBlockedByUniqueKeyWithoutDoubleCounting()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-UF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-UT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-UNIQ");
        newBatch(sku, fromWh, "AE", "B:SRC-UNIQ", 10, "9.0000", LocalDateTime.of(2026, 7, 1, 9, 0));
        seedLedger(sku, fromWh, "AE", 10);
        long transfer = newTransfer("TR-UNIQ-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-UNIQ", 10);
        long child = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, 10)), rid())).childBatchIds().get(0);

        // 预置同 (biz_event_key,batch_id) 的短少流水（模拟另一路径已过账同一事件）。
        String eventId = "dup-evt-1";
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('TS:manual',?,?,?,2,'9.0000','18.0000',current_timestamp(3),"
                        + "'TRANSFER_SHORT',1,'2026-07',0)",
                "TSHORT:" + item + ":" + eventId, child, sku);

        // 收货 8 → 短少 2，服务尝试写同事件键 → 唯一键拦截 400，整事务回滚（不双计）。
        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class,
                () -> service.receive(new TransferReceiveInput(transfer, toWh,
                        List.of(new TransferReceiveLine(item, 8, eventId)), rid())));
        assertTrue(duplicated.getMessage().contains("短少事件已过账"), duplicated.getMessage());
        assertEquals(1, count("inv_batch_consumption"), "唯一键拦截后不得双计");
        assertEquals(10, jdbc.queryForObject("select qty_in from inv_batch where id=?", Integer.class, child),
                "失败事务回滚，子批次仍为发货态");
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, child));
        assertEquals(10, ledgerInTransit(sku, toWh), "失败事务回滚，在途不变");
        assertEquals(0, ledgerAvailable(sku, fromWh), "源仓可用仍为发货后的 0");
    }

    // ---------------------------------------------------------- 列表 / 详情 / 分页

    @Test
    void listGetRespectSiteAuthorizationPagingAndDetailLineage()
    {
        login(1L);
        long fromAe = newWarehouse("WH-TR-LA", "AE", "ACTIVE");
        long toAe = newWarehouse("WH-TR-LB", "AE", "ACTIVE");
        long fromSa = newWarehouse("WH-TR-LC", "SA", "ACTIVE");
        long toSa = newWarehouse("WH-TR-LD", "SA", "ACTIVE");
        long sku = newSku("SKU-TR-LIST");
        newBatch(sku, fromAe, "AE", "B:SRC-LA", 20, "10.0000", LocalDateTime.of(2026, 3, 7, 9, 0));
        newBatch(sku, fromSa, "SA", "B:SRC-LC", 20, "10.0000", LocalDateTime.of(2026, 3, 7, 9, 0));
        seedLedger(sku, fromAe, "AE", 20);
        seedLedger(sku, fromSa, "SA", 20);

        long t1 = newTransfer("TR-LIST-1", fromAe, toAe);
        long t1Item = newTransferItem(t1, sku, "B:SRC-LA", 4);
        long t2 = newTransfer("TR-LIST-2", fromAe, toAe);
        long t2Item = newTransferItem(t2, sku, "B:SRC-LA", 4);
        long t3 = newTransfer("TR-LIST-3", fromSa, toSa);
        long t3Item = newTransferItem(t3, sku, "B:SRC-LC", 4);

        TransferResult shipped1 = service.ship(new TransferShipInput(t1, fromAe, toAe, null,
                List.of(new TransferShipLine(t1Item, 4)), rid()));
        service.ship(new TransferShipInput(t2, fromAe, toAe, null,
                List.of(new TransferShipLine(t2Item, 2)), rid()));
        service.ship(new TransferShipInput(t3, fromSa, toSa, null,
                List.of(new TransferShipLine(t3Item, 3)), rid()));

        // 超管：全部站点；详情含头、行、子批次血缘。
        PageResult<TransferRow> all = service.list(null, 0, 10);
        assertEquals(3, all.total());
        assertEquals(3, all.rows().size());
        TransferDetail detail = service.get(shipped1.transferId());
        assertEquals("TR-LIST-1", detail.head().transferNo());
        assertEquals("AE", detail.head().siteCode());
        assertEquals("IN_TRANSIT", detail.head().status());
        assertEquals(1, detail.head().itemCount().intValue());
        assertEquals(1, detail.items().size());
        TransferItemRow itemRow = detail.items().get(0);
        assertEquals(4, itemRow.qtyShipped().intValue());
        assertEquals(0, itemRow.qtyReceived().intValue());
        assertEquals(0, itemRow.diffQty().intValue());
        assertEquals("B:SRC-LA", itemRow.batchNo());
        assertNotNull(itemRow.childBatchId());
        assertTrue(itemRow.childBatchNo().startsWith("B:TR:"), itemRow.childBatchNo());
        assertEquals("IN_TRANSIT", itemRow.childStatus());

        // 分页：total 不受 LIMIT 影响，翻页无重复无遗漏。
        List<Long> expected = all.rows().stream().map(TransferRow::id).toList();
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += 2)
        {
            PageResult<TransferRow> page = service.list(null, offset, 2);
            assertEquals(3, page.total());
            assertEquals(Math.min(2, expected.size() - offset), page.rows().size());
            seen.addAll(page.rows().stream().map(TransferRow::id).toList());
        }
        assertEquals(expected, seen, "翻页无重复无遗漏");

        // 普通用户仅 AE：只看 AE 单，跨站详情 403；未授权站点筛选 403。
        login(2L);
        assertEquals(2, service.list(null, 0, 10).total());
        assertEquals(0, service.list(null, 0, 10).rows().stream()
                .filter(row -> "SA".equals(row.siteCode())).count());
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 10));
        assertThrows(AccessDeniedException.class, () -> service.get(t3), "跨站详情 403");
        assertNotNull(service.get(t1), "同站详情可见");

        // 无站点：零行、筛选 403、详情 403。
        login(3L);
        assertEquals(0, service.list(null, 0, 10).total());
        assertThrows(AccessDeniedException.class, () -> service.list("AE", 0, 10));
        assertThrows(AccessDeniedException.class, () -> service.get(t1));

        // 不存在/非法 id → 400。
        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.get(999999L));
        assertThrows(IllegalArgumentException.class, () -> service.get(null));
    }

    // ---------------------------------------------------------- 总量守恒

    @Test
    void totalQuantityConservationAcrossSourceChildAndShortage()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-QF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-QT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-CONS");
        long initialRemaining = 10;
        long batch = newBatch(sku, fromWh, "AE", "B:SRC-CONS", (int) initialRemaining, "7.5000",
                LocalDateTime.of(2026, 4, 1, 9, 0));
        seedLedger(sku, fromWh, "AE", (int) initialRemaining);

        long transfer = newTransfer("TR-CONS-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-CONS", (int) initialRemaining);
        TransferResult ship = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item, (int) initialRemaining)), rid()));
        long childId = ship.childBatchIds().get(0);

        service.receive(new TransferReceiveInput(transfer, toWh,
                List.of(new TransferReceiveLine(item, 7, "short-conserve")), rid()));

        // 源批次剩余 + 子批次剩余 + 已记短少 = 初始剩余。
        long sourceRemaining = jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Long.class, batch);
        long childRemaining = jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Long.class, childId);
        long shortQty = jdbc.queryForObject("select COALESCE(SUM(qty),0) from inv_batch_consumption "
                + "where move_type='TRANSFER_SHORT'", Long.class);
        assertEquals(initialRemaining, sourceRemaining + childRemaining + shortQty,
                "源批次剩余 + 子批次 + 短少 = 初始剩余");
        assertEquals(0, sourceRemaining);
        assertEquals(7, childRemaining);
        assertEquals(3, shortQty);

        // 台账总量：两仓可用之和 + 短少 = 初始可用（源仓 0 + 目标仓 7 + 短少 3）。
        long available = jdbc.queryForObject("select COALESCE(SUM(qty_available),0) from inv_stock_ledger",
                Long.class);
        assertEquals(initialRemaining, available + shortQty, "可用 + 短少 = 初始可用");
        assertEquals(0, jdbc.queryForObject("select COALESCE(SUM(qty_in_transit),0) from inv_stock_ledger",
                Long.class), "在途清零");
        // 流水净额：-10（出）+7（入）=-3 = -短少。
        assertEquals(-3, jdbc.queryForObject("select COALESCE(SUM(qty_delta),0) from inv_stock_move",
                Integer.class));
        // 消耗金额 = 短少量 × 继承成本。
        assertEquals(0, new BigDecimal("22.5000").compareTo(jdbc.queryForObject(
                "select amount from inv_batch_consumption", BigDecimal.class)));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, transfer));
    }

    // ---------------------------------------------------------- 防伪造

    @Test
    void clientSuppliedSkuCostAndBatchAreIgnored()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-FF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-FT", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-FAKE");
        newBatch(sku, fromWh, "AE", "B:SRC-FAKE", 10, "12.3400", LocalDateTime.of(2026, 5, 1, 9, 0));
        seedLedger(sku, fromWh, "AE", 10);
        long transfer = newTransfer("TR-FAKE-1", fromWh, toWh);
        long item = newTransferItem(transfer, sku, "B:SRC-FAKE", 10);

        String body = "{\"transferId\":" + transfer + ",\"fromWarehouseId\":" + fromWh
                + ",\"toWarehouseId\":" + toWh + ",\"siteCode\":\"AE\","
                + "\"lines\":[{\"itemId\":" + item + ",\"qtyShipped\":3,"
                + "\"skuId\":9999,\"unitCost\":0.01,\"batchNo\":\"HACK\",\"qty\":100}],"
                + "\"requestId\":\"" + rid() + "\"}";
        TransferShipInput parsed;
        try
        {
            parsed = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(body, TransferShipInput.class);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        assertEquals(3, parsed.lines().get(0).qtyShipped());

        TransferResult result = service.ship(parsed);
        long child = result.childBatchIds().get(0);
        Map<String, Object> childRow = jdbc.queryForMap("select sku_id,unit_cost,qty_in,source_type "
                + "from inv_batch where id=?", child);
        assertEquals(sku, ((Number) childRow.get("sku_id")).longValue(), "SKU 必须来自调拨单行，忽略客户端 9999");
        assertEquals(0, new BigDecimal("12.3400").compareTo((BigDecimal) childRow.get("unit_cost")),
                "成本必须继承来源批次，忽略客户端 0.01");
        assertEquals(3, ((Number) childRow.get("qty_in")).intValue(), "数量只认 qtyShipped，忽略客户端 qty=100");
        assertEquals("TRANSFER_SPLIT", childRow.get("source_type"));
    }

    // ---------------------------------------------------------- 行校验

    @Test
    void lineValidationRequiresFullCoverageAndUniqueItems()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-VF", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-VT", "AE", "ACTIVE");
        long sku1 = newSku("SKU-TR-V1");
        long sku2 = newSku("SKU-TR-V2");
        newBatch(sku1, fromWh, "AE", "B:SRC-V1", 10, "10.0000", LocalDateTime.of(2026, 6, 1, 9, 0));
        newBatch(sku2, fromWh, "AE", "B:SRC-V2", 10, "10.0000", LocalDateTime.of(2026, 6, 1, 9, 0));
        seedLedger(sku1, fromWh, "AE", 10);
        seedLedger(sku2, fromWh, "AE", 10);
        long transfer = newTransfer("TR-VALID-1", fromWh, toWh);
        long item1 = newTransferItem(transfer, sku1, "B:SRC-V1", 10);
        long item2 = newTransferItem(transfer, sku2, "B:SRC-V2", 10);

        // 未覆盖全部行 → 400。
        IllegalArgumentException partial = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                        List.of(new TransferShipLine(item1, 2)), rid())));
        assertTrue(partial.getMessage().contains("必须覆盖全部调拨单行"), partial.getMessage());

        // 空行 / 重复 itemId / 数量 <=0 / itemId 非正整数 → 400。
        assertThrows(IllegalArgumentException.class, () -> service.ship(new TransferShipInput(transfer,
                fromWh, toWh, null, List.of(), rid())));
        assertThrows(IllegalArgumentException.class, () -> service.ship(new TransferShipInput(transfer,
                fromWh, toWh, null, List.of(new TransferShipLine(item1, 1), new TransferShipLine(item1, 1)), rid())));
        assertThrows(IllegalArgumentException.class, () -> service.ship(new TransferShipInput(transfer,
                fromWh, toWh, null, List.of(new TransferShipLine(item1, 0), new TransferShipLine(item2, 1)), rid())));
        assertThrows(IllegalArgumentException.class, () -> service.ship(new TransferShipInput(transfer,
                fromWh, toWh, null, List.of(new TransferShipLine(0L, 1), new TransferShipLine(item2, 1)), rid())));

        // itemId 不属于该调拨单 → 400（且不生成半截数据）。
        long otherTransfer = newTransfer("TR-VALID-2", fromWh, toWh);
        long foreignItem = newTransferItem(otherTransfer, sku1, "B:SRC-V1", 5);
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                        List.of(new TransferShipLine(item1, 1), new TransferShipLine(foreignItem, 1)), rid())));
        assertTrue(foreign.getMessage().contains("不属于该调拨单"), foreign.getMessage());
        assertEquals(0, count("inv_batch where batch_no like 'B:TR:%'"), "校验失败不得生成子批次");

        // 正常全行发货成功。
        TransferResult ok = service.ship(new TransferShipInput(transfer, fromWh, toWh, null,
                List.of(new TransferShipLine(item1, 2), new TransferShipLine(item2, 3)), rid()));
        assertEquals(2, ok.childBatchIds().size());
    }

    // ---------------------------------------------------------- create（建单）

    @Test
    void createAuthorizationFollowsWarehousesSitesAndSuppliedSite()
    {
        long fromAe = newWarehouse("WH-TR-CR-A", "AE", "ACTIVE");
        long toAe = newWarehouse("WH-TR-CR-B", "AE", "ACTIVE");
        long fromSa = newWarehouse("WH-TR-CR-SA1", "SA", "ACTIVE");
        long toSa = newWarehouse("WH-TR-CR-SA2", "SA", "ACTIVE");
        long inactive = newWarehouse("WH-TR-CR-OFF", "AE", "INACTIVE");
        long sku = newSku("SKU-TR-CR-AUTH");
        newBatch(sku, fromAe, "AE", "B:SRC-CR-AUTH", 10, "10.0000", LocalDateTime.of(2026, 5, 3, 9, 0));
        List<TransferCreateLine> lines = List.of(new TransferCreateLine(sku, "B:SRC-CR-AUTH", 5));

        // ① 无站点授权 → 403，零写入（授权先于一切校验）。
        login(3L);
        assertThrows(AccessDeniedException.class,
                () -> service.create(new TransferCreateInput(fromAe, toAe, "AE", lines, rid())));
        assertEquals(0, count("inv_transfer"), "越权不得建单");
        assertEquals(0, count("noon_m4_transfer_create_request"), "越权不得写幂等行");

        // ② 仅 AE 授权用户建 SA 单 → 403（按仓库真实站点，不看请求体）。
        login(2L);
        assertThrows(AccessDeniedException.class,
                () -> service.create(new TransferCreateInput(fromSa, toSa, null, lines, rid())));
        assertEquals(0, count("inv_transfer"));

        // ③ 超管：跨站 400 / 同仓 400 / 停用 400 / 不存在 400 / 自报站点 400。
        login(1L);
        IllegalArgumentException cross = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromAe, toSa, null, lines, rid())));
        assertTrue(cross.getMessage().contains("跨站调拨"), cross.getMessage());

        IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromAe, fromAe, null, lines, rid())));
        assertTrue(same.getMessage().contains("不能相同"), same.getMessage());

        IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(inactive, toAe, null, lines, rid())));
        assertTrue(off.getMessage().contains("已停用"), off.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(9999L, toAe, null, lines, rid())));
        assertTrue(missing.getMessage().contains("仓库不存在"), missing.getMessage());

        IllegalArgumentException siteMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromAe, toAe, "SA", lines, rid())));
        assertTrue(siteMismatch.getMessage().contains("不一致"), siteMismatch.getMessage());
        assertEquals(0, count("noon_m4_transfer_create_request"), "被拒请求不得写幂等行");

        // ④ 合法建单：仅 AE 用户（授权内）成功，状态 DRAFT、单号服务端生成。
        login(2L);
        TransferResult created = service.create(new TransferCreateInput(fromAe, toAe, "AE", lines, rid()));
        assertFalse(created.replayed());
        assertEquals("CREATE", created.action());
        assertTrue(created.childBatchIds().isEmpty(), "建单不产生子批次");
        assertTrue(created.transferNo().matches("TR:AE:[0-9A-F]{8}"), created.transferNo());
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
    }

    @Test
    void createValidatesSourceBatchAuthorityAndDuplicateLines()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CV-F", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CV-T", "AE", "ACTIVE");
        long otherWh = newWarehouse("WH-TR-CV-O", "AE", "ACTIVE");
        long skuA = newSku("SKU-TR-CV-A");
        long skuB = newSku("SKU-TR-CV-B");
        newBatch(skuA, fromWh, "AE", "B:SRC-CV-A", 10, "10.0000", LocalDateTime.of(2026, 5, 4, 9, 0));
        newBatch(skuB, fromWh, "AE", "B:SRC-CV-B", 4, "7.0000", LocalDateTime.of(2026, 5, 4, 9, 0));
        newBatch(skuA, otherWh, "AE", "B:SRC-CV-OTHER", 10, "10.0000", LocalDateTime.of(2026, 5, 4, 9, 0));
        // 非 OPEN 批次（CLOSED）与站点错配批次（合成数据，故意 site_code=SA）。
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,"
                        + "qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
                        + "values ('B:SRC-CV-CLOSED',?,?, 'AE',current_timestamp(3),3,0,'10.0000','CNY',1,'PURCHASE',0,'CLOSED')",
                skuA, fromWh);
        jdbc.update("insert into inv_batch(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,"
                        + "qty_remaining,unit_cost,currency,fx_rate,source_type,split_seq,status) "
                        + "values ('B:SRC-CV-SITE',?,?, 'SA',current_timestamp(3),5,5,'10.0000','CNY',1,'PURCHASE',0,'OPEN')",
                skuA, fromWh);

        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-NONE", 2)), rid())));
        assertTrue(absent.getMessage().contains("来源批次不存在"), absent.getMessage());

        IllegalArgumentException skuMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-B", 2)), rid())));
        assertTrue(skuMismatch.getMessage().contains("SKU"), skuMismatch.getMessage());

        IllegalArgumentException otherWarehouse = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-OTHER", 2)), rid())));
        assertTrue(otherWarehouse.getMessage().contains("不属于源仓"), otherWarehouse.getMessage());

        IllegalArgumentException notOpen = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-CLOSED", 2)), rid())));
        assertTrue(notOpen.getMessage().contains("不可用于建单"), notOpen.getMessage());

        IllegalArgumentException siteMismatch = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-SITE", 2)), rid())));
        assertTrue(siteMismatch.getMessage().contains("站点 SA"), siteMismatch.getMessage());

        IllegalArgumentException overRemaining = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuB, "B:SRC-CV-B", 5)), rid())));
        assertTrue(overRemaining.getMessage().contains("超过来源批次剩余量"), overRemaining.getMessage());

        IllegalArgumentException zeroQty = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuB, "B:SRC-CV-B", 0)), rid())));
        assertTrue(zeroQty.getMessage().contains("qty 必须大于 0"), zeroQty.getMessage());

        IllegalArgumentException duplicateLine = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(skuA, "B:SRC-CV-A", 2),
                                new TransferCreateLine(skuA, "B:SRC-CV-A", 3)), rid())));
        assertTrue(duplicateLine.getMessage().contains("重复提交"), duplicateLine.getMessage());

        assertEquals(0, count("inv_transfer"), "批次校验失败不得留半张单");
        assertEquals(0, count("noon_m4_transfer_create_request"));

        // 源批次剩余在 create 期不被扣减（校验只读）。
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-CV-A'",
                Integer.class));
    }

    @Test
    void createWritesDraftPlanWithoutDeductingStockAndIsListGetVisible()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CD-F", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CD-T", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-CD");
        newBatch(sku, fromWh, "AE", "B:SRC-CD", 10, "8.5000", LocalDateTime.of(2026, 5, 5, 9, 0));
        String requestId = rid();

        // 故意不预置台账：create 不触碰 inv_stock_ledger（若触碰会建行，断言可发现）。
        TransferResult created = service.create(new TransferCreateInput(fromWh, toWh, "AE",
                List.of(new TransferCreateLine(sku, "B:SRC-CD", 4)), requestId));

        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_transfer_item where transfer_id=?",
                Integer.class, created.transferId()));
        Map<String, Object> item = jdbc.queryForMap("select sku_id,batch_no,qty_shipped,qty_received,"
                + "diff_qty from inv_transfer_item where transfer_id=?", created.transferId());
        assertEquals(sku, ((Number) item.get("sku_id")).longValue());
        assertEquals("B:SRC-CD", item.get("batch_no"), "调拨行保留来源父批次号");
        assertEquals(4, ((Number) item.get("qty_shipped")).intValue(), "DRAFT 期 qty_shipped 为计划量");
        assertEquals(0, ((Number) item.get("qty_received")).intValue());
        assertEquals(0, ((Number) item.get("diff_qty")).intValue());

        // create 不扣批次、不动台账、不写流水、不生成子批次。
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-CD'",
                Integer.class), "create 不得扣减批次剩余");
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where batch_no='B:SRC-CD'",
                String.class));
        assertEquals(0, count("inv_stock_ledger"), "create 不得写台账");
        assertEquals(0, count("inv_stock_move"), "create 不得写流水");
        assertEquals(0, count("inv_batch where batch_no like 'B:TR:%'"), "create 不得生成在途子批次");

        // 审计：biz_type=TRANSFER / action=CREATE / biz_id=transferId，与业务同事务。
        Map<String, Object> auditRow = jdbc.queryForMap("select biz_type,action,biz_id,operator_id "
                + "from noon_m4_inventory_audit");
        assertEquals("TRANSFER", auditRow.get("biz_type"));
        assertEquals("CREATE", auditRow.get("action"));
        assertEquals(String.valueOf(created.transferId()), auditRow.get("biz_id"));
        assertEquals(1L, ((Number) auditRow.get("operator_id")).longValue());

        // 幂等表 DONE + 回填真实 transfer_id + 内容指纹。
        assertEquals("DONE", jdbc.queryForObject("select status from noon_m4_transfer_create_request "
                + "where request_id=?", String.class, requestId));
        assertEquals(created.transferId(), jdbc.queryForObject("select transfer_id "
                + "from noon_m4_transfer_create_request where request_id=?", Long.class, requestId));
        assertEquals(TransferRules.createFingerprint(fromWh, toWh,
                        List.of(new TransferCreateLine(sku, "B:SRC-CD", 4))),
                jdbc.queryForObject("select content_fingerprint from noon_m4_transfer_create_request "
                        + "where request_id=?", String.class, requestId));

        // 新单能被 list/get 按站点查到。
        PageResult<TransferRow> page = service.list("AE", 0, 20);
        assertEquals(1, page.total());
        assertEquals(created.transferId(), page.rows().get(0).id());
        assertEquals("AE", page.rows().get(0).siteCode());
        assertEquals(1, page.rows().get(0).itemCount());
        TransferDetail detail = service.get(created.transferId());
        assertEquals("DRAFT", detail.head().status());
        assertEquals(1, detail.items().size());
        assertEquals(sku, detail.items().get(0).skuId());
        assertNull(detail.items().get(0).childBatchId(), "未发货无子批次");
    }

    @Test
    void createIdempotencyReplayConflictPendingAndRetryAfterRollback()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CI-F", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CI-T", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-CI");
        newBatch(sku, fromWh, "AE", "B:SRC-CI", 10, "10.0000", LocalDateTime.of(2026, 5, 6, 9, 0));
        String requestId = rid();
        List<TransferCreateLine> lines = List.of(new TransferCreateLine(sku, "B:SRC-CI", 4));

        TransferResult first = service.create(new TransferCreateInput(fromWh, toWh, null, lines, requestId));
        assertFalse(first.replayed());
        int transfers = count("inv_transfer");
        int items = count("inv_transfer_item");
        int audits = count("noon_m4_inventory_audit");

        // ① 同键同内容 → replayed=true，零写入。
        TransferResult replay = service.create(new TransferCreateInput(fromWh, toWh, null, lines, requestId));
        assertTrue(replay.replayed());
        assertEquals(first.transferId(), replay.transferId());
        assertEquals(first.transferNo(), replay.transferNo());
        assertEquals("CREATE", replay.action());
        assertTrue(replay.childBatchIds().isEmpty());
        assertEquals(transfers, count("inv_transfer"), "重放不得建第二张单");
        assertEquals(items, count("inv_transfer_item"), "重放不得写行");
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不得写审计");
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-CI'",
                Integer.class), "重放不得扣库存");

        // ② 同键不同内容 → 400（指纹含明细），不新增数据。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(sku, "B:SRC-CI", 5)), requestId)));
        assertTrue(changed.getMessage().contains("内容与原请求不一致"), changed.getMessage());
        assertEquals(transfers, count("inv_transfer"));

        // ③ PENDING 并发占位（同键同内容）→ 400「处理中」。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_transfer_create_request"
                        + "(request_id,transfer_id,content_fingerprint,status) values (?,0,?, 'PENDING')",
                pendingKey, TransferRules.createFingerprint(fromWh, toWh,
                        List.of(new TransferCreateLine(sku, "B:SRC-CI", 2))));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(sku, "B:SRC-CI", 2)), pendingKey)));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());

        // ④ 失败回滚：占位后事务中途失败（审计表缺失）→ PENDING 与半张单一起回滚，同键可安全重试。
        String retryKey = rid();
        jdbc.execute("drop table noon_m4_inventory_audit");
        assertThrows(RuntimeException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(sku, "B:SRC-CI", 3)), retryKey)));
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_transfer_create_request "
                + "where request_id=?", Integer.class, retryKey), "失败事务的 PENDING 占位必须回滚");
        assertEquals(transfers, count("inv_transfer"), "失败事务不得留下半张调拨单");
        assertEquals(items, count("inv_transfer_item"));

        createAuditTable();
        TransferResult retried = service.create(new TransferCreateInput(fromWh, toWh, null,
                List.of(new TransferCreateLine(sku, "B:SRC-CI", 3)), retryKey));
        assertFalse(retried.replayed(), "回滚后重试是首次成功");
        assertEquals(transfers + 1, count("inv_transfer"));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-CI'",
                Integer.class), "建单不扣库存（扣减在 ship）");
    }

    @Test
    void createThenShipThenReceiveCompletesChainWithoutSeedTransfer()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CE-F", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CE-T", "AE", "ACTIVE");
        long skuA = newSku("SKU-TR-CE-A");
        long skuB = newSku("SKU-TR-CE-B");
        newBatch(skuA, fromWh, "AE", "B:SRC-CE-A", 10, "11.0000", LocalDateTime.of(2026, 5, 7, 9, 0));
        newBatch(skuB, fromWh, "AE", "B:SRC-CE-B", 6, "3.0000", LocalDateTime.of(2026, 5, 7, 9, 0));
        seedLedger(skuA, fromWh, "AE", 10);
        seedLedger(skuB, fromWh, "AE", 6);

        // 建单（两条计划行）→ 发运全量 → 分批/短少收货，全程不依赖任何种子调拨单。
        TransferResult created = service.create(new TransferCreateInput(fromWh, toWh, "AE",
                List.of(new TransferCreateLine(skuA, "B:SRC-CE-A", 6),
                        new TransferCreateLine(skuB, "B:SRC-CE-B", 4)), rid()));
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        assertEquals(10, ledgerAvailable(skuA, fromWh), "create 后源仓可用不变 = 10");
        assertEquals(2, count("inv_stock_ledger"), "create 不新增台账行（目标仓仍无行）");

        TransferDetail detail = service.get(created.transferId());
        long itemA = detail.items().get(0).itemId();
        long itemB = detail.items().get(1).itemId();

        TransferResult ship = service.ship(new TransferShipInput(created.transferId(), fromWh, toWh, null,
                List.of(new TransferShipLine(itemA, 6), new TransferShipLine(itemB, 4)), rid()));
        assertEquals(2, ship.childBatchIds().size());
        assertEquals("IN_TRANSIT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        assertEquals(4, ledgerAvailable(skuA, fromWh), "ship 才扣源仓可用 6");
        assertEquals(6, ledgerInTransit(skuA, toWh), "目标仓在途 +6");
        assertEquals(4, jdbc.queryForObject("select qty_remaining from inv_batch where batch_no='B:SRC-CE-A'",
                Integer.class), "ship 才扣批次剩余");

        service.receive(new TransferReceiveInput(created.transferId(), toWh,
                List.of(new TransferReceiveLine(itemA, 5, "short-create-e"),
                        new TransferReceiveLine(itemB, 4, null)), rid()));
        assertEquals("RECEIVED", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, created.transferId()));
        assertEquals(5, ledgerAvailable(skuA, toWh), "目标仓可用 +5（短少 1）");
        assertEquals(0, ledgerInTransit(skuA, toWh), "在途清零");
        assertEquals(1, jdbc.queryForObject("select diff_qty from inv_transfer_item where id=?",
                Integer.class, itemA), "短少 1 回写 diff_qty");
        assertEquals(1, count("inv_batch_consumption where move_type='TRANSFER_SHORT' and qty=1"), "短少记一条");
        assertEquals(2, count("inv_batch where batch_no like 'B:TR:%'"));
        assertEquals(4, count("inv_stock_move"), "ship 2 条 TRANSFER_OUT + receive 2 条 TRANSFER_IN");

        // list/get 反映终态。
        TransferDetail received = service.get(created.transferId());
        assertEquals("RECEIVED", received.head().status());
        assertEquals(1, received.items().get(0).diffQty());
        assertEquals("OPEN", received.items().get(0).childStatus());
    }

    @Test
    void createIgnoresClientSuppliedCostCurrencyAndBatchId()
    {
        login(1L);
        long fromWh = newWarehouse("WH-TR-CG-F", "AE", "ACTIVE");
        long toWh = newWarehouse("WH-TR-CG-T", "AE", "ACTIVE");
        long sku = newSku("SKU-TR-CG");
        long otherSku = newSku("SKU-TR-CG-OTHER");
        newBatch(sku, fromWh, "AE", "B:SRC-CG", 10, "12.5000", LocalDateTime.of(2026, 5, 8, 9, 0));

        String body = "{\"fromWarehouseId\":" + fromWh + ",\"toWarehouseId\":" + toWh
                + ",\"siteCode\":\"AE\",\"lines\":[{\"skuId\":" + sku + ",\"batchNo\":\"B:SRC-CG\","
                + "\"qty\":3,\"unitCost\":\"999.0000\",\"currency\":\"USD\",\"fxRate\":9.5,"
                + "\"batchId\":99999,\"qtyRemaining\":999}],\"requestId\":\"" + rid() + "\","
                + "\"transferNo\":\"TR:HACKED\",\"status\":\"IN_TRANSIT\"}";
        TransferCreateInput forged;
        try
        {
            forged = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(body, TransferCreateInput.class);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        assertEquals(3, forged.lines().get(0).qty());

        TransferResult result = service.create(forged);
        assertTrue(result.transferNo().matches("TR:AE:[0-9A-F]{8}"), "单号服务端生成，忽略客户端 TR:HACKED");
        assertEquals("DRAFT", jdbc.queryForObject("select status from inv_transfer where id=?",
                String.class, result.transferId()), "状态固定 DRAFT，忽略客户端 IN_TRANSIT");
        Map<String, Object> item = jdbc.queryForMap("select sku_id,batch_no,qty_shipped "
                + "from inv_transfer_item where transfer_id=?", result.transferId());
        assertEquals(sku, ((Number) item.get("sku_id")).longValue(), "SKU 来自服务端校验");
        assertEquals("B:SRC-CG", item.get("batch_no"));
        assertEquals(3, ((Number) item.get("qty_shipped")).intValue(), "计划量只认 qty");
        assertEquals(0, new BigDecimal("12.5000").compareTo(jdbc.queryForObject(
                "select unit_cost from inv_batch where batch_no='B:SRC-CG'", BigDecimal.class)),
                "批次成本不被客户端 999.0000 影响");

        // 客户端自报 SKU 与批次真实 SKU 不符 → 400（SKU 是选择键而非权威值）。
        IllegalArgumentException forgedSku = assertThrows(IllegalArgumentException.class,
                () -> service.create(new TransferCreateInput(fromWh, toWh, null,
                        List.of(new TransferCreateLine(otherSku, "B:SRC-CG", 1)), rid())));
        assertTrue(forgedSku.getMessage().contains("SKU"), forgedSku.getMessage());
    }

    @Test
    void resultOfHitsCreateRecordRequiresSiteAndIgnoresPending()
    {
        login(1L);
        long fromAe = newWarehouse("WH-TR-CP-A", "AE", "ACTIVE");
        long toAe = newWarehouse("WH-TR-CP-B", "AE", "ACTIVE");
        long fromSa = newWarehouse("WH-TR-CP-C", "SA", "ACTIVE");
        long toSa = newWarehouse("WH-TR-CP-D", "SA", "ACTIVE");
        long sku = newSku("SKU-TR-CP");
        newBatch(sku, fromAe, "AE", "B:SRC-CP-AE", 10, "10.0000", LocalDateTime.of(2026, 5, 9, 9, 0));
        newBatch(sku, fromSa, "SA", "B:SRC-CP-SA", 10, "10.0000", LocalDateTime.of(2026, 5, 9, 9, 0));

        String keyAe = rid();
        TransferResult created = service.create(new TransferCreateInput(fromAe, toAe, null,
                List.of(new TransferCreateLine(sku, "B:SRC-CP-AE", 4)), keyAe));

        // ① 命中 CREATE 记录 → DONE 缓存原样返回（replayed=true，action=CREATE）。
        TransferResult hit = service.resultOf(keyAe);
        assertNotNull(hit);
        assertTrue(hit.replayed());
        assertEquals("CREATE", hit.action());
        assertEquals(created.transferId(), hit.transferId());
        assertEquals(created.transferNo(), hit.transferNo());
        assertTrue(hit.childBatchIds().isEmpty());

        // ② PENDING / 非法键 / 不存在 → null。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_transfer_create_request"
                        + "(request_id,transfer_id,content_fingerprint,status) values (?,0,?, 'PENDING')",
                pendingKey, TransferRules.createFingerprint(fromAe, toAe,
                        List.of(new TransferCreateLine(sku, "B:SRC-CP-AE", 1))));
        assertNull(service.resultOf(pendingKey));
        assertNull(service.resultOf("bad key!"));
        assertNull(service.resultOf(rid()));

        // ③ 站点授权：仅 AE 用户查 AE 建单结果 → 200 语义；查 SA 建单结果 → 403。
        String keySa = rid();
        service.create(new TransferCreateInput(fromSa, toSa, null,
                List.of(new TransferCreateLine(sku, "B:SRC-CP-SA", 2)), keySa));
        login(2L);
        assertEquals(created.transferId(), service.resultOf(keyAe).transferId());
        assertThrows(AccessDeniedException.class, () -> service.resultOf(keySa));
        assertThrows(AccessDeniedException.class, () -> service.create(new TransferCreateInput(fromSa, toSa,
                null, List.of(new TransferCreateLine(sku, "B:SRC-CP-SA", 1)), rid())));
    }

    // ------------------------------------------------------------------ helpers

    private long newSku(String code)
    {
        jdbc.update("insert into md_sku(sku_code,status) values (?, 'ACTIVE')", code);
        return jdbc.queryForObject("select id from md_sku where sku_code=?", Long.class, code);
    }

    private long newWarehouse(String code, String site, String status)
    {
        jdbc.update("insert into md_warehouse(warehouse_code,site_code,warehouse_type,status) "
                + "values (?,?,'LOCAL',?)", code, site, status);
        return jdbc.queryForObject("select id from md_warehouse where warehouse_code=? and site_code=?",
                Long.class, code, site);
    }

    private long newBatch(long skuId, long warehouseId, String site, String batchNo, int qty, String unitCost,
                          LocalDateTime receivedAt)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                        + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                        + "split_reason,transfer_no,status) "
                        + "values (?,?,NULL,?,?,?,?,?,?,'CNY',1,'PURCHASE',NULL,0,NULL,NULL,'OPEN')",
                batchNo, skuId, warehouseId, site, Timestamp.valueOf(receivedAt), qty, qty, unitCost);
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(long skuId, long warehouseId, String site, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable) values (?,?,?,?,0,0,0)",
                skuId, warehouseId, site, available);
    }

    private long newTransfer(String transferNo, long fromWarehouseId, long toWarehouseId)
    {
        jdbc.update("insert into inv_transfer(transfer_no,from_warehouse_id,to_warehouse_id,status) "
                + "values (?,?,?,'DRAFT')", transferNo, fromWarehouseId, toWarehouseId);
        return jdbc.queryForObject("select id from inv_transfer where transfer_no=?", Long.class, transferNo);
    }

    private long newTransferItem(long transferId, long skuId, String batchNo, int qtyShipped)
    {
        jdbc.update("insert into inv_transfer_item(transfer_id,sku_id,batch_no,qty_shipped,qty_received,diff_qty) "
                + "values (?,?,?,?,0,0)", transferId, skuId, batchNo, qtyShipped);
        return jdbc.queryForObject("select max(id) from inv_transfer_item where transfer_id=?",
                Long.class, transferId);
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
     * H2 宽松建表：列名与 {@code yx-erp/sql/noon_m4_inventory.sql} / {@code noon_m4_transfer.sql}
     * 及 schema v0.5 对齐；JSON 用文本列、不含 CHECK（CHECK 与真库并发由 {@link MySqlTransferIT} 验证）。
     */
    private void createTables()
    {
        jdbc.execute("create table md_sku (id bigint auto_increment primary key, sku_code varchar(64) not null, "
                + "name_en varchar(512), status varchar(16) not null default 'ACTIVE', "
                + "constraint uk_sku_code unique(sku_code))");
        jdbc.execute("create table md_warehouse (id bigint auto_increment primary key, "
                + "warehouse_code varchar(64) not null, site_code varchar(16) not null, "
                + "warehouse_name varchar(256), warehouse_type varchar(24) not null default 'LOCAL', "
                + "fulfillment_mode varchar(24) not null default 'FBN', is_fbn tinyint default 0, "
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
        jdbc.execute("create table inv_transfer (id bigint auto_increment primary key, "
                + "transfer_no varchar(64) not null, from_warehouse_id bigint not null, "
                + "to_warehouse_id bigint not null, status varchar(24) not null default 'DRAFT', "
                + "shipped_at timestamp(3), received_at timestamp(3), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_transfer_no unique(transfer_no))");
        jdbc.execute("create table inv_transfer_item (id bigint auto_increment primary key, "
                + "transfer_id bigint not null, sku_id bigint not null, batch_no varchar(64) not null, "
                + "qty_shipped int not null, qty_received int default 0, diff_qty int default 0, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m4_transfer_request (request_id varchar(64) not null primary key, "
                + "transfer_id bigint not null, operator_id bigint, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', result_json varchar(10000), "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m4_transfer_create_request (request_id varchar(64) not null primary key, "
                + "transfer_id bigint not null, operator_id bigint, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', result_json varchar(10000), "
                + "created_at timestamp(3) not null default current_timestamp(3))");
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
        TransferService transferService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new TransferService(jdbcTemplate, guard, audit);
        }
    }
}
