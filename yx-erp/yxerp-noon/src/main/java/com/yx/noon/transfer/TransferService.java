package com.yx.noon.transfer;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * M4 库存第二轮「调拨 + 在途拆批 + 短少」后端内核（契约 v1.2 §2/§3/§9，owner：豆包）。
 *
 * <p><b>create（→ DRAFT）</b>（契约 §9）：授权（两仓存在且 ACTIVE、{@code requireSite(两仓站点)}、
 * 两仓同站点、源≠目标）→ 行结构校验 → 指纹 → DONE 重放 → 来源批次权威校验（每条
 * {@code (skuId,batchNo)} 必须是源仓唯一 OPEN 批次且剩余充足）→ PENDING 占位 → 写 DRAFT 单 + 计划行
 * （{@code qty_shipped} 在 DRAFT 期为计划量、{@code qty_received=0}、{@code diff_qty=0}）→ 审计 →
 * 回填 transfer_id 并 markDone。<b>create 不扣批次/不动台账/不写流水</b>，真实扣减在 ship 的
 * {@code FOR UPDATE} 重新校验后发生（故两个 create 可同时引用同一批次剩余量）。
 *
 * <p><b>ship（DRAFT → IN_TRANSIT）</b>：授权（源仓/目标仓都必须 ACTIVE、{@code requireSite(两仓站点)}、
 * 两仓同站点，跨站 400）→ 指纹 → DONE 重放 → 状态闸门 → 占位 PENDING → 逐行锁「调拨单行 + 来源批次」→
 * 拆批（子批次 {@code B:TR:UUID}、血缘、FIFO 继承 {@code received_at}）→ 台账双边（源仓 available −、
 * 目标仓 in_transit +）→ TRANSFER_OUT 流水（−shipped，源仓）→ 回写调拨单 IN_TRANSIT/shipped_at。
 *
 * <p><b>receive（IN_TRANSIT → RECEIVED）</b>：同授权（目标仓）→ 指纹 → DONE 重放 → 状态闸门 →
 * 逐行锁子批次（状态须 IN_TRANSIT，即未被收过）→ 收敛子批次 {@code qty_in/qty_remaining} 为实收量、
 * 在途减可用增 → 短少写 {@code inv_batch_consumption(TRANSFER_SHORT)}（
 * {@code biz_event_key=TSHORT:itemId:shortEventId}）→ 回写行 qty_received/diff_qty →
 * TRANSFER_IN 流水（+received，目标仓）；全部子批次离开 IN_TRANSIT 后整单置 RECEIVED。
 *
 * <p><b>幂等</b>（对齐第一轮 {@code noon_m4_inbound_request}）：{@code noon_m4_transfer_request}
 * 按 requestId 主键，优先级 授权 &gt; 指纹 &gt; DONE 重放 &gt; 状态闸门；指纹含动作
 * （{@code SHA-256(transferId|SHIP|RECEIVE|...)}），ship 的 requestId 复用于 receive 必然 400；
 * PENDING 并发 400「处理中」；异常整事务回滚（PENDING 占位一并消失，同键可安全重试）。
 *
 * <p><b>防伪造</b>：入参 record 无 skuId/unitCost/batchNo/源批次 id；SKU/成本/批次号全部按 itemId
 * （= {@code inv_transfer_item.id}）服务端权威带出。
 *
 * <p><b>契约未明确处的落地口径</b>（均已在报告中标注，供 WorkBuddy/Codex 裁决）：
 * <ol>
 *   <li>ship 必须覆盖<b>全部</b>调拨单行（每行恰好一次、itemId 必须属于该单）；允许单行发货量
 *       &lt;= 该行计划量（item.qty_shipped 在 DRAFT 期为计划量，发货时被实际发货量覆盖）——
 *       否则会出现"已发但未落子批次"的行，receive 无从定位批次。</li>
 *   <li>receive 允许多个 requestId 分批覆盖不同行，但同一行只能被收一次（子批次离开发货态
 *       IN_TRANSIT 即视为已收），二次收同一行 400，杜绝重复记短少。</li>
 *   <li>{@code qtyReceived = 0}（整行短少）时契约要求子批次 {@code qty_in = qtyReceived = 0}，
 *       与真 DDL {@code chk_batch_qty(qty_in > 0)} 冲突：此时保留 {@code qty_in = qtyShipped}、
 *       {@code qty_remaining = 0}、{@code status = 'CLOSED'}（历史发货量不丢、CHECK 不破）。</li>
 *   <li>{@code qtyReceived = 0} 时 {@code TRANSFER_IN} 流水 {@code qty_delta = 0} 违反
 *       {@code chk_move_delta(qty_delta <> 0)}：该行不写 TRANSFER_IN（短少由 TRANSFER_SHORT 记损失）。</li>
 * </ol>
 */
@Service
public class TransferService
{
    static final String ACTION_CREATE = "CREATE";
    static final String ACTION_SHIP = "SHIP";
    static final String ACTION_RECEIVE = "RECEIVE";

    private static final String TRANSFER_NO_PREFIX = "TR:";
    /** create 幂等表 {@code transfer_id} 列为 NOT NULL（建单时真实 id 尚未生成），PENDING 期用占位值。 */
    private static final long PENDING_TRANSFER_ID = 0L;
    private static final int TRANSFER_NO_ATTEMPTS = 3;

    private static final String CHILD_BATCH_PREFIX = "B:TR:";
    private static final String MOVE_NO_PREFIX = "TM:";
    private static final String CONSUMPTION_NO_PREFIX = "TS:";
    private static final String SHORT_EVENT_KEY_PREFIX = "TSHORT:";
    private static final String REF_DOC_TYPE = "TRANSFER";

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    private static final String STATUS_RECEIVED = "RECEIVED";

    private static final String BATCH_OPEN = "OPEN";
    private static final String BATCH_CLOSED = "CLOSED";

    /** result_json 只缓存 TransferResult（无时间字段），普通 ObjectMapper 即可原样重放（同第一轮）。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private static final String HEAD_SELECT = "SELECT t.id,t.transfer_no,t.from_warehouse_id,t.to_warehouse_id,"
            + "t.status,w.site_code,(SELECT COUNT(*) FROM inv_transfer_item it WHERE it.transfer_id=t.id) AS item_count,"
            + "t.created_at FROM inv_transfer t JOIN md_warehouse w ON w.id=t.from_warehouse_id";

    private static final RowMapper<TransferRow> HEAD_MAPPER = (rs, i) -> new TransferRow(
            rs.getLong("id"),
            rs.getString("transfer_no"),
            rs.getObject("from_warehouse_id", Long.class),
            rs.getObject("to_warehouse_id", Long.class),
            rs.getString("status"),
            rs.getString("site_code"),
            rs.getObject("item_count", Integer.class),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<TransferItemRow> ITEM_MAPPER = (rs, i) -> new TransferItemRow(
            rs.getLong("id"),
            rs.getObject("sku_id", Long.class),
            rs.getString("batch_no"),
            rs.getObject("qty_shipped", Integer.class),
            rs.getObject("qty_received", Integer.class),
            rs.getObject("diff_qty", Integer.class),
            rs.getObject("child_id", Long.class),
            rs.getString("child_batch_no"),
            rs.getString("child_status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;

    public TransferService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- create

    /**
     * 创建调拨单（契约 v1.2 §9）：只写 DRAFT 单与计划行，不扣库存。
     *
     * <p>顺序：授权（仓库存在/ACTIVE、两仓同站点、{@code requireSite}）→ 行结构校验 → 内容指纹 →
     * DONE 重放 → 来源批次权威校验（只读）→ PENDING 占位 → 写单/行 → 审计 → 回填 transfer_id + DONE。
     * 异常整事务回滚（含 PENDING 占位），同 requestId 可安全重试；重放零写入。
     */
    @Transactional(rollbackFor = Exception.class)
    public TransferResult create(TransferCreateInput in)
    {
        if (in == null) throw new IllegalArgumentException("调拨建单请求不能为空");
        TransferRules.requiredId("fromWarehouseId", in.fromWarehouseId());
        TransferRules.requiredId("toWarehouseId", in.toWarehouseId());
        String requestId = TransferRules.requireRequestId(in.requestId());

        Long operatorId = guard.requireCurrentUserId();

        // §9.2.1 授权在一切之前：两仓存在且 ACTIVE → 源≠目标 → 两仓同站点 → 两仓站点都 requireSite。
        WarehouseRef from = loadWarehouse(in.fromWarehouseId(), "源");
        WarehouseRef to = loadWarehouse(in.toWarehouseId(), "目标");
        if (from.id() == to.id())
            throw new IllegalArgumentException("调拨源仓与目标仓不能相同: id=" + from.id());
        requireSameSite(from, to);
        guard.requireSite(from.siteCode());
        guard.requireSite(to.siteCode());

        // §9.2.1 请求体 siteCode 仅展示校验（不参与授权，禁止自报提权）。
        TransferRules.requireSuppliedSiteMatches(in.siteCode(), from.siteCode());

        List<TransferCreateLine> lines = requireCreateLines(in.lines());

        // §9.2.6 指纹先于重放判定（授权 > 指纹 > DONE 重放 > 校验 > PENDING 占位）。
        String fingerprint = TransferRules.createFingerprint(from.id(), to.id(), lines);
        TransferResult replayed = replayDoneCreate(requestId, fingerprint);
        if (replayed != null) return replayed;

        // §9.2.2 来源批次权威校验（只读、不锁行、不扣减）：SKU/成本/币种/汇率/批次 id 均由服务端带出。
        List<CreateSourceBatch> sources = new ArrayList<>(lines.size());
        for (TransferCreateLine line : lines)
            sources.add(requireCreateSourceBatch(line, from));

        replayed = claimCreateRequest(requestId, operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = TransferRules.now();
        // §9.2.4 单号服务端生成 TR:<SITE>:<UUID前8大写>；唯一键 uk_transfer_no 兜重（撞键重试）。
        long transferId = insertTransfer(from.siteCode(), from.id(), to.id(), now);

        int totalQty = 0;
        for (int i = 0; i < lines.size(); i++)
        {
            TransferCreateLine line = lines.get(i);
            CreateSourceBatch source = sources.get(i);
            jdbc.update("INSERT INTO inv_transfer_item(transfer_id,sku_id,batch_no,qty_shipped,"
                            + "qty_received,diff_qty,created_at) VALUES(?,?,?,?,0,0,?)",
                    transferId, source.skuId(), source.batchNo(), line.qty(), now);
            totalQty += line.qty();
        }
        String transferNo = jdbc.queryForObject("SELECT transfer_no FROM inv_transfer WHERE id=?",
                String.class, transferId);

        audit.record("TRANSFER", String.valueOf(transferId), ACTION_CREATE,
                "transferNo=" + transferNo + ",from=" + from.id() + ",to=" + to.id()
                        + ",site=" + from.siteCode() + ",lines=" + lines.size() + ",qty=" + totalQty,
                operatorId);

        TransferResult result = new TransferResult(transferId, transferNo, false, ACTION_CREATE, List.of());
        markCreateDone(requestId, transferId, result);
        return result;
    }

    // ------------------------------------------------------------------- ship

    /**
     * 调拨发货 + 在途拆批（契约 §2.2/§2.3/§2.4）。授权/指纹/重放/状态闸门顺序见类注释；异常整事务回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public TransferResult ship(TransferShipInput in)
    {
        if (in == null) throw new IllegalArgumentException("调拨发货请求不能为空");
        TransferRules.requiredId("transferId", in.transferId());
        TransferRules.requiredId("fromWarehouseId", in.fromWarehouseId());
        TransferRules.requiredId("toWarehouseId", in.toWarehouseId());
        String requestId = TransferRules.requireRequestId(in.requestId());
        List<TransferShipLine> lines = requireShipLines(in.lines());

        Long operatorId = guard.requireCurrentUserId();

        // 锁调拨单（FOR UPDATE）：串行化同单的 ship。
        TransferHead head = lockTransfer(in.transferId());

        // §2.1 站点授权经仓库归属：两仓都必须存在/ACTIVE，按两仓真实站点 requireSite，且必须同站点。
        WarehouseRef from = loadWarehouse(head.fromWarehouseId(), "源");
        WarehouseRef to = loadWarehouse(head.toWarehouseId(), "目标");
        requireDistinctWarehouses(head.fromWarehouseId(), head.toWarehouseId(), head.transferNo());
        guard.requireSite(from.siteCode());
        guard.requireSite(to.siteCode());
        requireSameSite(from, to, head.transferNo());

        // 防伪造：请求体仓库 id 必须与调拨单真实源/目标一致；请求体 siteCode 仅展示校验。
        requireHeadWarehouses(head, in.fromWarehouseId(), in.toWarehouseId());
        TransferRules.requireSuppliedSiteMatches(in.siteCode(), from.siteCode());

        // §2.6 指纹先于状态/结果判定（授权 > 指纹 > DONE 重放 > 状态闸门）。
        String fingerprint = TransferRules.shipFingerprint(in.transferId(), lines);
        TransferResult replayed = replayDone(requestId, in.transferId(), fingerprint);
        if (replayed != null) return replayed;

        if (!STATUS_DRAFT.equals(head.status()))
            throw new IllegalArgumentException("当前调拨单状态 " + head.status()
                    + " 不可发货（仅 DRAFT 可发货）: transferId=" + in.transferId());

        // 必须先覆盖全部调拨单行（见类注释 1），校验在占位前完成，失败不留 PENDING 行。
        requireAllItemsCovered(in.transferId(), lines.size());

        replayed = claimRequest(requestId, in.transferId(), operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = TransferRules.now();
        List<TransferShipLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(TransferShipLine::itemId));

        List<Long> childBatchIds = new ArrayList<>(sorted.size());
        int totalShipped = 0;
        for (TransferShipLine line : sorted)
        {
            TransferItem item = lockItem(in.transferId(), line.itemId());
            // 计划量闸门：发货不得超过调拨单行计划量（DRAFT 期 qty_shipped 即计划量）。
            if (line.qtyShipped() > item.qtyShipped())
                throw new IllegalArgumentException("发货量超过调拨单行计划量: itemId=" + item.id()
                        + "，计划=" + item.qtyShipped() + "，本次=" + line.qtyShipped());

            SourceBatch source = lockSourceBatch(from.id(), item.batchNo());
            if (!BATCH_OPEN.equals(source.status()))
                throw new IllegalArgumentException("来源批次状态 " + source.status()
                        + " 不可拆批出库（仅 OPEN 可拆批）: batchNo=" + source.batchNo());
            if (source.skuId() != item.skuId())
                throw new IllegalArgumentException("来源批次 SKU " + source.skuId()
                        + " 与调拨单行 SKU " + item.skuId() + " 不一致: itemId=" + item.id());
            if (!from.siteCode().equals(source.siteCode()))
                throw new IllegalArgumentException("来源批次站点 " + source.siteCode()
                        + " 与源仓站点 " + from.siteCode() + " 不一致: batchNo=" + source.batchNo());

            // §2.2 守恒：qty_shipped <= 源批次.qty_remaining。
            if (line.qtyShipped() > source.qtyRemaining())
                throw new IllegalArgumentException("发货量超过来源批次剩余量: batchNo=" + source.batchNo()
                        + "，剩余=" + source.qtyRemaining() + "，本次=" + line.qtyShipped());

            // 源批次扣减：CAS 显式比较原剩余（避免 MySQL/H2 对 SET 表达式求值差异），扣到 0 关批。
            int remaining = source.qtyRemaining() - line.qtyShipped();
            int deducted = jdbc.update("UPDATE inv_batch SET qty_remaining=?, status=? "
                            + "WHERE id=? AND qty_remaining=?",
                    remaining, remaining == 0 ? BATCH_CLOSED : BATCH_OPEN, source.id(), source.qtyRemaining());
            if (deducted != 1)
                throw new IllegalStateException("来源批次并发扣减失败，事务整体回滚: batchNo=" + source.batchNo());

            long childId = insertChildBatch(item, source, to, head.transferNo(), line.qtyShipped(), now);
            childBatchIds.add(childId);

            // §2.3 台账双边：源仓 available −、目标仓 in_transit +。
            applyLedger(item.skuId(), from.id(), from.siteCode(), -line.qtyShipped(), 0, "调拨发货扣源仓可用");
            applyLedger(item.skuId(), to.id(), to.siteCode(), 0, line.qtyShipped(), "调拨发货加目标仓在途");

            // §2.4 流水：TRANSFER_OUT，负增量，源仓，引用调拨单号。
            insertMove(item.skuId(), from.id(), childId, -line.qtyShipped(),
                    "TRANSFER_OUT", head.transferNo(), operatorId, now);

            // 回写行：qty_shipped 收敛为实际发货量（计划量 → 实发量），收货量/差异清零。
            jdbc.update("UPDATE inv_transfer_item SET qty_shipped=?, qty_received=0, diff_qty=0 WHERE id=?",
                    line.qtyShipped(), item.id());

            totalShipped += line.qtyShipped();
        }

        int headUpdated = jdbc.update("UPDATE inv_transfer SET status=?, shipped_at=? "
                + "WHERE id=? AND status=?", STATUS_IN_TRANSIT, now, head.id(), STATUS_DRAFT);
        if (headUpdated != 1)
            throw new IllegalStateException("调拨单状态并发更新失败，事务整体回滚: transferId=" + head.id());

        audit.record("TRANSFER", String.valueOf(head.id()), ACTION_SHIP,
                "transferNo=" + head.transferNo() + ",from=" + from.id() + ",to=" + to.id()
                        + ",lines=" + sorted.size() + ",qty=" + totalShipped + ",children=" + childBatchIds.size(),
                operatorId);

        TransferResult result = new TransferResult(head.id(), head.transferNo(), false, ACTION_SHIP, childBatchIds);
        markDone(requestId, result);
        return result;
    }

    // ---------------------------------------------------------------- receive

    /**
     * 调拨收货（契约 §2.3/§2.5/§2.7）：在途转 OPEN/CLOSED、台账在途减可用增、短少记 TRANSFER_SHORT。
     */
    @Transactional(rollbackFor = Exception.class)
    public TransferResult receive(TransferReceiveInput in)
    {
        if (in == null) throw new IllegalArgumentException("调拨收货请求不能为空");
        TransferRules.requiredId("transferId", in.transferId());
        TransferRules.requiredId("toWarehouseId", in.toWarehouseId());
        String requestId = TransferRules.requireRequestId(in.requestId());
        List<TransferReceiveLine> lines = requireReceiveLines(in.lines());

        Long operatorId = guard.requireCurrentUserId();

        TransferHead head = lockTransfer(in.transferId());
        WarehouseRef from = loadWarehouse(head.fromWarehouseId(), "源");
        WarehouseRef to = loadWarehouse(head.toWarehouseId(), "目标");
        requireDistinctWarehouses(head.fromWarehouseId(), head.toWarehouseId(), head.transferNo());
        guard.requireSite(to.siteCode());
        guard.requireSite(from.siteCode());
        requireSameSite(from, to, head.transferNo());

        if (in.toWarehouseId() != head.toWarehouseId())
            throw new IllegalArgumentException("调拨单目标仓 " + head.toWarehouseId()
                    + " 与请求 toWarehouseId " + in.toWarehouseId() + " 不一致: transferId=" + head.id());

        String fingerprint = TransferRules.receiveFingerprint(in.transferId(), lines);
        TransferResult replayed = replayDone(requestId, in.transferId(), fingerprint);
        if (replayed != null) return replayed;

        if (!STATUS_IN_TRANSIT.equals(head.status()))
            throw new IllegalArgumentException("当前调拨单状态 " + head.status()
                    + " 不可收货（仅 IN_TRANSIT 可收货）: transferId=" + in.transferId());

        replayed = claimRequest(requestId, in.transferId(), operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = TransferRules.now();
        List<TransferReceiveLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(TransferReceiveLine::itemId));

        List<Long> childBatchIds = new ArrayList<>(sorted.size());
        long totalReceived = 0;
        long totalShort = 0;
        for (TransferReceiveLine line : sorted)
        {
            TransferItem item = lockItem(in.transferId(), line.itemId());
            if (line.qtyReceived() > item.qtyShipped())
                throw new IllegalArgumentException("收货量超过发货量: itemId=" + item.id()
                        + "，发货=" + item.qtyShipped() + "，本次收货=" + line.qtyReceived());

            ChildBatch child = lockChildBatch(item, head.transferNo(), to.id());
            if (!STATUS_IN_TRANSIT.equals(child.status()))
                throw new IllegalArgumentException("该调拨行已收货，禁止重复收货: itemId=" + item.id()
                        + "，子批次=" + child.batchNo() + "，状态=" + child.status());

            int diff = item.qtyShipped() - line.qtyReceived();
            String shortEventId = diff > 0 ? TransferRules.requireShortEventId(line.shortEventId()) : null;

            // 子批次收敛：qty_remaining = 实收；qty_in 见类注释 3（0 收货时保留发货量，守 chk_batch_qty qty_in>0）。
            int childQtyIn = line.qtyReceived() > 0 ? line.qtyReceived() : child.qtyIn();
            String childStatus = line.qtyReceived() > 0 ? BATCH_OPEN : BATCH_CLOSED;
            int childUpdated = jdbc.update("UPDATE inv_batch SET qty_in=?, qty_remaining=?, status=? "
                            + "WHERE id=? AND status=?",
                    childQtyIn, line.qtyReceived(), childStatus, child.id(), STATUS_IN_TRANSIT);
            if (childUpdated != 1)
                throw new IllegalStateException("子批次状态并发更新失败，事务整体回滚: batchNo=" + child.batchNo());

            // §2.3 台账双边：目标仓 in_transit − qtyShipped、available + qtyReceived。
            applyLedger(item.skuId(), to.id(), to.siteCode(), line.qtyReceived(), -item.qtyShipped(),
                    "调拨收货转可用");

            // §2.4 流水：TRANSFER_IN（实收 0 时不写，见类注释 4），目标仓。
            insertMove(item.skuId(), to.id(), child.id(), line.qtyReceived(),
                    "TRANSFER_IN", head.transferNo(), operatorId, now);

            // §2.5 短少：diff > 0 记一条 TRANSFER_SHORT，事件键 TSHORT:itemId:shortEventId，唯一键防双计。
            if (diff > 0)
            {
                insertShortConsumption(item, child, diff, shortEventId, now);
                totalShort += diff;
            }

            jdbc.update("UPDATE inv_transfer_item SET qty_received=?, diff_qty=? WHERE id=?",
                    line.qtyReceived(), diff, item.id());

            childBatchIds.add(child.id());
            totalReceived += line.qtyReceived();
        }

        // 全部子批次离开 IN_TRANSIT 才算整单收齐（支持按行分批发货/分批收货）。
        int stillInTransit = jdbc.queryForObject("SELECT COUNT(*) FROM inv_batch "
                        + "WHERE transfer_no=? AND parent_batch_id IS NOT NULL AND status=?",
                Integer.class, head.transferNo(), STATUS_IN_TRANSIT);
        if (stillInTransit == 0)
        {
            int headUpdated = jdbc.update("UPDATE inv_transfer SET status=?, received_at=? "
                    + "WHERE id=? AND status=?", STATUS_RECEIVED, now, head.id(), STATUS_IN_TRANSIT);
            if (headUpdated != 1)
                throw new IllegalStateException("调拨单状态并发更新失败，事务整体回滚: transferId=" + head.id());
        }

        audit.record("TRANSFER", String.valueOf(head.id()), ACTION_RECEIVE,
                "transferNo=" + head.transferNo() + ",to=" + to.id() + ",lines=" + sorted.size()
                        + ",received=" + totalReceived + ",short=" + totalShort,
                operatorId);

        TransferResult result = new TransferResult(head.id(), head.transferNo(), false, ACTION_RECEIVE,
                childBatchIds);
        markDone(requestId, result);
        return result;
    }

    // ---------------------------------------------------------------- read-only

    /**
     * 只读结果查询（对齐第一轮 resultOf）：先查 ship/receive 幂等表，再查 create 幂等表（契约 §9.2.5）。
     * 命中后按调拨单真实源/目标仓 {@code requireSite} → 才返回 DONE 缓存结果（replayed 固定 true）。
     * requestId 非法/两表都没有/未完成（PENDING）/单不存在返回 null；无权 403。不写任何表。
     */
    @Transactional(readOnly = true)
    public TransferResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = TransferRules.requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<RequestRow> rows = requestRows(rid, false);
        if (!rows.isEmpty())
        {
            RequestRow row = rows.get(0);
            TransferHead head = loadTransferOrNull(row.transferId());
            if (head == null || !requireTransferSite(head)) return null;
            if (!"DONE".equals(row.status())) return null;
            return replayResult(row);
        }

        List<RequestRow> creates = createRequestRows(rid, false);
        if (creates.isEmpty()) return null;
        RequestRow createRow = creates.get(0);
        if (!"DONE".equals(createRow.status())) return null;
        TransferHead created = loadTransferOrNull(createRow.transferId());
        if (created == null || !requireTransferSite(created)) return null;
        TransferResult stored = readResult(createRow.resultJson());
        if (stored.transferId() == null || stored.transferId() != created.id())
            throw new IllegalStateException("建单幂等结果与调拨单不一致: requestId=" + rid);
        return new TransferResult(stored.transferId(), stored.transferNo(), true, stored.action(),
                stored.childBatchIds());
    }

    /**
     * 结果查询的授权口径：源仓站点 requireSite；跨站单再校验目标仓站点（与 list/get 同口径）。
     * 仓库行缺失（数据异常）返回 false，调用方按「查不到」返回 null。
     */
    private boolean requireTransferSite(TransferHead head)
    {
        WarehouseRef from = loadWarehouseOrNull(head.fromWarehouseId());
        WarehouseRef to = loadWarehouseOrNull(head.toWarehouseId());
        if (from == null || to == null) return false;
        guard.requireSite(from.siteCode());
        if (!from.siteCode().equals(to.siteCode()))
            guard.requireSite(to.siteCode());
        return true;
    }

    /** 调拨单列表：按授权站点集合过滤（传 siteCode 先 requireSite），total 为同条件真实总数。 */
    public PageResult<TransferRow> list(String siteCode, int offset, int limit)
    {
        TransferRules.offset(offset);
        int size = TransferRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE w.site_code IN (" + TransferRules.placeholders(gate.size()) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM inv_transfer t "
                + "JOIN md_warehouse w ON w.id=t.from_warehouse_id" + where,
                Long.class, gate.toArray());
        List<Object> args = new ArrayList<>(gate);
        args.add(size);
        args.add(offset);
        List<TransferRow> rows = jdbc.query(HEAD_SELECT + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, args.toArray());
        return new PageResult<>(rows, total);
    }

    /** 调拨单详情（带授权：以调拨单源仓真实站点 requireSite；跨站单再校验目标仓）。 */
    @Transactional(readOnly = true)
    public TransferDetail get(Long id)
    {
        TransferRules.requiredId("id", id);
        List<TransferRow> heads = jdbc.query(HEAD_SELECT + " WHERE t.id=?", HEAD_MAPPER, id);
        if (heads.isEmpty()) throw new IllegalArgumentException("调拨单不存在: id=" + id);
        TransferRow head = heads.get(0);
        guard.requireSite(head.siteCode());
        TransferHead detailHead = loadTransferOrNull(id);
        if (detailHead == null) throw new IllegalArgumentException("调拨单不存在: id=" + id);
        WarehouseRef to = loadWarehouseOrNull(detailHead.toWarehouseId());
        if (to != null && !head.siteCode().equals(to.siteCode()))
            guard.requireSite(to.siteCode());

        List<TransferItemRow> items = jdbc.query("SELECT i.id,i.sku_id,i.batch_no,i.qty_shipped,"
                        + "COALESCE(i.qty_received,0) AS qty_received,COALESCE(i.diff_qty,0) AS diff_qty,"
                        + "c.id AS child_id,c.batch_no AS child_batch_no,c.status AS child_status "
                        + "FROM inv_transfer_item i "
                        + "LEFT JOIN inv_batch s ON s.batch_no=i.batch_no "
                        + "LEFT JOIN inv_batch c ON c.parent_batch_id=s.id AND c.transfer_no=? "
                        + "WHERE i.transfer_id=? ORDER BY i.id",
                ITEM_MAPPER, head.transferNo(), id);
        return new TransferDetail(head, items);
    }

    // ---------------------------------------------------------------- 幂等

    /** 命中 DONE 且内容一致 → 重放（replayed=true）；无行/PENDING → null；内容/调拨单不一致 → 400。 */
    private TransferResult replayDone(String requestId, long transferId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameRequest(row, transferId, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 requestId 处理权：返回 null 表示本次是新请求（已占位 PENDING，含内容指纹）；
     * 非 null 表示命中已有 DONE 请求，直接重放首次结果。
     */
    private TransferResult claimRequest(String requestId, long transferId, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_transfer_request"
                    + "(request_id,transfer_id,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,'PENDING')", requestId, transferId, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 唯一键（request_id）冲突：同键已存在（处理中或已完成）。当前读避免 RR 旧快照。
            return replayOf(requestId, transferId, fingerprint);
        }
    }

    private TransferResult replayOf(String requestId, long transferId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, true);
        if (rows.isEmpty())
            throw new IllegalArgumentException("同一调拨请求正在处理中，请稍后重试");
        RequestRow row = rows.get(0);
        requireSameRequest(row, transferId, fingerprint);
        if (!"DONE".equals(row.status()))
            throw new IllegalArgumentException("同一调拨请求正在处理中，请稍后重试");
        return replayResult(row);
    }

    /** 同键必须同单、同内容：内容指纹含动作，ship 的 requestId 用于 receive 即判内容不一致 400。 */
    private static void requireSameRequest(RequestRow row, long transferId, String fingerprint)
    {
        if (row.transferId() != transferId)
            throw new IllegalArgumentException("requestId 已用于其他调拨单");
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的调拨内容与原请求不一致（含动作 SHIP/RECEIVE）");
    }

    private TransferResult replayResult(RequestRow row)
    {
        TransferResult stored = readResult(row.resultJson());
        return new TransferResult(stored.transferId(), stored.transferNo(), true, stored.action(),
                stored.childBatchIds());
    }

    private List<RequestRow> requestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT transfer_id,content_fingerprint,status,result_json "
                        + "FROM noon_m4_transfer_request WHERE request_id=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getLong("transfer_id"), rs.getString("content_fingerprint"),
                        rs.getString("status"), rs.getString("result_json")), requestId);
    }

    private void markDone(String requestId, TransferResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("调拨结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m4_transfer_request SET status='DONE', result_json=? "
                + "WHERE request_id=?", json, requestId);
        if (updated == 0)
            throw new IllegalStateException("调拨幂等记录状态更新失败: requestId=" + requestId);
    }

    private TransferResult readResult(String resultJson)
    {
        if (resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("调拨幂等记录缺少结果快照");
        try
        {
            TransferResult stored = RESULT_JSON.readValue(resultJson, TransferResult.class);
            if (stored == null) throw new IllegalStateException("调拨幂等结果反序列化为空");
            return stored;
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("调拨幂等结果反序列化失败", e);
        }
    }

    // ------------------------------------------------------- 幂等（CREATE 独立表）

    /** 命中 CREATE 幂等行的 DONE 且内容一致 → 重放；无行/PENDING → null；内容不一致 → 400。 */
    private TransferResult replayDoneCreate(String requestId, String fingerprint)
    {
        List<RequestRow> rows = createRequestRows(requestId, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameCreateRequest(row, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 CREATE 幂等键处理权：返回 null 表示首次请求（已占位 PENDING）；非 null 表示命中 DONE 重放。
     * create 时真实 transferId 尚未生成，{@code transfer_id} 列 NOT NULL，占位 0，DONE 时回填。
     */
    private TransferResult claimCreateRequest(String requestId, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_transfer_create_request"
                    + "(request_id,transfer_id,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,'PENDING')", requestId, PENDING_TRANSFER_ID, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            return replayOfCreate(requestId, fingerprint);
        }
    }

    private TransferResult replayOfCreate(String requestId, String fingerprint)
    {
        List<RequestRow> rows = createRequestRows(requestId, true);
        if (rows.isEmpty())
            throw new IllegalArgumentException("同一调拨建单请求正在处理中，请稍后重试");
        RequestRow row = rows.get(0);
        requireSameCreateRequest(row, fingerprint);
        if (!"DONE".equals(row.status()))
            throw new IllegalArgumentException("同一调拨建单请求正在处理中，请稍后重试");
        return replayResult(row);
    }

    /** CREATE 幂等键只绑定内容（建单时无 transferId）：内容指纹不一致即 400。 */
    private static void requireSameCreateRequest(RequestRow row, String fingerprint)
    {
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的调拨建单内容与原请求不一致");
    }

    private List<RequestRow> createRequestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT transfer_id,content_fingerprint,status,result_json "
                        + "FROM noon_m4_transfer_create_request WHERE request_id=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getLong("transfer_id"), rs.getString("content_fingerprint"),
                        rs.getString("status"), rs.getString("result_json")), requestId);
    }

    private void markCreateDone(String requestId, long transferId, TransferResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("调拨建单结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m4_transfer_create_request "
                + "SET transfer_id=?,status='DONE',result_json=? WHERE request_id=?",
                transferId, json, requestId);
        if (updated == 0)
            throw new IllegalStateException("调拨建单幂等记录状态更新失败: requestId=" + requestId);
    }

    private record RequestRow(long transferId, String contentFingerprint, String status, String resultJson)
    {
    }

    // ---------------------------------------------------------------- 写入

    /** 在途子批次（契约 §2.2）：SKU/成本/币种/汇率/FIFO 入库时刻全部继承来源批次，不重估不摊头程。 */
    private long insertChildBatch(TransferItem item, SourceBatch source, WarehouseRef to, String transferNo,
                                  int shipped, LocalDateTime now)
    {
        String batchNo = CHILD_BATCH_PREFIX + UUID.randomUUID();
        int splitSeq = nextSplitSeq(source.id());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                            + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,"
                            + "split_seq,split_reason,transfer_no,status) "
                            + "VALUES(?,?,NULL,?,?,?,?,?,?,?,?,'TRANSFER_SPLIT',?,?,'TRANSFER',?,?)",
                    new String[]{"id"});
            ps.setString(1, batchNo);
            ps.setLong(2, item.skuId());
            ps.setLong(3, to.id());
            ps.setString(4, to.siteCode());
            // FIFO 顺序按父批次原始入库时刻延续（契约 §2.2）。
            ps.setObject(5, source.receivedAt());
            ps.setInt(6, shipped);
            ps.setInt(7, shipped);
            ps.setBigDecimal(8, TransferRules.money(source.unitCost()));
            ps.setString(9, source.currency());
            ps.setBigDecimal(10, source.fxRate());
            ps.setLong(11, source.id());
            ps.setInt(12, splitSeq);
            ps.setString(13, transferNo);
            ps.setString(14, STATUS_IN_TRANSIT);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("在途子批次未返回主键");
        return key.longValue();
    }

    /** 同父多次拆批 split_seq 递增：父批次已被 FOR UPDATE 锁定，MAX 读取与插入在同一锁内串行。 */
    private int nextSplitSeq(long parentBatchId)
    {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(split_seq),0) FROM inv_batch "
                + "WHERE parent_batch_id=?", Integer.class, parentBatchId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 四态台账按字段累加（契约 §2.3）：先 SELECT ... FOR UPDATE 再按绝对新值写入；
     * 不存在则插入，唯一键 (sku_id,warehouse_id) 兜并发（撞键后重读再累加）。四态不得为负（应用层前置校验）。
     */
    private void applyLedger(long skuId, long warehouseId, String siteCode, int availableDelta,
                             int inTransitDelta, String scene)
    {
        LedgerState current = lockLedger(skuId, warehouseId);
        if (current != null)
        {
            requireLedgerSite(current.siteCode(), siteCode);
            updateLedger(current, availableDelta, inTransitDelta, scene);
            return;
        }
        if (availableDelta < 0)
            throw new IllegalArgumentException("台账可用量不足（" + scene + "）: SKU " + skuId
                    + " 仓库 " + warehouseId + " 无台账行，需扣 " + (-availableDelta));
        if (inTransitDelta < 0)
            throw new IllegalArgumentException("台账在途量不足（" + scene + "）: SKU " + skuId
                    + " 仓库 " + warehouseId + " 无台账行，需扣 " + (-inTransitDelta));
        try
        {
            jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,"
                            + "qty_in_transit,qty_reserved,qty_unsellable) VALUES(?,?,?,?,?,0,0)",
                    skuId, warehouseId, siteCode, availableDelta, inTransitDelta);
        }
        catch (DuplicateKeyException duplicate)
        {
            // 并发插入同一 (sku,仓库)：另一事务已提交，重读行锁后累加，不重复建行。
            LedgerState raced = lockLedger(skuId, warehouseId);
            if (raced == null)
                throw new IllegalStateException("台账并发插入后读不到行: skuId=" + skuId
                        + "，warehouseId=" + warehouseId);
            requireLedgerSite(raced.siteCode(), siteCode);
            updateLedger(raced, availableDelta, inTransitDelta, scene);
        }
    }

    private void updateLedger(LedgerState current, int availableDelta, int inTransitDelta, String scene)
    {
        int available = current.available() + availableDelta;
        int inTransit = current.inTransit() + inTransitDelta;
        if (available < 0)
            throw new IllegalArgumentException("台账可用量不足（" + scene + "）: SKU " + current.skuId()
                    + " 仓库 " + current.warehouseId() + " 当前可用 " + current.available()
                    + "，需扣 " + (-availableDelta));
        if (inTransit < 0)
            throw new IllegalArgumentException("台账在途量不足（" + scene + "）: SKU " + current.skuId()
                    + " 仓库 " + current.warehouseId() + " 当前在途 " + current.inTransit()
                    + "，需扣 " + (-inTransitDelta));
        jdbc.update("UPDATE inv_stock_ledger SET qty_available=?, qty_in_transit=? WHERE id=?",
                available, inTransit, current.id());
    }

    private LedgerState lockLedger(long skuId, long warehouseId)
    {
        List<LedgerState> rows = jdbc.query("SELECT id,sku_id,warehouse_id,site_code,qty_available,qty_in_transit "
                        + "FROM inv_stock_ledger WHERE sku_id=? AND warehouse_id=? FOR UPDATE",
                (rs, i) -> new LedgerState(rs.getLong("id"), rs.getLong("sku_id"), rs.getLong("warehouse_id"),
                        rs.getString("site_code"), rs.getInt("qty_available"), rs.getInt("qty_in_transit")),
                skuId, warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requireLedgerSite(String existing, String warehouseSite)
    {
        if (!warehouseSite.equals(existing))
            throw new IllegalArgumentException("同一 SKU+仓库的台账站点已为 " + existing + "，与仓库站点 "
                    + warehouseSite + " 不一致，拒绝跨站复用台账");
    }

    /** 库存流水：qty_delta 为 0 时跳过（真 DDL chk_move_delta 禁止 0，见类注释 4）。 */
    private void insertMove(long skuId, long warehouseId, long batchId, int qtyDelta, String changeType,
                            String transferNo, Long operatorId, LocalDateTime now)
    {
        if (qtyDelta == 0) return;
        jdbc.update("INSERT INTO inv_stock_move"
                        + "(move_no,sku_id,warehouse_id,batch_id,change_type,qty_delta,"
                        + "ref_doc_type,ref_doc_no,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                MOVE_NO_PREFIX + UUID.randomUUID(), skuId, warehouseId, batchId, changeType, qtyDelta,
                REF_DOC_TYPE, transferNo, operatorId, now);
    }

    /**
     * 短少消耗流水（契约 §2.5）：move_type=TRANSFER_SHORT、sign=+1、amount=qty*unit_cost（继承，不重估）、
     * biz_event_key=TSHORT:itemId:shortEventId，consumption_no 唯一；唯一键 (biz_event_key,batch_id)
     * 拦截同事件重放（撞键即 400，整事务回滚不双计）。
     */
    private void insertShortConsumption(TransferItem item, ChildBatch child, int diff, String shortEventId,
                                        LocalDateTime now)
    {
        BigDecimal unitCost = TransferRules.money(child.unitCost());
        BigDecimal amount = TransferRules.money(unitCost.multiply(BigDecimal.valueOf(diff)));
        String bizEventKey = SHORT_EVENT_KEY_PREFIX + item.id() + ":" + shortEventId;
        try
        {
            jdbc.update("INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                            + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                            + "period,cross_period,raw_payload,created_at) "
                            + "VALUES(?,?,?,?,NULL,?,?,?,?,?,'TRANSFER_SHORT',1,?,0,NULL,?)",
                    CONSUMPTION_NO_PREFIX + UUID.randomUUID(), bizEventKey, shortEventId, child.id(),
                    item.skuId(), diff, unitCost, amount, now, TransferRules.period(now), now);
        }
        catch (DuplicateKeyException duplicate)
        {
            throw new IllegalArgumentException("短少事件已过账，禁止重复记账: bizEventKey=" + bizEventKey);
        }
    }

    /**
     * 建单号 + 插 DRAFT 单头（契约 §9.2.4）：{@code TR:<SITE>:<UUID前8大写>}。{@code uk_transfer_no}
     * 兜重，撞键换新号重试（同事务内，最多 3 次）。
     */
    private long insertTransfer(String siteCode, long fromWarehouseId, long toWarehouseId, LocalDateTime now)
    {
        for (int attempt = 0; attempt < TRANSFER_NO_ATTEMPTS; attempt++)
        {
            String transferNo = TRANSFER_NO_PREFIX + siteCode + ":"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            KeyHolder keys = new GeneratedKeyHolder();
            try
            {
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO inv_transfer(transfer_no,from_warehouse_id,to_warehouse_id,status,created_at) "
                                    + "VALUES(?,?,?,?,?)",
                            new String[]{"id"});
                    ps.setString(1, transferNo);
                    ps.setLong(2, fromWarehouseId);
                    ps.setLong(3, toWarehouseId);
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
            if (key == null) throw new IllegalStateException("调拨单未返回主键");
            return key.longValue();
        }
        throw new IllegalStateException("调拨单号生成冲突，重试 " + TRANSFER_NO_ATTEMPTS + " 次仍失败");
    }

    /**
     * 来源批次权威校验（契约 §9.2.2，只读、不加锁）：{@code batch_no} 全局唯一，按号取回后逐项比对
     * 仓库/站点/SKU/状态/剩余量，明细返回真实 {@code skuId}（写入调拨行，客户端自报 SKU 仅作选择键）。
     */
    private CreateSourceBatch requireCreateSourceBatch(TransferCreateLine line, WarehouseRef from)
    {
        List<CreateSourceBatch> rows = jdbc.query("SELECT id,batch_no,sku_id,warehouse_id,site_code,"
                        + "qty_remaining,status FROM inv_batch WHERE batch_no=?",
                (rs, i) -> new CreateSourceBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getLong("warehouse_id"), rs.getString("site_code"), rs.getInt("qty_remaining"),
                        rs.getString("status")), line.batchNo());
        if (rows.isEmpty())
            throw new IllegalArgumentException("来源批次不存在: batchNo=" + line.batchNo());
        if (rows.size() > 1)
            throw new IllegalStateException("来源批次号不唯一，数据异常: batchNo=" + line.batchNo());
        CreateSourceBatch source = rows.get(0);
        if (source.warehouseId() != from.id())
            throw new IllegalArgumentException("来源批次不属于源仓: batchNo=" + line.batchNo()
                    + "，批次仓库=" + source.warehouseId() + "，源仓=" + from.id());
        if (source.skuId() != line.skuId())
            throw new IllegalArgumentException("来源批次 SKU " + source.skuId() + " 与提交 SKU "
                    + line.skuId() + " 不一致: batchNo=" + line.batchNo());
        if (!from.siteCode().equals(source.siteCode()))
            throw new IllegalArgumentException("来源批次站点 " + source.siteCode() + " 与源仓站点 "
                    + from.siteCode() + " 不一致: batchNo=" + line.batchNo());
        if (!BATCH_OPEN.equals(source.status()))
            throw new IllegalArgumentException("来源批次状态 " + source.status() + " 不可用于建单（仅 OPEN）: batchNo="
                    + line.batchNo());
        if (source.qtyRemaining() < line.qty())
            throw new IllegalArgumentException("计划量超过来源批次剩余量: batchNo=" + line.batchNo()
                    + "，剩余=" + source.qtyRemaining() + "，计划=" + line.qty());
        return source;
    }

    private record CreateSourceBatch(long id, String batchNo, long skuId, long warehouseId, String siteCode,
                                     int qtyRemaining, String status)
    {
    }

    // ---------------------------------------------------------------- internals

    private WarehouseRef loadWarehouse(long warehouseId, String role)
    {
        WarehouseRef warehouse = loadWarehouseOrNull(warehouseId);
        if (warehouse == null)
            throw new IllegalArgumentException(role + "仓库不存在: id=" + warehouseId);
        if (!"ACTIVE".equals(warehouse.status()))
            throw new IllegalArgumentException(role + "仓库已停用，不能调拨: id=" + warehouseId
                    + "，status=" + warehouse.status());
        if (warehouse.siteCode() == null || warehouse.siteCode().isBlank())
            throw new IllegalArgumentException(role + "仓库未绑定站点，不能调拨: id=" + warehouseId);
        return warehouse;
    }

    private WarehouseRef loadWarehouseOrNull(long warehouseId)
    {
        List<WarehouseRef> rows = jdbc.query("SELECT id,site_code,status FROM md_warehouse WHERE id=?",
                (rs, i) -> new WarehouseRef(rs.getLong("id"), rs.getString("site_code"),
                        rs.getString("status")), warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 调拨在站点内进行：源仓与目标仓必须同站点（跨站调拨属第三轮在途路由）。 */
    private static void requireSameSite(WarehouseRef from, WarehouseRef to, String transferNo)
    {
        if (!from.siteCode().equals(to.siteCode()))
            throw new IllegalArgumentException("调拨单 " + transferNo + " 源仓站点 " + from.siteCode()
                    + " 与目标仓站点 " + to.siteCode() + " 不一致，跨站调拨本轮不支持");
    }

    /** 建单时的同站点校验（尚无单号，文案不带 transferNo）。 */
    private static void requireSameSite(WarehouseRef from, WarehouseRef to)
    {
        if (!from.siteCode().equals(to.siteCode()))
            throw new IllegalArgumentException("新建调拨单源仓站点 " + from.siteCode() + " 与目标仓站点 "
                    + to.siteCode() + " 不一致，跨站调拨本轮不支持");
    }

    private static void requireDistinctWarehouses(long fromId, long toId, String transferNo)
    {
        if (fromId == toId)
            throw new IllegalArgumentException("调拨单 " + transferNo + " 源仓与目标仓不能相同");
    }

    /** 防伪造：请求体仓库 id 必须与调拨单真实源/目标仓一致。 */
    private static void requireHeadWarehouses(TransferHead head, long fromWarehouseId, long toWarehouseId)
    {
        if (head.fromWarehouseId() != fromWarehouseId)
            throw new IllegalArgumentException("调拨单真实源仓 " + head.fromWarehouseId()
                    + " 与请求 fromWarehouseId " + fromWarehouseId + " 不一致: transferId=" + head.id());
        if (head.toWarehouseId() != toWarehouseId)
            throw new IllegalArgumentException("调拨单真实目标仓 " + head.toWarehouseId()
                    + " 与请求 toWarehouseId " + toWarehouseId + " 不一致: transferId=" + head.id());
    }

    private TransferHead lockTransfer(long transferId)
    {
        List<TransferHead> rows = jdbc.query("SELECT id,transfer_no,from_warehouse_id,to_warehouse_id,status,"
                        + "shipped_at,received_at FROM inv_transfer WHERE id=? FOR UPDATE",
                TransferService::mapHead, transferId);
        if (rows.isEmpty()) throw new IllegalArgumentException("调拨单不存在: id=" + transferId);
        return rows.get(0);
    }

    private TransferHead loadTransferOrNull(long transferId)
    {
        List<TransferHead> rows = jdbc.query("SELECT id,transfer_no,from_warehouse_id,to_warehouse_id,status,"
                        + "shipped_at,received_at FROM inv_transfer WHERE id=?",
                TransferService::mapHead, transferId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static TransferHead mapHead(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException
    {
        java.sql.Timestamp shipped = rs.getTimestamp("shipped_at");
        java.sql.Timestamp received = rs.getTimestamp("received_at");
        return new TransferHead(rs.getLong("id"), rs.getString("transfer_no"),
                rs.getLong("from_warehouse_id"), rs.getLong("to_warehouse_id"), rs.getString("status"),
                shipped == null ? null : shipped.toLocalDateTime(),
                received == null ? null : received.toLocalDateTime());
    }

    /** 调拨单行（FOR UPDATE）：itemId = inv_transfer_item.id，sku/batchNo/计划量全部服务端带出。 */
    private TransferItem lockItem(long transferId, long itemId)
    {
        List<TransferItem> rows = jdbc.query("SELECT id,sku_id,batch_no,qty_shipped,"
                        + "COALESCE(qty_received,0) AS qty_received,COALESCE(diff_qty,0) AS diff_qty "
                        + "FROM inv_transfer_item WHERE id=? AND transfer_id=? FOR UPDATE",
                (rs, i) -> new TransferItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("batch_no"),
                        rs.getInt("qty_shipped"), rs.getInt("qty_received"), rs.getInt("diff_qty")),
                itemId, transferId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("调拨单行不属于该调拨单: itemId=" + itemId
                    + "，transferId=" + transferId);
        return rows.get(0);
    }

    /** 来源父批次（按 batch_no + 源仓，FOR UPDATE）。 */
    private SourceBatch lockSourceBatch(long fromWarehouseId, String batchNo)
    {
        List<SourceBatch> rows = jdbc.query("SELECT id,batch_no,sku_id,warehouse_id,site_code,qty_in,"
                        + "qty_remaining,unit_cost,currency,fx_rate,received_at,status FROM inv_batch "
                        + "WHERE batch_no=? AND warehouse_id=? FOR UPDATE",
                (rs, i) -> new SourceBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getLong("warehouse_id"), rs.getString("site_code"), rs.getInt("qty_remaining"),
                        rs.getBigDecimal("unit_cost"), rs.getString("currency"), rs.getBigDecimal("fx_rate"),
                        rs.getTimestamp("received_at").toLocalDateTime(), rs.getString("status")),
                batchNo, fromWarehouseId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("调拨单行来源批次不存在于源仓: batchNo=" + batchNo
                    + "，fromWarehouseId=" + fromWarehouseId);
        return rows.get(0);
    }

    /**
     * 发货生成的子批次（按 父批次号 + 调拨单号 + 目标仓，FOR UPDATE）。同单同父只应有一条；
     * 多条说明数据异常（拆批血缘冲突），拒绝继续。
     */
    private ChildBatch lockChildBatch(TransferItem item, String transferNo, long toWarehouseId)
    {
        List<ChildBatch> rows = jdbc.query("SELECT c.id,c.batch_no,c.sku_id,c.qty_in,c.unit_cost,c.status "
                        + "FROM inv_batch c JOIN inv_batch p ON p.id=c.parent_batch_id "
                        + "WHERE p.batch_no=? AND c.transfer_no=? AND c.warehouse_id=? FOR UPDATE",
                (rs, i) -> new ChildBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getInt("qty_in"), rs.getBigDecimal("unit_cost"), rs.getString("status")),
                item.batchNo(), transferNo, toWarehouseId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("该调拨行尚未发货，不能收货: itemId=" + item.id()
                    + "，batchNo=" + item.batchNo());
        if (rows.size() > 1)
            throw new IllegalStateException("调拨单行存在多个在途子批次，拆批血缘异常: itemId=" + item.id());
        ChildBatch child = rows.get(0);
        if (child.skuId() != item.skuId())
            throw new IllegalStateException("子批次 SKU " + child.skuId() + " 与调拨单行 SKU "
                    + item.skuId() + " 不一致: itemId=" + item.id());
        return child;
    }

    private void requireAllItemsCovered(long transferId, int lineCount)
    {
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM inv_transfer_item WHERE transfer_id=?",
                Integer.class, transferId);
        int expected = items == null ? 0 : items;
        if (expected == 0)
            throw new IllegalArgumentException("调拨单没有可发货的行: transferId=" + transferId);
        if (expected != lineCount)
            throw new IllegalArgumentException("调拨发货必须覆盖全部调拨单行: transferId=" + transferId
                    + "，单行数=" + expected + "，提交行数=" + lineCount);
    }

    /**
     * 站点闸门：指定 siteCode 先 requireSite 再单站过滤；未指定返回全部授权站点
     * （超管=SUPPORTED_SITES、普通用户=已授权，空集合表示零行，调用方不得省略 WHERE）。
     */
    private List<String> siteGate(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
            return List.of(guard.requireSite(TransferRules.siteCode("siteCode", siteCode)));
        return guard.allowedSiteCodes();
    }

    /** 建单行校验：至少 1 行、skuId 正整数、batchNo 非空 ≤64、qty &gt; 0、同一 (skuId,batchNo) 不得重复。 */
    private static List<TransferCreateLine> requireCreateLines(List<TransferCreateLine> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行调拨明细");
        Set<String> seen = new LinkedHashSet<>();
        for (TransferCreateLine line : lines)
        {
            if (line == null) throw new IllegalArgumentException("调拨明细行不能为空");
            TransferRules.requiredId("skuId", line.skuId());
            if (line.batchNo() == null || line.batchNo().isBlank())
                throw new IllegalArgumentException("batchNo 不能为空");
            String batchNo = line.batchNo().trim();
            if (batchNo.length() > 64)
                throw new IllegalArgumentException("batchNo 长度不能超过 64");
            if (line.qty() <= 0)
                throw new IllegalArgumentException("qty 必须大于 0");
            if (!seen.add(line.skuId() + "|" + batchNo))
                throw new IllegalArgumentException("同一批次不能重复提交: skuId=" + line.skuId()
                        + "，batchNo=" + batchNo);
        }
        return List.copyOf(lines);
    }

    /** 发货行校验：至少 1 行、itemId 正整数、qtyShipped &gt; 0、同一 itemId 不得重复提交。 */
    private static List<TransferShipLine> requireShipLines(List<TransferShipLine> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行发货明细");
        Set<Long> seen = new LinkedHashSet<>();
        for (TransferShipLine line : lines)
        {
            if (line == null) throw new IllegalArgumentException("发货明细行不能为空");
            TransferRules.requiredId("itemId", line.itemId());
            if (line.qtyShipped() <= 0)
                throw new IllegalArgumentException("qtyShipped 必须大于 0");
            if (!seen.add(line.itemId()))
                throw new IllegalArgumentException("同一调拨单行不能重复提交: itemId=" + line.itemId());
        }
        return List.copyOf(lines);
    }

    /** 收货行校验：至少 1 行、itemId 正整数、qtyReceived ≥ 0、同一 itemId 不得重复提交。 */
    private static List<TransferReceiveLine> requireReceiveLines(List<TransferReceiveLine> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行收货明细");
        Set<Long> seen = new LinkedHashSet<>();
        for (TransferReceiveLine line : lines)
        {
            if (line == null) throw new IllegalArgumentException("收货明细行不能为空");
            TransferRules.requiredId("itemId", line.itemId());
            if (line.qtyReceived() < 0)
                throw new IllegalArgumentException("qtyReceived 不能为负数");
            if (!seen.add(line.itemId()))
                throw new IllegalArgumentException("同一调拨单行不能重复提交: itemId=" + line.itemId());
        }
        return List.copyOf(lines);
    }

    /** 仓库归属（授权唯一依据）。 */
    private record WarehouseRef(long id, String siteCode, String status)
    {
    }

    private record TransferHead(long id, String transferNo, long fromWarehouseId, long toWarehouseId,
                                String status, LocalDateTime shippedAt, LocalDateTime receivedAt)
    {
    }

    private record TransferItem(long id, long skuId, String batchNo, int qtyShipped, int qtyReceived,
                                int diffQty)
    {
    }

    private record SourceBatch(long id, String batchNo, long skuId, long warehouseId, String siteCode,
                               int qtyRemaining, BigDecimal unitCost, String currency, BigDecimal fxRate,
                               LocalDateTime receivedAt, String status)
    {
    }

    private record ChildBatch(long id, String batchNo, long skuId, int qtyIn, BigDecimal unitCost,
                              String status)
    {
    }

    private record LedgerState(long id, long skuId, long warehouseId, String siteCode, int available,
                               int inTransit)
    {
    }
}
