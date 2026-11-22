package com.yx.noon.stocktake;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * M4 第三轮第一批盘点 H2 单测（真 Spring 事务 + H2 MySQL 模式 + 真 MyBatis 站点 Mapper + 真 SiteAuthGuard）。
 *
 * <p>覆盖：建单只写 DRAFT 快照不动库存；apply 以<b>建单快照</b>为唯一差异基线（建单后台账变动 → 整单拒绝
 * {@link StaleStocktakeException}，任何写入之前）；盘亏 FIFO 逐批消耗 + 不足拒绝 + 总量守恒；盘盈 ADJUSTMENT
 * 批次 + 加权平均成本（无 OPEN 批次 / 成本为 0 → 整单拒绝 {@link ZeroCostGainException}、多币种拒绝）；
 * 动作级幂等（CREATE/APPLY 各自重放、同键跨动作 400、内容冲突 400、PENDING 400）；撤销授权/跨站 403
 * （授权先于重放）；STK 事件键跨批多行合法 + 同批重传被拦；重复 SKU 行裁决；审计同事务回滚。
 *
 * <p>H2 建表为宽松 DDL（无 CHECK/JSON 差异），真实 DDL 的 CHECK/并发/真实唯一键由 {@link MySqlStocktakeIT} 验证。
 */
class StocktakeServiceTest
{
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private StocktakeService service;

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
                + "values (2,'AE',1,current_timestamp(3)), (4,'AE',1,current_timestamp(3)), "
                + "(4,'SA',1,current_timestamp(3))");

        createTables();
        service = context.getBean(StocktakeService.class);
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    // ------------------------------------------------------- 建单：只写快照

    @Test
    void createWritesDraftSnapshotOnly()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-C1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-C1");
        newBatch(sku, wh, "AE", "B:ST-C1", 10, "10.0000", LocalDateTime.of(2026, 7, 1, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        String requestId = rid();

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", requestId,
                List.of(new StocktakeLineInput(sku, "B:ST-C1", 8, "CNT-C1"))));

        assertFalse(created.replayed(), "首次建单 replayed=false");
        assertTrue(created.stocktakeId() > 0);
        assertTrue(created.stocktakeNo().matches("ST:AE:[0-9A-F]{8}"), created.stocktakeNo());
        assertEquals("DRAFT", created.status());

        Map<String, Object> head = jdbc.queryForMap("select warehouse_id,period,status from inv_stocktake");
        assertEquals(wh, ((Number) head.get("warehouse_id")).longValue());
        assertEquals("2026-07", head.get("period"));
        assertEquals("DRAFT", head.get("status"));

        // 建单快照：qty_book = 台账可用量 10、qty_actual = 8、qty_diff 快照差 -2（唯一合法差异基线，apply 不得覆盖）。
        Map<String, Object> item = jdbc.queryForMap("select sku_id,batch_no,qty_book,qty_actual,qty_diff "
                + "from inv_stocktake_item");
        assertEquals(sku, ((Number) item.get("sku_id")).longValue());
        assertEquals("B:ST-C1", item.get("batch_no"));
        assertEquals(10, ((Number) item.get("qty_book")).intValue());
        assertEquals(8, ((Number) item.get("qty_actual")).intValue());
        assertEquals(-2, ((Number) item.get("qty_diff")).intValue());

        // 建单不改库存/不写流水/不写消耗。
        assertEquals(10, available(sku, wh));
        assertEquals(10, jdbc.queryForObject("select qty_remaining from inv_batch", Integer.class));
        assertEquals(0, count("inv_stock_move"));
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(1, count("inv_stocktake"));

        // 审计同事务写入。
        Map<String, Object> audit = jdbc.queryForMap("select biz_type,biz_id,action,detail,operator_id "
                + "from noon_m4_inventory_audit");
        assertEquals("STOCKTAKE", audit.get("biz_type"));
        assertEquals(String.valueOf(created.stocktakeId()), audit.get("biz_id"));
        assertEquals("CREATE", audit.get("action"));
        assertTrue(String.valueOf(audit.get("detail")).contains(created.stocktakeNo()),
                String.valueOf(audit.get("detail")));
        assertEquals(1L, ((Number) audit.get("operator_id")).longValue());

        // 幂等行 DONE 且回填真实 id；period 为空时取业务时刻当月。
        assertEquals("DONE", requestStatus(requestId));
        assertEquals(created.stocktakeId(), jdbc.queryForObject("select stocktake_id "
                + "from noon_m4_stocktake_request where request_id=?", Long.class, requestId));
        assertEquals("CREATE", jdbc.queryForObject("select action from noon_m4_stocktake_request "
                + "where request_id=?", String.class, requestId));

        StocktakeResult noPeriod = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, "", 10, "CNT-C1B"))));
        String period = jdbc.queryForObject("select period from inv_stocktake where id=?",
                String.class, noPeriod.stocktakeId());
        assertTrue(period != null && period.matches("\\d{4}-\\d{2}"), period);
        assertEquals(0, jdbc.queryForObject("select qty_diff from inv_stocktake_item where stocktake_id=?",
                Integer.class, noPeriod.stocktakeId()), "实盘=账面时快照差为 0");
        assertNull(jdbc.queryForObject("select batch_no from inv_stocktake_item where stocktake_id=?",
                String.class, noPeriod.stocktakeId()), "空 batchNo 落库为 NULL");
    }

    // ------------------------------------------------------- 盘亏：FIFO 逐批 + 守恒

    @Test
    void applyLossConsumesOpenBatchesFifoAndKeepsConservation()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-L1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-L1");
        long first = newBatch(sku, wh, "AE", "B:ST-L1-1", 5, "10.0000", LocalDateTime.of(2026, 7, 1, 9, 0));
        long second = newBatch(sku, wh, "AE", "B:ST-L1-2", 5, "20.0000", LocalDateTime.of(2026, 7, 1, 9, 10));
        seedLedger(sku, wh, "AE", 10);

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 2, "CNT-L1"))));
        long itemId = itemId(created.stocktakeId());
        StocktakeResult applied = service.apply(new StocktakeApplyInput(created.stocktakeId(), rid()));

        assertFalse(applied.replayed());
        assertEquals("APPLIED", applied.status());
        assertEquals("APPLIED", docStatus(created.stocktakeId()));

        // 快照基线：qty_book 仍为 10（建单后无出入库，快照=实时），qty_diff = 2 - 10 = -8。
        Map<String, Object> item = jdbc.queryForMap("select qty_book,qty_actual,qty_diff from inv_stocktake_item "
                + "where id=?", itemId);
        assertEquals(10, ((Number) item.get("qty_book")).intValue());
        assertEquals(-8, ((Number) item.get("qty_diff")).intValue());

        // FIFO：批1 5 全扣并关批，批2 只扣 3（5→2）。
        assertEquals(0, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, first));
        assertEquals("CLOSED", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, first));
        assertEquals(2, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, second));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, second));

        // 台账 10 - 8 = 2；流水 STOCKTAKE_LOSS -8。
        assertEquals(2, available(sku, wh));
        assertEquals(1, count("inv_stock_move"));
        Map<String, Object> move = jdbc.queryForMap("select change_type,qty_delta,ref_doc_type,ref_doc_no,"
                + "operator_id,batch_id from inv_stock_move");
        assertEquals("STOCKTAKE_LOSS", move.get("change_type"));
        assertEquals(-8, ((Number) move.get("qty_delta")).intValue());
        assertEquals("STOCKTAKE", move.get("ref_doc_type"));
        assertEquals(created.stocktakeNo(), move.get("ref_doc_no"));
        assertEquals(1L, ((Number) move.get("operator_id")).longValue());
        assertNull(move.get("batch_id"), "跨批盘亏流水不绑定单一批次");

        // 批次消耗：同事件键跨两批两行，成本取各自批次、金额 4 位。
        List<Map<String, Object>> consumptions = jdbc.queryForList("select biz_event_key,batch_id,sku_id,qty,"
                + "unit_cost,amount,move_type,sign,period,cross_period,sal_order_item_id,source_event_id "
                + "from inv_batch_consumption order by batch_id");
        assertEquals(2, consumptions.size());
        String expectedKey = "STK:" + itemId + ":CNT-L1";
        assertEquals(expectedKey, consumptions.get(0).get("biz_event_key"));
        assertEquals(expectedKey, consumptions.get(1).get("biz_event_key"), "同一事件跨批多行共用事件键");
        assertEquals(first, ((Number) consumptions.get(0).get("batch_id")).longValue());
        assertEquals(5, ((Number) consumptions.get(0).get("qty")).intValue());
        assertMoney("10.0000", consumptions.get(0).get("unit_cost"));
        assertMoney("50.0000", consumptions.get(0).get("amount"));
        assertEquals(second, ((Number) consumptions.get(1).get("batch_id")).longValue());
        assertEquals(3, ((Number) consumptions.get(1).get("qty")).intValue());
        assertMoney("20.0000", consumptions.get(1).get("unit_cost"));
        assertMoney("60.0000", consumptions.get(1).get("amount"));
        for (Map<String, Object> row : consumptions)
        {
            assertEquals("STOCKTAKE", row.get("move_type"));
            assertEquals(1, ((Number) row.get("sign")).intValue(), "盘亏 sign=+1（COGS 来源）");
            assertEquals("2026-07", row.get("period"));
            assertEquals(0, ((Number) row.get("cross_period")).intValue());
            assertNull(row.get("sal_order_item_id"));
            assertEquals("CNT-L1", row.get("source_event_id"));
            assertEquals(sku, ((Number) row.get("sku_id")).longValue());
        }

        // 守恒：批次剩余 2 + 消耗 8 = 初始 10；台账 = 批次剩余。
        assertEquals(10, jdbc.queryForObject("select (select coalesce(sum(qty_remaining),0) from inv_batch) "
                + "+ (select coalesce(sum(qty),0) from inv_batch_consumption where move_type='STOCKTAKE')",
                Integer.class));
        assertEquals(2, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch", Integer.class));

        // 审计 APPLY 同事务落库。
        Map<String, Object> audit = jdbc.queryForMap("select biz_type,biz_id,action,detail from "
                + "noon_m4_inventory_audit where action='APPLY'");
        assertEquals("STOCKTAKE", audit.get("biz_type"));
        assertEquals(String.valueOf(created.stocktakeId()), audit.get("biz_id"));
        assertTrue(String.valueOf(audit.get("detail")).contains("loss=8"), String.valueOf(audit.get("detail")));
    }

    @Test
    void applyLossRejectedWhenOpenBatchesCannotCoverAndRollsBackEverything()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-L2", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-L2");
        long batch = newBatch(sku, wh, "AE", "B:ST-L2", 3, "10.0000", LocalDateTime.of(2026, 7, 2, 9, 0));
        seedLedger(sku, wh, "AE", 5); // 台账 5 与批次剩余 3 不一致（模拟历史差异），盘亏 5 无法被批次覆盖
        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 0, "CNT-L2"))));
        String applyKey = rid();

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertTrue(rejected.getMessage().contains("可用批次不足以覆盖盘亏"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("需扣=5"), rejected.getMessage());

        // 整单回滚：台账/批次/消耗/流水/状态/幂等占位/审计都不得留下痕迹。
        assertEquals(5, available(sku, wh));
        assertEquals(3, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals("OPEN", jdbc.queryForObject("select status from inv_batch where id=?",
                String.class, batch));
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
        assertEquals(0, requestRows(applyKey), "失败事务的 PENDING 占位必须回滚");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));
        assertEquals(5, jdbc.queryForObject("select qty_book from inv_stocktake_item", Integer.class),
                "行快照未被失败事务改动");

        // 同键可安全重试（补齐批次后可成功）。
        newBatch(sku, wh, "AE", "B:ST-L2B", 2, "10.0000", LocalDateTime.of(2026, 7, 2, 9, 30));
        StocktakeResult applied = service.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey));
        assertFalse(applied.replayed(), "回滚后同键重试是首次成功");
        assertEquals(0, available(sku, wh));
        assertEquals("DONE", requestStatus(applyKey));
    }

    // ------------------------------------------------------- 盘盈：ADJUSTMENT 承接成本

    @Test
    void applyGainCreatesAdjustmentBatchWithWeightedAverageCost()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-G1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-G1");
        newBatch(sku, wh, "AE", "B:ST-G1-1", 5, "10.0000", LocalDateTime.of(2026, 7, 3, 9, 0));
        newBatch(sku, wh, "AE", "B:ST-G1-2", 5, "20.0000", LocalDateTime.of(2026, 7, 3, 9, 10));
        seedLedger(sku, wh, "AE", 10);

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, "B:ST-G1-1", 13, "CNT-G1"))));
        long itemId = itemId(created.stocktakeId());
        service.apply(new StocktakeApplyInput(created.stocktakeId(), rid()));

        // 盘盈 3：台账 10 → 13；ADJUSTMENT 批次承接加权平均 (5*10+5*20)/10 = 15。
        assertEquals(13, available(sku, wh));
        Map<String, Object> batch = jdbc.queryForMap("select batch_no,sku_id,pur_order_item_id,warehouse_id,"
                + "site_code,qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,"
                + "split_seq,status from inv_batch where source_type='ADJUSTMENT'");
        assertTrue(String.valueOf(batch.get("batch_no")).matches("B:STK:AE:[0-9A-F]{8}"), String.valueOf(batch.get("batch_no")));
        assertEquals(sku, ((Number) batch.get("sku_id")).longValue());
        assertEquals(wh, ((Number) batch.get("warehouse_id")).longValue());
        assertEquals("AE", batch.get("site_code"));
        assertNull(batch.get("pur_order_item_id"));
        assertEquals(3, ((Number) batch.get("qty_in")).intValue());
        assertEquals(3, ((Number) batch.get("qty_remaining")).intValue());
        assertMoney("15.0000", batch.get("unit_cost"));
        assertEquals("CNY", batch.get("currency"));
        assertMoney("1", batch.get("fx_rate"));
        assertNull(batch.get("parent_batch_id"));
        assertEquals(0, ((Number) batch.get("split_seq")).intValue());
        assertEquals("OPEN", batch.get("status"));
        assertNotNull(batch.get("status"));

        // 流水 STOCKTAKE_GAIN +3 指向承接批次；盘盈不写消耗。
        Map<String, Object> move = jdbc.queryForMap("select change_type,qty_delta,batch_id,ref_doc_no "
                + "from inv_stock_move");
        assertEquals("STOCKTAKE_GAIN", move.get("change_type"));
        assertEquals(3, ((Number) move.get("qty_delta")).intValue());
        assertNotNull(move.get("batch_id"), "盘盈流水指向承接成本的新批次");
        assertEquals(created.stocktakeNo(), move.get("ref_doc_no"));
        assertEquals(0, count("inv_batch_consumption"), "盘盈不是消耗，不得写批次消耗");
        assertEquals(13, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch", Integer.class));
        assertEquals(10, jdbc.queryForObject("select qty_book from inv_stocktake_item where id=?",
                Integer.class, itemId), "账面仍为 10（实盘 13）");
        assertEquals(3, jdbc.queryForObject("select qty_diff from inv_stocktake_item where id=?",
                Integer.class, itemId));

        // 多币种 OPEN 批次：不臆测折算，盘盈拒绝（零成本闸门之外的另一条拒绝路径，v1.1 裁决点 F 保留）。
        long skuMixed = newSku("SKU-ST-G1-M");
        newBatch(skuMixed, wh, "AE", "B:ST-G1-CNY", 2, "10.0000", LocalDateTime.of(2026, 7, 3, 10, 0));
        newBatchCurrency(skuMixed, wh, "AE", "B:ST-G1-AED", 2, "8.0000", "AED");
        seedLedger(skuMixed, wh, "AE", 4);
        StocktakeResult mixed = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(skuMixed, null, 6, "CNT-G1M"))));
        IllegalArgumentException currency = assertThrows(IllegalArgumentException.class,
                () -> service.apply(new StocktakeApplyInput(mixed.stocktakeId(), rid())));
        assertTrue(currency.getMessage().contains("多种币种"), currency.getMessage());
        assertEquals(4, available(skuMixed, wh), "拒绝后不得虚增库存");
        assertEquals("DRAFT", docStatus(mixed.stocktakeId()));
    }

    // ------------------------------------------------------- 账面快照闸门（v1.2 §6.1）

    /** Codex 场景原形：盘点时实物=账面=100，之后真实出库 10（实物/账面同步 90）→ 旧实盘 100 必须整单拒绝，不得凭空盘盈 10。 */
    @Test
    void outboundAfterCreateIsRejectedAsStaleAndWritesNothing()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-S1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-S1");
        long batch = newBatch(sku, wh, "AE", "B:ST-S1", 100, "10.0000", LocalDateTime.of(2026, 7, 4, 9, 0));
        seedLedger(sku, wh, "AE", 100);

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 100, "CNT-S1"))));
        long itemId = itemId(created.stocktakeId());
        assertEquals(100, bookOf(created.stocktakeId()), "建单快照 100");
        assertEquals(0, diffOf(created.stocktakeId()), "实盘=账面时快照差 0");

        // 建单之后、过账之前发生真实出库 10：实物与账面同步降到 90（模拟合法业务变动，非测试造错）。
        jdbc.update("update inv_stock_ledger set qty_available=90 where sku_id=? and warehouse_id=?", sku, wh);
        jdbc.update("update inv_batch set qty_remaining=90 where id=?", batch);
        String applyKey = rid();

        StaleStocktakeException stale = assertThrows(StaleStocktakeException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertTrue(stale.getMessage().contains("skuId=" + sku), stale.getMessage());
        assertTrue(stale.getMessage().contains("snapshotBook=100"), stale.getMessage());
        assertTrue(stale.getMessage().contains("currentBook=90"), stale.getMessage());
        assertTrue(stale.getMessage().contains("请重新盘点"), stale.getMessage());

        // 整单拒绝且发生在任何写入之前：台账/批次不变、无盘盈批次/流水/消耗、单仍 DRAFT、快照未被覆盖。
        assertEquals(90, available(sku, wh), "台账仍 90");
        assertEquals(90, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch), "批次仍 90");
        assertEquals(0, count("inv_stock_move"), "不得写 STOCKTAKE_GAIN/LOSS 流水");
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='ADJUSTMENT'",
                Integer.class), "不得凭空造盘盈批次");
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
        assertEquals(100, jdbc.queryForObject("select qty_book from inv_stocktake_item where id=?",
                Integer.class, itemId), "item.qty_book 必须保持建单快照 100");
        assertEquals(0, jdbc.queryForObject("select qty_diff from inv_stocktake_item where id=?",
                Integer.class, itemId));
        assertEquals(0, requestRows(applyKey), "业务校验失败整单回滚，PENDING 占位不得残留");
        assertEquals(0, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));

        // 同键重试仍判 stale（不缓存错误结果），依旧零写入。
        assertThrows(StaleStocktakeException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertEquals(90, available(sku, wh));
        assertEquals(0, count("inv_stock_move"));
    }

    /** 建单后补入库（账面 100 → 110）同样拒绝，防用旧实盘算出凭空盘亏。 */
    @Test
    void inboundAfterCreateIsRejectedAsStale()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-S2", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-S2");
        newBatch(sku, wh, "AE", "B:ST-S2", 100, "10.0000", LocalDateTime.of(2026, 7, 4, 9, 0));
        seedLedger(sku, wh, "AE", 100);

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 90, "CNT-S2"))));
        assertEquals(-10, diffOf(created.stocktakeId()), "建单快照差 -10");

        newBatch(sku, wh, "AE", "B:ST-S2-IN", 10, "10.0000", LocalDateTime.of(2026, 7, 4, 10, 0));
        jdbc.update("update inv_stock_ledger set qty_available=110 where sku_id=? and warehouse_id=?", sku, wh);

        StaleStocktakeException stale = assertThrows(StaleStocktakeException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), rid())));
        assertTrue(stale.getMessage().contains("snapshotBook=100"), stale.getMessage());
        assertTrue(stale.getMessage().contains("currentBook=110"), stale.getMessage());
        assertEquals(110, available(sku, wh), "拒绝后不得凭空盘亏 10");
        assertEquals(0, count("inv_batch_consumption"), "不得按旧实盘扣批次");
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
        assertEquals(100, bookOf(created.stocktakeId()));
    }

    /** 重盘正确路径：按新账面 90 重新建单（实盘 90、快照 90）→ 应用成功、台账不变、无盘盈盘亏流水。 */
    @Test
    void recountAgainstNewBookAppliesWithNoGainOrLoss()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-S3", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-S3");
        long batch = newBatch(sku, wh, "AE", "B:ST-S3", 100, "10.0000", LocalDateTime.of(2026, 7, 5, 9, 0));
        seedLedger(sku, wh, "AE", 100);

        StocktakeResult staleDoc = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 100, "CNT-S3-OLD"))));
        jdbc.update("update inv_stock_ledger set qty_available=90 where sku_id=? and warehouse_id=?", sku, wh);
        jdbc.update("update inv_batch set qty_remaining=90 where id=?", batch);
        assertThrows(StaleStocktakeException.class,
                () -> service.apply(new StocktakeApplyInput(staleDoc.stocktakeId(), rid())));

        StocktakeResult recount = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(sku, null, 90, "CNT-S3-NEW"))));
        assertEquals(90, bookOf(recount.stocktakeId()), "新单快照 = 新账面 90");
        assertEquals(0, diffOf(recount.stocktakeId()));
        StocktakeResult applied = service.apply(new StocktakeApplyInput(recount.stocktakeId(), rid()));

        assertFalse(applied.replayed());
        assertEquals("APPLIED", applied.status());
        assertEquals(90, available(sku, wh), "实盘=账面，台账不变");
        assertEquals(90, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batch));
        assertEquals(0, count("inv_stock_move"), "无盘盈盘亏流水");
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='ADJUSTMENT'",
                Integer.class));
        assertEquals("DRAFT", docStatus(staleDoc.stocktakeId()), "旧单保持 DRAFT 待业务作废");
    }

    /** 多行部分 stale：只要一行快照失配，整单拒绝，合规的盘亏行也不得过账（事务回滚）。 */
    @Test
    void multiRowPartialStaleRejectsWholeDocument()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-S4", "AE", "ACTIVE");
        long skuA = newSku("SKU-ST-S4A");
        long skuB = newSku("SKU-ST-S4B");
        long batchA = newBatch(skuA, wh, "AE", "B:ST-S4A", 100, "10.0000", LocalDateTime.of(2026, 7, 6, 9, 0));
        long batchB = newBatch(skuB, wh, "AE", "B:ST-S4B", 50, "10.0000", LocalDateTime.of(2026, 7, 6, 9, 0));
        seedLedger(skuA, wh, "AE", 100);
        seedLedger(skuB, wh, "AE", 50);

        StocktakeResult created = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(skuA, null, 90, "CNT-S4A"),
                        new StocktakeLineInput(skuB, null, 50, "CNT-S4B"))));
        // 只有 B 失配（真实出库 10），A 的快照仍与新账面一致（本可盘亏 10）。
        jdbc.update("update inv_stock_ledger set qty_available=40 where sku_id=? and warehouse_id=?", skuB, wh);
        jdbc.update("update inv_batch set qty_remaining=40 where id=?", batchB);

        StaleStocktakeException stale = assertThrows(StaleStocktakeException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), rid())));
        assertTrue(stale.getMessage().contains("skuId=" + skuB), stale.getMessage());

        assertEquals(100, available(skuA, wh), "合规行 A 也不得过账（整单回滚）");
        assertEquals(100, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, batchA));
        assertEquals(40, available(skuB, wh));
        assertEquals(0, count("inv_batch_consumption"));
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
        assertEquals(100, jdbc.queryForObject("select qty_book from inv_stocktake_item where stocktake_id=? "
                + "and sku_id=?", Integer.class, created.stocktakeId(), skuA), "A 快照保持 100");
        assertEquals(50, jdbc.queryForObject("select qty_book from inv_stocktake_item where stocktake_id=? "
                + "and sku_id=?", Integer.class, created.stocktakeId(), skuB), "B 快照保持 50");
    }

    // ------------------------------------------------------- 零成本盘盈闸门（v1.2 §6.3）

    @Test
    void gainRejectedWhenNoOpenBatchOrWeightedCostIsZero()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-Z1", "AE", "ACTIVE");

        // 1) 无任何 OPEN 批次、无台账行：实盘 5 盘盈 → 无可追溯成本，拒绝且不得新建台账/批次。
        long noBatchSku = newSku("SKU-ST-Z1-N");
        StocktakeResult noBatch = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(noBatchSku, null, 5, "CNT-Z1N"))));
        ZeroCostGainException zero = assertThrows(ZeroCostGainException.class,
                () -> service.apply(new StocktakeApplyInput(noBatch.stocktakeId(), rid())));
        assertTrue(zero.getMessage().contains("skuId=" + noBatchSku), zero.getMessage());
        assertTrue(zero.getMessage().contains("warehouseId=" + wh), zero.getMessage());
        assertTrue(zero.getMessage().contains("gain=5"), zero.getMessage());
        assertTrue(zero.getMessage().contains("无可追溯成本，请先补录采购价或由业务确认估值"), zero.getMessage());
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_stock_ledger where sku_id=?",
                Integer.class, noBatchSku), "拒绝后不得新建台账行");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where sku_id=?",
                Integer.class, noBatchSku), "拒绝后不得造零成本批次");
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(noBatch.stocktakeId()));

        // 2) 有 OPEN 批次但加权成本为 0 → 同样拒绝；补录成本后同键重试成功（证明未缓存错误结果）。
        long zeroCostSku = newSku("SKU-ST-Z1-C");
        long zeroBatch = newBatch(zeroCostSku, wh, "AE", "B:ST-Z1-C", 3, "0.0000",
                LocalDateTime.of(2026, 7, 7, 9, 0));
        seedLedger(zeroCostSku, wh, "AE", 3);
        StocktakeResult gain = service.create(new StocktakeInput(wh, "2026-07", rid(),
                List.of(new StocktakeLineInput(zeroCostSku, null, 5, "CNT-Z1C"))));
        assertEquals(2, diffOf(gain.stocktakeId()), "盘盈 2");
        String applyKey = rid();
        ZeroCostGainException zeroCost = assertThrows(ZeroCostGainException.class,
                () -> service.apply(new StocktakeApplyInput(gain.stocktakeId(), applyKey)));
        assertTrue(zeroCost.getMessage().contains("gain=2"), zeroCost.getMessage());
        assertTrue(zeroCost.getMessage().contains("加权平均成本为 0"), zeroCost.getMessage());
        assertEquals(3, available(zeroCostSku, wh), "拒绝后不得虚增库存");
        assertEquals(0, jdbc.queryForObject("select count(*) from inv_batch where source_type='ADJUSTMENT'",
                Integer.class));
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(gain.stocktakeId()));

        // 业务补录采购价（0 → 12）后，同 requestId 重试：盘盈 2 以可追溯成本 12 入 ADJUSTMENT 批次。
        jdbc.update("update inv_batch set unit_cost='12.0000' where id=?", zeroBatch);
        StocktakeResult applied = service.apply(new StocktakeApplyInput(gain.stocktakeId(), applyKey));
        assertFalse(applied.replayed(), "失败未缓存，重试是首次成功");
        assertEquals(5, available(zeroCostSku, wh));
        Map<String, Object> adjust = jdbc.queryForMap("select qty_in,unit_cost,currency,fx_rate,status "
                + "from inv_batch where source_type='ADJUSTMENT'");
        assertEquals(2, ((Number) adjust.get("qty_in")).intValue());
        assertMoney("12.0000", adjust.get("unit_cost"));
        assertEquals("CNY", adjust.get("currency"));
        assertMoney("1", adjust.get("fx_rate"));
        assertEquals("OPEN", adjust.get("status"));
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stock_move "
                + "where change_type='STOCKTAKE_GAIN' and qty_delta=2", Integer.class));
        assertEquals(0, count("inv_batch_consumption"), "盘盈不是消耗");
    }

    // ------------------------------------------------------- 幂等

    @Test
    void idempotencyReplaysPerActionAndKeepsCreateApplyIsolated()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-I1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-I1");
        newBatch(sku, wh, "AE", "B:ST-I1", 10, "10.0000", LocalDateTime.of(2026, 7, 5, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        String createKey = rid();

        StocktakeResult first = service.create(new StocktakeInput(wh, "2026-07", createKey,
                List.of(new StocktakeLineInput(sku, null, 12, "CNT-I1"))));
        int docs = count("inv_stocktake");
        int audits = count("noon_m4_inventory_audit");

        // 1) 同键同内容 → replayed=true，返回首次结果，零写入。
        StocktakeResult replay = service.create(new StocktakeInput(wh, "2026-07", createKey,
                List.of(new StocktakeLineInput(sku, null, 12, "CNT-I1"))));
        assertTrue(replay.replayed());
        assertEquals(first.stocktakeId(), replay.stocktakeId());
        assertEquals(first.stocktakeNo(), replay.stocktakeNo());
        assertEquals(docs, count("inv_stocktake"), "重放不得多建单");
        assertEquals(audits, count("noon_m4_inventory_audit"), "重放不得写审计");
        assertEquals(1, jdbc.queryForObject("select count(*) from inv_stocktake_item", Integer.class));

        // 2) 同键改内容 → 400。
        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, "2026-07", createKey,
                        List.of(new StocktakeLineInput(sku, null, 11, "CNT-I1")))));
        assertEquals("requestId 对应的盘点内容与原请求不一致", changed.getMessage());
        assertEquals(docs, count("inv_stocktake"));

        // 3) apply 首次 + 重放。
        String applyKey = rid();
        StocktakeResult applied = service.apply(new StocktakeApplyInput(first.stocktakeId(), applyKey));
        assertFalse(applied.replayed());
        int availableAfter = available(sku, wh);
        int moves = count("inv_stock_move");
        int consumptions = count("inv_batch_consumption");

        StocktakeResult applyReplay = service.apply(new StocktakeApplyInput(first.stocktakeId(), applyKey));
        assertTrue(applyReplay.replayed(), "同 requestId 重放 replayed=true（状态闸门在重放之后）");
        assertEquals("APPLIED", applyReplay.status());
        assertEquals(availableAfter, available(sku, wh), "重放不得重复过账");
        assertEquals(moves, count("inv_stock_move"));
        assertEquals(consumptions, count("inv_batch_consumption"));
        assertEquals(audits + 1, count("noon_m4_inventory_audit"));

        // 4) 动作冲突：CREATE 的键用于 APPLY / APPLY 的键用于 CREATE → 400，互不误伤。
        IllegalArgumentException applyWithCreateKey = assertThrows(IllegalArgumentException.class,
                () -> service.apply(new StocktakeApplyInput(first.stocktakeId(), createKey)));
        assertTrue(applyWithCreateKey.getMessage().contains("其他盘点动作"), applyWithCreateKey.getMessage());
        IllegalArgumentException createWithApplyKey = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, applyKey,
                        List.of(new StocktakeLineInput(sku, null, 1, "CNT-I1")))));
        assertTrue(createWithApplyKey.getMessage().contains("其他盘点动作"), createWithApplyKey.getMessage());
        assertEquals(availableAfter, available(sku, wh));
        assertEquals(docs, count("inv_stocktake"), "动作冲突不得建单");

        // 5) PENDING 占位 → 400「处理中」。
        String pendingKey = rid();
        jdbc.update("insert into noon_m4_stocktake_request(request_id,stocktake_id,warehouse_id,action,"
                        + "content_fingerprint,status) values (?,?,?,?,?,'PENDING')",
                pendingKey, 0, wh, "CREATE",
                StocktakeRules.createFingerprint(wh, List.of(new StocktakeLineInput(sku, null, 3, "CNT-I1"))));
        IllegalArgumentException pending = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, pendingKey,
                        List.of(new StocktakeLineInput(sku, null, 3, "CNT-I1")))));
        assertTrue(pending.getMessage().contains("正在处理中"), pending.getMessage());

        // 6) 不同键的第二张单：正常应用（同一 SKU 多张单互不影响）。
        StocktakeResult other = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, null, available(sku, wh), "CNT-I1X"))));
        service.apply(new StocktakeApplyInput(other.stocktakeId(), rid()));
        assertEquals(availableAfter, available(sku, wh), "实盘=账面时不动库存");
        assertEquals(docs + 1, count("inv_stocktake"));
    }

    @Test
    void concurrentSameApplyRequestIdBooksOnce() throws Exception
    {
        login(1L);
        long wh = newWarehouse("WH-ST-CONC", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-CONC");
        newBatch(sku, wh, "AE", "B:ST-CONC", 10, "10.0000", LocalDateTime.of(2026, 7, 6, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        StocktakeResult created = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, null, 4, "CNT-CONC"))));
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
                StocktakeResult result = service.apply(new StocktakeApplyInput(created.stocktakeId(), requestId));
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
                "同一 requestId 并发只能一次真实过账: " + results);
        assertEquals(1, results.stream().filter(r -> "REPLAYED".equals(r) || r.startsWith("REJECT:")).count(),
                "另一个必须是幂等重放或明确拒绝: " + results);
        assertEquals(4, available(sku, wh), "台账只扣一次");
        assertEquals(4, jdbc.queryForObject("select sum(qty_remaining) from inv_batch", Integer.class),
                "批次只扣一次");
        assertEquals(6, jdbc.queryForObject("select sum(qty) from inv_batch_consumption", Integer.class));
        assertEquals(1, count("inv_stock_move"));
        assertEquals(1, jdbc.queryForObject("select count(*) from noon_m4_inventory_audit where action='APPLY'",
                Integer.class));
        assertEquals("DONE", requestStatus(requestId));
    }

    // ------------------------------------------------------- 授权

    @Test
    void authorizationIsSiteScopedAndPrecedesReplay()
    {
        long whAe = newWarehouse("WH-ST-A1", "AE", "ACTIVE");
        long whSa = newWarehouse("WH-ST-A2", "SA", "ACTIVE");
        long skuAe = newSku("SKU-ST-A1");
        long skuSa = newSku("SKU-ST-A2");
        newBatch(skuAe, whAe, "AE", "B:ST-A1", 5, "10.0000", LocalDateTime.of(2026, 7, 7, 9, 0));
        seedLedger(skuAe, whAe, "AE", 5);
        newBatch(skuSa, whSa, "SA", "B:ST-A2", 5, "10.0000", LocalDateTime.of(2026, 7, 7, 9, 0));
        seedLedger(skuSa, whSa, "SA", 5);

        // 超管建 SA 单（跨站夹具）。
        login(1L);
        StocktakeResult sa = service.create(new StocktakeInput(whSa, null, rid(),
                List.of(new StocktakeLineInput(skuSa, null, 5, "CNT-SA"))));

        // 仅 AE 用户：SA 建单 403（授权按仓库真实站点，且先于启用状态）。
        login(2L);
        AccessDeniedException crossCreate = assertThrows(AccessDeniedException.class,
                () -> service.create(new StocktakeInput(whSa, null, rid(),
                        List.of(new StocktakeLineInput(skuSa, null, 5, "CNT-SA2")))));
        assertNotNull(crossCreate.getMessage());
        assertEquals(1, count("inv_stocktake"), "越权不得建单");

        // 跨站 apply：持合法 requestId 也只 403，且不写任何库存。
        String crossApplyKey = rid();
        assertThrows(AccessDeniedException.class,
                () -> service.apply(new StocktakeApplyInput(sa.stocktakeId(), crossApplyKey)));
        assertEquals(0, requestRows(crossApplyKey), "403 发生在占位之前");
        assertEquals("DRAFT", docStatus(sa.stocktakeId()));
        assertEquals(5, available(skuSa, whSa));

        // resultOf / get / list 全部按站点鉴权。
        assertThrows(AccessDeniedException.class, () -> service.resultOf(createRequestOf(sa.stocktakeId())));
        assertThrows(AccessDeniedException.class, () -> service.get(sa.stocktakeId()));
        assertThrows(AccessDeniedException.class, () -> service.list("SA", 0, 10));

        // AE 单可正常读写；list() 只看到 AE。
        StocktakeResult ae = service.create(new StocktakeInput(whAe, null, rid(),
                List.of(new StocktakeLineInput(skuAe, null, 5, "CNT-AE"))));
        PageResult<StocktakeRow> aeList = service.list(null, 0, 10);
        assertEquals(1, aeList.total());
        assertEquals("AE", aeList.rows().get(0).siteCode());
        StocktakeResult aeResult = service.resultOf(aeIdempotentKey(ae.stocktakeId()));
        assertNotNull(aeResult);
        assertTrue(aeResult.replayed());
        assertEquals("DRAFT", aeResult.status());

        // 撤销授权：删掉 AE 授权后，持已有合法 requestId 查/过账都只 403。
        jdbc.update("delete from sys_user_site where user_id=2 and site_code='AE'");
        String revokedKey = rid();
        assertThrows(AccessDeniedException.class,
                () -> service.apply(new StocktakeApplyInput(ae.stocktakeId(), revokedKey)));
        assertThrows(AccessDeniedException.class, () -> service.get(ae.stocktakeId()));
        assertThrows(AccessDeniedException.class, () -> service.resultOf(aeIdempotentKey(ae.stocktakeId())));
        assertEquals("DRAFT", docStatus(ae.stocktakeId()));
        jdbc.update("insert into sys_user_site(user_id,site_code,create_by,create_time) "
                + "values (2,'AE',1,current_timestamp(3))");
        assertFalse(service.apply(new StocktakeApplyInput(ae.stocktakeId(), revokedKey)).replayed());

        // 无任何站点：列表零行、写入 403。
        login(3L);
        assertEquals(0, service.list(null, 0, 10).total(), "空授权必须零行");
        assertThrows(AccessDeniedException.class,
                () -> service.create(new StocktakeInput(whAe, null, rid(),
                        List.of(new StocktakeLineInput(skuAe, null, 1, "CNT-N")))));
        assertThrows(AccessDeniedException.class, () -> service.resultOf(aeIdempotentKey(ae.stocktakeId())));

        // 仓库不存在 400 / 停用 400 / 非法站点 400。
        login(1L);
        long inactive = newWarehouse("WH-ST-A3", "AE", "INACTIVE");
        IllegalArgumentException stopped = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(inactive, null, rid(),
                        List.of(new StocktakeLineInput(skuAe, null, 1, "CNT-OFF")))));
        assertTrue(stopped.getMessage().contains("已停用"), stopped.getMessage());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(9999L, null, rid(),
                        List.of(new StocktakeLineInput(skuAe, null, 1, "CNT-MISS")))));
        assertTrue(missing.getMessage().contains("仓库不存在"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.list("XX", 0, 10));
    }

    // ------------------------------------------------------- STK 事件键 / 重复行

    @Test
    void stkEventKeySpansBatchesAndDuplicateBatchRetransmitIsBlocked()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-E1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-E1");
        long first = newBatch(sku, wh, "AE", "B:ST-E1-1", 4, "10.0000", LocalDateTime.of(2026, 7, 8, 9, 0));
        long second = newBatch(sku, wh, "AE", "B:ST-E1-2", 4, "20.0000", LocalDateTime.of(2026, 7, 8, 9, 10));
        seedLedger(sku, wh, "AE", 8);
        StocktakeResult created = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, null, 1, "CNT-E1"))));
        long itemId = itemId(created.stocktakeId());

        // 模拟另一路径已为「同事件 + 同批次」过账（唯一键 (biz_event_key,batch_id) 撞键）。
        String key = "STK:" + itemId + ":CNT-E1";
        jdbc.update("insert into inv_batch_consumption(consumption_no,biz_event_key,batch_id,sku_id,qty,"
                        + "unit_cost,amount,consumed_at,move_type,sign,period,cross_period) "
                        + "values ('SC:TEST-DUP',?,?,?,1,'10.0000','10.0000',current_timestamp(3),'STOCKTAKE',1,"
                        + "'2026-07',0)", key, first, sku);

        String applyKey = rid();
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), applyKey)));
        assertTrue(duplicate.getMessage().contains("盘点事件已过账"), duplicate.getMessage());

        // 整单回滚：台账未动、批1 未被扣（批2 未进入循环）、单据仍 DRAFT、无流水、幂等占位回滚。
        assertEquals(8, available(sku, wh));
        assertEquals(4, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, first));
        assertEquals(4, jdbc.queryForObject("select qty_remaining from inv_batch where id=?",
                Integer.class, second));
        assertEquals(1, count("inv_batch_consumption"));
        assertEquals(0, count("inv_stock_move"));
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
        assertEquals(0, requestRows(applyKey));

        // 不同点数事件号（同一盘点行不可能二次应用，这里用另一张单）跨批多行合法：3 伤害跨 2 批。
        jdbc.execute("delete from inv_batch_consumption");
        StocktakeResult again = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, null, 1, "CNT-E2"))));
        service.apply(new StocktakeApplyInput(again.stocktakeId(), rid()));
        List<Map<String, Object>> rows = jdbc.queryForList("select batch_id,qty from inv_batch_consumption "
                + "order by batch_id");
        assertEquals(2, rows.size(), "7 件盘亏跨两批两行");
        assertEquals(first, ((Number) rows.get(0).get("batch_id")).longValue());
        assertEquals(4, ((Number) rows.get(0).get("qty")).intValue());
        assertEquals(second, ((Number) rows.get(1).get("batch_id")).longValue());
        assertEquals(3, ((Number) rows.get(1).get("qty")).intValue());
        assertEquals(1, available(sku, wh));
        assertEquals(7, jdbc.queryForObject("select sum(qty) from inv_batch_consumption", Integer.class));
        assertEquals(1, jdbc.queryForObject("select coalesce(sum(qty_remaining),0) from inv_batch", Integer.class));
    }

    @Test
    void duplicateSkuLinesAreRejectedAtCreateAndApply()
    {
        login(1L);
        long wh = newWarehouse("WH-ST-D1", "AE", "ACTIVE");
        long sku = newSku("SKU-ST-D1");
        long sku2 = newSku("SKU-ST-D2");
        newBatch(sku, wh, "AE", "B:ST-D1", 10, "10.0000", LocalDateTime.of(2026, 7, 9, 9, 0));
        newBatch(sku2, wh, "AE", "B:ST-D2", 5, "10.0000", LocalDateTime.of(2026, 7, 9, 9, 0));
        seedLedger(sku, wh, "AE", 10);
        seedLedger(sku2, wh, "AE", 5);

        // 同一 SKU 两条（不同批次标签 / 相同批次标签）都在建单被拒：SKU 级基线重复会重复计数同一批实物。
        List<StocktakeLineInput> twoBatchLabels = List.of(
                new StocktakeLineInput(sku, "B:ST-D1", 5, "CNT-D1"),
                new StocktakeLineInput(sku, "B:ST-D2", 4, "CNT-D1"));
        IllegalArgumentException sameSku = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, rid(), twoBatchLabels)));
        assertTrue(sameSku.getMessage().contains("同一 SKU 不能"), sameSku.getMessage());
        List<StocktakeLineInput> sameLabelTwice = List.of(
                new StocktakeLineInput(sku, "B:ST-D1", 5, "CNT-D1"),
                new StocktakeLineInput(sku, "B:ST-D1", 3, "CNT-D1"));
        IllegalArgumentException sameKey = assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, rid(), sameLabelTwice)));
        assertTrue(sameKey.getMessage().contains("同一 SKU 不能"), sameKey.getMessage());
        assertEquals(0, count("inv_stocktake"), "重复行不得建单");

        // 行校验：qtyActual 负数 / countEventId 非法 / 空行 / 空明细。
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, rid(),
                        List.of(new StocktakeLineInput(sku, null, -1, "CNT-D1")))))
                .getMessage().contains("qtyActual"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, rid(),
                        List.of(new StocktakeLineInput(sku, null, 1, "bad:event")))))
                .getMessage().contains("countEventId"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(new StocktakeInput(wh, null, rid(), List.of())))
                .getMessage().contains("至少需要提交 1 行"));

        // apply 侧防御：库里被写入同 SKU 重复行时拒绝应用（不按全量账面重复计数）。
        StocktakeResult created = service.create(new StocktakeInput(wh, null, rid(),
                List.of(new StocktakeLineInput(sku, null, 8, "CNT-D3"))));
        jdbc.update("insert into inv_stocktake_item(stocktake_id,sku_id,batch_no,qty_book,qty_actual,qty_diff) "
                + "values (?,?,NULL,10,6,-4)", created.stocktakeId(), sku);
        IllegalArgumentException duplicateRows = assertThrows(IllegalArgumentException.class,
                () -> service.apply(new StocktakeApplyInput(created.stocktakeId(), rid())));
        assertTrue(duplicateRows.getMessage().contains("重复 SKU"), duplicateRows.getMessage());
        assertEquals(10, available(sku, wh));
        assertEquals("DRAFT", docStatus(created.stocktakeId()));
    }

    // ------------------------------------------------------- 列表 / 详情

    @Test
    void listAndGetRespectSiteAndPaging()
    {
        login(1L);
        long whAe = newWarehouse("WH-ST-P1", "AE", "ACTIVE");
        long whSa = newWarehouse("WH-ST-P2", "SA", "ACTIVE");
        long skuAe = newSku("SKU-ST-P1");
        long skuSa = newSku("SKU-ST-P2");
        newBatch(skuAe, whAe, "AE", "B:ST-P1", 10, "10.0000", LocalDateTime.of(2026, 7, 10, 9, 0));
        newBatch(skuSa, whSa, "SA", "B:ST-P2", 10, "10.0000", LocalDateTime.of(2026, 7, 10, 9, 0));
        seedLedger(skuAe, whAe, "AE", 10);
        seedLedger(skuSa, whSa, "SA", 10);

        List<Long> aeIds = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            aeIds.add(service.create(new StocktakeInput(whAe, "2026-07", rid(),
                    List.of(new StocktakeLineInput(skuAe, null, 10 - i, "CNT-P" + i)))).stocktakeId());
        long saId = service.create(new StocktakeInput(whSa, "2026-07", rid(),
                List.of(new StocktakeLineInput(skuSa, null, 10, "CNT-PS")))).stocktakeId();

        PageResult<StocktakeRow> all = service.list(null, 0, 10);
        assertEquals(4, all.total());
        assertEquals(4, all.rows().size());
        assertEquals(1, service.list("SA", 0, 10).total());

        // 分页游标：不重复不遗漏，total 不受 LIMIT 截断。
        List<Long> expected = all.rows().stream().map(StocktakeRow::id).toList();
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += 2)
        {
            PageResult<StocktakeRow> page = service.list(null, offset, 2);
            assertEquals(4, page.total());
            assertEquals(Math.min(2, expected.size() - offset), page.rows().size());
            seen.addAll(page.rows().stream().map(StocktakeRow::id).toList());
        }
        assertEquals(expected, seen, "翻页无重复无遗漏");

        StocktakeDetail detail = service.get(aeIds.get(0));
        assertEquals("AE", detail.head().siteCode());
        assertEquals("2026-07", detail.head().period());
        assertEquals("DRAFT", detail.head().status());
        assertEquals(1, detail.items().size());
        StocktakeItemRow row = detail.items().get(0);
        assertEquals(skuAe, row.skuId());
        assertEquals(10, row.qtyBook());
        assertEquals(10, row.qtyActual());
        assertEquals(0, row.qtyDiff());

        login(2L);
        assertEquals(3, service.list(null, 0, 10).total(), "AE 用户只看 AE");
        assertThrows(AccessDeniedException.class, () -> service.get(saId));

        login(1L);
        assertThrows(IllegalArgumentException.class, () -> service.get(9999L));
        assertThrows(IllegalArgumentException.class, () -> service.get(null));
        assertNull(service.resultOf(null));
        assertNull(service.resultOf(""));
        assertNull(service.resultOf("short"), "非法格式返回 null 而非 400");
        assertNull(service.resultOf("r-" + UUID.randomUUID()), "不存在返回 null");
    }

    // ------------------------------------------------------------------ helpers

    private int available(long skuId, long warehouseId)
    {
        return jdbc.queryForObject("select qty_available from inv_stock_ledger where sku_id=? and warehouse_id=?",
                Integer.class, skuId, warehouseId);
    }

    private long itemId(long stocktakeId)
    {
        return jdbc.queryForObject("select id from inv_stocktake_item where stocktake_id=?",
                Long.class, stocktakeId);
    }

    private int bookOf(long stocktakeId)
    {
        return jdbc.queryForObject("select qty_book from inv_stocktake_item where stocktake_id=?",
                Integer.class, stocktakeId);
    }

    private int diffOf(long stocktakeId)
    {
        return jdbc.queryForObject("select qty_diff from inv_stocktake_item where stocktake_id=?",
                Integer.class, stocktakeId);
    }

    private String docStatus(long stocktakeId)
    {
        return jdbc.queryForObject("select status from inv_stocktake where id=?", String.class, stocktakeId);
    }

    private String createRequestOf(long stocktakeId)
    {
        return jdbc.queryForObject("select request_id from noon_m4_stocktake_request where stocktake_id=? "
                + "and action='CREATE'", String.class, stocktakeId);
    }

    private String aeIdempotentKey(long stocktakeId) { return createRequestOf(stocktakeId); }

    private int count(String table)
    {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int requestRows(String requestId)
    {
        return jdbc.queryForObject("select count(*) from noon_m4_stocktake_request where request_id=?",
                Integer.class, requestId);
    }

    private String requestStatus(String requestId)
    {
        return jdbc.queryForObject("select status from noon_m4_stocktake_request where request_id=?",
                String.class, requestId);
    }

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
                batchNo, skuId, warehouseId, site, java.sql.Timestamp.valueOf(receivedAt), qty, qty, unitCost);
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private long newBatchCurrency(long skuId, long warehouseId, String site, String batchNo, int qty,
                                  String unitCost, String currency)
    {
        jdbc.update("insert into inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                        + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,split_seq,"
                        + "split_reason,transfer_no,status) "
                        + "values (?,?,NULL,?,?,?,?,?,?,?,NULL,'PURCHASE',NULL,0,NULL,NULL,'OPEN')",
                batchNo, skuId, warehouseId, site, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 7, 9, 10, 0)),
                qty, qty, unitCost, currency);
        return jdbc.queryForObject("select id from inv_batch where batch_no=?", Long.class, batchNo);
    }

    private void seedLedger(long skuId, long warehouseId, String site, int available)
    {
        jdbc.update("insert into inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable) values (?,?,?,?,0,0,0)",
                skuId, warehouseId, site, available);
    }

    private static void assertMoney(String expected, Object actual)
    {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), expected + " vs " + actual);
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
     * H2 宽松建表：列名与 {@code docs/noon-erp-schema-v0.5.sql}（inv_stocktake/_item）及
     * {@code yx-erp/sql/noon_m4_stocktake.sql}（幂等表）、{@code noon_m4_inventory.sql}（批次/台账/流水/审计）对齐；
     * JSON 用文本列、不含 CHECK（CHECK 与真库并发由 {@link MySqlStocktakeIT} 验证）。
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
                + "move_type varchar(24) not null default 'SALE', sign int not null default 1, "
                + "reversal_of_id bigint, sal_return_id bigint, refund_amount decimal(18,4) default 0, "
                + "period char(7), cross_period int not null default 0, raw_payload varchar(4000), "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_consumption_no unique(consumption_no), "
                + "constraint uk_consume_biz_event unique(biz_event_key,batch_id))");
        jdbc.execute("create table inv_stocktake (id bigint auto_increment primary key, "
                + "stocktake_no varchar(64) not null, warehouse_id bigint not null, period char(7), "
                + "status varchar(24) not null default 'DRAFT', "
                + "created_at timestamp(3) not null default current_timestamp(3), "
                + "constraint uk_stocktake_no unique(stocktake_no))");
        jdbc.execute("create table inv_stocktake_item (id bigint auto_increment primary key, "
                + "stocktake_id bigint not null, sku_id bigint not null, batch_no varchar(64), "
                + "qty_book int not null, qty_actual int not null, qty_diff int not null, "
                + "created_at timestamp(3) not null default current_timestamp(3))");
        jdbc.execute("create table noon_m4_stocktake_request (request_id varchar(64) not null primary key, "
                + "stocktake_id bigint not null default 0, warehouse_id bigint not null, operator_id bigint, "
                + "action varchar(16) not null, content_fingerprint char(64) not null, "
                + "status varchar(16) not null default 'PENDING', result_json varchar(10000), "
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
        StocktakeService stocktakeService(JdbcTemplate jdbcTemplate, SiteAuthGuard guard, InventoryAudit audit)
        {
            return new StocktakeService(jdbcTemplate, guard, audit);
        }
    }
}
