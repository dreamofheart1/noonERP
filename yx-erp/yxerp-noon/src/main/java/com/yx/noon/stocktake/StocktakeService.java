package com.yx.noon.stocktake;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.inventory.InventoryAudit;
import com.yx.noon.masterdata.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M4 库存第三轮第一批「盘点闭环」后端内核（契约 v1 §2–§7，owner：豆包）。
 *
 * <p><b>create（→ DRAFT，不改库存）</b>：入参校验 → operatorId → 仓库归属授权（真实站点
 * {@code requireSite}，且仓库必须 ACTIVE）→ 逐行校验（skuId / qtyActual ≥ 0 / countEventId /
 * 同一 SKU 不重复）→ CREATE 内容指纹 → DONE 重放 → PENDING 占位（{@code stocktake_id=0}）→
 * 写单 + 明细（{@code qty_book} 为台账快照、{@code qty_diff} 为快照差）→ 审计 CREATE →
 * DONE 回填真实 id。建单<b>不写台账/流水/批次消耗</b>。
 *
 * <p><b>apply（DRAFT → APPLIED，过账）</b>：入参校验 → operatorId → 锁盘点单（FOR UPDATE）+
 * 头仓库授权 → APPLY 指纹 → DONE 重放 → 状态闸门（仅 DRAFT）→ <b>账面快照比对闸门 + 零成本盘盈闸门</b>
 * （逐行 FOR UPDATE 锁 item 与台账，整单校验，任何写入之前）→ PENDING 占位 → 逐行过账
 * （差异用<b>建单快照</b> {@code qty_actual - qty_book}）→ 盘亏按 FIFO 逐批扣 {@code qty_remaining} 并写
 * {@code inv_batch_consumption}（{@code STK:<itemId>:<countEventId>}）→ 盘盈新建 ADJUSTMENT 批次承接
 * 加权平均成本 → 台账与 {@code STOCKTAKE_GAIN/LOSS} 流水 → 单置 APPLIED → 审计 APPLY → DONE。
 * 任一行失败整单回滚（台账/批次/消耗/流水/幂等/审计一起回滚）。
 *
 * <p><b>守恒关键（契约 v1.2 §6.1）</b>：{@code qty_actual} 是某时刻的实盘事实，只有<b>同一时刻</b>的账面快照
 * {@code qty_book}（create 持久化，apply 不得覆盖）才能与之配对算差异。apply 时 FOR UPDATE 读实时台账
 * {@code nowBook}：与快照不一致说明建单后发生了真实出库/入库/调拨（实物与账面同步变动），旧实盘已失配，
 * <b>整单拒绝</b>（{@link StaleStocktakeException}）并要求按新账面重新盘点，绝不臆造盘盈/盘亏；
 * 一致（快照=实时）时才按 {@code qty_actual - qty_book} 过账。
 *
 * <p><b>授权先于判重（契约 §3，硬约束）</b>：create 经仓库真实站点、apply 经单头仓库真实站点、
 * resultOf 经幂等行 {@code warehouse_id} 对应仓库真实站点 {@code requireSite}。撤销授权/跨站者即使
 * 持合法 requestId 也只能得到 403，拿不到重放结果。本实现把 {@code requireSite} 放在仓库
 * 「ACTIVE」判定之前（报告裁决点 E），保证越权恒 403、不因仓库停用泄漏 400。
 *
 * <p><b>防伪造</b>：入参 record 无成本/单位价/批次成本字段（结构上不接收）；盘盈单位成本只取
 * 该 (sku, 仓库) 现有 OPEN 批次加权平均，<b>无 OPEN 批次或加权成本 ≤ 0 时整单拒绝</b>
 * （{@link ZeroCostGainException}，契约 v1.2 §6.3），不接受客户端成本、不让零成本批次进入 FIFO。
 */
@Service
public class StocktakeService
{
    static final String ACTION_CREATE = "CREATE";
    static final String ACTION_APPLY = "APPLY";

    private static final String BIZ_TYPE = "STOCKTAKE";
    private static final String STOCKTAKE_NO_PREFIX = "ST:";
    private static final String ADJUST_BATCH_PREFIX = "B:STK:";
    private static final String MOVE_NO_PREFIX = "SM:";
    private static final String CONSUMPTION_NO_PREFIX = "SC:";
    private static final String STOCKTAKE_EVENT_PREFIX = "STK:";
    private static final String REF_DOC_TYPE = "STOCKTAKE";

    /** create 幂等表 {@code stocktake_id} 列为 NOT NULL（建单时真实 id 尚未生成），PENDING 期用占位值。 */
    private static final long PENDING_STOCKTAKE_ID = 0L;
    private static final int NO_ATTEMPTS = 3;

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_APPLIED = "APPLIED";

    private static final String BATCH_OPEN = "OPEN";
    private static final String BATCH_CLOSED = "CLOSED";

    private static final String CHANGE_GAIN = "STOCKTAKE_GAIN";
    private static final String CHANGE_LOSS = "STOCKTAKE_LOSS";
    private static final String CONSUME_MOVE_TYPE = "STOCKTAKE";
    private static final String BOOK_CURRENCY = "CNY";

    /** result_json 为 JSON 列：用树方式写入/读取，避免 JOIN 项目透传额外键时反序列化失败。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private static final String HEAD_SELECT = "SELECT s.id,s.stocktake_no,s.warehouse_id,s.period,s.status,"
            + "w.site_code FROM inv_stocktake s JOIN md_warehouse w ON w.id=s.warehouse_id";

    private static final RowMapper<StocktakeRow> HEAD_MAPPER = (rs, i) -> new StocktakeRow(
            rs.getLong("id"),
            rs.getString("stocktake_no"),
            rs.getLong("warehouse_id"),
            rs.getString("site_code"),
            rs.getString("period"),
            rs.getString("status"));

    private static final RowMapper<StocktakeItemRow> ITEM_MAPPER = (rs, i) -> new StocktakeItemRow(
            rs.getLong("id"),
            rs.getLong("sku_id"),
            rs.getString("batch_no"),
            rs.getInt("qty_book"),
            rs.getInt("qty_actual"),
            rs.getInt("qty_diff"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;

    public StocktakeService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ create

    /**
     * 创建盘点单（契约 §5）：只写 DRAFT 单 + 明细快照，不改库存、不写流水/消耗。
     *
     * <p>顺序：授权（仓库存在/站点 {@code requireSite}/ACTIVE）→ 行校验 → 指纹 → DONE 重放 →
     * PENDING 占位 → 写单/明细 → 审计 → 回填 id + DONE。异常整事务回滚（含 PENDING 占位），
     * 同 requestId 可安全重试；重放零写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public StocktakeResult create(StocktakeInput in)
    {
        if (in == null) throw new IllegalArgumentException("盘点建单请求不能为空");
        StocktakeRules.requiredId("warehouseId", in.warehouseId());
        String requestId = StocktakeRules.requireRequestId(in.requestId());

        Long operatorId = guard.requireCurrentUserId();

        // §3.1 授权经仓库归属：站点先于启用状态（越权/跨站恒 403），再判 ACTIVE。
        WarehouseRef warehouse = loadWarehouse(in.warehouseId());
        guard.requireSite(warehouse.siteCode());
        requireActive(warehouse, "建单");

        List<StocktakeLineInput> lines = StocktakeRules.requireCreateLines(in.lines());
        String period = StocktakeRules.requirePeriod(in.period());

        // §4 指纹先于重放判定（授权 > 指纹 > DONE 重放 > PENDING 占位）。
        String fingerprint = StocktakeRules.createFingerprint(in.warehouseId(), lines);
        StocktakeResult replayed = replayDone(requestId, ACTION_CREATE, in.warehouseId(), fingerprint);
        if (replayed != null) return replayed;

        replayed = claimRequest(requestId, ACTION_CREATE, PENDING_STOCKTAKE_ID, in.warehouseId(), operatorId,
                fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = StocktakeRules.now();
        String resolvedPeriod = period == null ? StocktakeRules.period(now) : period;
        long stocktakeId = insertStocktake(warehouse.siteCode(), in.warehouseId(), resolvedPeriod, now);
        String stocktakeNo = jdbc.queryForObject("SELECT stocktake_no FROM inv_stocktake WHERE id=?",
                String.class, stocktakeId);

        // §5 建单只写快照：qty_book = 建单时点台账可用量，是差异计算与过账的唯一合法基线（apply 不得覆盖）；
        // qty_diff = qty_actual - qty_book 同为真值。
        Map<Long, String> itemEventIds = new LinkedHashMap<>();
        int totalBook = 0;
        int totalActual = 0;
        for (StocktakeLineInput line : lines)
        {
            int qtyBook = snapshotBook(line.skuId(), in.warehouseId(), warehouse.siteCode());
            int qtyDiff = line.qtyActual() - qtyBook;
            long itemId = insertItem(stocktakeId, line, qtyBook, qtyDiff, now);
            itemEventIds.put(itemId, line.countEventId());
            totalBook += qtyBook;
            totalActual += line.qtyActual();
        }

        audit.record(BIZ_TYPE, String.valueOf(stocktakeId), ACTION_CREATE,
                "stocktakeNo=" + stocktakeNo + ",warehouseId=" + in.warehouseId() + ",site=" + warehouse.siteCode()
                        + ",period=" + resolvedPeriod + ",lines=" + lines.size()
                        + ",qtyBook=" + totalBook + ",qtyActual=" + totalActual,
                operatorId);

        StocktakeResult result = new StocktakeResult(stocktakeId, stocktakeNo, STATUS_DRAFT, false);
        markCreateDone(requestId, stocktakeId, result, itemEventIds);
        return result;
    }

    // ------------------------------------------------------------------- apply

    /**
     * 应用盘点（契约 §6）：实时台账基线 + 盘亏 FIFO 逐批消耗 + 盘盈 ADJUSTMENT 承接成本。
     *
     * <p>顺序：授权（锁单 + 头仓库真实站点 {@code requireSite} / ACTIVE）→ 指纹 → DONE 重放 →
     * 状态闸门（仅 DRAFT）→ PENDING 占位 → 整单闸门（快照 == 实时、盘盈成本可追溯）→ 逐行过账 →
     * 单 APPLIED → 审计 APPLY → DONE。状态闸门置于 DONE 重放之后（报告裁决点 D），否则已 APPLIED 单无法幂等重放。
     */
    @Transactional(rollbackFor = Exception.class)
    public StocktakeResult apply(StocktakeApplyInput in)
    {
        if (in == null) throw new IllegalArgumentException("盘点应用请求不能为空");
        StocktakeRules.requiredId("stocktakeId", in.stocktakeId());
        String requestId = StocktakeRules.requireRequestId(in.requestId());

        Long operatorId = guard.requireCurrentUserId();

        // §3.2 锁盘点单（FOR UPDATE，串行化同单应用）→ 头仓库归属授权 → ACTIVE。
        StocktakeHead head = lockStocktake(in.stocktakeId());
        WarehouseRef warehouse = loadWarehouse(head.warehouseId());
        guard.requireSite(warehouse.siteCode());
        requireActive(warehouse, "应用盘点");

        String fingerprint = StocktakeRules.applyFingerprint(in.stocktakeId());
        StocktakeResult replayed = replayDone(requestId, ACTION_APPLY, head.warehouseId(), fingerprint);
        if (replayed != null) return replayed;

        if (!STATUS_DRAFT.equals(head.status()))
            throw new IllegalArgumentException("当前盘点单状态 " + head.status()
                    + " 不可应用（仅 DRAFT 可应用；已 APPLIED 请用 result 查结果）: stocktakeId=" + in.stocktakeId());

        replayed = claimRequest(requestId, ACTION_APPLY, head.id(), head.warehouseId(), operatorId, fingerprint);
        if (replayed != null) return replayed;

        List<StocktakeItem> items = stocktakeItems(head.id());
        if (items.isEmpty())
            throw new IllegalArgumentException("盘点单没有可应用的明细行: stocktakeId=" + head.id());
        requireUniqueSku(items, head);

        // §6.1 / §6.3 整单闸门：逐行 FOR UPDATE 锁 item + 台账，先校验「快照 == 实时」与「盘盈成本可追溯」，
        // 任一不合格即整单拒绝（此时尚未写入任何台账/流水/批次/消耗/状态；PENDING 占位随异常整单回滚）。
        for (StocktakeItem row : items)
        {
            StocktakeItem item = lockItem(head.id(), row.id());
            LedgerState ledger = lockLedger(item.skuId(), head.warehouseId());
            if (ledger != null) requireLedgerSite(ledger.siteCode(), warehouse.siteCode());
            requireSnapshotMatches(head, item, ledger == null ? 0 : ledger.available());

            int gain = item.qtyActual() - item.qtyBook();
            if (gain > 0)
                requireWeightedOpenCost(head, item.skuId(), head.warehouseId(), warehouse.siteCode(), gain);
        }

        Map<Long, String> itemEventIds = loadItemEventIds(head.id());
        LocalDateTime now = StocktakeRules.now();
        String period = head.period() == null || head.period().isBlank()
                ? StocktakeRules.period(now) : head.period();

        int totalBook = 0;
        int totalActual = 0;
        int totalGain = 0;
        int totalLoss = 0;
        int gainBatches = 0;

        for (StocktakeItem row : items)
        {
            StocktakeItem item = lockItem(head.id(), row.id());
            LedgerState ledger = lockLedger(item.skuId(), head.warehouseId());
            if (ledger != null) requireLedgerSite(ledger.siteCode(), warehouse.siteCode());

            // §6.1 差异基线 = 建单快照（上面已校验快照 == 实时台账），不再用实时值回算、也不覆盖 qty_book/qty_diff。
            int qtyBook = item.qtyBook();
            int qtyDiff = item.qtyActual() - qtyBook;

            totalBook += qtyBook;
            totalActual += item.qtyActual();

            if (qtyDiff < 0)
            {
                int loss = -qtyDiff;
                postLoss(head, warehouse, item, ledger, loss, requireEventId(itemEventIds, item), period,
                        operatorId, now);
                totalLoss += loss;
            }
            else if (qtyDiff > 0)
            {
                WeightedCost cost = requireWeightedOpenCost(head, item.skuId(), head.warehouseId(),
                        warehouse.siteCode(), qtyDiff);
                long batchId = insertAdjustmentBatch(item.skuId(), head.warehouseId(), warehouse.siteCode(),
                        qtyDiff, cost, now);
                increaseLedger(item.skuId(), head.warehouseId(), warehouse.siteCode(), ledger, qtyDiff);
                insertMove(item.skuId(), head.warehouseId(), batchId, qtyDiff, CHANGE_GAIN, head.stocktakeNo(),
                        operatorId, now);
                totalGain += qtyDiff;
                gainBatches++;
            }
        }

        int headUpdated = jdbc.update("UPDATE inv_stocktake SET status=? WHERE id=? AND status=?",
                STATUS_APPLIED, head.id(), STATUS_DRAFT);
        if (headUpdated != 1)
            throw new IllegalStateException("盘点单状态并发更新失败，事务整体回滚: stocktakeId=" + head.id());

        audit.record(BIZ_TYPE, String.valueOf(head.id()), ACTION_APPLY,
                "stocktakeNo=" + head.stocktakeNo() + ",warehouseId=" + head.warehouseId() + ",site="
                        + warehouse.siteCode() + ",period=" + period + ",lines=" + items.size()
                        + ",qtyBook=" + totalBook + ",qtyActual=" + totalActual
                        + ",gain=" + totalGain + ",loss=" + totalLoss + ",gainBatches=" + gainBatches,
                operatorId);

        StocktakeResult result = new StocktakeResult(head.id(), head.stocktakeNo(), STATUS_APPLIED, false);
        markDone(requestId, result);
        return result;
    }

    // ---------------------------------------------------------------- read-only

    /**
     * 只读结果查询（契约 §3.3）：按 requestId 取幂等行 → 用幂等行 {@code warehouse_id} 对应仓库的
     * <b>真实</b>站点 {@code requireSite} → 才返回结果（replayed 恒 true）。无权 403、非法/不存在/
     * 未完成（PENDING）返回 null。不写任何表。
     */
    @Transactional(readOnly = true)
    public StocktakeResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = StocktakeRules.requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<RequestRow> rows = requestRows(rid, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);

        WarehouseRef warehouse = loadWarehouseOrNull(row.warehouseId());
        if (warehouse == null) return null;
        guard.requireSite(warehouse.siteCode());

        if (!"DONE".equals(row.status())) return null;
        StocktakeHead head = loadStocktakeOrNull(row.stocktakeId());
        if (head == null) return null;
        return new StocktakeResult(head.id(), head.stocktakeNo(), head.status(), true);
    }

    /** 盘点单列表：按授权站点集合过滤（传 siteCode 先 requireSite），total 为同条件真实总数。 */
    public PageResult<StocktakeRow> list(String siteCode, int offset, int limit)
    {
        StocktakeRules.offset(offset);
        int size = StocktakeRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE w.site_code IN (" + StocktakeRules.placeholders(gate.size()) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_stocktake s "
                + "JOIN md_warehouse w ON w.id=s.warehouse_id" + where, Long.class, gate.toArray());
        List<Object> args = new ArrayList<>(gate);
        args.add(size);
        args.add(offset);
        List<StocktakeRow> rows = jdbc.query(HEAD_SELECT + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, args.toArray());
        return new PageResult<>(rows, total);
    }

    /** 盘点单详情（带授权：以单所属仓库真实站点 requireSite）。 */
    @Transactional(readOnly = true)
    public StocktakeDetail get(Long stocktakeId)
    {
        StocktakeRules.requiredId("stocktakeId", stocktakeId);
        List<StocktakeRow> heads = jdbc.query(HEAD_SELECT + " WHERE s.id=?", HEAD_MAPPER, stocktakeId);
        if (heads.isEmpty()) throw new IllegalArgumentException("盘点单不存在: id=" + stocktakeId);
        StocktakeRow head = heads.get(0);
        guard.requireSite(head.siteCode());

        List<StocktakeItemRow> items = jdbc.query("SELECT id,sku_id,batch_no,qty_book,qty_actual,qty_diff "
                + "FROM inv_stocktake_item WHERE stocktake_id=? ORDER BY id", ITEM_MAPPER, stocktakeId);
        return new StocktakeDetail(head, items);
    }

    // ---------------------------------------------------------------- 过账：盘亏

    /**
     * 盘亏（契约 §6.2）：FIFO 从 OPEN 批次逐批扣 {@code qty_remaining}（扣到 0 关批），每批写一条
     * {@code inv_batch_consumption}（{@code biz_event_key=STK:<itemId>:<countEventId>}、{@code move_type=STOCKTAKE}、
     * {@code sign=1}、{@code amount=qty*unit_cost} 4 位）；台账 available −loss；写 {@code STOCKTAKE_LOSS} 流水。
     * 先校验累计批次剩余能否覆盖 loss（不足 400，不透支台账、不改任何批次）。
     */
    private void postLoss(StocktakeHead head, WarehouseRef warehouse, StocktakeItem item, LedgerState ledger,
                          int loss, String countEventId, String period, Long operatorId, LocalDateTime now)
    {
        List<BatchState> batches = lockOpenBatches(item.skuId(), head.warehouseId());
        int coverage = 0;
        for (BatchState batch : batches)
            coverage += batch.qtyRemaining();
        if (coverage < loss)
            throw new IllegalArgumentException("可用批次不足以覆盖盘亏: stocktakeId=" + head.id()
                    + "，skuId=" + item.skuId() + "，需扣=" + loss + "，OPEN 批次剩余=" + coverage);
        if (ledger == null)
            throw new IllegalStateException("台账行缺失，无法扣减盘亏: stocktakeId=" + head.id()
                    + "，skuId=" + item.skuId());

        String bizEventKey = STOCKTAKE_EVENT_PREFIX + item.id() + ":" + countEventId;
        int need = loss;
        for (BatchState batch : batches)
        {
            if (need == 0) break;
            int take = Math.min(batch.qtyRemaining(), need);
            int remaining = batch.qtyRemaining() - take;
            // CAS 显式比较原剩余（避免 MySQL/H2 对 SET 表达式求值差异），扣到 0 关批。
            int deducted = jdbc.update("UPDATE inv_batch SET qty_remaining=?, status=? WHERE id=? AND qty_remaining=?",
                    remaining, remaining == 0 ? BATCH_CLOSED : BATCH_OPEN, batch.id(), batch.qtyRemaining());
            if (deducted != 1)
                throw new IllegalStateException("批次并发扣减失败，事务整体回滚: batchNo=" + batch.batchNo());
            insertConsumption(bizEventKey, countEventId, item, batch, take, period, now);
            need -= take;
        }
        if (need != 0)
            throw new IllegalStateException("盘亏批次扣减不完整，事务整体回滚: stocktakeId=" + head.id()
                    + "，剩余未扣=" + need);

        decreaseLedger(ledger, loss, item.skuId());
        // 盘亏可能跨多批，流水 batch_id 不指向单一批次（NULL），批次级明细见 inv_batch_consumption。
        insertMove(item.skuId(), head.warehouseId(), null, -loss, CHANGE_LOSS, head.stocktakeNo(), operatorId, now);
    }

    /** OPEN 批次按 FIFO（{@code received_at, id}）加锁读取；只取还有剩余的批次。 */
    private List<BatchState> lockOpenBatches(long skuId, long warehouseId)
    {
        return jdbc.query("SELECT id,batch_no,qty_remaining,unit_cost FROM inv_batch "
                        + "WHERE sku_id=? AND warehouse_id=? AND status=? AND qty_remaining>0 "
                        + "ORDER BY received_at, id FOR UPDATE",
                (rs, i) -> new BatchState(rs.getLong("id"), rs.getString("batch_no"),
                        rs.getInt("qty_remaining"), rs.getBigDecimal("unit_cost")),
                skuId, warehouseId, BATCH_OPEN);
    }

    /**
     * 批次消耗流水（契约 §6.2）：move_type=STOCKTAKE、sign=+1、amount=qty*unit_cost（取批次成本，不重估）。
     * 唯一键 {@code (biz_event_key,batch_id)} 保证同事件跨批多行合法、同批重传被拦（撞键即 400，整单回滚）。
     */
    private void insertConsumption(String bizEventKey, String countEventId, StocktakeItem item, BatchState batch,
                                   int take, String period, LocalDateTime now)
    {
        BigDecimal unitCost = StocktakeRules.money(batch.unitCost());
        BigDecimal amount = StocktakeRules.money(unitCost.multiply(BigDecimal.valueOf(take)));
        try
        {
            jdbc.update("INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                            + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                            + "period,cross_period,raw_payload,created_at) "
                            + "VALUES(?,?,?,?,NULL,?,?,?,?,?,?,1,?,0,NULL,?)",
                    CONSUMPTION_NO_PREFIX + UUID.randomUUID(), bizEventKey, countEventId, batch.id(),
                    item.skuId(), take, unitCost, amount, now, CONSUME_MOVE_TYPE, period, now);
        }
        catch (DuplicateKeyException duplicate)
        {
            throw new IllegalArgumentException("盘点事件已过账，禁止重复记账: bizEventKey=" + bizEventKey
                    + "，batchNo=" + batch.batchNo());
        }
    }

    // ---------------------------------------------------------------- 过账：盘盈

    /**
     * 盘盈承接成本（契约 v1.2 §6.3）：该 (sku, 仓库) 现有 OPEN 批次的加权平均 4 位成本。
     *
     * <p><b>零成本闸门</b>：无任何 OPEN 批次、或加权平均成本 ≤ 0 → 抛 {@link ZeroCostGainException} 整单拒绝，
     * 绝不让零成本批次进入 FIFO（否则后续销售 COGS=0、毛利虚高，且无人确认过该估值）。OPEN 批次币种不一致时
     * 仍拒绝（不臆测折算，v1.1 裁决点 F 保留）。本批结构上不接受客户端传成本。
     */
    private WeightedCost requireWeightedOpenCost(StocktakeHead head, long skuId, long warehouseId, String siteCode,
                                                 int gain)
    {
        List<OpenCost> rows = jdbc.query("SELECT currency,SUM(qty_remaining) AS qty,SUM(qty_remaining*unit_cost) AS cost "
                        + "FROM inv_batch WHERE sku_id=? AND warehouse_id=? AND status=? AND qty_remaining>0 "
                        + "GROUP BY currency",
                (rs, i) -> new OpenCost(rs.getString("currency"), rs.getInt("qty"), rs.getBigDecimal("cost")),
                skuId, warehouseId, BATCH_OPEN);
        if (rows.isEmpty()) throw zeroCostGain(head, skuId, warehouseId, siteCode, gain, "无 OPEN 批次");
        if (rows.size() > 1)
        {
            List<String> currencies = new ArrayList<>();
            for (OpenCost row : rows) currencies.add(String.valueOf(row.currency()));
            throw new IllegalArgumentException("该 SKU+仓库的 OPEN 批次存在多种币种 " + currencies
                    + "，本轮不臆测折算，拒绝盘盈承接成本: skuId=" + skuId + "，warehouseId=" + warehouseId);
        }
        OpenCost row = rows.get(0);
        if (row.qty() <= 0 || row.cost() == null)
            throw zeroCostGain(head, skuId, warehouseId, siteCode, gain, "OPEN 批次加权数量为 0 或成本缺失");

        BigDecimal average = StocktakeRules.money(row.cost().divide(BigDecimal.valueOf(row.qty()), 8,
                RoundingMode.HALF_UP));
        if (average.compareTo(BigDecimal.ZERO) <= 0)
            throw zeroCostGain(head, skuId, warehouseId, siteCode, gain, "OPEN 批次加权平均成本为 0");

        String currency = row.currency() == null || row.currency().isBlank() ? BOOK_CURRENCY : row.currency();
        return new WeightedCost(average, currency, BOOK_CURRENCY.equals(currency) ? BigDecimal.ONE : null);
    }

    private static ZeroCostGainException zeroCostGain(StocktakeHead head, long skuId, long warehouseId, String siteCode,
                                                      int gain, String reason)
    {
        return new ZeroCostGainException("盘盈无可追溯成本，拒绝应用（整单回滚）: stocktakeId=" + head.id()
                + "，stocktakeNo=" + head.stocktakeNo() + "，skuId=" + skuId + "，warehouseId=" + warehouseId
                + "，site=" + siteCode + "，gain=" + gain + "，原因=" + reason
                + "；无可追溯成本，请先补录采购价或由业务确认估值");
    }

    /**
     * 账面快照比对闸门（契约 v1.2 §6.1）：实时台账可用量必须等于建单快照 {@code qty_book}，否则说明建单后
     * 发生了真实出库/入库/调拨（实物与账面同步变动），旧实盘已失配，整单拒绝并要求重新盘点。
     */
    private static void requireSnapshotMatches(StocktakeHead head, StocktakeItem item, int currentBook)
    {
        if (currentBook != item.qtyBook())
            throw new StaleStocktakeException("盘点账面快照与实时台账不一致，拒绝应用: stocktakeId=" + head.id()
                    + "，stocktakeNo=" + head.stocktakeNo() + "，skuId=" + item.skuId()
                    + "，snapshotBook=" + item.qtyBook() + "，currentBook=" + currentBook
                    + "；请重新盘点（按当前账面重新建单后再应用）");
    }

    /**
     * 盘盈新建 ADJUSTMENT 批次（契约 §6.3）：{@code B:STK:<SITE>:<UUID8>}、{@code qty_in=qty_remaining=gain}、
     * {@code source_type=ADJUSTMENT}、{@code received_at=业务时刻}（FIFO 排到队尾）、{@code status=OPEN}。
     * <b>不写 inv_batch_consumption</b>（盘盈不是消耗）。
     */
    private long insertAdjustmentBatch(long skuId, long warehouseId, String siteCode, int gain, WeightedCost cost,
                                       LocalDateTime now)
    {
        for (int attempt = 0; attempt < NO_ATTEMPTS; attempt++)
        {
            String batchNo = ADJUST_BATCH_PREFIX + siteCode + ":"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            KeyHolder keys = new GeneratedKeyHolder();
            try
            {
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,"
                                    + "received_at,qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,"
                                    + "parent_batch_id,split_seq,split_reason,transfer_no,status) "
                                    + "VALUES(?,?,NULL,?,?,?,?,?,?,?,?,'ADJUSTMENT',NULL,0,NULL,NULL,'OPEN')",
                            new String[]{"id"});
                    ps.setString(1, batchNo);
                    ps.setLong(2, skuId);
                    ps.setLong(3, warehouseId);
                    ps.setString(4, siteCode);
                    ps.setObject(5, now);
                    ps.setInt(6, gain);
                    ps.setInt(7, gain);
                    ps.setBigDecimal(8, cost.unitCost());
                    ps.setString(9, cost.currency());
                    ps.setObject(10, cost.fxRate());
                    return ps;
                }, keys);
            }
            catch (DuplicateKeyException duplicate)
            {
                continue;
            }
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("盘盈 ADJUSTMENT 批次未返回主键");
            return key.longValue();
        }
        throw new IllegalStateException("盘盈批次号生成冲突，重试 " + NO_ATTEMPTS + " 次仍失败");
    }

    // ---------------------------------------------------------------- 台账

    /** 建单快照：读当前 (sku, 仓库) 台账可用量（只读、不加锁；写库为建单时点值，apply 只比对不覆盖）。 */
    private int snapshotBook(long skuId, long warehouseId, String siteCode)
    {
        List<LedgerState> rows = jdbc.query("SELECT id,site_code,qty_available FROM inv_stock_ledger "
                        + "WHERE sku_id=? AND warehouse_id=?",
                (rs, i) -> new LedgerState(rs.getLong("id"), rs.getString("site_code"), rs.getInt("qty_available")),
                skuId, warehouseId);
        if (rows.isEmpty()) return 0;
        LedgerState ledger = rows.get(0);
        requireLedgerSite(ledger.siteCode(), siteCode);
        return ledger.available();
    }

    private LedgerState lockLedger(long skuId, long warehouseId)
    {
        List<LedgerState> rows = jdbc.query("SELECT id,site_code,qty_available FROM inv_stock_ledger "
                        + "WHERE sku_id=? AND warehouse_id=? FOR UPDATE",
                (rs, i) -> new LedgerState(rs.getLong("id"), rs.getString("site_code"), rs.getInt("qty_available")),
                skuId, warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 盘亏扣台账：行已 FOR UPDATE，按绝对新值写入（CHECK 保证不为负；loss ≤ 账面由 qtyActual ≥ 0 保证）。 */
    private void decreaseLedger(LedgerState ledger, int loss, long skuId)
    {
        int next = ledger.available() - loss;
        if (next < 0)
            throw new IllegalArgumentException("台账可用量不足，拒绝盘亏透支: skuId=" + skuId
                    + "，当前可用=" + ledger.available() + "，需扣=" + loss);
        jdbc.update("UPDATE inv_stock_ledger SET qty_available=? WHERE id=?", next, ledger.id());
    }

    /** 盘盈加台账：存在则按绝对新值累加；不存在则插入（唯一键 (sku,仓库) 兜并发，撞键重读再加）。 */
    private void increaseLedger(long skuId, long warehouseId, String siteCode, LedgerState ledger, int gain)
    {
        if (ledger != null)
        {
            requireLedgerSite(ledger.siteCode(), siteCode);
            jdbc.update("UPDATE inv_stock_ledger SET qty_available=? WHERE id=?", ledger.available() + gain, ledger.id());
            return;
        }
        try
        {
            jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                    + "qty_in_transit,qty_reserved,qty_unsellable) VALUES(?,?,?,?,0,0,0)",
                    skuId, warehouseId, siteCode, gain);
        }
        catch (DuplicateKeyException duplicate)
        {
            LedgerState raced = lockLedger(skuId, warehouseId);
            if (raced == null)
                throw new IllegalStateException("台账并发插入后读不到行: skuId=" + skuId + "，warehouseId=" + warehouseId);
            requireLedgerSite(raced.siteCode(), siteCode);
            jdbc.update("UPDATE inv_stock_ledger SET qty_available=? WHERE id=?", raced.available() + gain, raced.id());
        }
    }

    private static void requireLedgerSite(String existing, String warehouseSite)
    {
        if (!warehouseSite.equals(existing))
            throw new IllegalArgumentException("同一 SKU+仓库的台账站点已为 " + existing + "，与仓库站点 "
                    + warehouseSite + " 不一致，拒绝跨站复用台账");
    }

    private void insertMove(long skuId, long warehouseId, Long batchId, int qtyDelta, String changeType,
                            String stocktakeNo, Long operatorId, LocalDateTime now)
    {
        jdbc.update("INSERT INTO inv_stock_move(move_no,sku_id,warehouse_id,batch_id,change_type,qty_delta,"
                        + "ref_doc_type,ref_doc_no,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                MOVE_NO_PREFIX + UUID.randomUUID(), skuId, warehouseId, batchId, changeType, qtyDelta,
                REF_DOC_TYPE, stocktakeNo, operatorId, now);
    }

    // ---------------------------------------------------------------- 幂等

    /** 命中 DONE 且动作/仓库/内容一致 → 重放（replayed=true）；无行/PENDING → null；冲突 → 400。 */
    private StocktakeResult replayDone(String requestId, String action, long warehouseId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameRequest(row, action, warehouseId, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return readResult(row.resultJson());
    }

    /**
     * 申请 requestId 处理权：返回 null 表示本次是新请求（已占位 PENDING）；非 null 表示命中已有 DONE 请求。
     * CREATE 时真实 stocktakeId 尚未生成，{@code stocktake_id} 列 NOT NULL，占位 0，DONE 时回填；
     * APPLY 时 id 已知，直接写真实 id。
     */
    private StocktakeResult claimRequest(String requestId, String action, long stocktakeIdForPending,
                                         long warehouseId, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_stocktake_request"
                    + "(request_id,stocktake_id,warehouse_id,operator_id,action,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,?,?,'PENDING')",
                    requestId, stocktakeIdForPending, warehouseId, operatorId, action, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 唯一键（request_id）冲突：同键已存在（处理中或已完成）。当前读避免 RR 旧快照。
            List<RequestRow> rows = requestRows(requestId, true);
            if (rows.isEmpty())
                throw new IllegalArgumentException("同一盘点请求正在处理中，请稍后重试");
            RequestRow row = rows.get(0);
            requireSameRequest(row, action, warehouseId, fingerprint);
            if (!"DONE".equals(row.status()))
                throw new IllegalArgumentException("同一盘点请求正在处理中，请稍后重试");
            return readResult(row.resultJson());
        }
    }

    /** 同键必须同动作、同仓库、同内容；动作进指纹，CREATE/APPLY 互不误伤（冲突即 400）。 */
    private static void requireSameRequest(RequestRow row, String action, long warehouseId, String fingerprint)
    {
        if (row.action() == null || !row.action().equals(action))
            throw new IllegalArgumentException("requestId 已用于其他盘点动作（" + row.action()
                    + "），同一幂等键不能跨 CREATE/APPLY 复用");
        if (row.warehouseId() != warehouseId)
            throw new IllegalArgumentException("requestId 已用于其他仓库的盘点请求");
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的盘点内容与原请求不一致");
    }

    private List<RequestRow> requestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT stocktake_id,warehouse_id,action,content_fingerprint,status,result_json "
                        + "FROM noon_m4_stocktake_request WHERE request_id=?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getLong("stocktake_id"), rs.getLong("warehouse_id"),
                        rs.getString("action"), rs.getString("content_fingerprint"), rs.getString("status"),
                        rs.getString("result_json")), requestId);
    }

    private void markDone(String requestId, StocktakeResult result)
    {
        int updated = jdbc.update("UPDATE noon_m4_stocktake_request SET status='DONE', result_json=? "
                + "WHERE request_id=?", toJson(result, null), requestId);
        if (updated == 0) throw new IllegalStateException("盘点幂等记录状态更新失败: requestId=" + requestId);
    }

    /**
     * CREATE 的 DONE 回填：除 StocktakeResult 外，把「itemId → countEventId」一并存进 result_json 的
     * {@code itemEventIds} 键。apply 只接收 stocktakeId（契约 §2 签名），没有行入参，而
     * {@code inv_stocktake_item} 无事件号列、迁移不在本包 owner 范围，故用幂等结果快照作为
     * 点数事件号的<b>唯一</b>持久通道（报告裁决点 B），保证 {@code STK:<itemId>:<countEventId>} 稳定可复算。
     */
    private void markCreateDone(String requestId, long stocktakeId, StocktakeResult result,
                                Map<Long, String> itemEventIds)
    {
        int updated = jdbc.update("UPDATE noon_m4_stocktake_request SET stocktake_id=?, status='DONE', result_json=? "
                + "WHERE request_id=?", stocktakeId, toJson(result, itemEventIds), requestId);
        if (updated == 0) throw new IllegalStateException("盘点建单幂等记录状态更新失败: requestId=" + requestId);
    }

    private static String toJson(StocktakeResult result, Map<Long, String> itemEventIds)
    {
        ObjectNode node = RESULT_JSON.createObjectNode();
        node.put("stocktakeId", result.stocktakeId());
        node.put("stocktakeNo", result.stocktakeNo());
        node.put("status", result.status());
        node.put("replayed", result.replayed());
        if (itemEventIds != null && !itemEventIds.isEmpty())
        {
            ObjectNode events = node.putObject("itemEventIds");
            itemEventIds.forEach((itemId, eventId) -> events.put(String.valueOf(itemId), eventId));
        }
        try
        {
            return RESULT_JSON.writeValueAsString(node);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("盘点结果序列化失败", e);
        }
    }

    /** 重放结果固定 replayed=true（首次结果缓存中也可能是 false，语义由调用场景决定）。 */
    private static StocktakeResult readResult(String resultJson)
    {
        if (resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("盘点幂等记录缺少结果快照");
        try
        {
            JsonNode root = RESULT_JSON.readTree(resultJson);
            if (root == null || root.isNull()) throw new IllegalStateException("盘点幂等结果反序列化为空");
            return new StocktakeResult(root.path("stocktakeId").asLong(), root.path("stocktakeNo").asText(null),
                    root.path("status").asText(null), true);
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("盘点幂等结果反序列化失败", e);
        }
    }

    /** 从建单幂等快照恢复 itemId → countEventId（apply 无行入参，事件键必须可稳定复算）。 */
    private Map<Long, String> loadItemEventIds(long stocktakeId)
    {
        List<String> snapshots = jdbc.query("SELECT result_json FROM noon_m4_stocktake_request "
                        + "WHERE stocktake_id=? AND action=? AND status='DONE' ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> rs.getString("result_json"), stocktakeId, ACTION_CREATE);
        if (snapshots.isEmpty() || snapshots.get(0) == null || snapshots.get(0).isBlank())
            throw new IllegalArgumentException("盘点单缺少建单结果快照，无法恢复点数事件号: stocktakeId=" + stocktakeId);
        Map<Long, String> events = new LinkedHashMap<>();
        try
        {
            JsonNode root = RESULT_JSON.readTree(snapshots.get(0));
            JsonNode node = root == null ? null : root.path("itemEventIds");
            if (node != null && node.isObject())
                node.fieldNames().forEachRemaining(field -> events.put(Long.valueOf(field), node.get(field).asText()));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("盘点建单结果快照解析失败: stocktakeId=" + stocktakeId, e);
        }
        return events;
    }

    private static String requireEventId(Map<Long, String> itemEventIds, StocktakeItem item)
    {
        String eventId = itemEventIds.get(item.id());
        if (eventId == null || eventId.isBlank())
            throw new IllegalArgumentException("盘点行缺少点数事件号，无法生成批次消耗幂等键: itemId=" + item.id());
        return eventId;
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 插盘点单头（契约 §5）：{@code ST:<SITE>:<UUID前8大写>}；{@code uk_stocktake_no} 兜重，撞键换新号重试。
     */
    private long insertStocktake(String siteCode, long warehouseId, String period, LocalDateTime now)
    {
        for (int attempt = 0; attempt < NO_ATTEMPTS; attempt++)
        {
            String stocktakeNo = STOCKTAKE_NO_PREFIX + siteCode + ":"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            KeyHolder keys = new GeneratedKeyHolder();
            try
            {
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO inv_stocktake(stocktake_no,warehouse_id,period,status,created_at) "
                                    + "VALUES(?,?,?,?,?)",
                            new String[]{"id"});
                    ps.setString(1, stocktakeNo);
                    ps.setLong(2, warehouseId);
                    ps.setString(3, period);
                    ps.setString(4, STATUS_DRAFT);
                    ps.setObject(5, now);
                    return ps;
                }, keys);
            }
            catch (DuplicateKeyException duplicate)
            {
                continue;
            }
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("盘点单未返回主键");
            return key.longValue();
        }
        throw new IllegalStateException("盘点单号生成冲突，重试 " + NO_ATTEMPTS + " 次仍失败");
    }

    private long insertItem(long stocktakeId, StocktakeLineInput line, int qtyBook, int qtyDiff, LocalDateTime now)
    {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inv_stocktake_item(stocktake_id,sku_id,batch_no,qty_book,qty_actual,qty_diff,"
                            + "created_at) VALUES(?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setLong(1, stocktakeId);
            ps.setLong(2, line.skuId());
            ps.setString(3, line.batchNo());
            ps.setInt(4, qtyBook);
            ps.setInt(5, line.qtyActual());
            ps.setInt(6, qtyDiff);
            ps.setObject(7, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("盘点明细行未返回主键");
        return key.longValue();
    }

    // ---------------------------------------------------------------- internals

    private WarehouseRef loadWarehouse(long warehouseId)
    {
        WarehouseRef warehouse = loadWarehouseOrNull(warehouseId);
        if (warehouse == null) throw new IllegalArgumentException("仓库不存在: id=" + warehouseId);
        if (warehouse.siteCode() == null || warehouse.siteCode().isBlank())
            throw new IllegalArgumentException("仓库未绑定站点，不能盘点: id=" + warehouseId);
        return warehouse;
    }

    private WarehouseRef loadWarehouseOrNull(long warehouseId)
    {
        List<WarehouseRef> rows = jdbc.query("SELECT id,site_code,status FROM md_warehouse WHERE id=?",
                (rs, i) -> new WarehouseRef(rs.getLong("id"), rs.getString("site_code"), rs.getString("status")),
                warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 仓库必须 ACTIVE（授权已在前，越权者拿不到这条 400）。 */
    private static void requireActive(WarehouseRef warehouse, String scene)
    {
        if (!"ACTIVE".equals(warehouse.status()))
            throw new IllegalArgumentException("仓库已停用，不能" + scene + ": id=" + warehouse.id()
                    + "，status=" + warehouse.status());
    }

    private StocktakeHead lockStocktake(long stocktakeId)
    {
        List<StocktakeHead> rows = jdbc.query("SELECT id,stocktake_no,warehouse_id,period,status "
                        + "FROM inv_stocktake WHERE id=? FOR UPDATE",
                StocktakeService::mapHead, stocktakeId);
        if (rows.isEmpty()) throw new IllegalArgumentException("盘点单不存在: id=" + stocktakeId);
        return rows.get(0);
    }

    private StocktakeHead loadStocktakeOrNull(long stocktakeId)
    {
        List<StocktakeHead> rows = jdbc.query("SELECT id,stocktake_no,warehouse_id,period,status "
                        + "FROM inv_stocktake WHERE id=?",
                StocktakeService::mapHead, stocktakeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static StocktakeHead mapHead(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException
    {
        return new StocktakeHead(rs.getLong("id"), rs.getString("stocktake_no"), rs.getLong("warehouse_id"),
                rs.getString("period"), rs.getString("status"));
    }

    private List<StocktakeItem> stocktakeItems(long stocktakeId)
    {
        return jdbc.query("SELECT id,sku_id,batch_no,qty_book,qty_actual FROM inv_stocktake_item "
                        + "WHERE stocktake_id=? ORDER BY id",
                (rs, i) -> new StocktakeItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("batch_no"),
                        rs.getInt("qty_book"), rs.getInt("qty_actual")), stocktakeId);
    }

    /** 行加锁后重读（qty_actual/qty_book 为建单快照，不可变，但以库内值为准）。 */
    private StocktakeItem lockItem(long stocktakeId, long itemId)
    {
        List<StocktakeItem> rows = jdbc.query("SELECT id,sku_id,batch_no,qty_book,qty_actual FROM inv_stocktake_item "
                        + "WHERE id=? AND stocktake_id=? FOR UPDATE",
                (rs, i) -> new StocktakeItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("batch_no"),
                        rs.getInt("qty_book"), rs.getInt("qty_actual")), itemId, stocktakeId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("盘点明细行不属于该盘点单: itemId=" + itemId
                    + "，stocktakeId=" + stocktakeId);
        return rows.get(0);
    }

    /** apply 防御：同一 SKU 多行会把同一批实物按全量账面重复计数，拒绝（报告裁决点 A）。 */
    private static void requireUniqueSku(List<StocktakeItem> items, StocktakeHead head)
    {
        Set<Long> seen = new HashSet<>();
        for (StocktakeItem item : items)
            if (!seen.add(item.skuId()))
                throw new IllegalArgumentException("盘点单存在重复 SKU 明细行，拒绝应用: stocktakeNo="
                        + head.stocktakeNo() + "，skuId=" + item.skuId());
    }

    /**
     * 站点闸门：指定 siteCode 先 requireSite 再单站过滤；未指定返回全部授权站点
     * （超管=SUPPORTED_SITES、普通用户=已授权，空集合表示零行，调用方不得省略 WHERE）。
     */
    private List<String> siteGate(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
            return List.of(guard.requireSite(StocktakeRules.siteCode("siteCode", siteCode)));
        return guard.allowedSiteCodes();
    }

    /** 仓库归属（授权唯一依据）。 */
    private record WarehouseRef(long id, String siteCode, String status)
    {
    }

    private record StocktakeHead(long id, String stocktakeNo, long warehouseId, String period, String status)
    {
    }

    private record StocktakeItem(long id, long skuId, String batchNo, int qtyBook, int qtyActual)
    {
    }

    private record BatchState(long id, String batchNo, int qtyRemaining, BigDecimal unitCost)
    {
    }

    private record LedgerState(long id, String siteCode, int available)
    {
    }

    private record OpenCost(String currency, int qty, BigDecimal cost)
    {
    }

    /** 盘盈承接成本：4 位单位成本 + 币种 + 汇率（CNY 显式 1，其他不臆测即 NULL）。 */
    private record WeightedCost(BigDecimal unitCost, String currency, BigDecimal fxRate)
    {
    }

    private record RequestRow(long stocktakeId, long warehouseId, String action, String contentFingerprint,
                              String status, String resultJson)
    {
    }
}
