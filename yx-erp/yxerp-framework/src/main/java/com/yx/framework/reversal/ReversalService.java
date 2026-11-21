package com.yx.framework.reversal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * NOON-20261121-REVERSAL：库存/累计退货红冲守恒闸门（独立工作包）。
 *
 * <p><b>职责边界（务必先读）</b>：这是<b>内部 Spring service 闸门</b>，不是完整库存/COGS 业务闭环。
 * 没有 HTTP/UI、不连生产、不做真实 FIFO 批次选择、不做跨期快照重算、不生成平台事件身份、
 * 不替代 AI 审批与数据库最小权限。它只保证：在调用方已具备可信来源与可信父记录的前提下，
 * 把"站点授权、可信父退货、可信原消耗行、累计红冲不超原消耗、幂等去重、冲突不误判为重传、
 * 失败整体回滚"这几条闸门做对并可独立测试。
 *
 * <p><b>平台事实边界（Codex 2026-11-21 只读联调）</b>：真实事件类型是 FBPI::ORDER_SYNC /
 * FBPO::PO_SYNC / RETURNS::REFERENCE_UPDATE，均非 ORDER/RETURN。本服务的
 * {@code RETURN_REVERSAL} 事件类型是<b>系统侧内部契约</b>，不是 Noon 平台原生事件类型；
 * 真实类型→内部类型适配层未实现，禁止把内部类型当平台事实。来源佐证只信系统侧
 * {@code int_webhook_event} 留痕，不因调用方字面非空就通过。
 *
 * <p><b>处理顺序（二次复核修订，务必按此理解）</b>：授权 → 锁原行并校验 sign/类型 →
 * 父退货 → 一致性 → 来源证据 → <b>幂等/冲突判定（前置）</b> → <b>仅确属新增才算累计守恒</b> →
 * 写入。幂等必须前置于守恒：否则原消耗已用满时，相同事件重传会被"已冲回+本次>原消耗"
 * 误判为超限隔离，而不是 ACK（Codex 二次复核意见 1）。
 *
 * <p><b>累计守恒契约</b>：schema v0.5 明确 DDL 只负责单次幂等（uk_consume_biz_event），
 * 累计守恒须应用层事务。以 {@code SELECT ... FOR UPDATE} 锁住原消耗行与父退货行使同组串行；
 * 两次 SUM 一律用 {@code FOR UPDATE} <b>当前读</b>（Codex 二次复核意见 2），避免 MySQL
 * REPEATABLE READ 下上游普通读先建快照导致 SUM 看不到已提交红冲行而超额。锁顺序固定为
 * 原消耗行 → 父退货行 → 汇总行（按 id 升序），全程当前读、无循环等待；撞锁异常由事务
 * 整体回滚，调用方重试（本 service 不吞锁异常、不在半途放行）。确认"已冲回 + 本次 qty"
 * 同时不超过原消耗 qty 与父退货 qty（long 防溢出）才过账；任一超限进待核对，不写正式表。
 * DB 唯一键 + DuplicateKeyException 兜底并发重传。
 *
 * <p>事务：红冲写入与不可变审计同事务，失败整体回滚。
 */
@Service
public class ReversalService
{
    /** 系统侧内部事件类型契约（非 Noon 平台原生事件类型）。 */
    private static final String INTERNAL_EVENT_TYPE = "RETURN_REVERSAL";

    public enum Outcome
    {
        /** 通过全部闸门，已写红冲流水（或同内容重传 ACK）。 */
        POSTED,
        /** 未通过闸门或来源不可核验/冲突/累计超限，已隔离，未写正式表。 */
        NEEDS_REVIEW
    }

    public record Result(Outcome outcome, Long entityId, String reviewNo, String reasonCode)
    {
        public boolean posted() { return outcome == Outcome.POSTED; }
    }

    /**
     * 红冲入参。sourceEventId 必须能在系统侧 int_webhook_event 佐证；salReturnId 必须指向
     * 真实父退货；originalConsumptionId 必须指向真实且 sign=+1 的原消耗行。
     * 服务不猜、不补、不臆造平台事件身份或批次归属。
     */
    public record ReversalInput(
            String siteCode,
            String sourceEventId,
            Long salReturnId,
            Long originalConsumptionId,
            int qty,
            String rawPayload)
    { }

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public ReversalService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** 红冲源证据：可信 payload 或待核对原因码（二者互斥）。 */
    private record EvidenceResult(JsonNode payload, String problem) { }

    /** 父退货真实值：站点、SKU、订单行、数量只从数据库取。 */
    private record ParentReturn(String siteCode, Long skuId, Long salOrderItemId, int qty) { }

    /** 原消耗行真实值：批次、SKU、订单行、数量、成本、类型、sign 只从数据库取。 */
    private record OriginalConsumption(Long id, Long batchId, Long skuId, Long salOrderItemId,
                                       int qty, java.math.BigDecimal unitCost, String period, String moveType,
                                       int sign) { }

    /**
     * 红冲过账闸门。
     * 步骤：站点授权 → 输入校验 → 锁原行(sign/类型) → 父退货/一致性 → 来源证据 →
     * 幂等比对(前置) → 仅确属新增才算累计守恒(当前读) → 写入/隔离，全程同事务。
     * 幂等前置是关键：原消耗已用满时，同一事件重传必须先命中 ACK，不能被守恒误判为超限。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result postReversal(ReversalInput in)
    {
        long actor = guard.requireCurrentUserId();
        validateInput(in);
        // 1) 站点授权：空授权/无权限直接抛 AccessDeniedException，不留隔离。
        guard.requireSite(in.siteCode());

        // 2) 锁原消耗行（FOR UPDATE 当前读）：串行化同原行并发，并读取真实 sign/类型。
        OriginalConsumption orig = lockOriginal(in.originalConsumptionId());
        if (orig == null)
            return quarantine(in, "ORIGINAL_CONSUMPTION_MISSING", "原消耗行不存在，禁止臆造批次消耗", actor);
        if (orig.sign() != 1 || !"SALE".equals(orig.moveType()))
            return quarantine(in, "ORIGINAL_NOT_SALE",
                    "原消耗行不是 sign=+1 的 SALE 正常扣减（实际 sign=" + orig.sign()
                            + ", move_type=" + orig.moveType() + "），禁止红冲非销售扣减", actor);

        // 3) 可信父退货：站点/SKU/订单行/数量只取数据库真实值。
        ParentReturn parent = findParentReturn(in.salReturnId());
        if (parent == null)
            return quarantine(in, "PARENT_RETURN_MISSING", "父退货单不存在，禁止孤儿红冲", actor);
        if (!in.siteCode().equals(parent.siteCode()))
            return quarantine(in, "SITE_MISMATCH", "父退货站点与调用方站点不一致，禁止跨站红冲", actor);
        if (!sameId(orig.skuId(), parent.skuId()) || !sameId(orig.salOrderItemId(), parent.salOrderItemId()))
            return quarantine(in, "ITEM_MISMATCH", "原消耗行 SKU/订单行与父退货不一致，禁止错配红冲", actor);

        // 4) 来源证据绑定：系统侧事件佐证 + payload 业务字段逐项一致。
        String sourceProblem = verifyEvidence(in, parent, orig);
        if (sourceProblem != null)
            return quarantine(in, sourceProblem, "红冲来源证据不足或与请求绑定不一致", actor);

        // 5) 幂等/冲突【前置】：先经可信证据 + 完整业务键判定，命中即 ACK 或冲突隔离，
        //    绝不让已用满的原消耗把合法重传误判为超限（Codex 二次复核意见 1）。
        String bizEventKey = "REV:" + in.sourceEventId();
        Result idempotent = checkIdempotent(in, parent, orig, bizEventKey, actor);
        if (idempotent != null) return idempotent;

        // 6) 累计守恒【仅确属新增】：已锁原行 + 父退货行，SUM 用 FOR UPDATE 当前读
        //    （Codex 二次复核意见 2），long 防溢出（意见 3）。
        long alreadyOnOriginal = sumReversedOnOriginal(orig.id());
        if (longSum(alreadyOnOriginal, in.qty()) > orig.qty())
            return quarantine(in, "REVERSAL_EXCEEDS_ORIGINAL",
                    "累计红冲将超过原消耗数量（已冲回 " + alreadyOnOriginal + " + 本次 " + in.qty()
                            + " > 原消耗 " + orig.qty() + "）", actor);
        long alreadyOnReturn = sumReversedOnReturn(in.salReturnId());
        if (longSum(alreadyOnReturn, in.qty()) > parent.qty())
            return quarantine(in, "REVERSAL_EXCEEDS_RETURN",
                    "累计红冲将超过退货单数量（已冲回 " + alreadyOnReturn + " + 本次 " + in.qty()
                            + " > 退货 qty " + parent.qty() + "）", actor);

        // 7) 写入红冲流水：sign=-1 表示红冲，金额正值乘 sign 即 COGS 减项。
        LocalDateTime now = now();
        java.math.BigDecimal amount = orig.unitCost().multiply(java.math.BigDecimal.valueOf(in.qty()));
        KeyHolder generated = new GeneratedKeyHolder();
        try
        {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO inv_batch_consumption(consumption_no,biz_event_key,source_event_id,batch_id,"
                                + "sal_order_item_id,sku_id,qty,unit_cost,amount,consumed_at,move_type,sign,"
                                + "reversal_of_id,sal_return_id,period,cross_period,raw_payload,created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "CN:" + UUID.randomUUID());
                ps.setString(2, bizEventKey);
                ps.setString(3, in.sourceEventId());
                ps.setLong(4, orig.batchId());
                ps.setLong(5, parent.salOrderItemId());
                ps.setLong(6, parent.skuId());
                ps.setInt(7, in.qty());
                ps.setBigDecimal(8, orig.unitCost());
                ps.setBigDecimal(9, amount);
                ps.setTimestamp(10, Timestamp.valueOf(now));
                ps.setString(11, INTERNAL_EVENT_TYPE);
                ps.setInt(12, -1); // 红冲
                ps.setLong(13, orig.id());
                ps.setLong(14, in.salReturnId());
                ps.setString(15, orig.period());
                ps.setInt(16, 0);
                ps.setString(17, in.rawPayload());
                ps.setTimestamp(18, Timestamp.valueOf(now));
                return ps;
            }, generated);
        }
        catch (DuplicateKeyException concurrent)
        {
            return resolveConcurrent(in, parent, orig, bizEventKey, actor);
        }
        Number key = generated.getKey();
        if (key == null) throw new IllegalStateException("未取得红冲流水 ID");
        audit(null, "POSTED", actor, now,
                "{\"bizEventKey\":\"" + bizEventKey + "\",\"originalId\":" + orig.id() + "}");
        return new Result(Outcome.POSTED, key.longValue(), null, "POSTED");
    }

    /**
     * 幂等/冲突前置判定：完整业务键锁读。命中且核心业务一致 → ACK（返回既有行 ID）；
     * 同键核心业务不同 → SOURCE_CONFLICT 隔离；未命中返回 null 表示确属新增，继续守恒检查。
     */
    private Result checkIdempotent(ReversalInput in, ParentReturn parent, OriginalConsumption orig,
                                   String bizEventKey, long actor)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,qty,sku_id,sal_return_id,reversal_of_id FROM inv_batch_consumption "
                        + "WHERE biz_event_key=? AND batch_id=? FOR UPDATE", bizEventKey, orig.batchId());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        return sameReversalBusiness(row, parent, orig, in)
                ? new Result(Outcome.POSTED, ((Number) row.get("id")).longValue(), null, "ALREADY_POSTED")
                : quarantine(in, "SOURCE_CONFLICT", "同源红冲事件但核心业务字段不同，禁止当重传 ACK", actor);
    }

    /** 并发撞唯一键后完整键锁读回读：同业务 ACK 并返回同一行 ID，不同隔离。 */
    private Result resolveConcurrent(ReversalInput in, ParentReturn parent, OriginalConsumption orig,
                                     String bizEventKey, long actor)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,qty,sku_id,sal_return_id,reversal_of_id FROM inv_batch_consumption "
                        + "WHERE biz_event_key=? AND batch_id=? FOR UPDATE", bizEventKey, orig.batchId());
        if (rows.size() == 1 && sameReversalBusiness(rows.get(0), parent, orig, in))
            return new Result(Outcome.POSTED, ((Number) rows.get(0).get("id")).longValue(), null, "ALREADY_POSTED");
        return quarantine(in, "SOURCE_CONFLICT", "并发红冲撞唯一键且核心业务字段不一致", actor);
    }

    /** 红冲核心业务字段等同判定：数量/SKU/父退货/原消耗行。两入口共用，语义一致。 */
    private static boolean sameReversalBusiness(Map<String, Object> row, ParentReturn parent,
                                                OriginalConsumption orig, ReversalInput in)
    {
        return ((Number) row.get("qty")).intValue() == in.qty()
                && sameId(((Number) row.get("sku_id")).longValue(), parent.skuId())
                && sameId(((Number) row.get("sal_return_id")).longValue(), in.salReturnId())
                && sameId(((Number) row.get("reversal_of_id")).longValue(), orig.id());
    }

    /** 锁原消耗行并读取真实值（FOR UPDATE 当前读；含 sign 供显式校验）。 */
    private OriginalConsumption lockOriginal(Long id)
    {
        if (id == null || id <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,batch_id,sku_id,sal_order_item_id,qty,unit_cost,period,move_type,sign "
                        + "FROM inv_batch_consumption WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new OriginalConsumption(((Number) row.get("id")).longValue(),
                ((Number) row.get("batch_id")).longValue(),
                ((Number) row.get("sku_id")).longValue(),
                row.get("sal_order_item_id") == null ? null : ((Number) row.get("sal_order_item_id")).longValue(),
                ((Number) row.get("qty")).intValue(),
                (java.math.BigDecimal) row.get("unit_cost"),
                (String) row.get("period"),
                (String) row.get("move_type"),
                ((Number) row.get("sign")).intValue());
    }

    private ParentReturn findParentReturn(Long id)
    {
        if (id == null || id <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT site_code,sku_id,sal_order_item_id,qty FROM sal_return WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new ParentReturn((String) row.get("site_code"),
                ((Number) row.get("sku_id")).longValue(),
                row.get("sal_order_item_id") == null ? null : ((Number) row.get("sal_order_item_id")).longValue(),
                ((Number) row.get("qty")).intValue());
    }

    /**
     * 已冲回原消耗行数量：先 {@code SELECT id,qty ... FOR UPDATE} 当前读锁住所有匹配红冲行
     * （按 id 升序固定锁序），再在 Java 端以 long 累加。这样避免 MySQL RR 下普通一致性读
     * 使用旧快照看不到已提交红冲行而超额（Codex 二次复核意见 2），且跨 MySQL/H2 兼容
     * （H2 不支持聚合 + FOR UPDATE）。行锁在当前事务内读到最新已提交值，杜绝幻读。
     */
    private long sumReversedOnOriginal(Long originalId)
    {
        return sumLocked("SELECT id,qty FROM inv_batch_consumption "
                + "WHERE reversal_of_id=? AND sign=-1 ORDER BY id FOR UPDATE", originalId);
    }

    /** 已红冲父退货数量：当前读行锁 + long 累加，理由同上。 */
    private long sumReversedOnReturn(Long returnId)
    {
        return sumLocked("SELECT id,qty FROM inv_batch_consumption "
                + "WHERE sal_return_id=? AND sign=-1 ORDER BY id FOR UPDATE", returnId);
    }

    /** 当前读锁行后 long 域求和，避免 int 溢出。 */
    private long sumLocked(String sql, Object key)
    {
        long total = 0L;
        for (Map<String, Object> row : jdbc.queryForList(sql, key))
            total += ((Number) row.get("qty")).longValue();
        return total;
    }

    /** long 域求和，避免两个 int 相加溢出。 */
    private static long longSum(long a, int b) { return a + (long) b; }

    /**
     * 来源证据绑定：系统侧 int_webhook_event 中存在同 event_id、站点非空且等于请求站点、
     * 状态非 FAILED/KILLED、event_type 为内部约定类型、payload_raw 与请求报文内容一致，
     * 且 payload 中 sal_return_id/reversal_of_id/qty/sku_id 与入参/父记录一致才算可核验。
     */
    private String verifyEvidence(ReversalInput in, ParentReturn parent, OriginalConsumption orig)
    {
        if (!hasText(in.sourceEventId()) || !hasText(in.rawPayload())) return "SOURCE_UNIDENTIFIABLE";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type,site_code,payload_raw,status FROM int_webhook_event WHERE event_id=?",
                in.sourceEventId());
        if (rows.isEmpty()) return "SOURCE_UNVERIFIED";
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        if ("FAILED".equals(status) || "KILLED".equals(status)) return "SOURCE_UNVERIFIED";
        String eventSite = (String) row.get("site_code");
        if (!hasText(eventSite) || !eventSite.equals(in.siteCode())) return "SOURCE_UNVERIFIED";
        if (!INTERNAL_EVENT_TYPE.equals(row.get("event_type"))) return "SOURCE_UNVERIFIED";
        String eventPayload = textOf(row.get("payload_raw"));
        if (!sameContent(eventPayload, in.rawPayload())) return "SOURCE_UNVERIFIED";
        JsonNode p = parseJson(eventPayload);
        if (p == null || !p.isObject()) return "SOURCE_UNVERIFIED";
        if (!matchesLong(p, "sal_return_id", in.salReturnId())
                || !matchesLong(p, "reversal_of_id", in.originalConsumptionId())
                || !matchesLong(p, "qty", in.qty())
                || !matchesLong(p, "sku_id", parent.skuId()))
            return "SOURCE_UNVERIFIED";
        return null;
    }

    /** 隔离到待核对层；原样保留来源标识与报文，不生成任何平台/业务身份。 */
    private Result quarantine(ReversalInput in, String reasonCode, String detail, long actor)
    {
        String reviewNo = "RV:" + UUID.randomUUID();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO noon_reversal_review(review_no,entity_type,site_code_claimed,"
                        + "sal_return_id_claimed,original_consumption_id_claimed,source_event_id,raw_payload,"
                        + "reason_code,reason_detail,status,created_by,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                reviewNo, "REVERSAL", in.siteCode(), in.salReturnId(), in.originalConsumptionId(),
                in.sourceEventId(), in.rawPayload(), reasonCode, detail, "NEEDS_REVIEW", actor,
                Timestamp.valueOf(now));
        audit(reviewNo, "QUARANTINED", actor, now,
                "{\"reason\":\"" + reasonCode + "\",\"reviewNo\":\"" + reviewNo + "\"}");
        return new Result(Outcome.NEEDS_REVIEW, null, reviewNo, reasonCode);
    }

    private void audit(String reviewNo, String action, long actor, LocalDateTime now, String detail)
    {
        jdbc.update("INSERT INTO noon_reversal_audit(audit_no,review_no,entity_type,action,actor_id,act_at,detail_json) "
                        + "VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), reviewNo, "REVERSAL", action, actor, Timestamp.valueOf(now), detail);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean matchesLong(JsonNode payload, String field, long expected)
    {
        JsonNode value = payload.get(field);
        return value != null && value.canConvertToLong() && value.asLong() == expected;
    }

    /** 内容摘要：JSON 按键递归排序后 SHA-256，兼容 MySQL JSON 列字段重排。 */
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
            java.util.List<String> fields = new java.util.ArrayList<>();
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

    private static boolean sameId(Long a, Long b) { return a != null && b != null && a.longValue() == b.longValue(); }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static LocalDateTime now()
    {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void validateInput(ReversalInput in)
    {
        if (in == null || !hasText(in.siteCode()) || in.salReturnId() == null || in.salReturnId() <= 0
                || in.originalConsumptionId() == null || in.originalConsumptionId() <= 0 || in.qty() <= 0)
            throw new IllegalArgumentException("红冲入账参数无效");
    }
}
