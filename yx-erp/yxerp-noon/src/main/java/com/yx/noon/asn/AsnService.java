package com.yx.noon.asn;

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
 * M4 第三轮第三批「FBN 入仓 ASN 收货闭环」后端内核（契约 v1 §3–§8，owner：豆包）。
 *
 * <p>建模为「本地仓 → FBN 仓」的入仓调拨，与 M4 第二轮调拨同构（契约 §1）：
 * {@code log_asn_item.batch_no} = 本地仓已存在的 OPEN 成本批次（承载成本），
 * {@code qty_shipped} = 计划/实发量，{@code qty_received} = FBN 仓实收量，
 * {@code diff_qty = qty_shipped - qty_received} = 短少。
 *
 * <p><b>create（→ DRAFT，只登记）</b>：授权（{@code requireSite} + FBN 目标仓按
 * {@code (warehouseCode,siteCode)} 存在/ACTIVE/is_fbn）→ 行结构校验 → 指纹 → DONE 重放 →
 * 源批次强校验（只读）→ PENDING 占位 → 写 {@code log_asn(DRAFT)} + {@code log_asn_item} + 审计 CREATE →
 * 回填 asn_id 并 markDone。<b>不动库存/批次/流水</b>。
 *
 * <p><b>seal（DRAFT → SEALED，发运）</b>：FOR UPDATE 锁 ASN 头 → 授权（头真实站点 + FBN 仓 + 源仓 ACTIVE/同站）
 * → 指纹 → DONE 重放 → 状态闸门 DRAFT → 占位 → 逐行锁行 + 源批次 → 源批次 CAS 扣减（到 0 关批）→
 * 拆在途子批次（{@code B:ASN:UUID}、{@code TRANSFER_SPLIT}、parent 血缘、{@code ASN_TRANSIT}、{@code asn_no}、
 * {@code IN_TRANSIT}、成本/币种/fx/received_at 继承）→ 台账双边（源仓 available −、FBN 仓 in_transit +）→
 * 流水 {@code ASN_OUT}（−，ref_doc_no=asnNo）→ 头 CAS DRAFT→SEALED({@code sealed_at}) → 审计 SEAL。
 *
 * <p><b>receive（SEALED → RECEIVED，按行实收）</b>：同授权 → 指纹 → DONE 重放 → 状态闸门 SEALED → 占位 →
 * 逐行锁行（{@code qtyReceived <= qtyShipped}）+ 锁在途子批次（{@code asn_no + parent_batch_id + sku + FBN 仓}）
 * → 子批次收敛（实收&gt;0 → OPEN、=0 → CLOSED、{@code qty_remaining=qtyReceived}、CAS 离 IN_TRANSIT 防重收）→
 * 台账双边（FBN 仓 in_transit −{@code qtyShipped}、available +{@code qtyReceived}）→ 流水 {@code ASN_IN}
 * （实收 0 不写）→ 短少写 {@code inv_batch_consumption(ASN_SHORT, biz_event_key=ASNSHORT:itemId:shortEventId)}
 * → 回写行 → 全部子批次离开 IN_TRANSIT 则头 CAS SEALED→RECEIVED → 审计 RECEIVE。支持按行分批。
 *
 * <p><b>幂等</b>（契约 §2）：{@code noon_m4_asn_request} 主键 {@code (request_id, action)}，动作级隔离；
 * 优先级 授权 &gt; 指纹 &gt; DONE 重放 &gt; 状态闸门；PENDING 并发 400「处理中」；异常整事务回滚
 * （PENDING 占位一并消失，同键可安全重试）；DONE 重放零副作用。
 *
 * <p><b>防伪造</b>：入参无 SKU 成本/币种/汇率、无源批次 id、无子批次 id；成本/币种/fx/批次号一律按
 * 行（{@code log_asn_item}）与源批次服务端带出；{@code operatorId} 入参仅结构保留，实际取
 * {@link SiteAuthGuard#requireCurrentUserId()}。
 *
 * <p><b>与契约字面差异（均在报告裁决点标注）</b>：
 * <ol>
 *   <li>类非 {@code final}：{@code @Transactional} 依赖 CGLIB 代理，final 类无法被代理（与既有
 *       TransferService/InboundService 一致）。</li>
 *   <li>子批次号用 {@code B:ASN:<32 位无横线 UUID>}（契约写 UUID8），唯一性更强、前缀一致。
 *   <li>整单封单（seal 无明细入参，发货量 = 登记量），不存在部分行发运。
 *   <li>{@code qtyReceived = 0} 保留子批次 {@code qty_in = qtyShipped}（守真 DDL {@code qty_in > 0}），
 *       且不写 {@code ASN_IN}（守 {@code qty_delta <> 0}）。
 * </ol>
 */
@Service
public class AsnService
{
    static final String ACTION_CREATE = "CREATE";
    static final String ACTION_SEAL = "SEAL";
    static final String ACTION_RECEIVE = "RECEIVE";

    private static final String ASN_NO_PREFIX = "ASN:";
    /** 幂等表 {@code asn_id} 列为 NOT NULL（create 时真实 id 尚未生成），PENDING 期占位 0。 */
    private static final long PENDING_ASN_ID = 0L;
    private static final int ASN_NO_ATTEMPTS = 3;

    private static final String CHILD_BATCH_PREFIX = "B:ASN:";
    private static final String MOVE_NO_PREFIX = "AM:";
    private static final String CONSUMPTION_NO_PREFIX = "AS:";
    private static final String SHORT_EVENT_KEY_PREFIX = "ASNSHORT:";
    private static final String REF_DOC_TYPE = "ASN";
    private static final String SPLIT_REASON = "ASN_TRANSIT";
    private static final String SOURCE_TYPE_SPLIT = "TRANSFER_SPLIT";

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SEALED = "SEALED";
    private static final String STATUS_RECEIVED = "RECEIVED";

    private static final String BATCH_OPEN = "OPEN";
    private static final String BATCH_CLOSED = "CLOSED";
    private static final String BATCH_IN_TRANSIT = "IN_TRANSIT";

    private static final String AUDIT_BIZ_TYPE = "ASN";

    /** result_json 只缓存 AsnResult（无时间字段），普通 ObjectMapper 即可原样重放（同调拨）。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final InventoryAudit audit;

    public AsnService(JdbcTemplate jdbc, SiteAuthGuard guard, InventoryAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- create

    /**
     * 登记 ASN（契约 §4）：只写 DRAFT 头 + 明细行 + 审计，不动库存。
     *
     * <p>顺序：授权 → 行结构校验 → 内容指纹 → DONE 重放 → 源批次强校验（只读）→ PENDING 占位 →
     * 写头/行 → 审计 → 回填 asn_id + DONE。异常整事务回滚（含 PENDING 占位），同键可安全重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsnResult create(AsnCreateInput in)
    {
        if (in == null) throw new IllegalArgumentException("ASN 建单请求不能为空");
        String siteCode = AsnRules.siteCode("siteCode", in.siteCode());
        String warehouseCode = AsnRules.warehouseCode(in.warehouseCode());
        String requestId = AsnRules.requireRequestId(in.requestId());

        Long operatorId = guard.requireCurrentUserId();

        // §4.1 授权先于一切：站点 requireSite；FBN 目标仓必须存在/ACTIVE/is_fbn（按 code+site 解析）。
        guard.requireSite(siteCode);
        WarehouseRef fbn = requireFbnWarehouse(warehouseCode, siteCode);

        // §4.2 行结构校验（排序去重、qty>0、SKU ACTIVE、批次存在/OPEN/站点/SKU/仓库 ACTIVE）。
        List<AsnCreateLine> lines = requireCreateLines(in.lines());

        // §4.3 指纹先于重放判定（授权 > 指纹 > DONE 重放 > 源批次校验 > 占位）。
        String fingerprint = AsnRules.createFingerprint(siteCode, fbn.warehouseCode(), lines);
        AsnResult replayed = replayDoneCreate(requestId, fingerprint);
        if (replayed != null) return replayed;

        // §4.2 源批次强校验（只读、不锁行、不扣减）：批次/SKU/站点/仓库/状态/剩余量全部服务端带出。
        List<SourceBatch> sources = new ArrayList<>(lines.size());
        for (AsnCreateLine line : lines)
            sources.add(requireCreateSourceBatch(line, siteCode, fbn));

        replayed = claimCreate(requestId, operatorId, siteCode, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = AsnRules.now();
        // §4.4 单号服务端生成 ASN:<SITE>:<UUID8 大写>；uk_asn_no 兜重（撞键换号重试）。
        long asnId = insertAsn(siteCode, fbn.warehouseCode(), now);

        int totalQty = 0;
        for (int i = 0; i < lines.size(); i++)
        {
            AsnCreateLine line = lines.get(i);
            SourceBatch source = sources.get(i);
            jdbc.update("INSERT INTO log_asn_item(asn_id,sku_id,partner_sku,batch_no,qty_shipped,"
                            + "qty_received,diff_qty,created_at) VALUES(?,?,?,?,?,0,0,?)",
                    asnId, source.skuId(), line.partnerSku(), source.batchNo(), line.qtyShipped(), now);
            totalQty += line.qtyShipped();
        }
        String asnNo = jdbc.queryForObject("SELECT asn_no FROM log_asn WHERE id=?", String.class, asnId);

        audit.record(AUDIT_BIZ_TYPE, String.valueOf(asnId), ACTION_CREATE,
                "asnNo=" + asnNo + ",site=" + siteCode + ",fbn=" + fbn.warehouseCode()
                        + ",lines=" + lines.size() + ",qty=" + totalQty,
                operatorId);

        AsnResult result = new AsnResult(asnId, asnNo, false, ACTION_CREATE, List.of());
        markDone(requestId, ACTION_CREATE, asnId, result);
        return result;
    }

    // ------------------------------------------------------------------- seal

    /**
     * 封单发运（契约 §5）：扣源仓可用、拆在途子批次、加 FBN 仓在途、写 ASN_OUT 流水。
     *
     * <p>整单封单：seal 无明细入参，发货量 = create 登记量；所有明细行必须一次全部发运
     * （否则会出现"已封单但未落子批次"的行，receive 无从定位批次）。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsnResult seal(AsnSealInput in)
    {
        if (in == null) throw new IllegalArgumentException("ASN 封单请求不能为空");
        AsnRules.requiredId("asnId", in.asnId());
        String requestId = AsnRules.requireRequestId(in.requestId());

        Long operatorId = guard.requireCurrentUserId();

        // 先锁头（串行化同单动作，也是 receive 并发按行收货时"整单收齐"判定的必要前提）。
        AsnHead head = lockAsn(in.asnId());
        guard.requireSite(head.siteCode());
        WarehouseRef fbn = requireFbnWarehouse(head.warehouseCode(), head.siteCode());

        String fingerprint = AsnRules.sealFingerprint(head.id());
        AsnResult replayed = replayDone(requestId, ACTION_SEAL, head, fingerprint);
        if (replayed != null) return replayed;

        // §5 状态闸门：仅 DRAFT 可封单（放重放之后，保证"已封单但响应丢失"的同键重试可重放）。
        if (!STATUS_DRAFT.equals(head.status()))
            throw new IllegalArgumentException("当前 ASN 状态 " + head.status()
                    + " 不可封单（仅 DRAFT 可封单）: asnId=" + head.id());

        replayed = claim(requestId, ACTION_SEAL, head, operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = AsnRules.now();
        List<AsnItem> items = lockItems(head.id());
        if (items.isEmpty())
            throw new IllegalArgumentException("ASN 没有任何明细行，不能封单: asnId=" + head.id());

        List<Long> childBatchIds = new ArrayList<>(items.size());
        int totalShipped = 0;
        for (AsnItem item : items)
        {
            SourceBatch source = lockSourceBatch(item.batchNo());
            if (!BATCH_OPEN.equals(source.status()))
                throw new IllegalArgumentException("源批次状态 " + source.status()
                        + " 不可拆批出库（仅 OPEN 可拆批）: batchNo=" + source.batchNo());
            if (source.skuId() != item.skuId())
                throw new IllegalArgumentException("源批次 SKU " + source.skuId()
                        + " 与 ASN 行 SKU " + item.skuId() + " 不一致: itemId=" + item.id());
            if (!head.siteCode().equals(source.siteCode()))
                throw new IllegalArgumentException("源批次站点 " + source.siteCode()
                        + " 与 ASN 站点 " + head.siteCode() + " 不一致（禁止跨站发运）: batchNo="
                        + source.batchNo());
            WarehouseRef sourceWarehouse = requireActiveWarehouse(source.warehouseId(), "源");
            if (sourceWarehouse.id() == fbn.id())
                throw new IllegalArgumentException("源批次所在仓库与 FBN 目标仓相同，不能自己入仓自己: batchNo="
                        + source.batchNo());
            if (!sourceWarehouse.siteCode().equals(head.siteCode()))
                throw new IllegalArgumentException("源仓站点 " + sourceWarehouse.siteCode()
                        + " 与 ASN 站点 " + head.siteCode() + " 不一致（禁止跨站发运）: batchNo="
                        + source.batchNo());

            // §5.2 守恒：qtyShipped <= 源批次剩余量（FOR UPDATE 下的权威判定）。
            if (item.qtyShipped() > source.qtyRemaining())
                throw new IllegalArgumentException("发运量超过源批次剩余量: batchNo=" + source.batchNo()
                        + "，剩余=" + source.qtyRemaining() + "，本次=" + item.qtyShipped());

            // 源批次 CAS 扣减（显式比较原剩余，避免 MySQL/H2 对 SET 表达式求值差异），扣到 0 关批。
            int remaining = source.qtyRemaining() - item.qtyShipped();
            int deducted = jdbc.update("UPDATE inv_batch SET qty_remaining=?, status=? "
                            + "WHERE id=? AND qty_remaining=?",
                    remaining, remaining == 0 ? BATCH_CLOSED : BATCH_OPEN, source.id(), source.qtyRemaining());
            if (deducted != 1)
                throw new IllegalStateException("源批次并发扣减失败，事务整体回滚: batchNo=" + source.batchNo());

            long childId = insertChildBatch(item, source, fbn, head.asnNo(), now);
            childBatchIds.add(childId);

            // §5.2 台账双边：源仓 available −、FBN 仓 in_transit +。
            applyLedger(item.skuId(), sourceWarehouse.id(), sourceWarehouse.siteCode(),
                    -item.qtyShipped(), 0, "ASN 封单扣源仓可用");
            applyLedger(item.skuId(), fbn.id(), fbn.siteCode(), 0, item.qtyShipped(), "ASN 封单加 FBN 仓在途");

            // §5.2 流水：ASN_OUT，负增量，源仓，ref_doc_no=asnNo。
            insertMove(item.skuId(), sourceWarehouse.id(), childId, -item.qtyShipped(),
                    "ASN_OUT", head.asnNo(), operatorId, now);

            totalShipped += item.qtyShipped();
        }

        int headUpdated = jdbc.update("UPDATE log_asn SET status=?, sealed_at=? WHERE id=? AND status=?",
                STATUS_SEALED, now, head.id(), STATUS_DRAFT);
        if (headUpdated != 1)
            throw new IllegalStateException("ASN 状态并发更新失败，事务整体回滚: asnId=" + head.id());

        audit.record(AUDIT_BIZ_TYPE, String.valueOf(head.id()), ACTION_SEAL,
                "asnNo=" + head.asnNo() + ",fbn=" + fbn.warehouseCode() + ",lines=" + items.size()
                        + ",qty=" + totalShipped + ",children=" + childBatchIds.size(),
                operatorId);

        AsnResult result = new AsnResult(head.id(), head.asnNo(), false, ACTION_SEAL, childBatchIds);
        markDone(requestId, ACTION_SEAL, head.id(), result);
        return result;
    }

    // ---------------------------------------------------------------- receive

    /**
     * 收货（契约 §6）：在途子批次转 OPEN/CLOSED、FBN 仓在途减可用增、短少记 ASN_SHORT。
     *
     * <p>支持按行分批（不同 requestId 覆盖不同行），同一行只能收一次（子批次离开 IN_TRANSIT 即拒）；
     * 全部子批次离开 IN_TRANSIT 时整单转 RECEIVED。
     */
    @Transactional(rollbackFor = Exception.class)
    public AsnResult receive(AsnReceiveInput in)
    {
        if (in == null) throw new IllegalArgumentException("ASN 收货请求不能为空");
        AsnRules.requiredId("asnId", in.asnId());
        String requestId = AsnRules.requireRequestId(in.requestId());
        List<AsnReceiveLine> lines = requireReceiveLines(in.lines());

        Long operatorId = guard.requireCurrentUserId();

        AsnHead head = lockAsn(in.asnId());
        guard.requireSite(head.siteCode());
        WarehouseRef fbn = requireFbnWarehouse(head.warehouseCode(), head.siteCode());

        String fingerprint = AsnRules.receiveFingerprint(head.id(), lines);
        AsnResult replayed = replayDone(requestId, ACTION_RECEIVE, head, fingerprint);
        if (replayed != null) return replayed;

        // §6 状态闸门：SEALED 期可分批收；已 RECEIVED 整单同键重放走上一步重放。
        if (!STATUS_SEALED.equals(head.status()))
            throw new IllegalArgumentException("当前 ASN 状态 " + head.status()
                    + " 不可收货（仅 SEALED 可收货）: asnId=" + head.id());

        replayed = claim(requestId, ACTION_RECEIVE, head, operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = AsnRules.now();
        List<AsnReceiveLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(AsnReceiveLine::asnItemId));

        List<Long> childBatchIds = new ArrayList<>(sorted.size());
        long totalReceived = 0;
        long totalShort = 0;
        for (AsnReceiveLine line : sorted)
        {
            AsnItem item = lockItem(head.id(), line.asnItemId());
            if (line.qtyReceived() > item.qtyShipped())
                throw new IllegalArgumentException("实收量超过发运量: asnItemId=" + item.id()
                        + "，发运=" + item.qtyShipped() + "，本次实收=" + line.qtyReceived());

            ChildBatch child = lockReceiveChild(item, head.asnNo(), fbn.id());
            if (!BATCH_IN_TRANSIT.equals(child.status()))
                throw new IllegalArgumentException("该 ASN 行已收货，禁止重复收货: asnItemId=" + item.id()
                        + "，子批次=" + child.batchNo() + "，状态=" + child.status());

            int diff = item.qtyShipped() - line.qtyReceived();
            String shortEventId = diff > 0 ? AsnRules.requireShortEventId(line.shortEventId())
                    : AsnRules.optionalShortEventId(line.shortEventId());

            // 子批次收敛：qty_remaining = 实收；qty_in 在实收 0 时保留发运量（守 chk_batch_qty qty_in > 0）。
            int childQtyIn = line.qtyReceived() > 0 ? line.qtyReceived() : child.qtyIn();
            String childStatus = line.qtyReceived() > 0 ? BATCH_OPEN : BATCH_CLOSED;
            int childUpdated = jdbc.update("UPDATE inv_batch SET qty_in=?, qty_remaining=?, status=? "
                            + "WHERE id=? AND status=?",
                    childQtyIn, line.qtyReceived(), childStatus, child.id(), BATCH_IN_TRANSIT);
            if (childUpdated != 1)
                throw new IllegalStateException("子批次状态并发更新失败，事务整体回滚: batchNo=" + child.batchNo());

            // §6.4 台账双边：FBN 仓 in_transit − qtyShipped、available + qtyReceived。
            applyLedger(item.skuId(), fbn.id(), fbn.siteCode(), line.qtyReceived(), -item.qtyShipped(),
                    "ASN 收货 FBN 仓在途转可用");

            // §6.5 流水：ASN_IN（实收 0 不写，守 chk_move_delta qty_delta <> 0）。
            insertMove(item.skuId(), fbn.id(), child.id(), line.qtyReceived(),
                    "ASN_IN", head.asnNo(), operatorId, now);

            // §6.6 短少：diff > 0 记一条 ASN_SHORT，事件键 ASNSHORT:asnItemId:shortEventId，唯一键防双计。
            if (diff > 0)
            {
                insertShortConsumption(item, child, diff, shortEventId, now);
                totalShort += diff;
            }

            jdbc.update("UPDATE log_asn_item SET qty_received=?, diff_qty=? WHERE id=?",
                    line.qtyReceived(), diff, item.id());

            childBatchIds.add(child.id());
            totalReceived += line.qtyReceived();
        }

        // §6.8 全部子批次离开 IN_TRANSIT → 整单 RECEIVED（头行已在方法入口 FOR UPDATE，串行化并发按行收货）。
        int stillInTransit = jdbc.queryForObject("SELECT COUNT(*) FROM inv_batch "
                        + "WHERE asn_no=? AND parent_batch_id IS NOT NULL AND status=?",
                Integer.class, head.asnNo(), BATCH_IN_TRANSIT);
        if (stillInTransit == 0)
        {
            int headUpdated = jdbc.update("UPDATE log_asn SET status=? WHERE id=? AND status=?",
                    STATUS_RECEIVED, head.id(), STATUS_SEALED);
            if (headUpdated != 1)
                throw new IllegalStateException("ASN 状态并发更新失败，事务整体回滚: asnId=" + head.id());
        }

        audit.record(AUDIT_BIZ_TYPE, String.valueOf(head.id()), ACTION_RECEIVE,
                "asnNo=" + head.asnNo() + ",fbn=" + fbn.warehouseCode() + ",lines=" + sorted.size()
                        + ",received=" + totalReceived + ",short=" + totalShort,
                operatorId);

        AsnResult result = new AsnResult(head.id(), head.asnNo(), false, ACTION_RECEIVE, childBatchIds);
        markDone(requestId, ACTION_RECEIVE, head.id(), result);
        return result;
    }

    // ---------------------------------------------------------------- read-only

    /**
     * 幂等结果查询（契约 §7）：按 requestId 命中 RECEIVE/SEAL/CREATE 的 DONE 记录（优先级
     * RECEIVE &gt; SEAL &gt; CREATE）→ 按 ASN 头真实 FBN 仓站点 {@code requireSite} → 才返回缓存
     * （{@code replayed=true}）。未命中/PENDING/ASN 不存在返回 null；无权 403。不写任何表。
     */
    @Transactional(readOnly = true)
    public AsnResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = AsnRules.requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<RequestRow> rows = requestRowsOfAnyAction(rid);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        if (!"DONE".equals(row.status())) return null;
        AsnHead head = loadAsnOrNull(row.asnId());
        if (head == null) return null;
        guard.requireSite(head.siteCode());
        AsnResult stored = readResult(row.resultJson());
        if (stored.asnId() == null || stored.asnId() != head.id())
            throw new IllegalStateException("ASN 幂等结果与头记录不一致: requestId=" + rid);
        return new AsnResult(stored.asnId(), stored.asnNo(), true, stored.action(), stored.childBatchIds());
    }

    /** ASN 列表：按授权站点集合过滤（传 siteCode 先 requireSite），total 为同条件真实总数。 */
    public PageResult<AsnRow> list(String siteCode, int offset, int limit)
    {
        AsnRules.offset(offset);
        int size = AsnRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String where = " WHERE a.site_code IN (" + AsnRules.placeholders(gate.size()) + ")";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM log_asn a" + where, Long.class, gate.toArray());
        List<Object> args = new ArrayList<>(gate);
        args.add(size);
        args.add(offset);
        List<AsnRow> rows = jdbc.query(HEAD_SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, args.toArray());
        return new PageResult<>(rows, total);
    }

    /** ASN 详情（带授权：以 ASN 头真实站点 requireSite），返回头 + 行 + 在途子批次血缘。 */
    @Transactional(readOnly = true)
    public AsnDetail get(long asnId)
    {
        AsnRules.requiredId("asnId", asnId);
        AsnHead head = loadAsnOrNull(asnId);
        if (head == null) throw new IllegalArgumentException("ASN 不存在: asnId=" + asnId);
        guard.requireSite(head.siteCode());

        AsnRow row = jdbc.query(HEAD_SELECT + " WHERE a.id=?", HEAD_MAPPER, asnId).get(0);
        List<AsnItemRow> items = jdbc.query("SELECT i.id,i.sku_id,i.partner_sku,i.batch_no,i.qty_shipped,"
                        + "COALESCE(i.qty_received,0) AS qty_received,COALESCE(i.diff_qty,0) AS diff_qty,"
                        + "c.id AS child_id,c.batch_no AS child_batch_no,c.status AS child_status,"
                        + "c.split_seq AS child_split_seq "
                        + "FROM log_asn_item i "
                        + "LEFT JOIN inv_batch p ON p.batch_no=i.batch_no "
                        + "LEFT JOIN inv_batch c ON c.parent_batch_id=p.id AND c.asn_no=? "
                        + "WHERE i.asn_id=? ORDER BY i.id",
                ITEM_MAPPER, head.asnNo(), asnId);
        return new AsnDetail(row, items);
    }

    /** 列表/详情头查询：带明细计数与计划/实收合计。 */
    private static final String HEAD_SELECT = "SELECT a.id,a.asn_no,a.site_code,a.warehouse_code,a.status,"
            + "a.sealed_at,a.created_at,"
            + "(SELECT COUNT(*) FROM log_asn_item i WHERE i.asn_id=a.id) AS item_count,"
            + "(SELECT COALESCE(SUM(i.qty_shipped),0) FROM log_asn_item i WHERE i.asn_id=a.id) AS qty_shipped_total,"
            + "(SELECT COALESCE(SUM(i.qty_received),0) FROM log_asn_item i WHERE i.asn_id=a.id) AS qty_received_total "
            + "FROM log_asn a";

    private static final RowMapper<AsnRow> HEAD_MAPPER = (rs, i) -> new AsnRow(
            rs.getLong("id"),
            rs.getString("asn_no"),
            rs.getString("site_code"),
            rs.getString("warehouse_code"),
            rs.getString("status"),
            rs.getObject("item_count", Integer.class),
            rs.getObject("qty_shipped_total", Integer.class),
            rs.getObject("qty_received_total", Integer.class),
            toLocal(rs.getTimestamp("sealed_at")),
            toLocal(rs.getTimestamp("created_at")));

    private static final RowMapper<AsnItemRow> ITEM_MAPPER = (rs, i) -> new AsnItemRow(
            rs.getLong("id"),
            rs.getObject("sku_id", Long.class),
            rs.getString("partner_sku"),
            rs.getString("batch_no"),
            rs.getObject("qty_shipped", Integer.class),
            rs.getObject("qty_received", Integer.class),
            rs.getObject("diff_qty", Integer.class),
            rs.getObject("child_id", Long.class),
            rs.getString("child_batch_no"),
            rs.getString("child_status"),
            rs.getObject("child_split_seq", Integer.class));

    private static LocalDateTime toLocal(java.sql.Timestamp timestamp)
    {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    // ---------------------------------------------------------------- 幂等（动作级）

    /** 命中 DONE 且内容一致 → 重放；无行/PENDING → null；ASN/内容不一致 → 400。 */
    private AsnResult replayDone(String requestId, String action, AsnHead head, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, action, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameRequest(row, action, head.id(), fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 requestId+action 处理权：返回 null 表示首次（已占位 PENDING）；
     * 非 null 表示命中 DONE 重放；PENDING → 400「处理中」。
     */
    private AsnResult claim(String requestId, String action, AsnHead head, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_asn_request"
                    + "(request_id,action,asn_id,site_code,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,?,?,'PENDING')",
                    requestId, action, head.id(), head.siteCode(), operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 唯一键 (request_id, action) 冲突（处理中或已完成）。当前读避免 RR 旧快照。
            List<RequestRow> rows = requestRows(requestId, action, true);
            if (rows.isEmpty())
                throw new IllegalArgumentException("同一 ASN 请求正在处理中，请稍后重试");
            RequestRow row = rows.get(0);
            requireSameRequest(row, action, head.id(), fingerprint);
            if (!"DONE".equals(row.status()))
                throw new IllegalArgumentException("同一 ASN 请求正在处理中，请稍后重试");
            return replayResult(row);
        }
    }

    /** 同键必须同 ASN、同内容：指纹含动作前缀，跨动作复用 requestId 也会因内容不一致被拒。 */
    private static void requireSameRequest(RequestRow row, String action, long asnId, String fingerprint)
    {
        if (!action.equals(row.action()))
            throw new IllegalArgumentException("requestId 已用于其他 ASN 动作");
        if (row.asnId() != PENDING_ASN_ID && row.asnId() != asnId)
            throw new IllegalArgumentException("requestId 已用于其他 ASN 单: asnId=" + row.asnId());
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的 ASN 内容与原请求不一致（含动作 "
                    + action + "）");
    }

    private AsnResult replayResult(RequestRow row)
    {
        AsnResult stored = readResult(row.resultJson());
        return new AsnResult(stored.asnId(), stored.asnNo(), true, stored.action(), stored.childBatchIds());
    }

    private List<RequestRow> requestRows(String requestId, String action, boolean forUpdate)
    {
        return jdbc.query("SELECT action,asn_id,content_fingerprint,status,result_json "
                        + "FROM noon_m4_asn_request WHERE request_id=? AND action=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getString("action"), rs.getLong("asn_id"),
                        rs.getString("content_fingerprint"), rs.getString("status"),
                        rs.getString("result_json")), requestId, action);
    }

    /** resultOf 用：同一 requestId 的全部动作行，按 RECEIVE &gt; SEAL &gt; CREATE 排序取首条。 */
    private List<RequestRow> requestRowsOfAnyAction(String requestId)
    {
        return jdbc.query("SELECT action,asn_id,content_fingerprint,status,result_json "
                        + "FROM noon_m4_asn_request WHERE request_id=? "
                        + "ORDER BY CASE action WHEN 'RECEIVE' THEN 0 WHEN 'SEAL' THEN 1 WHEN 'CREATE' THEN 2 ELSE 3 END",
                (rs, i) -> new RequestRow(rs.getString("action"), rs.getLong("asn_id"),
                        rs.getString("content_fingerprint"), rs.getString("status"),
                        rs.getString("result_json")), requestId);
    }

    private void markDone(String requestId, String action, long asnId, AsnResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("ASN 结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m4_asn_request SET asn_id=?,status='DONE',result_json=? "
                + "WHERE request_id=? AND action=?", asnId, json, requestId, action);
        if (updated == 0)
            throw new IllegalStateException("ASN 幂等记录状态更新失败: requestId=" + requestId
                    + "，action=" + action);
    }

    private AsnResult readResult(String resultJson)
    {
        if (resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("ASN 幂等记录缺少结果快照");
        try
        {
            AsnResult stored = RESULT_JSON.readValue(resultJson, AsnResult.class);
            if (stored == null) throw new IllegalStateException("ASN 幂等结果反序列化为空");
            return stored;
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("ASN 幂等结果反序列化失败", e);
        }
    }

    // ------------------------------------------------------- 幂等（CREATE，asn_id 占位）

    private AsnResult replayDoneCreate(String requestId, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, ACTION_CREATE, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameCreateRequest(row, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 CREATE 幂等键：返回 null 表示首次（已占位 PENDING，asn_id=0）；非 null 表示命中 DONE 重放。
     * create 时真实 asnId 尚未生成，{@code asn_id} 列 NOT NULL，占位 0，DONE 时回填。
     */
    private AsnResult claimCreate(String requestId, Long operatorId, String siteCode, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_asn_request"
                    + "(request_id,action,asn_id,site_code,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,?,?,'PENDING')",
                    requestId, ACTION_CREATE, PENDING_ASN_ID, siteCode, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            List<RequestRow> rows = requestRows(requestId, ACTION_CREATE, true);
            if (rows.isEmpty())
                throw new IllegalArgumentException("同一 ASN 建单请求正在处理中，请稍后重试");
            RequestRow row = rows.get(0);
            requireSameCreateRequest(row, fingerprint);
            if (!"DONE".equals(row.status()))
                throw new IllegalArgumentException("同一 ASN 建单请求正在处理中，请稍后重试");
            return replayResult(row);
        }
    }

    /** CREATE 幂等键只绑定内容（建单时尚无 asnId）：指纹不一致即 400。 */
    private static void requireSameCreateRequest(RequestRow row, String fingerprint)
    {
        if (!ACTION_CREATE.equals(row.action()))
            throw new IllegalArgumentException("requestId 已用于其他 ASN 动作");
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的 ASN 建单内容与原请求不一致");
    }

    private record RequestRow(String action, long asnId, String contentFingerprint, String status,
                              String resultJson)
    {
    }

    // ---------------------------------------------------------------- 写入

    /** 在途子批次（契约 §5.2）：SKU/成本/币种/汇率/FIFO 入库时刻全部继承源批次，不重估不摊头程。 */
    private long insertChildBatch(AsnItem item, SourceBatch source, WarehouseRef fbn, String asnNo,
                                  LocalDateTime now)
    {
        String batchNo = CHILD_BATCH_PREFIX + UUID.randomUUID().toString().replace("-", "");
        int splitSeq = nextSplitSeq(source.id());
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inv_batch(batch_no,sku_id,pur_order_item_id,warehouse_id,site_code,received_at,"
                            + "qty_in,qty_remaining,unit_cost,currency,fx_rate,source_type,parent_batch_id,"
                            + "split_seq,split_reason,transfer_no,asn_no,status) "
                            + "VALUES(?,?,NULL,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?)",
                    new String[]{"id"});
            ps.setString(1, batchNo);
            ps.setLong(2, item.skuId());
            ps.setLong(3, fbn.id());
            ps.setString(4, fbn.siteCode());
            // FIFO 顺序按父批次原始入库时刻延续（契约 §5.2）。
            ps.setObject(5, source.receivedAt());
            ps.setInt(6, item.qtyShipped());
            ps.setInt(7, item.qtyShipped());
            ps.setBigDecimal(8, AsnRules.money(source.unitCost()));
            ps.setString(9, source.currency());
            ps.setBigDecimal(10, source.fxRate());
            ps.setString(11, SOURCE_TYPE_SPLIT);
            ps.setLong(12, source.id());
            ps.setInt(13, splitSeq);
            ps.setString(14, SPLIT_REASON);
            ps.setString(15, asnNo);
            ps.setString(16, BATCH_IN_TRANSIT);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("ASN 在途子批次未返回主键");
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
     * 四态台账按字段累加（契约 §5.2/§6.4）：先 SELECT ... FOR UPDATE 再按绝对新值写入；不存在则插入，
     * 唯一键 (sku_id,warehouse_id) 兜并发（撞键后重读再累加）。四态不得为负（应用层前置校验）。
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

    /** 库存流水：qty_delta 为 0 时跳过（真 DDL chk_move_delta 禁止 0）。 */
    private void insertMove(long skuId, long warehouseId, long batchId, int qtyDelta, String changeType,
                            String asnNo, Long operatorId, LocalDateTime now)
    {
        if (qtyDelta == 0) return;
        jdbc.update("INSERT INTO inv_stock_move"
                        + "(move_no,sku_id,warehouse_id,batch_id,change_type,qty_delta,"
                        + "ref_doc_type,ref_doc_no,operator_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                MOVE_NO_PREFIX + UUID.randomUUID(), skuId, warehouseId, batchId, changeType, qtyDelta,
                REF_DOC_TYPE, asnNo, operatorId, now);
    }

    /**
     * 短少消耗流水（契约 §6.6）：move_type=ASN_SHORT、sign=+1、amount=qty*unit_cost（取子批次成本，不重估）、
     * biz_event_key=ASNSHORT:asnItemId:shortEventId、consumption_no 唯一；唯一键 (biz_event_key,batch_id)
     * 拦截同事件重放（撞键即 400，整事务回滚不双计）。
     */
    private void insertShortConsumption(AsnItem item, ChildBatch child, int diff, String shortEventId,
                                        LocalDateTime now)
    {
        BigDecimal unitCost = AsnRules.money(child.unitCost());
        BigDecimal amount = AsnRules.money(unitCost.multiply(BigDecimal.valueOf(diff)));
        String bizEventKey = SHORT_EVENT_KEY_PREFIX + item.id() + ":" + shortEventId;
        try
        {
            jdbc.update("INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                            + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                            + "period,cross_period,raw_payload,created_at) "
                            + "VALUES(?,?,?,?,NULL,?,?,?,?,?,'ASN_SHORT',1,?,0,NULL,?)",
                    CONSUMPTION_NO_PREFIX + UUID.randomUUID(), bizEventKey, shortEventId, child.id(),
                    item.skuId(), diff, unitCost, amount, now, AsnRules.period(now), now);
        }
        catch (DuplicateKeyException duplicate)
        {
            throw new IllegalArgumentException("短少事件已过账，禁止重复记账: bizEventKey=" + bizEventKey);
        }
    }

    /**
     * 建号 + 插 DRAFT 头（契约 §4.4）：{@code ASN:<SITE>:<UUID前8大写>}。{@code uk_asn_no}
     * 兜重，撞键换新号重试（同事务内，最多 3 次）。
     */
    private long insertAsn(String siteCode, String warehouseCode, LocalDateTime now)
    {
        for (int attempt = 0; attempt < ASN_NO_ATTEMPTS; attempt++)
        {
            String asnNo = ASN_NO_PREFIX + siteCode + ":"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            KeyHolder keys = new GeneratedKeyHolder();
            try
            {
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO log_asn(asn_no,site_code,warehouse_code,status,created_at) "
                                    + "VALUES(?,?,?,?,?)",
                            new String[]{"id"});
                    ps.setString(1, asnNo);
                    ps.setString(2, siteCode);
                    ps.setString(3, warehouseCode);
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
            if (key == null) throw new IllegalStateException("ASN 头未返回主键");
            return key.longValue();
        }
        throw new IllegalStateException("ASN 号生成冲突，重试 " + ASN_NO_ATTEMPTS + " 次仍失败");
    }

    /**
     * 建单源批次强校验（契约 §4.2，只读、不加锁）：{@code batch_no} 全局唯一，按号取回后逐项比对
     * 站点/SKU/仓库 ACTIVE/状态 OPEN/剩余充足，返回真实 {@code skuId}（写入明细行；客户端自报 SKU 仅作选择键）。
     */
    private SourceBatch requireCreateSourceBatch(AsnCreateLine line, String siteCode, WarehouseRef fbn)
    {
        List<SourceBatch> rows = jdbc.query("SELECT id,batch_no,sku_id,warehouse_id,site_code,"
                        + "qty_remaining,unit_cost,currency,fx_rate,received_at,status "
                        + "FROM inv_batch WHERE batch_no=?",
                (rs, i) -> new SourceBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getLong("warehouse_id"), rs.getString("site_code"), rs.getInt("qty_remaining"),
                        rs.getBigDecimal("unit_cost"), rs.getString("currency"), rs.getBigDecimal("fx_rate"),
                        rs.getTimestamp("received_at").toLocalDateTime(), rs.getString("status")),
                line.batchNo());
        if (rows.isEmpty())
            throw new IllegalArgumentException("源批次不存在: batchNo=" + line.batchNo());
        if (rows.size() > 1)
            throw new IllegalStateException("源批次号不唯一，数据异常: batchNo=" + line.batchNo());
        SourceBatch source = rows.get(0);
        if (source.skuId() != line.skuId())
            throw new IllegalArgumentException("源批次 SKU " + source.skuId() + " 与提交 SKU "
                    + line.skuId() + " 不一致: batchNo=" + line.batchNo());
        if (!siteCode.equals(source.siteCode()))
            throw new IllegalArgumentException("源批次站点 " + source.siteCode() + " 与 ASN 站点 "
                    + siteCode + " 不一致（禁止跨站）: batchNo=" + line.batchNo());
        WarehouseRef sourceWarehouse = requireActiveWarehouse(source.warehouseId(), "源");
        if (!siteCode.equals(sourceWarehouse.siteCode()))
            throw new IllegalArgumentException("源批次所在仓库站点 " + sourceWarehouse.siteCode()
                    + " 与 ASN 站点 " + siteCode + " 不一致（禁止跨站）: batchNo=" + line.batchNo());
        if (sourceWarehouse.id() == fbn.id())
            throw new IllegalArgumentException("源批次所在仓库即 FBN 目标仓，不能自己入仓自己: batchNo="
                    + line.batchNo());
        if (!BATCH_OPEN.equals(source.status()))
            throw new IllegalArgumentException("源批次状态 " + source.status()
                    + " 不可用于建单（仅 OPEN）: batchNo=" + line.batchNo());
        if (source.qtyRemaining() < line.qtyShipped())
            throw new IllegalArgumentException("计划量超过源批次剩余量: batchNo=" + line.batchNo()
                    + "，剩余=" + source.qtyRemaining() + "，计划=" + line.qtyShipped());
        requireActiveSku(source.skuId());
        return source;
    }

    /** SKU 必须存在且 ACTIVE（契约 §4.2，masterdata）。 */
    private void requireActiveSku(long skuId)
    {
        List<String> rows = jdbc.query("SELECT status FROM md_sku WHERE id=?",
                (rs, i) -> rs.getString("status"), skuId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("SKU 不存在: skuId=" + skuId);
        if (!"ACTIVE".equals(rows.get(0)))
            throw new IllegalArgumentException("SKU 已停用，不能登记 ASN: skuId=" + skuId
                    + "，status=" + rows.get(0));
    }

    // ---------------------------------------------------------------- internals

    /** FBN 目标仓（契约 §4.1）：按 (warehouseCode, siteCode) 解析，必须存在、ACTIVE、is_fbn=1 或 type=FBN。 */
    private WarehouseRef requireFbnWarehouse(String warehouseCode, String siteCode)
    {
        List<WarehouseRef> rows = jdbc.query("SELECT id,warehouse_code,site_code,warehouse_type,is_fbn,status "
                        + "FROM md_warehouse WHERE warehouse_code=? AND site_code=?",
                (rs, i) -> new WarehouseRef(rs.getLong("id"), rs.getString("warehouse_code"),
                        rs.getString("site_code"), rs.getString("warehouse_type"),
                        rs.getObject("is_fbn", Integer.class), rs.getString("status")),
                warehouseCode, siteCode);
        if (rows.isEmpty())
            throw new IllegalArgumentException("FBN 目标仓不存在: warehouseCode=" + warehouseCode
                    + "，siteCode=" + siteCode);
        if (rows.size() > 1)
            throw new IllegalStateException("仓库编码+站点不唯一，数据异常: warehouseCode=" + warehouseCode);
        WarehouseRef fbn = rows.get(0);
        if (!"ACTIVE".equals(fbn.status()))
            throw new IllegalArgumentException("FBN 目标仓已停用: warehouseCode=" + warehouseCode
                    + "，status=" + fbn.status());
        boolean flagged = fbn.isFbn() != null && fbn.isFbn() == 1;
        boolean typed = "FBN".equals(fbn.warehouseType());
        if (!flagged && !typed)
            throw new IllegalArgumentException("目标仓不是 FBN 仓（is_fbn=0 且 warehouse_type="
                    + fbn.warehouseType() + "）: warehouseCode=" + warehouseCode);
        return fbn;
    }

    private WarehouseRef requireActiveWarehouse(long warehouseId, String role)
    {
        WarehouseRef warehouse = loadWarehouseOrNull(warehouseId);
        if (warehouse == null)
            throw new IllegalArgumentException(role + "仓库不存在: id=" + warehouseId);
        if (warehouse.siteCode() == null || warehouse.siteCode().isBlank())
            throw new IllegalArgumentException(role + "仓库未绑定站点: id=" + warehouseId);
        if (!"ACTIVE".equals(warehouse.status()))
            throw new IllegalArgumentException(role + "仓库已停用: id=" + warehouseId
                    + "，status=" + warehouse.status());
        return warehouse;
    }

    private WarehouseRef loadWarehouseOrNull(long warehouseId)
    {
        List<WarehouseRef> rows = jdbc.query("SELECT id,warehouse_code,site_code,warehouse_type,is_fbn,status "
                        + "FROM md_warehouse WHERE id=?",
                (rs, i) -> new WarehouseRef(rs.getLong("id"), rs.getString("warehouse_code"),
                        rs.getString("site_code"), rs.getString("warehouse_type"),
                        rs.getObject("is_fbn", Integer.class), rs.getString("status")),
                warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AsnHead lockAsn(long asnId)
    {
        List<AsnHead> rows = jdbc.query("SELECT id,asn_no,site_code,warehouse_code,status,sealed_at "
                        + "FROM log_asn WHERE id=? FOR UPDATE",
                AsnService::mapHead, asnId);
        if (rows.isEmpty()) throw new IllegalArgumentException("ASN 不存在: asnId=" + asnId);
        return rows.get(0);
    }

    private AsnHead loadAsnOrNull(long asnId)
    {
        List<AsnHead> rows = jdbc.query("SELECT id,asn_no,site_code,warehouse_code,status,sealed_at "
                        + "FROM log_asn WHERE id=?", AsnService::mapHead, asnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static AsnHead mapHead(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException
    {
        return new AsnHead(rs.getLong("id"), rs.getString("asn_no"), rs.getString("site_code"),
                rs.getString("warehouse_code"), rs.getString("status"),
                toLocal(rs.getTimestamp("sealed_at")));
    }

    /** ASN 全部明细行（FOR UPDATE，按 id 升序）：整单封单，逐行锁定后逐行发运。 */
    private List<AsnItem> lockItems(long asnId)
    {
        return jdbc.query("SELECT id,sku_id,batch_no,qty_shipped,COALESCE(qty_received,0) AS qty_received "
                        + "FROM log_asn_item WHERE asn_id=? ORDER BY id FOR UPDATE",
                (rs, i) -> new AsnItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("batch_no"),
                        rs.getInt("qty_shipped"), rs.getInt("qty_received")),
                asnId);
    }

    /** ASN 单行（FOR UPDATE）：必须是该 ASN 的行。 */
    private AsnItem lockItem(long asnId, long asnItemId)
    {
        List<AsnItem> rows = jdbc.query("SELECT id,sku_id,batch_no,qty_shipped,"
                        + "COALESCE(qty_received,0) AS qty_received "
                        + "FROM log_asn_item WHERE id=? AND asn_id=? FOR UPDATE",
                (rs, i) -> new AsnItem(rs.getLong("id"), rs.getLong("sku_id"), rs.getString("batch_no"),
                        rs.getInt("qty_shipped"), rs.getInt("qty_received")),
                asnItemId, asnId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("ASN 明细行不属于该 ASN: asnItemId=" + asnItemId
                    + "，asnId=" + asnId);
        return rows.get(0);
    }

    /** 源父批次（按 batch_no 全局唯一，FOR UPDATE）。 */
    private SourceBatch lockSourceBatch(String batchNo)
    {
        List<SourceBatch> rows = jdbc.query("SELECT id,batch_no,sku_id,warehouse_id,site_code,qty_in,"
                        + "qty_remaining,unit_cost,currency,fx_rate,received_at,status FROM inv_batch "
                        + "WHERE batch_no=? FOR UPDATE",
                (rs, i) -> new SourceBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getLong("warehouse_id"), rs.getString("site_code"), rs.getInt("qty_remaining"),
                        rs.getBigDecimal("unit_cost"), rs.getString("currency"), rs.getBigDecimal("fx_rate"),
                        rs.getTimestamp("received_at").toLocalDateTime(), rs.getString("status")),
                batchNo);
        if (rows.isEmpty())
            throw new IllegalArgumentException("ASN 行源批次不存在: batchNo=" + batchNo);
        if (rows.size() > 1)
            throw new IllegalStateException("源批次号不唯一，数据异常: batchNo=" + batchNo);
        return rows.get(0);
    }

    /**
     * 收货时定位在途子批次（裁决点 D4）：{@code 父批次号 + asn_no + sku + FBN 仓}，FOR UPDATE。
     * 同一 ASN 内 (skuId,batchNo) 已在建单去重，故同一定位键只应有一条子批次；多条 = 血缘异常。
     */
    private ChildBatch lockReceiveChild(AsnItem item, String asnNo, long fbnWarehouseId)
    {
        List<ChildBatch> rows = jdbc.query("SELECT c.id,c.batch_no,c.sku_id,c.qty_in,c.unit_cost,c.status "
                        + "FROM inv_batch c JOIN inv_batch p ON p.id=c.parent_batch_id "
                        + "WHERE p.batch_no=? AND c.asn_no=? AND c.sku_id=? AND c.warehouse_id=? FOR UPDATE",
                (rs, i) -> new ChildBatch(rs.getLong("id"), rs.getString("batch_no"), rs.getLong("sku_id"),
                        rs.getInt("qty_in"), rs.getBigDecimal("unit_cost"), rs.getString("status")),
                item.batchNo(), asnNo, item.skuId(), fbnWarehouseId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("该 ASN 行尚未封单发运（找不到在途子批次）: asnItemId=" + item.id()
                    + "，batchNo=" + item.batchNo());
        if (rows.size() > 1)
            throw new IllegalStateException("ASN 明细行存在多个子批次，拆批血缘异常: asnItemId=" + item.id());
        ChildBatch child = rows.get(0);
        if (child.skuId() != item.skuId())
            throw new IllegalStateException("子批次 SKU " + child.skuId() + " 与 ASN 行 SKU "
                    + item.skuId() + " 不一致: asnItemId=" + item.id());
        return child;
    }

    /**
     * 站点闸门：指定 siteCode 先 requireSite 再单站过滤；未指定返回全部授权站点
     * （超管=SUPPORTED_SITES、普通用户=已授权，空集合表示零行，调用方不得省略 WHERE）。
     */
    private List<String> siteGate(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
            return List.of(guard.requireSite(AsnRules.siteCode("siteCode", siteCode)));
        return guard.allowedSiteCodes();
    }

    /**
     * 建单行校验（契约 §4.2）：至少 1 行；skuId 正整数；batchNo 非空 ≤64（trim）；
     * qtyShipped &gt; 0；同一 (skuId,batchNo) 不得重复；partnerSku trim 后空白 → null、≤128。
     */
    private static List<AsnCreateLine> requireCreateLines(List<AsnCreateLine> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行 ASN 明细");
        Set<String> seen = new LinkedHashSet<>();
        List<AsnCreateLine> normalized = new ArrayList<>(lines.size());
        for (AsnCreateLine line : lines)
        {
            if (line == null) throw new IllegalArgumentException("ASN 明细行不能为空");
            AsnRules.requiredId("skuId", line.skuId());
            if (line.batchNo() == null || line.batchNo().isBlank())
                throw new IllegalArgumentException("batchNo 不能为空");
            String batchNo = line.batchNo().trim();
            if (batchNo.length() > 64)
                throw new IllegalArgumentException("batchNo 长度不能超过 64");
            if (line.qtyShipped() == null || line.qtyShipped() <= 0)
                throw new IllegalArgumentException("qtyShipped 必须大于 0");
            String partnerSku = line.partnerSku() == null ? null : line.partnerSku().trim();
            if (partnerSku != null && partnerSku.isEmpty()) partnerSku = null;
            if (partnerSku != null && partnerSku.length() > 128)
                throw new IllegalArgumentException("partnerSku 长度不能超过 128");
            if (!seen.add(line.skuId() + "|" + batchNo))
                throw new IllegalArgumentException("同一批次不能重复提交: skuId=" + line.skuId()
                        + "，batchNo=" + batchNo);
            normalized.add(new AsnCreateLine(line.skuId(), batchNo, line.qtyShipped(), partnerSku));
        }
        return normalized;
    }

    /**
     * 收货行校验（契约 §6）：至少 1 行；asnItemId 正整数；qtyReceived ≥ 0；
     * 同一 asnItemId 不得重复提交；shortEventId 空白 → null、非空须合法。
     */
    private static List<AsnReceiveLine> requireReceiveLines(List<AsnReceiveLine> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行 ASN 收货明细");
        Set<Long> seen = new LinkedHashSet<>();
        List<AsnReceiveLine> normalized = new ArrayList<>(lines.size());
        for (AsnReceiveLine line : lines)
        {
            if (line == null) throw new IllegalArgumentException("ASN 收货明细行不能为空");
            AsnRules.requiredId("asnItemId", line.asnItemId());
            if (line.qtyReceived() == null || line.qtyReceived() < 0)
                throw new IllegalArgumentException("qtyReceived 不能为空或负数");
            if (!seen.add(line.asnItemId()))
                throw new IllegalArgumentException("同一 ASN 明细行不能重复提交: asnItemId=" + line.asnItemId());
            normalized.add(new AsnReceiveLine(line.asnItemId(), line.qtyReceived(),
                    AsnRules.optionalShortEventId(line.shortEventId())));
        }
        return normalized;
    }

    /** 仓库归属（授权唯一依据；FBN 判定带上 warehouse_type/is_fbn）。 */
    private record WarehouseRef(long id, String warehouseCode, String siteCode, String warehouseType,
                                Integer isFbn, String status)
    {
    }

    private record AsnHead(long id, String asnNo, String siteCode, String warehouseCode, String status,
                           LocalDateTime sealedAt)
    {
    }

    private record AsnItem(long id, long skuId, String batchNo, int qtyShipped, int qtyReceived)
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
