package com.yx.noon.inventory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * M4 库存第一轮 H2 单测（真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper + 真 SiteAuthGuard）。
 *
 * <p>覆盖：站点授权经仓库归属（仓库不存在/停用 400，无站点/跨站 403，请求体 siteCode 不一致 400）、
 * 防伪造（客户端夹带 unitCost/skuId/batchNo 被忽略，成本/SKU/批次号服务端权威带出）、
 * 守恒（超累计收货量/已入库量 400、无收货 400、行不属订单 400、重复行 400）、
 * 幂等（DONE 重放不重复写、内容冲突 400、PENDING 400、失败回滚同键可重试、并发同键仅一次）、
 * 台账二次入库累加与站点冲突、批次字段对齐 FIFO、resultOf 两态、list/batches/ledger 分页与跨站。
 *
 * <p>H2 建表为宽松 DDL（不含 CHECK/JSON），真实 DDL 的 CHECK/并发由 {@link MySqlInventoryIT} 验证。
 */
class InventoryServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private InboundService service;

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

        createInventoryTables();
        service = context.getBean(InboundService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ------------------------------------------------------- 写入闭环与权威带出

    @Test
    void confirmCreatesInboundBatchLedgerMoveAndAudit()
    {
        login(1L);
        long wh = newWarehouse("WH-AE-1", "AE", "ACTIVE");
        long sku1 = newSku("SKU-M4-1");
        long sku2 = newSku("SKU-M4-2");
        long order = newOrder("PO-M4-1", "AE", "RECEIVED");
        long item1 = newItem(order, sku1, 10, 10, "10.0000");
        long item2 = newItem(order, sku2, 5, 5, "50.0000");
        newReceipt(order, item1, 10, "10.0000");
        newReceipt(order, item2, 5, "50.0000");

        // 行顺序故意打乱：服务端按 purOrderItemId 升序处理，批次与行一一对应。
        InboundResult result = service.confirm(new InboundInput(order, wh, "AE",
                List.of(new InboundLineInput(item2, 5), new InboundLineInput(item1, 4)), rid()));

        assertFalse(result.replayed(), "首次确认 replayed=false");
        assertTrue(result.inboundId() != null && result.inboundId() > 0);
        assertTrue(result.inboundNo().startsWith("IN:"), result.inboundNo());
        assertEquals(2, result.batchIds().size());
        assertEquals(2, distinctBatchIds());

        assertEquals("CONFIRMED", jdbc.queryForObject("select status from inv_inbound", String.class));
        assertEquals(wh, jdbc.queryForObject("select warehouse_id from inv_inbound", Long.class));
        assertEquals(order, jdbc.queryForObject("select pur_order_id from inv_inbound", Long.class));
        assertEquals(1L, jdbc.queryForObject("select operator_id from inv_inbound", Long.class));

        // 入库行：qty=qty_qualified，成本来自收货记录（10 / 50），批次号服务端生成。
        List<java.util.Map<String, Object>> items = jdbc.queryForList(
                "select pur_order_item_id,sku_id,batch_no,qty,qty_qualified,unit_cost from inv_inbound_item "
                        + "order by pur_order_item_id");
        assertEquals(2, items.size());
        assertEquals(0, new java.math.BigDecimal("10.0000").compareTo((java.math.BigDecimal) items.get(0).get("unit_cost")));
        assertEquals(0, new java.math.BigDecimal("50.0000").compareTo((java.math.BigDecimal) items.get(1).get("unit_cost")));
        assertEquals(4, ((Number) items.get(0).get("qty")).intValue());
        assertEquals(4, ((Number) items.get(0).get("qty_qualified")).intValue());
        for (java.util.Map<String, Object> row : items)
            assertTrue(String.valueOf(row.get("batch_no")).startsWith("B:IN:"), String.valueOf(row.get("batch_no")));

        // FIFO 批次字段对齐 FifoConsumptionService.openBatches()（OPEN / received_at / site / warehouse）。
        List<java.util.Map<String, Object>> batches = jdbc.queryForList("select batch_no,sku_id,pur_order_item_id,"
                + "warehouse_id,site_code,received_at,qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,"
                + "parent_batch_id,split_seq,status from inv_batch order by id");
        assertEquals(2, batches.size());
        long sumRemaining = 0;
        for (java.util.Map<String, Object> batch : batches)
        {
            assertEquals("OPEN", batch.get("status"));
            assertEquals("PURCHASE", batch.get("source_type"));
            assertEquals(null, batch.get("parent_batch_id"));
            assertEquals(0, ((Number) batch.get("split_seq")).intValue());
            assertEquals("CNY", batch.get("currency"));
            assertEquals(0, new java.math.BigDecimal("1").compareTo((java.math.BigDecimal) batch.get("fx_rate")));
            assertEquals("AE", batch.get("site_code"));
            assertEquals(wh, ((Number) batch.get("warehouse_id")).longValue());
            assertNotNull(batch.get("received_at"));
            assertEquals(((Number) batch.get("qty_in")).intValue(),
                    ((Number) batch.get("qty_remaining")).intValue(), "首次入库 qty_in = qty_remaining");
            sumRemaining += ((Number) batch.get("qty_remaining")).longValue();
        }
        assertEquals(9, sumRemaining, "4 + 5 全部合格量进入可用批次");

        // 四态台账：首次插入 available=合格量，其余三态不动。
        assertEquals(2, count("inv_stock_ledger"));
        assertEquals(4, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?", Integer.class, sku1));
        assertEquals(5, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?", Integer.class, sku2));
        assertEquals(0, jdbc.queryForObject("select sum(qty_in_transit+qty_reserved+qty_unsellable) "
                + "from inv_stock_ledger", Integer.class), "本轮其余三态不动");
        assertEquals("AE", jdbc.queryForObject("select site_code from inv_stock_ledger where sku_id=?", String.class, sku1));

        // 库存流水：每行一条 INBOUND，带编号、引用与操作人。
        List<java.util.Map<String, Object>> moves = jdbc.queryForList(
                "select move_no,sku_id,batch_id,change_type,qty_delta,ref_doc_type,ref_doc_no,operator_id "
                        + "from inv_stock_move order by id");
        assertEquals(2, moves.size());
        for (java.util.Map<String, Object> move : moves)
        {
            assertTrue(String.valueOf(move.get("move_no")).startsWith("IM:"), String.valueOf(move.get("move_no")));
            assertEquals("INBOUND", move.get("change_type"));
            assertEquals("INBOUND", move.get("ref_doc_type"));
            assertEquals(result.inboundNo(), move.get("ref_doc_no"));
            assertEquals(1L, ((Number) move.get("operator_id")).longValue());
            assertNotNull(move.get("batch_id"));
            assertTrue(((Number) move.get("qty_delta")).intValue() > 0);
        }
        assertEquals(9, jdbc.queryForObject("select sum(qty_delta) from inv_stock_move", Integer.class));

        // 审计同事务落库。
        assertEquals(1, count("noon_m4_inventory_audit"));
        java.util.Map<String, Object> audit = jdbc.queryForMap("select biz_type,biz_id,action,detail,operator_id "
                + "from noon_m4_inventory_audit");
        assertEquals("INBOUND", audit.get("biz_type"));
        assertEquals(String.valueOf(result.inboundId()), audit.get("biz_id"));
        assertEquals("CONFIRM", audit.get("action"));
        assertTrue(String.valueOf(audit.get("detail")).contains(result.inboundNo()), String.valueOf(audit.get("detail")));
        assertEquals(1L, ((Number) audit.get("operator_id")).longValue());

        // 采购单状态闸门：DRAFT 不可入库。
        long draft = newOrder("PO-M4-DRAFT", "AE", "DRAFT");
        long draftItem = newItem(draft, sku1, 3, 0, "10.0000");
        IllegalArgumentException notReceived = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(draft, wh, null,
                        List.of(new InboundLineInput(draftItem, 1)), rid())));
        assertTrue(notReceived.getMessage().contains("不可入库"), notReceived.getMessage());
    }

    @Test
    void clientSuppliedCostSkuAndBatchNoAreIgnored()
    {
        login(1L);
        long wh = newWarehouse("WH-AE-2", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-3");
        long order = newOrder("PO-M4-2", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 10, "12.3400");
        newReceipt(order, item, 10, "12.3400");

        // 模拟前端夹带 unitCost/skuId/batchNo/qty：record 无这些字段，反序列化阶段即丢弃。
        String body = "{\"purOrderId\":" + order + ",\"warehouseId\":" + wh + ",\"siteCode\":\"AE\","
                + "\"lines\":[{\"purOrderItemId\":" + item + ",\"qtyQualified\":3,"
                + "\"unitCost\":0.01,\"skuId\":9999,\"batchNo\":\"HACK\",\"qty\":100}],"
                + "\"requestId\":\"" + rid() + "\"}";
        InboundInput parsed;
        try
        {
            parsed = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(body, InboundInput.class);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
        assertEquals(3, parsed.lines().get(0).qtyQualified());

        InboundResult result = service.confirm(parsed);
        assertEquals(sku, jdbc.queryForObject("select sku_id from inv_inbound_item", Long.class),
                "SKU 必须来自 pur_order_item，忽略客户端 9999");
        assertEquals(0, new java.math.BigDecimal("12.3400").compareTo(
                jdbc.queryForObject("select unit_cost from inv_batch", java.math.BigDecimal.class)),
                "成本必须来自 M3 收货记录，忽略客户端 0.01");
        assertEquals(3, jdbc.queryForObject("select qty_in from inv_batch", Integer.class),
                "数量只认 qtyQualified，忽略客户端 qty=100");
        String batchNo = jdbc.queryForObject("select batch_no from inv_batch", String.class);
        assertTrue(batchNo.startsWith("B:IN:"), "批次号必须服务端生成: " + batchNo);
        assertEquals(batchNo, jdbc.queryForObject("select batch_no from inv_inbound_item", String.class));
        assertEquals(1, result.batchIds().size());
    }

    // ------------------------------------------------------- 授权（经仓库归属）

    @Test
    void authorizationAlwaysFollowsWarehouseOwnership()
    {
        long whAe = newWarehouse("WH-AUTH-AE", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-AUTH");
        long orderAe = newOrder("PO-AUTH-AE", "AE", "RECEIVED");
        long itemAe = newItem(orderAe, sku, 10, 10, "10.0000");
        newReceipt(orderAe, itemAe, 10, "10.0000");

        // 无任何站点授权 → 403，且不写任何数据。
        login(3L);
        InboundInput aeInput = new InboundInput(orderAe, whAe, "AE",
                List.of(new InboundLineInput(itemAe, 2)), rid());
        assertThrows(AccessDeniedException.class, () -> service.confirm(aeInput), "无站点授权必须 403");
        assertEquals(0, count("inv_inbound"));
        assertEquals(0, count("inv_batch"));

        // 仅 AE 授权用户可入库 AE 仓库（服务端按仓库站点授权，不看请求体）。
        login(2L);
        assertFalse(service.confirm(aeInput).replayed());
        assertEquals(1, count("inv_inbound"));

        // 跨站：SA 仓库 / SA 采购单，AE 用户 → 403（授权以仓库站点为准）。
        long whSa = newWarehouse("WH-AUTH-SA", "SA", "ACTIVE");
        long orderSa = newOrder("PO-AUTH-SA", "SA", "RECEIVED");
        long itemSa = newItem(orderSa, sku, 5, 5, "10.0000");
        newReceipt(orderSa, itemSa, 5, "10.0000");
        InboundInput saInput = new InboundInput(orderSa, whSa, "SA",
                List.of(new InboundLineInput(itemSa, 2)), rid());
        assertThrows(AccessDeniedException.class, () -> service.confirm(saInput), "跨站仓库必须 403");
        assertEquals(1, count("inv_inbound"), "越权不得写入库单");

        // 仓库不存在 → 400；仓库停用 → 400。
        login(1L);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(orderAe, 9999L, null,
                        List.of(new InboundLineInput(itemAe, 1)), rid())));
        assertTrue(missing.getMessage().contains("仓库不存在"), missing.getMessage());

        long whInactive = newWarehouse("WH-AUTH-OFF", "AE", "INACTIVE");
        IllegalArgumentException inactive = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(orderAe, whInactive, null,
                        List.of(new InboundLineInput(itemAe, 1)), rid())));
        assertTrue(inactive.getMessage().contains("已停用"), inactive.getMessage());

        // 请求体 siteCode 与仓库站点不一致 → 400（不参与授权，禁止自报站点绕过）。
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(orderAe, whAe, "SA",
                        List.of(new InboundLineInput(itemAe, 1)), rid())));
        assertTrue(mismatch.getMessage().contains("不一致"), mismatch.getMessage());

        // 采购单站点与仓库站点不一致（跨站伪造）→ 400。
        IllegalArgumentException crossOrder = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(orderSa, whAe, "AE",
                        List.of(new InboundLineInput(itemSa, 1)), rid())));
        assertTrue(crossOrder.getMessage().contains("禁止跨站入库"), crossOrder.getMessage());
        assertEquals(1, count("inv_inbound"), "所有被拒请求都不得写入");
    }

    // ------------------------------------------------------- 守恒

    @Test
    void conservationRejectsOverInboundBadLinesAndMissingReceipt()
    {
        login(1L);
        long wh = newWarehouse("WH-CONS", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-CONS");
        long order = newOrder("PO-CONS-1", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 6, "10.0000");
        newReceipt(order, item, 6, "10.0000");

        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 7)), rid())));
        assertTrue(over.getMessage().contains("可入库量"), over.getMessage());
        assertTrue(over.getMessage().contains("累计收货=6"), over.getMessage());
        assertEquals(0, count("inv_batch"), "超量失败不得生成批次");
        assertEquals(0, count("inv_stock_ledger"), "超量失败不得写台账");

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 0)), rid())));
        assertTrue(zero.getMessage().contains("大于 0"), zero.getMessage());

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 1), new InboundLineInput(item, 1)), rid())));
        assertTrue(duplicate.getMessage().contains("不能重复提交"), duplicate.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null, List.of(), rid())));
        assertTrue(empty.getMessage().contains("至少需要提交 1 行"), empty.getMessage());

        // 行不属于该采购单 → 400。
        long otherOrder = newOrder("PO-CONS-OTHER", "AE", "RECEIVED");
        long otherItem = newItem(otherOrder, sku, 3, 3, "10.0000");
        newReceipt(otherOrder, otherItem, 3, "10.0000");
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(otherItem, 1)), rid())));
        assertTrue(foreign.getMessage().contains("不属于该订单"), foreign.getMessage());

        // 无收货记录 → 400。
        long noReceiptOrder = newOrder("PO-CONS-NORCV", "AE", "RECEIVED");
        long noReceiptItem = newItem(noReceiptOrder, sku, 4, 0, "10.0000");
        IllegalArgumentException noReceipt = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(noReceiptOrder, wh, null,
                        List.of(new InboundLineInput(noReceiptItem, 1)), rid())));
        assertTrue(noReceipt.getMessage().contains("没有收货记录"), noReceipt.getMessage());

        // 累计已入库守恒：先入 6（全部收货量），再用另一 requestId 入 1 → 400 可入库=0。
        service.confirm(new InboundInput(order, wh, null, List.of(new InboundLineInput(item, 6)), rid()));
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 1)), rid())));
        assertTrue(again.getMessage().contains("可入库=0"), again.getMessage());
        assertEquals(1, count("inv_inbound"), "重复入库不得新增入库单");
        assertEquals(6, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class),
                "台账不得因重复入库虚增");
    }

    // --------------------------------------------- 契约 §9.1 PARTIAL_RECEIVED 分批入库

    @Test
    void partialReceivedOrderSupportsStagedInboundWithinRowConservation()
    {
        login(1L);
        long wh = newWarehouse("WH-PART", "AE", "ACTIVE");
        long sku1 = newSku("SKU-M4-PART-1");
        long sku2 = newSku("SKU-M4-PART-2");
        long order = newOrder("PO-PART-1", "AE", "PARTIAL_RECEIVED");
        long item1 = newItem(order, sku1, 60, 60, "10.0000");
        long item2 = newItem(order, sku2, 20, 0, "20.0000");
        newReceipt(order, item1, 60, "10.0000");

        // ① 首批到货：单仍 PARTIAL_RECEIVED（行2 未收货），只对行1 入 40 → 成功。
        String firstKey = rid();
        InboundResult first = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item1, 40)), firstKey));
        assertFalse(first.replayed(), "PARTIAL_RECEIVED 首批入库是首次确认");
        assertEquals(1, count("inv_batch"));
        assertEquals(40, jdbc.queryForObject("select qty_in from inv_batch", Integer.class));
        assertEquals(40, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?",
                Integer.class, sku1));
        assertEquals(1, count("inv_stock_move"));
        assertEquals(40, jdbc.queryForObject("select qty_qualified from inv_inbound_item", Integer.class));
        assertEquals("PARTIAL_RECEIVED", jdbc.queryForObject("select status from pur_order where id=?",
                String.class, order), "入库不改采购单收货状态机");

        // ② 同单第二批补齐：再入 20（累计 40+20=60，未超累计收货 60）→ 成功。
        InboundResult second = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item1, 20)), rid()));
        assertFalse(second.replayed());
        assertEquals(2, count("inv_batch"));
        assertEquals(60, jdbc.queryForObject("select sum(qty_in) from inv_batch", Integer.class));
        assertEquals(60, jdbc.queryForObject("select sum(qty_qualified) from inv_inbound_item", Integer.class));
        assertEquals(60, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?",
                Integer.class, sku1), "台账 40+20 累加");
        assertEquals(2, count("inv_stock_move"));

        // ③ 第三批超累计收货（60+1=61>60）→ 400，且不新增批次/台账/流水/入库单。
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item1, 1)), rid())));
        assertTrue(over.getMessage().contains("可入库=0"), over.getMessage());
        assertEquals(2, count("inv_batch"), "超累计失败不得生成批次");
        assertEquals(2, count("inv_stock_move"), "超累计失败不得写流水");
        assertEquals(60, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?",
                Integer.class, sku1), "超累计失败不得累加台账");
        assertEquals(2, count("inv_inbound"));

        // ④ 未收货的行2 提交任何数量 → 400「没有收货记录」。
        IllegalArgumentException noReceipt = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item2, 1)), rid())));
        assertTrue(noReceipt.getMessage().contains("没有收货记录"), noReceipt.getMessage());
        assertEquals(2, count("inv_batch"));

        // ⑤ DONE 重放不受状态闸门影响：仍 PARTIAL_RECEIVED，同 requestId 同内容 → replayed=true 且零写入。
        InboundResult replay = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item1, 40)), firstKey));
        assertTrue(replay.replayed(), "PARTIAL_RECEIVED 下同键重放仍返回 replayed=true");
        assertEquals(first.inboundId(), replay.inboundId());
        assertEquals(2, count("inv_batch"), "重放不得新增批次");
        assertEquals(60, jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=?",
                Integer.class, sku1), "重放不得累加台账");

        // ⑥ CONFIRMED（尚无收货）仍被状态闸门拒绝，文案同步 v1.1。
        long confirmed = newOrder("PO-PART-CONF", "AE", "CONFIRMED");
        long confirmedItem = newItem(confirmed, sku1, 5, 0, "10.0000");
        IllegalArgumentException gate = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(confirmed, wh, null,
                        List.of(new InboundLineInput(confirmedItem, 1)), rid())));
        assertTrue(gate.getMessage().contains("仅 PARTIAL_RECEIVED/RECEIVED/CLOSED 可入库"), gate.getMessage());
    }

    // ------------------------------------------------------- 幂等

    @Test
    void idempotentReplayContentConflictPendingAndNewRequest()
    {
        login(1L);
        long wh = newWarehouse("WH-IDEM", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-IDEM");
        long order = newOrder("PO-IDEM-1", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 10, "10.0000");
        newReceipt(order, item, 10, "10.0000");
        String requestId = rid();

        InboundResult first = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 4)), requestId));
        assertFalse(first.replayed());
        int batches = count("inv_batch");
        int moves = count("inv_stock_move");
        int audits = count("noon_m4_inventory_audit");
        int available = jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class);

        // 1) 同键同内容 → replayed=true，返回首次结果，不重复生成批次/台账/流水/审计。
        InboundResult replay = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 4)), requestId));
        assertTrue(replay.replayed(), "同 requestId 重试必须 replayed");
        assertEquals(first.inboundId(), replay.inboundId());
        assertEquals(first.inboundNo(), replay.inboundNo());
        assertEquals(first.batchIds(), replay.batchIds());
        assertEquals(batches, count("inv_batch"), "重放不得新增批次");
        assertEquals(moves, count("inv_stock_move"), "重放不得新增流水");
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不得写审计");
        assertEquals(available, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class),
                "重放不得累加台账");
        assertEquals("DONE", requestStatus(requestId));
        assertTrue(jdbc.queryForObject("select result_json from noon_m4_inbound_request where request_id=?",
                String.class, requestId).contains("\"replayed\":false"), "缓存的是首次结果");

        // 2) 同键不同数量 → 400 内容不一致，且不改动已有库存。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 5)), requestId)));
        assertEquals("requestId 对应的入库内容与原请求不一致", changed.getMessage());
        assertEquals(available, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));

        // 3) 同键不同采购单 → 400。
        long otherOrder = newOrder("PO-IDEM-2", "AE", "RECEIVED");
        long otherItem = newItem(otherOrder, sku, 3, 3, "10.0000");
        newReceipt(otherOrder, otherItem, 3, "10.0000");
        IllegalArgumentException crossOrder = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(otherOrder, wh, null,
                        List.of(new InboundLineInput(otherItem, 3)), requestId)));
        assertTrue(crossOrder.getMessage().contains("其他采购单"), crossOrder.getMessage());

        // 4) PENDING 并发占位 → 400「处理中」。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_inbound_request(request_id,pur_order_id,content_fingerprint,status) "
                        + "values (?,?,?,'PENDING')", pendingKey, order,
                InventoryRules.contentFingerprint(order, wh, List.of(new InboundLineInput(item, 2))));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 2)), pendingKey)));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());

        // 5) 不同 requestId 的第二次真实入库：正常生成新批次并累加台账。
        InboundResult second = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 2)), rid()));
        assertFalse(second.replayed());
        assertEquals(batches + 1, count("inv_batch"));
        assertEquals(available + 2, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class),
                "台账二次入库累加");
        assertEquals(1, count("inv_stock_ledger"), "同一 sku+仓库只有一行台账");
    }

    @Test
    void failedConfirmRollsBackEverythingAndSameRequestIdCanRetry()
    {
        login(1L);
        long wh = newWarehouse("WH-RETRY", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-RETRY");
        long order = newOrder("PO-RETRY-1", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 4, "10.0000");
        newReceipt(order, item, 4, "10.0000");
        String requestId = rid();

        IllegalArgumentException failed = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 5)), requestId)));
        assertTrue(failed.getMessage().contains("可入库量"), failed.getMessage());

        assertEquals(0, requestRows(requestId), "失败事务的 PENDING 占位必须回滚");
        assertEquals(0, count("inv_inbound"));
        assertEquals(0, count("inv_batch"));
        assertEquals(0, count("inv_stock_ledger"));
        assertEquals(0, count("inv_stock_move"));
        assertEquals(0, count("noon_m4_inventory_audit"));

        InboundResult retried = service.confirm(new InboundInput(order, wh, null,
                List.of(new InboundLineInput(item, 4)), requestId));
        assertFalse(retried.replayed(), "重试是首次成功");
        assertEquals(4, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class));
        assertEquals("DONE", requestStatus(requestId));
    }

    @Test
    void concurrentSameRequestIdBooksOnce() throws Exception
    {
        login(1L);
        long wh = newWarehouse("WH-CONC", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-CONC");
        long order = newOrder("PO-CONC-1", "AE", "RECEIVED");
        long item = newItem(order, sku, 10, 10, "10.0000");
        newReceipt(order, item, 10, "10.0000");
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
                InboundResult result = service.confirm(new InboundInput(order, wh, null,
                        List.of(new InboundLineInput(item, 3)), requestId));
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

        assertEquals(1, results.stream().filter("FRESH"::equals).count(),
                "同一 requestId 并发只能一次真实入账: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(1, count("inv_inbound"), "并发同键只允许一张入库单");
        assertEquals(1, count("inv_batch"), "并发同键只允许一个批次");
        assertEquals(1, count("inv_stock_move"), "并发同键只允许一条流水");
        assertEquals(1, count("inv_stock_ledger"), "并发同键只允许一行台账");
        assertEquals(3, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class),
                "台账只累加一次");
        assertEquals(1, count("noon_m4_inventory_audit"));
        assertEquals("DONE", requestStatus(requestId));
    }

    // ------------------------------------------------------- 台账站点冲突

    @Test
    void ledgerAccumulatesAndRejectsCrossSiteReuse()
    {
        login(1L);
        long wh = newWarehouse("WH-LEDGER", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-LEDGER");
        long order1 = newOrder("PO-LEDGER-1", "AE", "RECEIVED");
        long item1 = newItem(order1, sku, 5, 5, "10.0000");
        newReceipt(order1, item1, 5, "10.0000");
        long order2 = newOrder("PO-LEDGER-2", "AE", "RECEIVED");
        long item2 = newItem(order2, sku, 7, 7, "12.0000");
        newReceipt(order2, item2, 7, "12.0000");

        service.confirm(new InboundInput(order1, wh, null, List.of(new InboundLineInput(item1, 5)), rid()));
        service.confirm(new InboundInput(order2, wh, null, List.of(new InboundLineInput(item2, 7)), rid()));

        assertEquals(1, count("inv_stock_ledger"), "同一 sku+仓库只有一行");
        assertEquals(12, jdbc.queryForObject("select qty_available from inv_stock_ledger", Integer.class), "5+7 累加");
        assertEquals(2, count("inv_batch"), "两个来源行各生成一个批次");

        // 跨站复用同 (sku, 仓库)：台账站点已为 SA，但仓库属于 AE → 400，不写任何库存。
        long whConflict = newWarehouse("WH-LEDGER-C", "AE", "ACTIVE");
        long order3 = newOrder("PO-LEDGER-3", "AE", "RECEIVED");
        long item3 = newItem(order3, sku, 3, 3, "10.0000");
        newReceipt(order3, item3, 3, "10.0000");
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available) values (?,?,'SA',0)",
                sku, whConflict);
        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> service.confirm(new InboundInput(order3, whConflict, null,
                        List.of(new InboundLineInput(item3, 3)), rid())));
        assertTrue(conflict.getMessage().contains("台账站点"), conflict.getMessage());
        assertEquals(0, jdbc.queryForObject("select qty_available from inv_stock_ledger "
                + "where warehouse_id=?", Integer.class, whConflict), "冲突失败不得累加");
        assertEquals(2, count("inv_inbound"), "冲突失败不得新增入库单");
    }

    // ------------------------------------------------------- resultOf

    @Test
    void resultOfIsReadOnlySiteAuthorizedAndTwoState()
    {
        login(1L);
        long whAe = newWarehouse("WH-RES-AE", "AE", "ACTIVE");
        long sku = newSku("SKU-M4-RES");
        long orderAe = newOrder("PO-RES-AE", "AE", "RECEIVED");
        long itemAe = newItem(orderAe, sku, 10, 10, "10.0000");
        newReceipt(orderAe, itemAe, 10, "10.0000");
        String requestId = rid();
        InboundResult first = service.confirm(new InboundInput(orderAe, whAe, null,
                List.of(new InboundLineInput(itemAe, 3)), requestId));
        int batches = count("inv_batch");
        int audits = count("noon_m4_inventory_audit");

        InboundResult queried = service.resultOf(requestId);
        assertNotNull(queried);
        assertTrue(queried.replayed(), "结果查询标记 replayed=true");
        assertEquals(first.inboundId(), queried.inboundId());
        assertEquals(first.batchIds(), queried.batchIds());
        assertEquals(batches, count("inv_batch"), "resultOf 只读");
        assertEquals(audits, count("noon_m4_inventory_audit"), "resultOf 不得写审计");

        assertNull(service.resultOf("r-" + UUID.randomUUID()), "不存在返回 null");
        assertNull(service.resultOf(null), "null 键返回 null");
        assertNull(service.resultOf(""), "空键返回 null");
        assertNull(service.resultOf("short"), "非法格式返回 null 而非 400");

        // PENDING（已回滚/处理中的行）不返回结果。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_inbound_request(request_id,pur_order_id,content_fingerprint,status) "
                        + "values (?,?,?,'PENDING')", pendingKey, orderAe,
                InventoryRules.contentFingerprint(orderAe, whAe, List.of(new InboundLineInput(itemAe, 1))));
        assertNull(service.resultOf(pendingKey), "PENDING 无结果可展示");

        // 跨站：SA 单的键，AE 用户查询 403。
        long whSa = newWarehouse("WH-RES-SA", "SA", "ACTIVE");
        long orderSa = newOrder("PO-RES-SA", "SA", "RECEIVED");
        long itemSa = newItem(orderSa, sku, 5, 5, "10.0000");
        newReceipt(orderSa, itemSa, 5, "10.0000");
        String saRequest = rid();
        service.confirm(new InboundInput(orderSa, whSa, null, List.of(new InboundLineInput(itemSa, 2)), saRequest));

        login(2L); // 仅 AE
        assertThrows(AccessDeniedException.class, () -> service.resultOf(saRequest), "跨站查结果 403");
        assertNotNull(service.resultOf(requestId), "有 AE 权限可查 AE 单结果");

        login(3L); // 无站点
        assertThrows(AccessDeniedException.class, () -> service.resultOf(requestId), "无站点查结果 403");
    }

    // ------------------------------------------------------- 列表 / 详情 / 分页

    @Test
    void listGetBatchesAndLedgerRespectSiteAuthorizationAndPaging()
    {
        login(1L);
        long whAe = newWarehouse("WH-LIST-AE", "AE", "ACTIVE");
        long whSa = newWarehouse("WH-LIST-SA", "SA", "ACTIVE");
        long skuA = newSku("SKU-M4-LA");
        long skuB = newSku("SKU-M4-LB");

        long aeOrder1 = newOrder("PO-LIST-AE1", "AE", "RECEIVED");
        long aeItem1 = newItem(aeOrder1, skuA, 5, 5, "10.0000");
        newReceipt(aeOrder1, aeItem1, 5, "10.0000");
        long aeOrder2 = newOrder("PO-LIST-AE2", "AE", "RECEIVED");
        long aeItem2 = newItem(aeOrder2, skuB, 3, 3, "20.0000");
        newReceipt(aeOrder2, aeItem2, 3, "20.0000");
        long saOrder = newOrder("PO-LIST-SA", "SA", "RECEIVED");
        long saItem = newItem(saOrder, skuA, 4, 4, "30.0000");
        newReceipt(saOrder, saItem, 4, "30.0000");

        InboundResult r1 = service.confirm(new InboundInput(aeOrder1, whAe, null,
                List.of(new InboundLineInput(aeItem1, 5)), rid()));
        InboundResult r2 = service.confirm(new InboundInput(aeOrder2, whAe, null,
                List.of(new InboundLineInput(aeItem2, 3)), rid()));
        InboundResult r3 = service.confirm(new InboundInput(saOrder, whSa, null,
                List.of(new InboundLineInput(saItem, 4)), rid()));

        // 详情：头 + 行，含站点与行数。
        InboundDetail detail = service.get(r1.inboundId());
        assertEquals("AE", detail.head().siteCode());
        assertEquals(orderNo(aeOrder1), orderNo(detail.head().purOrderId()), "详情头应回带采购单");
        assertEquals(1, detail.items().size());
        assertEquals(1, detail.head().itemCount().intValue(), "itemCount = 本入库单行数");
        InboundDetail empty = service.get(r3.inboundId());
        assertEquals(1, empty.items().size());

        // 超管：全部授权站点。
        PageResult<InboundRow> all = service.list(null, 0, 10);
        assertEquals(3, all.total());
        assertEquals(3, all.rows().size());
        assertEquals(1, service.list("SA", 0, 10).total());
        assertThrows(IllegalArgumentException.class, () -> service.list("XX", 0, 10), "非法站点 400");

        // 分页游标：不重复、不遗漏，total 不受 LIMIT 截断。
        List<Long> expected = all.rows().stream().map(InboundRow::id).toList();
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += 2)
        {
            PageResult<InboundRow> page = service.list(null, offset, 2);
            assertEquals(3, page.total(), "total 与分页无关");
            assertEquals(Math.min(2, expected.size() - offset), page.rows().size());
            seen.addAll(page.rows().stream().map(InboundRow::id).toList());
        }
        assertEquals(expected, seen, "翻页无重复无遗漏");

        // 超管：全部授权站点（AE 2 批次 + SA 1 批次；台账 AE 2 行 + SA 1 行，skuA 在两地各一行）。
        PageResult<BatchRow> allBatches = service.listBatches(null, 0, 10, null);
        assertEquals(3, allBatches.total());
        assertEquals(2, service.listBatches(null, 0, 10, skuA).total());
        assertEquals("OPEN", allBatches.rows().get(0).status());
        assertEquals("AE", allBatches.rows().get(0).siteCode());
        assertEquals(3, service.listLedger(null, 0, 10).total());

        // 普通用户（仅 AE）。
        login(2L);
        assertEquals(2, service.list(null, 0, 10).total(), "AE 用户只看 AE 入库单");
        assertEquals(0, service.list(null, 0, 10).rows().stream()
                .filter(row -> "SA".equals(row.siteCode())).count(), "AE 用户不得看到 SA");
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 10), "未授权站点 403");
        assertEquals(2, service.listBatches(null, 0, 10, null).total());
        assertEquals(2, service.listLedger(null, 0, 10).total());
        assertThrows(AccessDeniedException.class, () -> service.get(r3.inboundId()), "跨站详情 403");
        assertNotNull(service.get(r2.inboundId()), "同站详情可见");
        assertEquals(1, service.listBatches(null, 0, 10, skuA).total());

        // 无站点：零行（不能省略 WHERE 而读到全部），详情 403。
        login(3L);
        assertEquals(0, service.list(null, 0, 10).total(), "空授权零行");
        assertEquals(0, service.listBatches(null, 0, 10, null).total());
        assertEquals(0, service.listLedger(null, 0, 10).total());
        assertThrows(AccessDeniedException.class, () -> service.get(r1.inboundId()));
        assertThrows(AccessDeniedException.class, () -> service.list("AE", 0, 10));

        // 详情不存在的 id → 400。
        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.get(9999L));
        assertThrows(IllegalArgumentException.class, () -> service.get(null));
    }

    @Test
    void contentFingerprintMatchesContractCanonicalForm()
    {
        // 契约示例：purOrderId=12、warehouseId=7，行 3:60 + 5:40（按 purOrderItemId 升序）。
        assertEquals("4b1cf6e4ea72b34c0579ac1f0dfb59a7ed9c9d3b3a035d396a513898bc474f44",
                InventoryRules.contentFingerprint(12L, 7L,
                        List.of(new InboundLineInput(5L, 40), new InboundLineInput(3L, 60))),
                "规范化串 12|7|3:60,5:40 的 SHA-256（输入顺序无关）");
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

    private long newOrder(String orderNo, String site, String status)
    {
        jdbc.update("insert into pur_order(order_no,supplier_id,site_code,currency,total_amount,status,operator_id) "
                + "values (?,1,?,'CNY',0,?,1)", orderNo, site, status);
        return jdbc.queryForObject("select id from pur_order where order_no=?", Long.class, orderNo);
    }

    private long newItem(long orderId, long skuId, int qtyOrdered, int qtyReceived, String unitPrice)
    {
        jdbc.update("insert into pur_order_item(pur_order_id,sku_id,qty_ordered,qty_received,unit_price,"
                + "currency,fx_rate) values (?,?,?,?,?,'CNY',1)", orderId, skuId, qtyOrdered, qtyReceived, unitPrice);
        return jdbc.queryForObject("select id from pur_order_item where pur_order_id=? and sku_id=? "
                + "order by id desc limit 1", Long.class, orderId, skuId);
    }

    private void newReceipt(long orderId, long itemId, int qty, String unitCost)
    {
        jdbc.update("insert into noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
                + "values (?,?,?,?,current_timestamp(3),1)", orderId, itemId, qty, unitCost);
    }

    private String orderNo(long orderId)
    {
        return jdbc.queryForObject("select order_no from pur_order where id=?", String.class, orderId);
    }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int requestRows(String requestId)
    {
        return jdbc.queryForObject("select count(*) from noon_m4_inbound_request where request_id=?",
                Integer.class, requestId);
    }

    private String requestStatus(String requestId)
    {
        return jdbc.queryForObject("select status from noon_m4_inbound_request where request_id=?",
                String.class, requestId);
    }

    private int distinctBatchIds()
    {
        return jdbc.queryForObject("select count(distinct batch_no) from inv_inbound_item", Integer.class);
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
     * H2 宽松建表：列名与 {@code yx-erp/sql/noon_m4_inventory.sql} 及 schema v0.5 对齐；
     * JSON 用文本列、不含 CHECK（CHECK 与真库并发由 {@link MySqlInventoryIT} 验证）。
     */
    private void createInventoryTables()
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
        jdbc.execute("create table pur_order (id bigint auto_increment primary key, order_no varchar(64) not null, "
                + "supplier_id bigint not null, site_code varchar(16) not null, currency char(3) not null default 'CNY', "
                + "total_amount decimal(18,4) default 0, status varchar(24) not null default 'DRAFT', "
                + "source varchar(24) default 'MANUAL', source_ref_no varchar(64), operator_id bigint, "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_pur_no unique(order_no))");
        jdbc.execute("create table pur_order_item (id bigint auto_increment primary key, pur_order_id bigint not null, "
                + "sku_id bigint not null, qty_ordered int not null, qty_received int not null default 0, "
                + "unit_price decimal(18,4) not null, currency char(3) not null default 'CNY', "
                + "fx_rate decimal(18,8), created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m3_receipt (id bigint auto_increment primary key, order_id bigint not null, "
                + "order_item_id bigint not null, qty int not null, unit_cost_cny decimal(18,4) not null, "
                + "received_at timestamp(3) not null, operator_id bigint)");
        jdbc.execute("create table inv_inbound (id bigint auto_increment primary key, inbound_no varchar(64) not null, "
                + "pur_order_id bigint, warehouse_id bigint not null, status varchar(24) not null default 'DRAFT', "
                + "operator_id bigint, created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_inbound_no unique(inbound_no))");
        jdbc.execute("create table inv_inbound_item (id bigint auto_increment primary key, inbound_id bigint not null, "
                + "pur_order_item_id bigint, sku_id bigint not null, batch_no varchar(64) not null, qty int not null, "
                + "qty_qualified int not null, unit_cost decimal(18,4) not null, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
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
        // 与 yx-erp/sql/noon_m4_inventory.sql 同构：pur_order_id + content_fingerprint NOT NULL。
        jdbc.execute("create table noon_m4_inbound_request (request_id varchar(64) not null primary key, "
                + "pur_order_id bigint not null, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', result_json varchar(10000), "
                + "operator_id bigint, created_at timestamp(3) not null default current_timestamp(3))");
        // v2 §8 跨路径守恒：confirm 需扣减已直发量 D，故建 ASN 头/行（宽松 DDL，CHECK 由真库 IT 验证）。
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
        InboundService inboundService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new InboundService(jdbcTemplate, guard, audit);
        }
    }
}
