package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.yx.common.utils.SecurityUtils;
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
 * 部分收货（契约 4.4 + v2 §9.1 幂等 + v2.1 §9.6 授权顺序与内容绑定）。同一事务内：
 * <ol>
 *   <li>入参校验（orderId/requestId/明细）→ operatorId；</li>
 *   <li><b>先授权</b>：锁 pur_order 并按其真实 site_code 调 {@code guard.requireSite}——
 *       无该订单站点权限者即使持有合法 requestId+orderId 也只能得到 403，读不到任何重放结果（§9.6 ①）；</li>
 *   <li><b>再判内容</b>：比对 requestId 已存记录的内容指纹，不一致 400「收货内容与原请求不一致」，不得入账（§9.6 ②）；</li>
 *   <li><b>再谈状态/结果</b>：已 DONE 且内容一致 → 原样重放首次结果（replayed=true、不重复入账、不写审计）；
 *       PENDING → 400「正在处理中」；新请求则状态须 ∈ CONFIRMED/PARTIAL_RECEIVED；</li>
 *   <li>新请求：INSERT PENDING（带指纹）占位 → 逐行 SELECT ... FOR UPDATE 校验数量守恒
 *       qty_received+qty &lt;= qty_ordered → 更新 qty_received、写 noon_m3_receipt 明细
 *       （unit_cost_cny = unit_price × fx_rate，只含采购价）；</li>
 *   <li>重算订单状态（全收齐 RECEIVED，否则 PARTIAL_RECEIVED），写 action=RECEIVE 审计；</li>
 *   <li>提交前把本次结果写回 request 行 status='DONE'、result_json=首次完整结果（replayed=false）。</li>
 * </ol>
 * 任何异常 → 整个事务回滚（PENDING 行一并回滚），同 requestId 可安全重试。
 * 只读查询 {@link #resultOf(String)} 与 receive 共用同一套「订单真实站点」授权，不新增越权通道。
 * 不写 inv_batch / 四态台账（属 M4），不摊头程/关税/包材（Q8）。
 */
@Service
public class ReceiptService
{
    static final String ENTITY = PurchasingAudit.ENTITY_RECEIPT;

    /** 幂等键格式（契约 v2 §9.1）：8–64 位，仅字母数字下划线短横。 */
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final String REQUEST_ID_INVALID = "收货请求缺少有效的 requestId";

    /**
     * result_json 序列化：ReceiptRow 含 LocalDateTime，但 jsr310 未在本模块编译类路径内，
     * 这里用 jackson-databind 自带的 SimpleModule 显式按 ISO-8601 读写，保证首次结果可原样重放。
     */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper()
            .registerModule(new SimpleModule("receipt-result-time")
                    .addSerializer(LocalDateTime.class, new JsonSerializer<LocalDateTime>()
                    {
                        @Override
                        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                                throws java.io.IOException
                        {
                            gen.writeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                        }
                    })
                    .addDeserializer(LocalDateTime.class, new JsonDeserializer<LocalDateTime>()
                    {
                        @Override
                        public LocalDateTime deserialize(JsonParser p, DeserializationContext context)
                                throws java.io.IOException
                        {
                            return LocalDateTime.parse(p.getValueAsString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        }
                    }));

    private static final String RECEIPT_COLUMNS = "id,order_id,order_item_id,qty,unit_cost_cny,received_at";
    private static final String ORDER_COLUMNS = "id,order_no,supplier_id,site_code,currency,total_amount,status,"
            + "source,source_ref_no,operator_id";
    private static final String ITEM_COLUMNS = "id,pur_order_id,sku_id,qty_ordered,qty_received,"
            + "unit_price,currency,fx_rate";

    private static final RowMapper<ReceiptRow> MAPPER = (rs, i) -> new ReceiptRow(
            rs.getLong("id"),
            rs.getObject("order_id", Long.class),
            rs.getObject("order_item_id", Long.class),
            rs.getInt("qty"),
            rs.getBigDecimal("unit_cost_cny"),
            rs.getTimestamp("received_at").toLocalDateTime());

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, i) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getObject("supplier_id", Long.class),
            rs.getString("site_code"),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getString("source_ref_no"),
            rs.getObject("operator_id", Long.class),
            List.of());

    private static final RowMapper<OrderItemRow> ITEM_MAPPER = (rs, i) -> new OrderItemRow(
            rs.getLong("id"),
            rs.getLong("pur_order_id"),
            rs.getLong("sku_id"),
            rs.getInt("qty_ordered"),
            rs.getInt("qty_received"),
            rs.getBigDecimal("unit_price"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final PurchasingAudit audit;

    public ReceiptService(JdbcTemplate jdbc, SiteAuthGuard guard, PurchasingAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /**
     * 只读收货台账（契约 4.4 末尾）：按订单真实站点授权过滤（普通用户仅授权站点、超管全站、
     * 空授权零行；传 siteCode 先 requireSite），orderId 非空时精确过滤。total 为同条件真实总数。
     */
    public PageResult<ReceiptRow> list(String siteCode, int offset, int limit, Long orderId)
    {
        PurchasingRules.offset(offset);
        int size = PurchasingRules.limit(limit);
        Long userId = guard.requireCurrentUserId();
        boolean admin = SecurityUtils.isAdmin(userId);

        List<String> gate;
        if (siteCode != null && !siteCode.isBlank())
            gate = List.of(guard.requireSite(PurchasingRules.siteCode("siteCode", siteCode)));
        else if (!admin)
        {
            gate = guard.allowedSiteCodes();
            if (gate.isEmpty()) return PageResult.empty();
        }
        else gate = null;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (gate != null)
        {
            where.append(" AND o.site_code IN (").append(PurchasingRules.placeholders(gate.size())).append(")");
            args.addAll(gate);
        }
        if (orderId != null)
        {
            if (orderId <= 0) throw new IllegalArgumentException("orderId 必须为正整数");
            where.append(" AND r.order_id=?");
            args.add(orderId);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM noon_m3_receipt r "
                + "JOIN pur_order o ON o.id=r.order_id" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<ReceiptRow> rows = jdbc.query("SELECT r.id,r.order_id,r.order_item_id,r.qty,r.unit_cost_cny,"
                + "r.received_at FROM noon_m3_receipt r JOIN pur_order o ON o.id=r.order_id" + where
                + " ORDER BY r.id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    /**
     * 部分收货（契约 4.4 + v2 §9.1 + v2.1 §9.6）：授权 → 内容指纹 → 状态/结果，之后才入账。
     * 站点权限取订单真实 site_code，且必须在任何幂等判定（PENDING/DONE 分支）之前执行，
     * 因此跨站或授权被撤销者即使持有合法 requestId+orderId 也只能得到 403。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReceiptResult receive(ReceiptInput in)
    {
        if (in == null) throw new IllegalArgumentException("收货请求不能为空");
        PurchasingRules.requiredId("orderId", in.orderId());
        String requestId = requireRequestId(in.requestId());
        List<ReceiptLineInput> lines = requireLines(in.lines());

        Long operatorId = guard.requireCurrentUserId();

        // v2.1 ①：真实站点授权前移，先于 claimRequest / 任何重放读取。
        OrderRow head = lockOrder(in.orderId());
        guard.requireSite(head.siteCode());

        // v2.1 ②：内容指纹先于状态与结果判定（§9.6 优先级：站点授权 > 内容指纹 > 状态/结果）。
        // 已 DONE 的同键同内容请求直接重放，避免「首次已收满转 RECEIVED 后重试」被状态闸门误拒（§9.1 语义）。
        String fingerprint = contentFingerprint(in.orderId(), lines);
        ReceiptResult replayed = replayDone(requestId, in.orderId(), fingerprint);
        if (replayed != null) return replayed;

        if (!PurchasingRules.RECEIVABLE_STATUSES.contains(head.status()))
            throw new IllegalArgumentException("当前状态 " + head.status()
                    + " 不可收货（仅 CONFIRMED/PARTIAL_RECEIVED 可收）: orderId=" + in.orderId());

        replayed = claimRequest(requestId, in.orderId(), operatorId, fingerprint);
        if (replayed != null) return replayed;

        List<OrderItemRow> itemsBefore = itemsOf(in.orderId());
        OrderQtySnapshot before = snapshot(head, itemsBefore, List.of());

        List<ReceiptRow> written = new ArrayList<>(lines.size());
        for (ReceiptLineInput line : lines)
        {
            OrderItemRow item = lockItem(in.orderId(), line.itemId());
            int remaining = item.qtyOrdered() - item.qtyReceived();
            int qty = line.qty();
            if (qty > remaining)
                throw new IllegalArgumentException("收货数量超过可收余量: 行 id=" + item.id()
                        + "，订单量=" + item.qtyOrdered() + "，已收=" + item.qtyReceived()
                        + "，可收余量=" + remaining + "，本次=" + qty);
            int updated = jdbc.update("UPDATE pur_order_item SET qty_received=qty_received+? "
                    + "WHERE id=? AND pur_order_id=? AND qty_received+?<=qty_ordered",
                    qty, item.id(), in.orderId(), qty);
            if (updated == 0)
                throw new IllegalArgumentException("收货并发冲突，请重试: 行 id=" + item.id());

            BigDecimal unitCostCny = PurchasingRules.money(
                    item.unitPrice().multiply(PurchasingRules.fxRate(item.fxRate(), item.currency())));
            LocalDateTime receivedAt = PurchasingAudit.now();
            long receiptId = insertReceipt(in.orderId(), item.id(), qty, unitCostCny, receivedAt, operatorId);
            written.add(new ReceiptRow(receiptId, in.orderId(), item.id(), qty, unitCostCny, receivedAt));
        }

        String newStatus = recomputeStatus(in.orderId());
        int receivedTotal = jdbc.queryForObject("SELECT COALESCE(SUM(qty_received),0) FROM pur_order_item "
                + "WHERE pur_order_id=?", Integer.class, in.orderId());
        jdbc.update("UPDATE pur_order SET status=? WHERE id=?", newStatus, in.orderId());

        OrderRow after = loadHead(in.orderId());
        List<OrderItemRow> itemsAfter = itemsOf(in.orderId());
        OrderQtySnapshot afterSnapshot = snapshot(after, itemsAfter, written);
        audit.record(ENTITY, in.orderId(), PurchasingAudit.ACTION_RECEIVE, before, afterSnapshot);

        // 提交前写回首次结果：status=DONE + result_json（replayed=false）。异常时整事务（含本行）回滚。
        ReceiptResult result = new ReceiptResult(in.orderId(), newStatus, receivedTotal, written, false);
        markDone(requestId, result);
        return result;
    }

    // ---------------------------------------------------------------- 幂等（v2 §9.1 + v2.1 §9.6）

    /**
     * 只读结果查询（v2.1 §9.6 ③）：按 requestId 取幂等行 → 按订单**真实** site_code 走 requireSite
     * → 才返回 DONE 缓存结果（replayed 固定 true）。只读，不写任何表。
     * requestId 非法/不存在/未完成返回 null；订单不存在返回 null；无权抛 {@link org.springframework.security.access.AccessDeniedException}。
     */
    @Transactional(readOnly = true)
    public ReceiptResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<ReceiptRequestRow> rows = requestRows(rid, false);
        if (rows.isEmpty()) return null;
        ReceiptRequestRow row = rows.get(0);

        // 授权口径与 receive 完全一致：以幂等行 order_id → 订单真实 site_code 为唯一依据。
        OrderRow order = loadHeadOrNull(row.orderId());
        if (order == null) return null;
        guard.requireSite(order.siteCode());

        if (!"DONE".equals(row.status())) return null; // PENDING 或已回滚，无结果可展示
        return replayResult(row);
    }

    /**
     * 读幂等行；命中 DONE 且内容一致 → 重放结果（replayed=true）；无行/仍 PENDING → null（交由状态与占位判定）。
     * 内容不一致、或同键已绑定其他订单 → 400，先于状态/结果判定（§9.6 ②）。
     */
    private ReceiptResult replayDone(String requestId, long orderId, String fingerprint)
    {
        List<ReceiptRequestRow> rows = requestRows(requestId, false);
        if (rows.isEmpty()) return null;
        ReceiptRequestRow row = rows.get(0);
        requireSameRequest(row, orderId, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 requestId 处理权。返回 null 表示本次是新请求（已占位 PENDING，含内容指纹），继续后续业务；
     * 返回非 null 表示命中已有 DONE 请求，直接重放首次结果。
     */
    private ReceiptResult claimRequest(String requestId, long orderId, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m3_receipt_request"
                    + "(request_id,order_id,operator_id,content_fingerprint,status) VALUES(?,?,?,?, 'PENDING')",
                    requestId, orderId, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 唯一键（request_id）冲突：同 requestId 已存在（处理中或已完成）。用当前读避免 RR 快照看不到并发提交。
            return replayOf(requestId, orderId, fingerprint);
        }
    }

    private ReceiptResult replayOf(String requestId, long orderId, String fingerprint)
    {
        List<ReceiptRequestRow> rows = requestRows(requestId, true);
        if (rows.isEmpty())
            throw new IllegalArgumentException("同一收货请求正在处理中，请稍后重试");
        ReceiptRequestRow row = rows.get(0);
        requireSameRequest(row, orderId, fingerprint);
        if (!"DONE".equals(row.status()))
            throw new IllegalArgumentException("同一收货请求正在处理中，请稍后重试");
        return replayResult(row);
    }

    /** 同键必须同单、同内容：先查订单身份，再比内容指纹（两者不同时只能看到一个文案）。 */
    private static void requireSameRequest(ReceiptRequestRow row, long orderId, String fingerprint)
    {
        if (row.orderId() != orderId)
            throw new IllegalArgumentException("requestId 已用于其他采购单");
        // 列定义 NOT NULL；NULL 只会出现在旧库未回填的历史行，按内容不一致拒绝而不是放行。
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的收货内容与原请求不一致");
    }

    private ReceiptResult replayResult(ReceiptRequestRow row)
    {
        ReceiptResult stored = readResult(row.resultJson());
        return new ReceiptResult(stored.orderId(), stored.status(), stored.receivedTotalQty(),
                stored.lines(), true);
    }

    private List<ReceiptRequestRow> requestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT order_id,content_fingerprint,status,result_json "
                        + "FROM noon_m3_receipt_request WHERE request_id=?" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new ReceiptRequestRow(rs.getLong("order_id"), rs.getString("content_fingerprint"),
                        rs.getString("status"), rs.getString("result_json")), requestId);
    }

    private void markDone(String requestId, ReceiptResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("收货结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m3_receipt_request SET status='DONE', result_json=? "
                + "WHERE request_id=?", json, requestId);
        if (updated == 0)
            throw new IllegalStateException("收货幂等记录状态更新失败: requestId=" + requestId);
    }

    private ReceiptResult readResult(String resultJson)
    {
        if (resultJson == null || resultJson.isBlank())
            throw new IllegalStateException("收货幂等记录缺少结果快照");
        try
        {
            ReceiptResult stored = RESULT_JSON.readValue(resultJson, ReceiptResult.class);
            if (stored == null) throw new IllegalStateException("收货幂等结果反序列化为空");
            return stored;
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("收货幂等结果反序列化失败", e);
        }
    }

    /** 幂等键校验：非空白、长度 8–64、仅 [A-Za-z0-9_-]，否则 400 统一文案。 */
    private static String requireRequestId(String requestId)
    {
        if (requestId == null || requestId.isBlank() || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException(REQUEST_ID_INVALID);
        return requestId;
    }

    /** 明细行校验：至少 1 行、itemId 合法、qty&gt;0、同一 itemId 不得重复提交。 */
    private static List<ReceiptLineInput> requireLines(List<ReceiptLineInput> lines)
    {
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("至少需要提交 1 行收货明细");
        Set<Long> seen = new LinkedHashSet<>();
        for (ReceiptLineInput line : lines)
        {
            if (line == null) throw new IllegalArgumentException("收货明细行不能为空");
            PurchasingRules.requiredId("itemId", line.itemId());
            PurchasingRules.qty(line.qty(), "qty");
            if (!seen.add(line.itemId()))
                throw new IllegalArgumentException("同一采购单行不能重复提交: itemId=" + line.itemId());
        }
        return List.copyOf(lines);
    }

    /**
     * 请求内容指纹（v2.1 §9.6 ②）：规范化串 = {@code orderId + "|" + 按 itemId 数值升序的 "itemId:qty"}，
     * 逗号连接（如 {@code 12|3:60,7:40}），SHA-256 后取 64 位小写十六进制。UTF-8 编码。
     */
    static String contentFingerprint(long orderId, List<ReceiptLineInput> lines)
    {
        StringBuilder canonical = new StringBuilder().append(orderId).append('|');
        List<ReceiptLineInput> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingLong(ReceiptLineInput::itemId));
        for (int i = 0; i < sorted.size(); i++)
        {
            ReceiptLineInput line = sorted.get(i);
            if (i > 0) canonical.append(',');
            canonical.append(line.itemId()).append(':').append(line.qty());
        }
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** noon_m3_receipt_request 中与幂等判定相关的一行（含内容指纹）。 */
    private record ReceiptRequestRow(long orderId, String contentFingerprint, String status, String resultJson)
    {
    }

    // ---------------------------------------------------------------- internals

    private OrderRow lockOrder(long orderId)
    {
        List<OrderRow> rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM pur_order WHERE id=? FOR UPDATE",
                ORDER_MAPPER, orderId);
        if (rows.isEmpty()) throw new IllegalArgumentException("采购单不存在: id=" + orderId);
        return rows.get(0);
    }

    private OrderItemRow lockItem(long orderId, long itemId)
    {
        List<OrderItemRow> rows = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM pur_order_item "
                        + "WHERE id=? AND pur_order_id=? FOR UPDATE",
                ITEM_MAPPER, itemId, orderId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("采购单行不属于该订单: itemId=" + itemId + "，orderId=" + orderId);
        return rows.get(0);
    }

    private List<OrderItemRow> itemsOf(long orderId)
    {
        return jdbc.query("SELECT " + ITEM_COLUMNS + " FROM pur_order_item WHERE pur_order_id=? ORDER BY id",
                ITEM_MAPPER, orderId);
    }

    private OrderRow loadHead(long orderId)
    {
        List<OrderRow> rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM pur_order WHERE id=?",
                ORDER_MAPPER, orderId);
        return rows.get(0);
    }

    /** 只读场景（resultOf）用：订单不存在返回 null，由调用方决定返回 null 而不是抛错。 */
    private OrderRow loadHeadOrNull(long orderId)
    {
        List<OrderRow> rows = jdbc.query("SELECT " + ORDER_COLUMNS + " FROM pur_order WHERE id=?",
                ORDER_MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 全部行 qty_received == qty_ordered → RECEIVED，否则 PARTIAL_RECEIVED。 */
    private String recomputeStatus(long orderId)
    {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM pur_order_item "
                + "WHERE pur_order_id=? AND qty_received<qty_ordered", Integer.class, orderId);
        if (open == null || open == 0)
        {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM pur_order_item WHERE pur_order_id=?",
                    Integer.class, orderId);
            if (total == null || total == 0)
                throw new IllegalStateException("采购单没有明细行，无法重算状态: id=" + orderId);
            return "RECEIVED";
        }
        return "PARTIAL_RECEIVED";
    }

    private long insertReceipt(long orderId, long itemId, int qty, BigDecimal unitCostCny,
                               LocalDateTime receivedAt, Long operatorId)
    {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con ->
        {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO noon_m3_receipt(order_id,order_item_id,qty,unit_cost_cny,received_at,operator_id) "
                            + "VALUES(?,?,?,?,?,?)", new String[]{"id"});
            ps.setLong(1, orderId);
            ps.setLong(2, itemId);
            ps.setInt(3, qty);
            ps.setObject(4, unitCostCny);
            ps.setObject(5, receivedAt);
            ps.setObject(6, operatorId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("收货明细未返回主键");
        return key.longValue();
    }

    private static OrderQtySnapshot snapshot(OrderRow head, List<OrderItemRow> items, List<ReceiptRow> received)
    {
        List<QtyLine> lines = new ArrayList<>(items.size());
        int total = 0;
        for (OrderItemRow item : items)
        {
            lines.add(new QtyLine(item.id(), item.skuId(), item.qtyOrdered(), item.qtyReceived()));
            total += item.qtyReceived();
        }
        List<ReceiptLineLog> receipts = new ArrayList<>(received.size());
        for (ReceiptRow row : received)
            receipts.add(new ReceiptLineLog(row.orderItemId(), row.qty(), row.unitCostCny()));
        return new OrderQtySnapshot(head.id(), head.status(), total, lines, receipts);
    }

    /** 审计前的订单数量快照（before/after JSON 含 qty_received 前后与本次收货行）。 */
    private record OrderQtySnapshot(long orderId, String status, int receivedTotalQty,
                                    List<QtyLine> items, List<ReceiptLineLog> receipts)
    {
    }

    private record QtyLine(long itemId, long skuId, int qtyOrdered, int qtyReceived)
    {
    }

    private record ReceiptLineLog(Long itemId, int qty, BigDecimal unitCostCny)
    {
    }
}
