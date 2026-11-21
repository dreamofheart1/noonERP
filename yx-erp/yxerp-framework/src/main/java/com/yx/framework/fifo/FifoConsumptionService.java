package com.yx.framework.fifo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * NOON-20261121-FIFO：FIFO 批次出库消耗闸门（独立工作包，阶段一）。
 *
 * <p><b>职责边界（务必先读）</b>：这是<b>内部 Spring service 闸门</b>，不是完整库存/COGS 闭环。
 * 没有 HTTP/UI、不连生产、不做跨期快照重算、不生成平台事件身份、不替代 AI 审批。它只把
 * "销售妥投出库"这一条路径做对并可独立验证：站点授权、可信父订单行、来源事件佐证、
 * FIFO 顺序选批、批次剩余量守恒、妥投量不超订单、台账同步、幂等去重、失败整体回滚。
 *
 * <p><b>平台事实边界</b>：真实事件类型为 FBPI::ORDER_SYNC / FBPO::PO_SYNC / RETURNS::REFERENCE_UPDATE。
 * 本服务 {@code SALE_DELIVERY} 是<b>系统侧内部契约</b>，不是 Noon 平台原生事件类型；真实类型→内部
 * 类型适配层未实现。事件号只信系统侧 {@code int_webhook_event} 留痕，不因调用方字面非空就通过。
 *
 * <p><b>处理顺序</b>：授权 → 锁父订单行 → 来源证据 → 幂等/冲突前置 → 逐行 FIFO 选批（当前读锁）
 * → 批次剩余量守恒 → 妥投量守恒 → 写 SALE 消耗流水 + 扣批次 + 扣台账 → 审计，全程同事务。
 *
 * <p><b>守恒契约</b>：FIFO 按 {@code received_at ASC, id ASC} 只选 {@code status='OPEN'} 且
 * {@code qty_remaining>0} 的批次；批次行用 {@code SELECT ... FOR UPDATE} 当前读锁定，避免并发超扣
 * 与 RR 旧快照。扣到 0 关批（status=CLOSED）。每行妥投的累计过账量（当前读行锁 + long 累加）
 * 不得超订单行可投量（qty - qty_cancelled）。台账 qty_available 同步递减，不足隔离。
 * 锁异常由事务整体回滚；调用方有界同键重试（同 ReversalRetryInvoker 范式）；service 不吞异常。
 * DB 唯一键 (biz_event_key,batch_id) + DuplicateKeyException 兜底并发重传。
 *
 * <p>事务：出库写入、批次扣减、台账扣减与不可变审计同事务，失败整体回滚。
 */
@Service
public class FifoConsumptionService
{
    /** 系统侧内部事件类型契约（非 Noon 平台原生事件类型）。 */
    private static final String INTERNAL_EVENT_TYPE = "SALE_DELIVERY";

    public enum Outcome
    {
        POSTED,       // 通过全部闸门，已写出库消耗/扣批次/扣台账（或同内容重传 ACK）
        NEEDS_REVIEW  // 未通过闸门或证据不足/守恒超限，已隔离，未写正式表
    }

    public record Result(Outcome outcome, Long entityId, String reviewNo, String reasonCode)
    {
        public boolean posted() { return outcome == Outcome.POSTED; }
    }

    /** 妥投明细行：一个 SKU 的数量；订单行身份 salOrderItemId 对所有行一致。 */
    public record DeliveryLine(Long skuId, int qty) { }

    /**
     * 妥投出库入参。sourceEventId 必须能在系统侧 int_webhook_event 佐证；salOrderItemId 指向真实父订单行；
     * lines 为本次妥投的 (sku_id, qty) 明细；服务不猜、不补、不臆造平台事件身份。
     */
    public record DeliveryInput(
            String siteCode,
            String sourceEventId,
            Long salOrderItemId,
            List<DeliveryLine> lines,
            String rawPayload)
    { }

    /** 父订单行真实值：站点/SKU/订单号/可投数量只从数据库取。 */
    private record ParentItem(String siteCode, Long skuId, Long salOrderId, int qty, int qtyDelivered, int qtyCancelled) { }

    /** FIFO 候选批次真实值。 */
    private record Batch(Long id, Long skuId, Long warehouseId, int qtyRemaining, java.math.BigDecimal unitCost,
                         String receivedAt) { }

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public FifoConsumptionService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * 妥投出库过账闸门。
     * 步骤：授权 → 锁父订单行 → 来源证据 → 幂等比对(前置) → 逐行 FIFO 选批 + 批次/妥投守恒
     * → 写消耗流水/扣批次/扣台账/审计，全程同事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result postDelivery(DeliveryInput in)
    {
        long actor = guard.requireCurrentUserId();
        validateInput(in);
        // 1) 站点授权。
        guard.requireSite(in.siteCode());

        // 2) 可信父订单行：站点/SKU/数量只取数据库真实值，FOR UPDATE 当前读锁定。
        ParentItem parent = lockParentItem(in.salOrderItemId());
        if (parent == null)
            return quarantine(in, "PARENT_ORDER_ITEM_MISSING", "父订单行不存在，禁止臆造出库归属", actor);
        if (!in.siteCode().equals(parent.siteCode()))
            return quarantine(in, "SITE_MISMATCH", "父订单行站点与调用方站点不一致，禁止跨站出库", actor);

        // 3) 来源证据绑定。
        String sourceProblem = verifyEvidence(in, parent);
        if (sourceProblem != null)
            return quarantine(in, sourceProblem, "出库来源证据不足或与请求绑定不一致", actor);

        // 4) 幂等/冲突【前置】：biz_event_key=SALE:sourceEventId，完整业务键锁读；命中 ACK 或隔离。
        String bizEventKey = "SALE:" + in.sourceEventId();
        Result idempotent = checkIdempotent(in, parent, bizEventKey, actor);
        if (idempotent != null) return idempotent;

        // 5) 妥投量守恒：该订单行累计已过账 SALE 出库 + 本次 <= 可投量(qty - qty_cancelled)。
        int requested = in.lines().stream().mapToInt(DeliveryLine::qty).sum();
        long already = sumPostedOnItem(parent.salOrderId(), in.salOrderItemId());
        long deliverable = (long) parent.qty() - parent.qtyCancelled();
        if (longSum(already, requested) > deliverable)
            return quarantine(in, "DELIVERY_EXCEEDS_ORDER",
                    "累计妥投将超过订单行可投量（已投 " + already + " + 本次 " + requested
                            + " > 可投 " + deliverable + "）", actor);

        // 6) 两阶段：先【全部判定】再【统一写入】。任何守恒失败都在写入前隔离，
        //    避免"先扣了旧批再发现台账不足"造成部分写入（H2 正反测试实证此坑）。
        LocalDateTime now = now();
        List<Allocation> plan = new ArrayList<>();
        // 同一批次可被多行 FIFO 命中：规划阶段跟踪每批已分配量，避免重复分配超扣。
        java.util.Map<Long, Integer> allocatedPerBatch = new java.util.HashMap<>();
        for (DeliveryLine line : in.lines())
        {
            Result planResult = planOneLine(in, line, actor, plan, allocatedPerBatch);
            if (planResult != null) return planResult; // 写入前的隔离，无部分写入
        }
        if (plan.isEmpty()) throw new IllegalStateException("妥投明细无过账计划，事务应回滚");

        // 7) 统一写入：批次扣减 + 台账扣减 + 消耗流水 + 审计，失败异常则整体回滚。
        List<Long> entityIds = writePlan(in, parent, bizEventKey, actor, now, plan);
        if (entityIds.isEmpty()) throw new IllegalStateException("妥投明细无过账行，事务应回滚");
        audit(null, "POSTED", actor, now,
                "{\"bizEventKey\":\"" + bizEventKey + "\",\"lines\":" + entityIds.size() + "}");
        return new Result(Outcome.POSTED, entityIds.get(0), null, "POSTED");
    }

    /** 一行分配：按 FIFO 顺序锁 OPEN 批次，逐批分配到满足本行 qty；只规划不写，返回隔离 Result 或 null。 */
    private Result planOneLine(DeliveryInput in, DeliveryLine line, long actor, List<Allocation> plan,
                               java.util.Map<Long, Integer> allocatedPerBatch)
    {
        int remaining = line.qty();
        for (Batch batch : openBatches(line.skuId()))
        {
            if (remaining <= 0) break;
            int freeInBatch = batch.qtyRemaining() - allocatedPerBatch.getOrDefault(batch.id(), 0);
            if (freeInBatch <= 0) continue;
            int take = Math.min(remaining, freeInBatch);
            // 台账可用量当前读预判（写入前的判定）：不足则整单隔离，无部分写入。
            Integer avail = jdbc.queryForObject("select qty_available from inv_stock_ledger "
                            + "where sku_id=? and warehouse_id=? and site_code=?",
                    Integer.class, line.skuId(), batch.warehouseId(), in.siteCode());
            if (avail == null || avail < take)
                return quarantine(in, "LEDGER_SHORTAGE",
                        "SKU " + line.skuId() + " 台账可用量不足（可用 " + (avail == null ? 0 : avail)
                                + " < 本次 " + take + "），事务整体回滚", actor);
            plan.add(new Allocation(batch, line.skuId(), take));
            allocatedPerBatch.merge(batch.id(), take, Integer::sum);
            remaining -= take;
        }
        if (remaining > 0)
            return quarantine(in, "BATCH_QTY_SHORTAGE",
                    "SKU " + line.skuId() + " 的 OPEN 批次剩余量合计不足以覆盖妥投 " + line.qty()
                            + "（缺 " + remaining + "），事务整体回滚", actor);
        return null;
    }

    /** 统一写入已判定的分配计划；任何 DAO 异常（含并发撞键）整体回滚，不在半途放行。 */
    private List<Long> writePlan(DeliveryInput in, ParentItem parent, String bizEventKey,
                                 long actor, LocalDateTime now, List<Allocation> plan)
    {
        // 批次扣减按批汇总一次完成（同批可被多行命中），关批用汇总后剩余量显式计算，
        // 不依赖 SQL 表达式对 qty_remaining 的求值顺序（MySQL SET 左到右读新值、H2 读原值）。
        java.util.Map<Long, Integer> totalPerBatch = new java.util.LinkedHashMap<>();
        java.util.Map<Long, Batch> batchById = new java.util.LinkedHashMap<>();
        for (Allocation a : plan)
        {
            totalPerBatch.merge(a.batch().id(), a.qty(), Integer::sum);
            batchById.putIfAbsent(a.batch().id(), a.batch());
        }
        for (java.util.Map.Entry<Long, Integer> e : totalPerBatch.entrySet())
        {
            Batch batch = batchById.get(e.getKey());
            int totalTake = e.getValue();
            String newStatus = (batch.qtyRemaining() - totalTake == 0) ? "CLOSED" : "OPEN";
            int updated = jdbc.update("UPDATE inv_batch SET qty_remaining=qty_remaining-?, status=? "
                            + "WHERE id=? AND qty_remaining>=?",
                    totalTake, newStatus, batch.id(), totalTake);
            if (updated != 1)
                throw new IllegalStateException("批次 " + batch.id() + " 并发扣减失败，事务整体回滚");
        }

        List<Long> entityIds = new ArrayList<>();
        for (Allocation a : plan)
        {
            Batch batch = a.batch();
            int take = a.qty();
            int ledger = jdbc.update("UPDATE inv_stock_ledger SET qty_available=qty_available-? "
                            + "WHERE sku_id=? AND warehouse_id=? AND site_code=? AND qty_available>=?",
                    take, a.skuId(), batch.warehouseId(), in.siteCode(), take);
            if (ledger != 1)
                throw new IllegalStateException("SKU " + a.skuId() + " 台账并发扣减失败，事务整体回滚");

            java.math.BigDecimal amount = batch.unitCost().multiply(java.math.BigDecimal.valueOf(take));
            KeyHolder generated = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                                + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                                + "period,cross_period,raw_payload,created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "FC:" + UUID.randomUUID());
                ps.setString(2, bizEventKey);
                ps.setString(3, in.sourceEventId());
                ps.setLong(4, batch.id());
                ps.setLong(5, in.salOrderItemId());
                ps.setLong(6, a.skuId());
                ps.setInt(7, take);
                ps.setBigDecimal(8, batch.unitCost());
                ps.setBigDecimal(9, amount);
                ps.setTimestamp(10, Timestamp.valueOf(now));
                ps.setString(11, INTERNAL_EVENT_TYPE);
                ps.setInt(12, 1); // SALE 正常扣减
                ps.setString(13, periodOf(now));
                ps.setInt(14, 0);
                ps.setString(15, in.rawPayload());
                ps.setTimestamp(16, Timestamp.valueOf(now));
                return ps;
            }, generated);
            Number key = generated.getKey();
            if (key == null) throw new IllegalStateException("未取得出库流水 ID");
            entityIds.add(key.longValue());
        }
        return entityIds;
    }

    /** 一条分配：批次 + SKU + 本次扣减量。 */
    private record Allocation(Batch batch, Long skuId, int qty) { }

    /** FIFO 候选批次：status=OPEN、qty_remaining>0，按 received_at,id 排序，FOR UPDATE 当前读锁定。 */
    private List<Batch> openBatches(Long skuId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,sku_id,warehouse_id,qty_remaining,unit_cost,received_at FROM inv_batch "
                        + "WHERE sku_id=? AND status='OPEN' AND qty_remaining>0 "
                        + "ORDER BY received_at ASC, id ASC FOR UPDATE", skuId);
        List<Batch> batches = new ArrayList<>();
        for (Map<String, Object> row : rows)
            batches.add(new Batch(((Number) row.get("id")).longValue(),
                    ((Number) row.get("sku_id")).longValue(),
                    ((Number) row.get("warehouse_id")).longValue(),
                    ((Number) row.get("qty_remaining")).intValue(),
                    (java.math.BigDecimal) row.get("unit_cost"),
                    String.valueOf(row.get("received_at"))));
        return batches;
    }

    /** 父订单行：FOR UPDATE 当前读锁定，读取真实站点/SKU/订单归属/数量。 */
    private ParentItem lockParentItem(Long id)
    {
        if (id == null || id <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.sal_order_id,i.sku_id,i.qty,i.qty_delivered,i.qty_cancelled,o.site_code "
                        + "FROM sal_order_item i JOIN sal_order o ON o.id=i.sal_order_id "
                        + "WHERE i.id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new ParentItem((String) row.get("site_code"),
                ((Number) row.get("sku_id")).longValue(),
                ((Number) row.get("sal_order_id")).longValue(),
                ((Number) row.get("qty")).intValue(),
                ((Number) row.get("qty_delivered")).intValue(),
                ((Number) row.get("qty_cancelled")).intValue());
    }

    /** 该订单行累计已过账 SALE 出库数量：当前读行锁 + long 累加（含跨批多行）。 */
    private long sumPostedOnItem(Long salOrderId, Long salOrderItemId)
    {
        long total = 0L;
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,qty FROM inv_batch_consumption "
                        + "WHERE sal_order_item_id=? AND move_type=? AND sign=1 ORDER BY id FOR UPDATE",
                salOrderItemId, INTERNAL_EVENT_TYPE))
            total += ((Number) row.get("qty")).longValue();
        return total;
    }

    /** 幂等/冲突前置：完整业务键锁读；同键核心字段一致 ACK 同一行 ID，不同 SOURCE_CONFLICT。 */
    private Result checkIdempotent(DeliveryInput in, ParentItem parent, String bizEventKey, long actor)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,qty,sku_id,sal_order_item_id FROM inv_batch_consumption "
                        + "WHERE biz_event_key=? ORDER BY id FOR UPDATE", bizEventKey);
        if (rows.isEmpty()) return null;
        if (sameDeliveryBusiness(rows, parent, in))
            return new Result(Outcome.POSTED, ((Number) rows.get(0).get("id")).longValue(), null, "ALREADY_POSTED");
        return quarantine(in, "SOURCE_CONFLICT", "同源妥投事件但核心业务字段不同，禁止当重传 ACK", actor);
    }

    private static boolean sameDeliveryBusiness(List<Map<String, Object>> rows, ParentItem parent, DeliveryInput in)
    {
        return !rows.isEmpty() && rows.stream().allMatch(r -> sameBusinessRow(r, parent, in));
    }

    private static boolean sameBusinessRow(Map<String, Object> row, ParentItem parent, DeliveryInput in)
    {
        long sku = ((Number) row.get("sku_id")).longValue();
        long item = ((Number) row.get("sal_order_item_id")).longValue();
        // 核心业务：订单行身份必须一致，且该行的 SKU 属于本次妥投明细之一。
        return item == in.salOrderItemId().longValue()
                && in.lines().stream().anyMatch(l -> l.skuId() != null && l.skuId().longValue() == sku);
    }

    /** 来源证据绑定：系统侧事件佐证 + payload 业务字段逐项一致。 */
    private String verifyEvidence(DeliveryInput in, ParentItem parent)
    {
        if (!hasText(in.sourceEventId()) || !hasText(in.rawPayload())) return "SOURCE_UNIDENTIFIABLE";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type,site_code,payload_raw,status FROM int_webhook_event WHERE event_id=?",
                in.sourceEventId());
        if (rows.isEmpty()) return "SOURCE_UNVERIFIED";
        Map<String, Object> row = rows.get(0);
        if ("FAILED".equals(row.get("status")) || "KILLED".equals(row.get("status"))) return "SOURCE_UNVERIFIED";
        String eventSite = (String) row.get("site_code");
        if (!hasText(eventSite) || !eventSite.equals(in.siteCode())) return "SOURCE_UNVERIFIED";
        if (!INTERNAL_EVENT_TYPE.equals(row.get("event_type"))) return "SOURCE_UNVERIFIED";
        String eventPayload = textOf(row.get("payload_raw"));
        if (!sameContent(eventPayload, in.rawPayload())) return "SOURCE_UNVERIFIED";
        JsonNode p = parseJson(eventPayload);
        if (p == null || !p.isObject()) return "SOURCE_UNVERIFIED";
        if (!matchesLong(p, "sal_order_item_id", in.salOrderItemId())) return "SOURCE_UNVERIFIED";
        // payload 的 lines 须与入参明细逐项一致（sku_id + qty）。
        JsonNode linesNode = p.get("lines");
        if (linesNode == null || !linesNode.isArray() || linesNode.size() != in.lines().size())
            return "SOURCE_UNVERIFIED";
        for (int i = 0; i < in.lines().size(); i++)
        {
            JsonNode ln = linesNode.get(i);
            DeliveryLine want = in.lines().get(i);
            JsonNode sku = ln.get("sku_id"), qty = ln.get("qty");
            if (sku == null || !sku.canConvertToLong() || sku.asLong() != want.skuId()
                    || qty == null || !qty.canConvertToInt() || qty.asInt() != want.qty())
                return "SOURCE_UNVERIFIED";
        }
        return null;
    }

    private Result quarantine(DeliveryInput in, String reasonCode, String detail, long actor)
    {
        String reviewNo = "FQ:" + UUID.randomUUID();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO noon_fifo_review(review_no,entity_type,site_code_claimed,"
                        + "sal_order_item_id_claimed,source_event_id,raw_payload,reason_code,reason_detail,"
                        + "status,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                reviewNo, "FIFO_DELIVERY", in.siteCode(), in.salOrderItemId(), in.sourceEventId(),
                in.rawPayload(), reasonCode, detail, "NEEDS_REVIEW", actor, Timestamp.valueOf(now));
        audit(reviewNo, "QUARANTINED", actor, now,
                "{\"reason\":\"" + reasonCode + "\",\"reviewNo\":\"" + reviewNo + "\"}");
        return new Result(Outcome.NEEDS_REVIEW, null, reviewNo, reasonCode);
    }

    private void audit(String reviewNo, String action, long actor, LocalDateTime now, String detail)
    {
        jdbc.update("INSERT INTO noon_fifo_audit(audit_no,review_no,entity_type,action,actor_id,act_at,detail_json) "
                        + "VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), reviewNo, "FIFO_DELIVERY", action, actor, Timestamp.valueOf(now), detail);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean matchesLong(JsonNode payload, String field, long expected)
    {
        JsonNode value = payload.get(field);
        return value != null && value.canConvertToLong() && value.asLong() == expected;
    }

    private static String contentDigest(String rawPayload)
    {
        String canonical = rawPayload == null ? "" : rawPayload.replaceAll("\\s+", "");
        JsonNode node = parseJson(rawPayload);
        if (node != null) canonical = sortedJson(node).toString();
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
    }

    private static String textOf(Object value)
    {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }

    private static boolean sameContent(Object storedRaw, String incomingRaw)
    {
        return contentDigest(textOf(storedRaw)).equals(contentDigest(incomingRaw));
    }

    private static JsonNode parseJson(String raw)
    {
        if (raw == null) return null;
        try { return JSON.readTree(raw); }
        catch (Exception e) { return null; }
    }

    private static JsonNode sortedJson(JsonNode node)
    {
        if (node.isObject())
        {
            com.fasterxml.jackson.databind.node.ObjectNode sorted = JSON.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            java.util.Collections.sort(fields);
            for (String field : fields) sorted.set(field, sortedJson(node.get(field)));
            return sorted;
        }
        if (node.isArray())
        {
            com.fasterxml.jackson.databind.node.ArrayNode sorted = JSON.createArrayNode();
            node.forEach(element -> sorted.add(sortedJson(element)));
            return sorted;
        }
        return node.deepCopy();
    }

    private static long longSum(long a, int b) { return a + (long) b; }

    private static String periodOf(LocalDateTime t)
    {
        return String.format(Locale.ROOT, "%04d-%02d", t.getYear(), t.getMonthValue());
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static LocalDateTime now()
    {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void validateInput(DeliveryInput in)
    {
        if (in == null || !hasText(in.siteCode()) || !hasText(in.sourceEventId())
                || in.salOrderItemId() == null || in.salOrderItemId() <= 0
                || in.lines() == null || in.lines().isEmpty()
                || !hasText(in.rawPayload()))
            throw new IllegalArgumentException("妥投出库参数无效");
        for (DeliveryLine line : in.lines())
            if (line == null || line.skuId() == null || line.skuId() <= 0 || line.qty() <= 0)
                throw new IllegalArgumentException("妥投明细行参数无效");
    }
}
