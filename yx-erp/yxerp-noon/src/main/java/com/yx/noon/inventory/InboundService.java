package com.yx.noon.inventory;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.masterdata.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M4 库存第一轮「采购收货 → 入库 → FIFO 批次 / 四态台账」后端内核（契约 v1 §2/§3，owner：豆包）。
 *
 * <p><b>confirm 处理顺序</b>（与 M3 {@code ReceiptService} v2.1 完全对齐，优先级 授权 &gt; 指纹 &gt;
 * DONE 重放 &gt; 状态闸门）：
 * <ol>
 *   <li>入参校验（purOrderId / warehouseId / requestId / lines，qtyQualified &gt; 0）→ operatorId；</li>
 *   <li><b>站点授权经仓库归属</b>：读 {@code md_warehouse}，必须 ACTIVE；{@code guard.requireSite(仓库.site_code)}；
 *       请求体 siteCode 非空时必须与仓库站点一致（否则 400）。禁止用请求体自报站点；</li>
 *   <li>锁采购单并校验其真实 site_code 与仓库站点一致（跨站 400）；</li>
 *   <li><b>内容指纹</b> SHA-256(purOrderId|warehouseId|按 purOrderItemId 升序的 purOrderItemId:qtyQualified)；</li>
 *   <li>同键判重：DONE 且指纹一致 → 原样重放（replayed=true，不写任何业务/审计）；DONE 但指纹不同 → 400；
 *       PENDING → 400「处理中」；不存在 → INSERT PENDING 占位（撞唯一键回到判重）；</li>
 *   <li>采购单状态必须 ∈ PARTIAL_RECEIVED/RECEIVED/CLOSED（否则 400）；PARTIAL_RECEIVED 表示分批到货，</li>
 *       可先入库已收部分，安全性依赖下方逐行守恒而非整单收齐（契约 §9.1）；
 *   <li>逐行按 purOrderItemId 锁采购单行，从 {@code noon_m3_receipt} 取该来源行累计收货量与最近一条
 *       {@code unit_cost_cny} 作为权威成本，SKU 取 {@code pur_order_item.sku_id}；校验
 *       {@code 0 &lt; qtyQualified &lt;= 累计收货量 - 累计已入库量}（跨请求重复入库不允许虚增库存）；</li>
 *   <li>写入库单（CONFIRMED）/入库行 / FIFO 批次（OPEN、PURCHASE、parent NULL、split_seq 0、CNY、fx 1）/
 *       台账（按 sku+warehouse 累加 qty_available，站点冲突 400）/ 库存流水 / 审计；</li>
 *   <li>提交前写回 request 行 status='DONE' + result_json（replayed=false）。任何异常整事务回滚，
 *       PENDING 行一并消失，同键可安全重试。</li>
 * </ol>
 *
 * <p><b>成本守恒</b>：批次/入库行 unit_cost 只来自 M3 收货记录（unit_price × fx_rate，只含采购价），
 * 不接受客户端传入的 unitCost / skuId / batchNo（record 结构上不接收）。CNY 显式 currency='CNY'、fx_rate=1。
 *
 * <p>本轮只做原始采购批次；不摊头程/关税/包材（Q8），不合格量不进批次/台账（待 M5 待检流程）。
 */
@Service
public class InboundService
{
    private static final String INBOUND_NO_PREFIX = "IN:";
    private static final String BATCH_NO_PREFIX = "B:IN:";
    private static final String MOVE_NO_PREFIX = "IM:";

    /** result_json 只缓存 InboundResult（无时间字段），普通 ObjectMapper 即可原样重放。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private static final String INBOUND_HEAD_SELECT = "SELECT i.id,i.inbound_no,i.pur_order_id,i.warehouse_id,"
            + "i.status,w.site_code,(SELECT COUNT(*) FROM inv_inbound_item it WHERE it.inbound_id=i.id) AS item_count,"
            + "i.created_at FROM inv_inbound i JOIN md_warehouse w ON w.id=i.warehouse_id";

    private static final RowMapper<InboundRow> INBOUND_MAPPER = (rs, i) -> new InboundRow(
            rs.getLong("id"),
            rs.getString("inbound_no"),
            rs.getObject("pur_order_id", Long.class),
            rs.getObject("warehouse_id", Long.class),
            rs.getString("status"),
            rs.getString("site_code"),
            rs.getObject("item_count", Integer.class),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<InboundItemRow> ITEM_MAPPER = (rs, i) -> new InboundItemRow(
            rs.getLong("id"),
            rs.getObject("pur_order_item_id", Long.class),
            rs.getObject("sku_id", Long.class),
            rs.getString("batch_no"),
            rs.getObject("qty", Integer.class),
            rs.getObject("qty_qualified", Integer.class),
            rs.getBigDecimal("unit_cost"));

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, i) -> new BatchRow(
            rs.getLong("id"),
            rs.getString("batch_no"),
            rs.getObject("sku_id", Long.class),
            rs.getObject("warehouse_id", Long.class),
            rs.getString("site_code"),
            rs.getTimestamp("received_at").toLocalDateTime(),
            rs.getObject("qty_in", Integer.class),
            rs.getObject("qty_remaining", Integer.class),
            rs.getBigDecimal("unit_cost"),
            rs.getString("status"));

    private static final RowMapper<LedgerRow> LEDGER_MAPPER = (rs, i) -> new LedgerRow(
            rs.getLong("id"),
            rs.getObject("sku_id", Long.class),
            rs.getObject("warehouse_id", Long.class),
            rs.getString("site_code"),
            rs.getObject("qty_available", Integer.class),
            rs.getObject("qty_in_transit", Integer.class),
            rs.getObject("qty_reserved", Integer.class),
            rs.getObject("qty_unsellable", Integer.class));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;

    public InboundService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ confirm

    /**
     * 入库确认（契约 §2.1–§2.10）。授权/指纹/重放/状态闸门顺序见类注释；任何异常整事务回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public InboundResult confirm(InboundInput in)
    {
        if (in == null) throw new IllegalArgumentException("入库请求不能为空");
        InventoryRules.requiredId("purOrderId", in.purOrderId());
        InventoryRules.requiredId("warehouseId", in.warehouseId());
        String requestId = InventoryRules.requireRequestId(in.requestId());
        List<InboundLineInput> lines = requireLines(in.lines());

        Long operatorId = guard.requireCurrentUserId();

        // §2.1 站点授权经仓库归属：先读仓库（必须 ACTIVE），再 requireSite(仓库站点)。
        WarehouseRef warehouse = loadWarehouse(in.warehouseId());
        guard.requireSite(warehouse.siteCode());
        requireSuppliedSiteMatches(in.siteCode(), warehouse);

        // 采购单真实站点必须与仓库站点一致（禁止跨站入库）；FOR UPDATE 串行化同单的入库确认。
        PurHead head = lockPurOrder(in.purOrderId());
        if (!head.siteCode().equals(warehouse.siteCode()))
            throw new IllegalArgumentException("采购单站点 " + head.siteCode() + " 与仓库归属站点 "
                    + warehouse.siteCode() + " 不一致，禁止跨站入库: purOrderId=" + in.purOrderId()
                    + "，warehouseId=" + in.warehouseId());

        // §2.7 内容指纹先于状态/结果判定（同 M3 §9.6 优先级：授权 > 指纹 > DONE 重放 > 状态闸门）。
        String fingerprint = InventoryRules.contentFingerprint(in.purOrderId(), in.warehouseId(), lines);
        InboundResult replayed = replayDone(requestId, in.purOrderId(), fingerprint);
        if (replayed != null) return replayed;

        if (!InventoryRules.INBOUNDABLE_ORDER_STATUSES.contains(head.status()))
            throw new IllegalArgumentException("当前采购单状态 " + head.status()
                    + " 不可入库（仅 PARTIAL_RECEIVED/RECEIVED/CLOSED 可入库）: purOrderId=" + in.purOrderId());

        replayed = claimRequest(requestId, in.purOrderId(), operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = InventoryRules.now();
        String inboundNo = INBOUND_NO_PREFIX + UUID.randomUUID();
        long inboundId = insertInbound(inboundNo, in.purOrderId(), in.warehouseId(), operatorId, now);

        List<InboundLineInput> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(InboundLineInput::purOrderItemId));

        List<Long> batchIds = new ArrayList<>(sorted.size());
        int totalQty = 0;
        for (InboundLineInput line : sorted)
        {
            PurItem item = lockItem(in.purOrderId(), line.purOrderItemId());
            SourceCost source = receiptSource(in.purOrderId(), item.id());
            if (source.receivedQty() <= 0)
                throw new IllegalArgumentException("采购单行没有收货记录，不能入库: 行 id=" + item.id()
                        + "，purOrderId=" + in.purOrderId());
            if (source.unitCost() == null)
                throw new IllegalStateException("采购单行收货记录缺少权威成本: 行 id=" + item.id());
            int already = inboundAlready(item.id());
            int inboundable = source.receivedQty() - already;
            if (line.qtyQualified() > inboundable)
                throw new IllegalArgumentException("合格量超过该来源行可入库量: 行 id=" + item.id()
                        + "，累计收货=" + source.receivedQty() + "，已入库=" + already
                        + "，可入库=" + inboundable + "，本次=" + line.qtyQualified());

            // §2.3 成本守恒：权威成本只来自 M3 收货记录，SKU 只来自采购单行；批次号服务端生成。
            BigDecimal unitCost = source.unitCost();
            String batchNo = BATCH_NO_PREFIX + UUID.randomUUID();
            insertInboundItem(inboundId, item.id(), item.skuId(), batchNo, line.qtyQualified(), unitCost, now);
            long batchId = insertBatch(batchNo, item, in.warehouseId(), warehouse.siteCode(),
                    line.qtyQualified(), unitCost, now);
            applyLedger(item.skuId(), in.warehouseId(), warehouse.siteCode(), line.qtyQualified());
            insertMove(item.skuId(), in.warehouseId(), batchId, line.qtyQualified(), inboundNo, operatorId, now);

            batchIds.add(batchId);
            totalQty += line.qtyQualified();
        }

        audit.record(InventoryAudit.BIZ_INBOUND, String.valueOf(inboundId), InventoryAudit.ACTION_CONFIRM,
                "inboundNo=" + inboundNo + ",purOrderId=" + in.purOrderId() + ",warehouseId=" + in.warehouseId()
                        + ",lines=" + sorted.size() + ",qty=" + totalQty + ",batches=" + batchIds.size(),
                operatorId);

        InboundResult result = new InboundResult(inboundId, inboundNo, false, List.copyOf(batchIds));
        markDone(requestId, result);
        return result;
    }

    // ---------------------------------------------------------------- read-only

    /**
     * 只读结果查询（对齐 M3 §9.6 ③）：按 requestId 取幂等行 → 按幂等行 pur_order_id 的采购单**真实**
     * site_code 走 requireSite（采购单站点与仓库站点在 confirm 时已强一致）→ 才返回 DONE 缓存结果
     * （replayed 固定 true）。requestId 非法/不存在/未完成返回 null；无权抛 403。不写任何表。
     */
    @Transactional(readOnly = true)
    public InboundResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = InventoryRules.requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<RequestRow> rows = requestRows(rid, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);

        PurHead order = loadPurOrderOrNull(row.purOrderId());
        if (order == null) return null;
        guard.requireSite(order.siteCode());

        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /** 入库单列表：按授权站点集合过滤（传 siteCode 先 requireSite），total 为同条件真实总数。 */
    public PageResult<InboundRow> list(String siteCode, int offset, int limit)
    {
        InventoryRules.offset(offset);
        int size = InventoryRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE w.site_code IN (" + InventoryRules.placeholders(gate.size()) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_inbound i "
                + "JOIN md_warehouse w ON w.id=i.warehouse_id" + where, Long.class, gate.toArray());
        List<Object> args = new ArrayList<>(gate);
        args.add(size);
        args.add(offset);
        List<InboundRow> rows = jdbc.query(INBOUND_HEAD_SELECT + where + " ORDER BY i.id LIMIT ? OFFSET ?",
                INBOUND_MAPPER, args.toArray());
        return new PageResult<>(rows, total);
    }

    /** 入库单详情（带授权：以入库单所属仓库真实站点 requireSite）。 */
    @Transactional(readOnly = true)
    public InboundDetail get(Long id)
    {
        InventoryRules.requiredId("id", id);
        List<InboundRow> heads = jdbc.query(INBOUND_HEAD_SELECT + " WHERE i.id=?", INBOUND_MAPPER, id);
        if (heads.isEmpty()) throw new IllegalArgumentException("入库单不存在: id=" + id);
        InboundRow head = heads.get(0);
        guard.requireSite(head.siteCode());

        List<InboundItemRow> items = jdbc.query("SELECT id,pur_order_item_id,sku_id,batch_no,qty,qty_qualified,"
                + "unit_cost FROM inv_inbound_item WHERE inbound_id=? ORDER BY id", ITEM_MAPPER, id);
        return new InboundDetail(head, items);
    }

    /** FIFO 批次只读台账：按授权站点过滤，可再按 skuId 精确过滤。 */
    public PageResult<BatchRow> listBatches(String siteCode, int offset, int limit, Long skuId)
    {
        InventoryRules.offset(offset);
        int size = InventoryRules.limit(limit);
        if (skuId != null) InventoryRules.requiredId("skuId", skuId);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE b.site_code IN (" + InventoryRules.placeholders(gate.size()) + ")"
                + (skuId == null ? "" : " AND b.sku_id=?");
        List<Object> args = new ArrayList<>(gate);
        if (skuId != null) args.add(skuId);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_batch b" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<BatchRow> rows = jdbc.query("SELECT id,batch_no,sku_id,warehouse_id,site_code,received_at,"
                + "qty_in,qty_remaining,unit_cost,status FROM inv_batch b" + where
                + " ORDER BY b.id LIMIT ? OFFSET ?", BATCH_MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    /** 四态台账只读：按授权站点过滤。 */
    public PageResult<LedgerRow> listLedger(String siteCode, int offset, int limit)
    {
        InventoryRules.offset(offset);
        int size = InventoryRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE l.site_code IN (" + InventoryRules.placeholders(gate.size()) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_ledger l" + where,
                Long.class, gate.toArray());
        List<Object> pageArgs = new ArrayList<>(gate);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<LedgerRow> rows = jdbc.query("SELECT id,sku_id,warehouse_id,site_code,qty_available,"
                + "qty_in_transit,qty_reserved,qty_unsellable FROM inv_stock_ledger l" + where
                + " ORDER BY l.id LIMIT ? OFFSET ?", LEDGER_MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    // ---------------------------------------------------------------- 幂等

    /** 命中 DONE 且内容一致 → 重放（replayed=true）；无行/PENDING → null；内容/订单不一致 → 400。 */
    private InboundResult replayDone(String requestId, long purOrderId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameRequest(row, purOrderId, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 requestId 处理权：返回 null 表示本次是新请求（已占位 PENDING，含内容指纹）；
     * 非 null 表示命中已有 DONE 请求，直接重放首次结果。
     */
    private InboundResult claimRequest(String requestId, long purOrderId, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_inbound_request"
                    + "(request_id,pur_order_id,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,'PENDING')", requestId, purOrderId, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 唯一键（request_id）冲突：同键已存在（处理中或已完成）。当前读避免 RR 旧快照。
            return replayOf(requestId, purOrderId, fingerprint);
        }
    }

    private InboundResult replayOf(String requestId, long purOrderId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, true);
        if (rows.isEmpty())
            throw new IllegalArgumentException("同一入库请求正在处理中，请稍后重试");
        RequestRow row = rows.get(0);
        requireSameRequest(row, purOrderId, fingerprint);
        if (!"DONE".equals(row.status()))
            throw new IllegalArgumentException("同一入库请求正在处理中，请稍后重试");
        return replayResult(row);
    }

    /** 同键必须同单、同内容：内容指纹不一致即 400（先于状态/结果判定）。 */
    private static void requireSameRequest(RequestRow row, long purOrderId, String fingerprint)
    {
        if (row.purOrderId() != purOrderId)
            throw new IllegalArgumentException("requestId 已用于其他采购单");
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的入库内容与原请求不一致");
    }

    private InboundResult replayResult(RequestRow row)
    {
        InboundResult stored = readResult(row.resultJson());
        return new InboundResult(stored.inboundId(), stored.inboundNo(), true, stored.batchIds());
    }

    private List<RequestRow> requestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT pur_order_id,content_fingerprint,status,result_json "
                        + "FROM noon_m4_inbound_request WHERE request_id=?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getLong("pur_order_id"), rs.getString("content_fingerprint"),
                        rs.getString("status"), rs.getString("result_json")), requestId);
    }

    private void markDone(String requestId, InboundResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("入库结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m4_inbound_request SET status='DONE', result_json=? "
                + "WHERE request_id=?", json, requestId);
        if (updated == 0)
            throw new IllegalStateException("入库幂等记录状态更新失败: requestId=" + requestId);
    }

    private InboundResult readResult(String resultJson)
    {
        if (resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("入库幂等记录缺少结果快照");
        try
        {
            InboundResult stored = RESULT_JSON.readValue(resultJson, InboundResult.class);
            if (stored == null) throw new IllegalStateException("入库幂等结果反序列化为空");
            return stored;
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("入库幂等结果反序列化失败", e);
        }
    }

    private record RequestRow(long purOrderId, String contentFingerprint, String status, String resultJson)
    {
    }

    // ---------------------------------------------------------------- 写入

    private long insertInbound(String inboundNo, long purOrderId, long warehouseId, Long operatorId,
                               LocalDateTime now)
    {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inv_inbound(inbound_no,pur_order_id,warehouse_id,status,operator_id,created_at) "
                            + "VALUES(?,?,?,'CONFIRMED',?,?)", new String[]{"id"});
            ps.setString(1, inboundNo);
            ps.setLong(2, purOrderId);
            ps.setLong(3, warehouseId);
            ps.setObject(4, operatorId);
            ps.setObject(5, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("入库单未返回主键");
        return key.longValue();
    }

    private void insertInboundItem(long inboundId, long purOrderItemId, long skuId, String batchNo,
                                   int qtyQualified, BigDecimal unitCost, LocalDateTime now)
    {
        // qty = qtyQualified：本轮入参只有合格量，不合格量差额不在本轮建模（契约 §5）。
        jdbc.update("INSERT INTO inv_inbound_item(inbound_id,pur_order_item_id,sku_id,batch_no,qty,"
                        + "qty_qualified,unit_cost,created_at) VALUES(?,?,?,?,?,?,?,?)",
                inboundId, purOrderItemId, skuId, batchNo, qtyQualified, qtyQualified,
                InventoryRules.money(unitCost), now);
    }

    private long insertBatch(String batchNo, PurItem item, long warehouseId, String siteCode, int qty,
                             BigDecimal unitCost, LocalDateTime now)
    {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                            + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,"
                            + "split_seq,status) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,'CNY',1,'PURCHASE',NULL,0,'OPEN')",
                    new String[]{"id"});
            ps.setString(1, batchNo);
            ps.setLong(2, item.skuId());
            ps.setLong(3, item.id());
            ps.setLong(4, warehouseId);
            ps.setString(5, siteCode);
            ps.setObject(6, now); // FIFO 排序第一键：确认时刻 UTC+8
            ps.setInt(7, qty);
            ps.setInt(8, qty);
            ps.setBigDecimal(9, InventoryRules.money(unitCost));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("FIFO 批次未返回主键");
        return key.longValue();
    }

    /**
     * 四态台账累加（契约 §2.5）：先 SELECT ... FOR UPDATE 再累加；不存在则插入，唯一键
     * (sku_id,warehouse_id) 兜并发（撞键后重读再累加）。站点必须与仓库站点一致，跨站复用同 (sku,仓库) 400。
     */
    private void applyLedger(long skuId, long warehouseId, String siteCode, int qty)
    {
        LedgerState current = lockLedger(skuId, warehouseId);
        if (current != null)
        {
            requireLedgerSite(current.siteCode(), siteCode);
            jdbc.update("UPDATE inv_stock_ledger SET qty_available=qty_available+? WHERE id=?", qty, current.id());
            return;
        }
        try
        {
            jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                    + "qty_in_transit,qty_reserved,qty_unsellable) VALUES(?,?,?,?,0,0,0)",
                    skuId, warehouseId, siteCode, qty);
        }
        catch (DuplicateKeyException duplicate)
        {
            // 并发插入同一 (sku,仓库)：另一事务已提交，重读行锁后累加，不重复建行。
            LedgerState raced = lockLedger(skuId, warehouseId);
            if (raced == null)
                throw new IllegalStateException("台账并发插入后读不到行: skuId=" + skuId
                        + "，warehouseId=" + warehouseId);
            requireLedgerSite(raced.siteCode(), siteCode);
            jdbc.update("UPDATE inv_stock_ledger SET qty_available=qty_available+? WHERE id=?", qty, raced.id());
        }
    }

    private LedgerState lockLedger(long skuId, long warehouseId)
    {
        List<LedgerState> rows = jdbc.query("SELECT id,site_code FROM inv_stock_ledger "
                        + "WHERE sku_id=? AND warehouse_id=? FOR UPDATE",
                (rs, i) -> new LedgerState(rs.getLong("id"), rs.getString("site_code")), skuId, warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requireLedgerSite(String existing, String warehouseSite)
    {
        if (!warehouseSite.equals(existing))
            throw new IllegalArgumentException("同一 SKU+仓库的台账站点已为 " + existing + "，与仓库站点 "
                    + warehouseSite + " 不一致，拒绝跨站复用台账");
    }

    private void insertMove(long skuId, long warehouseId, long batchId, int qty, String inboundNo,
                            Long operatorId, LocalDateTime now)
    {
        jdbc.update("INSERT INTO inv_stock_move(move_no,sku_id,warehouse_id,batch_id,change_type,qty_delta,"
                        + "ref_doc_type,ref_doc_no,operator_id,created_at) "
                        + "VALUES(?,?,?,?,'INBOUND',?,'INBOUND',?,?,?)",
                MOVE_NO_PREFIX + UUID.randomUUID(), skuId, warehouseId, batchId, qty, inboundNo,
                operatorId, now);
    }

    // ---------------------------------------------------------------- internals

    private WarehouseRef loadWarehouse(long warehouseId)
    {
        List<WarehouseRef> rows = jdbc.query("SELECT site_code,status FROM md_warehouse WHERE id=?",
                (rs, i) -> new WarehouseRef(rs.getString("site_code"), rs.getString("status")), warehouseId);
        if (rows.isEmpty()) throw new IllegalArgumentException("仓库不存在: id=" + warehouseId);
        WarehouseRef warehouse = rows.get(0);
        if (!"ACTIVE".equals(warehouse.status()))
            throw new IllegalArgumentException("仓库已停用，不能入库: id=" + warehouseId
                    + "，status=" + warehouse.status());
        if (warehouse.siteCode() == null || warehouse.siteCode().isBlank())
            throw new IllegalArgumentException("仓库未绑定站点，不能入库: id=" + warehouseId);
        return warehouse;
    }

    /** 请求体 siteCode 仅展示用；非空时必须与仓库站点一致（否则 400，不参与授权）。 */
    private static void requireSuppliedSiteMatches(String suppliedSite, WarehouseRef warehouse)
    {
        if (suppliedSite == null || suppliedSite.isBlank()) return;
        String normalized = suppliedSite.trim().toUpperCase(java.util.Locale.ROOT);
        if (!warehouse.siteCode().equals(normalized))
            throw new IllegalArgumentException("请求站点 " + normalized + " 与仓库 " + warehouse.siteCode()
                    + " 归属站点不一致");
    }

    private PurHead lockPurOrder(long purOrderId)
    {
        List<PurHead> rows = jdbc.query("SELECT id,site_code,status FROM pur_order WHERE id=? FOR UPDATE",
                (rs, i) -> new PurHead(rs.getLong("id"), rs.getString("site_code"), rs.getString("status")),
                purOrderId);
        if (rows.isEmpty()) throw new IllegalArgumentException("采购单不存在: id=" + purOrderId);
        return rows.get(0);
    }

    private PurHead loadPurOrderOrNull(long purOrderId)
    {
        List<PurHead> rows = jdbc.query("SELECT id,site_code,status FROM pur_order WHERE id=?",
                (rs, i) -> new PurHead(rs.getLong("id"), rs.getString("site_code"), rs.getString("status")),
                purOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PurItem lockItem(long purOrderId, long purOrderItemId)
    {
        List<PurItem> rows = jdbc.query("SELECT id,pur_order_id,sku_id FROM pur_order_item "
                        + "WHERE id=? AND pur_order_id=? FOR UPDATE",
                (rs, i) -> new PurItem(rs.getLong("id"), rs.getLong("pur_order_id"), rs.getLong("sku_id")),
                purOrderItemId, purOrderId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("采购单行不属于该订单: purOrderItemId=" + purOrderItemId
                    + "，purOrderId=" + purOrderId);
        return rows.get(0);
    }

    /** 来源行的累计收货量 + 最近一条收货的权威成本（same item 的 unit_cost_cny 恒定）。 */
    private SourceCost receiptSource(long purOrderId, long purOrderItemId)
    {
        Integer total = jdbc.queryForObject("SELECT COALESCE(SUM(qty),0) FROM noon_m3_receipt "
                + "WHERE order_id=? AND order_item_id=?", Integer.class, purOrderId, purOrderItemId);
        List<BigDecimal> costs = jdbc.query("SELECT unit_cost_cny FROM noon_m3_receipt "
                        + "WHERE order_id=? AND order_item_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getBigDecimal("unit_cost_cny"), purOrderId, purOrderItemId);
        return new SourceCost(total == null ? 0 : total,
                costs.isEmpty() ? null : InventoryRules.money(costs.get(0)));
    }

    /** 该来源行已入库的合格量（仅计已生成库存的入库单），防止跨 requestId 重复入库虚增库存。 */
    private int inboundAlready(long purOrderItemId)
    {
        Integer sum = jdbc.queryForObject("SELECT COALESCE(SUM(it.qty_qualified),0) FROM inv_inbound_item it "
                        + "JOIN inv_inbound i ON i.id=it.inbound_id "
                        + "WHERE it.pur_order_item_id=? AND i.status IN ('CONFIRMED','CLOSED')",
                Integer.class, purOrderItemId);
        return sum == null ? 0 : sum;
    }

    /**
     * 站点闸门：指定 siteCode 先 requireSite 再单站过滤；未指定返回全部授权站点
     * （超管=SUPPORTED_SITES、普通用户=已授权，空集合表示零行，调用方不得省略 WHERE）。
     */
    private List<String> siteGate(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
            return List.of(guard.requireSite(InventoryRules.siteCode("siteCode", siteCode)));
        return guard.allowedSiteCodes();
    }

    /** 入库明细行校验：至少 1 行、来源行合法、qtyQualified&gt;0、同一 purOrderItemId 不得重复提交。 */
    private static List<InboundLineInput> requireLines(List<InboundLineInput> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行入库明细");
        Set<Long> seen = new LinkedHashSet<>();
        for (InboundLineInput line : lines)
        {
            if (line == null) throw new IllegalArgumentException("入库明细行不能为空");
            InventoryRules.requiredId("purOrderItemId", line.purOrderItemId());
            if (line.qtyQualified() <= 0)
                throw new IllegalArgumentException("qtyQualified 必须大于 0");
            if (!seen.add(line.purOrderItemId()))
                throw new IllegalArgumentException("同一采购单行不能重复提交: purOrderItemId=" + line.purOrderItemId());
        }
        return List.copyOf(lines);
    }

    /** 仓库归属（授权唯一依据）。 */
    private record WarehouseRef(String siteCode, String status)
    {
    }

    private record PurHead(long id, String siteCode, String status)
    {
    }

    private record PurItem(long id, long purOrderId, long skuId)
    {
    }

    /** 来源行累计收货量与权威单位成本。 */
    private record SourceCost(int receivedQty, BigDecimal unitCost)
    {
    }

    private record LedgerState(long id, String siteCode)
    {
    }
}
