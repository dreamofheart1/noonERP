package com.yx.framework.intake;

import java.math.BigDecimal;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yx.framework.security.site.SiteAuthGuard;

/**
 * NOON-20261121-RETURN：订单/退货正式入账安全闸门（四次复核修订版）。
 *
 * <p><b>职责边界（务必先读）</b>：这是一个<b>内部 Spring service 闸门</b>，不是完整订单/退货业务闭环。
 * 它没有 HTTP/用户入口、没有管理 UI、不连生产、不自动过账 FIFO、不生成平台事件身份、
 * 不替代 AI 审批与数据库最小权限。接入 Controller/权限由后续工作包负责。
 * 本类只保证：在调用方已具备可信来源与主数据的前提下，把"站点授权、可信父记录、
 * 来源可核验且证据绑定、正式表无占位、冲突不误判为重传、失败整体回滚"这几条闸门做对并可独立测试。
 *
 * <p><b>可信来源契约（二次复核意见 2）</b>：来源可信必须同时满足——系统侧
 * {@code int_webhook_event} 存在同 event_id、站点一致、状态非 FAILED/KILLED、event_type
 * 与实体类型一致、系统接收的 payload_raw 与本次请求报文内容一致，且 payload 中携带的
 * 订单号/退货号/门店/商品/金额/数量等字段与请求一致。任一项不满足 → 待核对
 * （SOURCE_UNIDENTIFIABLE / SOURCE_UNVERIFIED），不写正式表。调用方字面非空、非空正数均
 * 不作为已验证事实。内容指纹与业务指纹均由服务端现算，不信自报值。
 * <b>本包只覆盖有系统侧 webhook 留痕的订单/退货</b>；Noon 是否存在无 webhook 的真实订单
 * 须按平台实际能力核实，不得假定全部订单都有事件——无事件即待核对，不臆造身份。
 *
 * <p><b>主数据契约（三次复核意见 3）</b>：门店/商品/Partner SKU 不仅要在可信 payload 中一致，
 * 还必须在本地主数据中存在且映射正确——{@code md_store} 通过 {@code md_site} 归属请求站点、
 * {@code md_sku} 存在、{@code md_partner_sku} 的 partner_sku↔sku_id↔站点映射存在且 ACTIVE。
 * 未建立可信映射一律隔离（MASTER_DATA_UNVERIFIED），不把平台数字当系统内部 ID。
 * 金额允许为零（如全折扣、样品单），但必须非空且必须被可信 payload 佐证。
 * 退货商品从可信父订单行取真实 sku_id，不臆造、不写 NULL。
 *
 * <p><b>幂等/回读契约（三次复核意见 4 + 四次复核意见）</b>：同站同号 ACK 使用完整业务键 + 锁读回读，
 * 直接返回该行 ID；订单业务指纹<b>只含核心业务字段</b>（站点/订单号/门店/商品/Partner SKU/
 * 金额/数量/币种），不含整份报文；退货两个幂等入口（正常重复 postReturn 与并发回读
 * resolveConcurrentReturn）统一改用<b>退货核心业务字段</b>比较——站点 + 订单号 + 平台退货号 +
 * 父订单行 + 数量（<b>不含事件 ID、不含整份 raw_payload</b>）。相应字段已经落在
 * {@code biz_return_key}（站点/订单/退货号/父行）与 qty/sal_order_item_id 列上，
 * 由 {@link #sameReturnBusiness} 统一判定，两个入口语义一致。
 * 冲突语义：核心业务字段不同 → SOURCE_CONFLICT 隔离，不覆盖已入账记录；仅事件元数据/报文格式
 * 变化 → ACK。空报文不构成已证明重传。
 *
 * <p><b>平台事实边界（三次复核意见 3）</b>：{@code int_webhook_event.event_type} 的
 * ORDER/RETURN 取值及 payload 字段是本工作包的<b>系统侧可信入口契约</b>，不是对 Noon 原始
 * webhook 格式或事件类型语义的声明；真实平台报文样本、事件元数据字段、退货 event ID 作用域
 * 仍待只读联调/平台文档核实，未核实前不把自定义测试报文当平台事实。
 *
 * <p>站点授权：调用方声明站点必须先经 {@link SiteAuthGuard#requireSite}；不信任声明本身；
 * 退货站点只取父订单数据库真实值。事务：正式入账/隔离写入各自与不可变审计同事务，失败整体回滚。
 */
@Service
public class IntakeService
{
    /** 入账判定结果。 */
    public enum Outcome
    {
        /** 通过全部闸门，已写正式表（或同内容重传 ACK）。 */
        POSTED,
        /** 未通过闸门或来源不可核验/冲突，已隔离到待核对层，未写正式表。 */
        NEEDS_REVIEW
    }

    public record Result(Outcome outcome, Long entityId, String reviewNo, String reasonCode)
    {
        public boolean posted() { return outcome == Outcome.POSTED; }
    }

    /**
     * 订单入账输入。
     * sourceEventId 必须对应系统侧已接收的 ORDER 事件，且事件 payload 与 rawPayload 内容一致、
     * 其中的订单号/门店/商品/Partner SKU/金额/数量/币种与入参一致；storeId/skuId/partnerSku
     * 还必须在本地主数据中映射正确（见 verifyMasterDataRefs）。金额可为零但不可缺，
     * 服务不猜、不补、不填占位。
     */
    public record OrderInput(
            String siteCode,
            String orderNo,
            String sourceChannel,
            String sourceEventId,
            String importFingerprint,
            String partnerSku,
            Long storeId,
            Long skuId,
            BigDecimal grossAmount,
            int qty,
            String currency,
            String rawPayload)
    { }

    /**
     * 退货入账输入。sourceEventId 必须对应系统侧已接收的 RETURN 事件，且事件 payload 与
     * rawPayload 内容一致、其中的订单号/平台退货号/数量/父订单行与入参一致；
     * sourceReturnNo 为平台退货单号，二者均不得臆造。sku_id 由可信父订单行提供，不入参。
     */
    public record ReturnInput(
            String siteCode,
            String orderNo,
            String sourceEventId,
            String sourceReturnNo,
            Long salOrderItemId,
            int qty,
            String rawPayload)
    { }

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public IntakeService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * 订单正式入账闸门。
     * 步骤：站点授权 → 主数据字段完整性 → 来源证据绑定 → 完整键锁读比对 → 正式入账/隔离，全程同事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result postOrder(OrderInput in)
    {
        long actor = guard.requireCurrentUserId();
        validateOrderInput(in);
        // 1) 站点授权：空授权/无权限直接抛 AccessDeniedException，不留隔离。
        guard.requireSite(in.siteCode());

        // 2) 主数据字段完整性：金额允许 0，但字段不可缺失；值与事件证据的比对在下一步完成。
        if (!hasMasterData(in.storeId(), in.skuId(), in.grossAmount()))
            return quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "ORDER",
                    "MASTER_DATA_INCOMPLETE", "缺少 store_id/sku_id/gross_amount，禁止占位入账",
                    in.rawPayload(), actor);

        // 3) 来源证据绑定：事件类型/站点/状态/报文/业务字段均须与系统侧留痕一致。
        String sourceProblem = verifyOrderEvidence(in);
        if (sourceProblem != null)
            return quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "ORDER",
                    sourceProblem, "订单来源证据不足或与请求绑定不一致，禁止臆造身份", in.rawPayload(), actor);

        // 4) 本地主数据映射：store 归属站点、sku 存在、partnerSku↔sku↔站点映射 ACTIVE。
        String masterProblem = verifyMasterDataRefs(in);
        if (masterProblem != null)
            return quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "ORDER",
                    masterProblem, "本地主数据映射不存在或与站点/Partner SKU 不匹配，禁止入账",
                    in.rawPayload(), actor);

        // 5) 幂等 + 冲突：完整业务键锁读回读，直接返回同一行 ID；比较服务端业务指纹。
        String businessFingerprint = orderBusinessFingerprint(in);
        // 首次存在性检查用普通读，避免对不存在的唯一键加 gap lock 与并发 INSERT 死锁；
        // 唯一键冲突后的回读用 FOR UPDATE 当前读，确保读到最新已提交行（见 resolveConcurrentOrder）。
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,import_fingerprint FROM sal_order WHERE order_no=? AND site_code=?",
                in.orderNo(), in.siteCode());
        if (!existing.isEmpty())
        {
            Map<String, Object> row = existing.get(0);
            return businessFingerprint.equals(row.get("import_fingerprint"))
                    ? new Result(Outcome.POSTED, ((Number) row.get("id")).longValue(), null, "ALREADY_POSTED")
                    : quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "ORDER",
                            "SOURCE_CONFLICT", "同站同订单号但可信业务内容不同，禁止当重传 ACK", in.rawPayload(), actor);
        }

        LocalDateTime now = now();
        String channel = hasText(in.sourceChannel()) ? in.sourceChannel() : "WEBHOOK";
        KeyHolder generated = new GeneratedKeyHolder();
        try
        {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO sal_order(order_no,site_code,store_id,partner_sku,sku_id,qty,gross_amount,currency,"
                                + "source_channel,import_fingerprint,status,state_version,raw_payload,created_at,updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, in.orderNo());
                ps.setString(2, in.siteCode());
                ps.setLong(3, in.storeId());          // 真实门店，非 0 占位
                ps.setString(4, in.partnerSku());
                ps.setLong(5, in.skuId());            // 可信商品，非 NULL
                ps.setInt(6, in.qty());
                ps.setBigDecimal(7, in.grossAmount()); // 真实金额，可为 0 但须被可信 payload 佐证
                ps.setString(8, in.currency());
                ps.setString(9, channel);
                ps.setString(10, businessFingerprint); // 服务端现算业务指纹，不信自报值
                ps.setString(11, "IMPORTED");
                ps.setInt(12, 1);
                ps.setString(13, in.rawPayload());
                ps.setTimestamp(14, Timestamp.valueOf(now));
                ps.setTimestamp(15, Timestamp.valueOf(now));
                return ps;
            }, generated);
        }
        catch (DuplicateKeyException concurrent)
        {
            // 并发重复提交：DB 唯一键为最终裁判，回读并按内容判定 ACK/冲突。
            return resolveConcurrentOrder(in, actor);
        }
        Number key = generated.getKey();
        if (key == null) throw new IllegalStateException("未取得订单 ID");
        long orderId = key.longValue();
        audit(null, "ORDER", "POSTED", actor, now,
                "{\"orderNo\":\"" + in.orderNo() + "\",\"site\":\"" + in.siteCode() + "\"}");
        return new Result(Outcome.POSTED, orderId, null, "POSTED");
    }

    /** 并发撞唯一键后完整键锁读回读：业务指纹相同 ACK 并返回同一行 ID，不同内容隔离。 */
    private Result resolveConcurrentOrder(OrderInput in, long actor)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,import_fingerprint FROM sal_order WHERE order_no=? AND site_code=? FOR UPDATE",
                in.orderNo(), in.siteCode());
        if (rows.size() == 1 && orderBusinessFingerprint(in).equals(rows.get(0).get("import_fingerprint")))
            return new Result(Outcome.POSTED, ((Number) rows.get(0).get("id")).longValue(), null, "ALREADY_POSTED");
        return quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "ORDER",
                "SOURCE_CONFLICT", "并发提交撞唯一键且可信业务内容不一致，疑似不同业务", in.rawPayload(), actor);
    }

    /**
     * 退货正式入账闸门。
     * 步骤：站点授权 → 可信父记录 → 来源佐证 → 同键内容比对 → 正式入账/隔离，全程同事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result postReturn(ReturnInput in)
    {
        long actor = guard.requireCurrentUserId();
        validateReturnInput(in);
        // 1) 站点授权
        guard.requireSite(in.siteCode());

        // 2) 可信父记录：订单行必须真实存在；站点与订单号只取数据库真实值。
        ParentInfo parent = findParent(in.salOrderItemId());
        if (parent == null)
            return quarantine(in.siteCode(), in.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                    "PARENT_MISSING", "关联订单行不存在，禁止孤儿退货入账", in.rawPayload(), actor);
        if (!in.siteCode().equals(parent.siteCode()))
            return quarantine(in.siteCode(), in.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                    "SITE_MISMATCH", "父订单站点与调用方站点不一致，禁止跨站关联", in.rawPayload(), actor);
        if (!in.orderNo().equals(parent.orderNo()))
            return quarantine(in.siteCode(), in.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                    "ITEM_PARENT_MISMATCH", "退货订单号与关联订单行的父订单不一致", in.rawPayload(), actor);

        // 3) 来源证据绑定：平台退货单号 + 系统事件类型/站点/状态/报文/退货身份一致。
        if (!hasText(in.sourceReturnNo()))
            return quarantine(in.siteCode(), in.orderNo(), null, in.sourceEventId(), "RETURN",
                    "SOURCE_UNIDENTIFIABLE", "缺少平台退货单号，禁止臆造业务幂等键", in.rawPayload(), actor);
        String sourceProblem = verifyReturnEvidence(in, parent);
        if (sourceProblem != null)
            return quarantine(in.siteCode(), in.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                    sourceProblem, "退货来源证据不足或与请求绑定不一致", in.rawPayload(), actor);

        // 4) 幂等 + 冲突：完整业务键普通读；退货核心业务字段相同 ACK，不同则隔离（四次复核）。
        String bizKey = returnBizKey(parent, in);
        // 首次存在性检查普通读，唯一键冲突回读才锁读，避免 gap lock 死锁。
        // 只取核心业务字段列，不取 raw_payload：元数据/报文格式变化不参与幂等判定。
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id,qty,sal_order_item_id,biz_return_key FROM sal_return "
                        + "WHERE site_code=? AND order_no=? AND source_return_no=?",
                parent.siteCode(), parent.orderNo(), in.sourceReturnNo());
        if (!existing.isEmpty())
        {
            Map<String, Object> row = existing.get(0);
            return sameReturnBusiness(row, parent, in)
                    ? new Result(Outcome.POSTED, ((Number) row.get("id")).longValue(), null, "ALREADY_POSTED")
                    : quarantine(parent.siteCode(), parent.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                            "SOURCE_CONFLICT", "同源退货号但核心业务字段不同（数量/父行/站点/退货号），禁止当重传 ACK",
                            in.rawPayload(), actor);
        }
        LocalDateTime now = now();
        KeyHolder generated = new GeneratedKeyHolder();
        try
        {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO sal_return(return_no,biz_return_key,source_return_no,order_no,site_code,sku_id,qty,"
                                + "sal_order_item_id,status,raw_payload,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "RTN:" + UUID.randomUUID());
                ps.setString(2, bizKey);
                ps.setString(3, in.sourceReturnNo());
                ps.setString(4, parent.orderNo());
                ps.setString(5, parent.siteCode()); // 站点取父记录，不取请求体
                ps.setLong(6, parent.skuId());      // 商品取可信父订单行真实值，非 NULL
                ps.setInt(7, in.qty());
                ps.setLong(8, in.salOrderItemId());
                ps.setString(9, "OPEN");
                ps.setString(10, in.rawPayload());
                ps.setTimestamp(11, Timestamp.valueOf(now));
                return ps;
            }, generated);
        }
        catch (DuplicateKeyException concurrent)
        {
            return resolveConcurrentReturn(parent, in, actor);
        }
        Number key = generated.getKey();
        if (key == null) throw new IllegalStateException("未取得退货 ID");
        audit(null, "RETURN", "POSTED", actor, now,
                "{\"bizReturnKey\":\"" + bizKey + "\",\"site\":\"" + parent.siteCode() + "\"}");
        return new Result(Outcome.POSTED, key.longValue(), null, "POSTED");
    }

    /** 并发撞唯一键后完整键锁读回读：退货核心业务字段相同 ACK 并返回同一行 ID，不同则隔离。 */
    private Result resolveConcurrentReturn(ParentInfo parent, ReturnInput in, long actor)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,qty,sal_order_item_id,biz_return_key FROM sal_return "
                        + "WHERE site_code=? AND order_no=? AND source_return_no=? FOR UPDATE",
                parent.siteCode(), parent.orderNo(), in.sourceReturnNo());
        if (rows.size() == 1 && sameReturnBusiness(rows.get(0), parent, in))
            return new Result(Outcome.POSTED, ((Number) rows.get(0).get("id")).longValue(), null, "ALREADY_POSTED");
        return quarantine(parent.siteCode(), parent.orderNo(), in.sourceReturnNo(), in.sourceEventId(), "RETURN",
                "SOURCE_CONFLICT", "并发退货提交撞唯一键且核心业务字段不一致", in.rawPayload(), actor);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 系统侧事件证据：可信 payload 或待核对原因码（二者互斥）。 */
    private record EvidenceResult(JsonNode payload, String problem) { }

    /**
     * 来源证据绑定（二次复核意见 2）：事件同 ID、站点一致、状态非失败、event_type 与实体类型一致、
     * 系统接收的 payload_raw 与本次请求报文内容一致，且 payload 为可解析对象。
     * 空事件号或空报文 → SOURCE_UNIDENTIFIABLE；其余任一不满足 → SOURCE_UNVERIFIED。
     * 调用方字面非空、非空正数均不作为已验证事实，绝不因"有值"就通过。
     */
    private EvidenceResult loadEvidence(String siteCode, String eventId, String expectedType, String rawPayload)
    {
        if (!hasText(eventId) || !hasText(rawPayload)) return new EvidenceResult(null, "SOURCE_UNIDENTIFIABLE");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type,site_code,payload_raw,status FROM int_webhook_event WHERE event_id=?", eventId);
        if (rows.isEmpty()) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        if ("FAILED".equals(status) || "KILLED".equals(status)) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        // 空站点证据不能证明属于请求站点，必须与已鉴权站点明确相等（三次复核意见 1）。
        String eventSite = (String) row.get("site_code");
        if (!hasText(eventSite) || !eventSite.equals(siteCode)) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        if (!expectedType.equals(row.get("event_type"))) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        String eventPayload = textOf(row.get("payload_raw"));
        if (!sameContent(eventPayload, rawPayload)) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        JsonNode payload = parseJson(eventPayload);
        if (payload == null || !payload.isObject()) return new EvidenceResult(null, "SOURCE_UNVERIFIED");
        return new EvidenceResult(payload, null);
    }

    /** 订单字段必须与系统侧已接收 payload 一致，否则证据不足（不能只信调用方非空正数）。 */
    private String verifyOrderEvidence(OrderInput in)
    {
        EvidenceResult ev = loadEvidence(in.siteCode(), in.sourceEventId(), "ORDER", in.rawPayload());
        if (ev.problem() != null) return ev.problem();
        JsonNode p = ev.payload();
        if (!matchesText(p, "order_no", in.orderNo())
                || !matchesLong(p, "store_id", in.storeId())
                || !matchesLong(p, "sku_id", in.skuId())
                || !matchesText(p, "partner_sku", in.partnerSku())
                || !matchesAmount(p, "gross_amount", in.grossAmount())
                || !matchesLong(p, "qty", in.qty())
                || !matchesText(p, "currency", in.currency()))
            return "SOURCE_UNVERIFIED";
        return null;
    }

    /** 退货身份（订单号、平台退货号、数量、父订单行）必须与系统侧 payload 一致。 */
    private String verifyReturnEvidence(ReturnInput in, ParentInfo parent)
    {
        EvidenceResult ev = loadEvidence(in.siteCode(), in.sourceEventId(), "RETURN", in.rawPayload());
        if (ev.problem() != null) return ev.problem();
        JsonNode p = ev.payload();
        if (!matchesText(p, "order_no", parent.orderNo())
                || !matchesText(p, "return_no", in.sourceReturnNo())
                || !matchesLong(p, "qty", in.qty())
                || !matchesLong(p, "item_id", in.salOrderItemId()))
            return "SOURCE_UNVERIFIED";
        return null;
    }

    /**
     * 本地主数据映射（三次复核意见 3）：store 必须存在且通过 md_site 归属请求站点，
     * sku 必须存在，partnerSku 必须映射到该站点同一 sku，且相关记录 ACTIVE。
     * 无映射 → MASTER_DATA_UNVERIFIED；不把平台报文里的数字直接当系统内部 ID。
     */
    private String verifyMasterDataRefs(OrderInput in)
    {
        List<Map<String, Object>> storeRows = jdbc.queryForList(
                "SELECT s.id FROM md_store s JOIN md_site site ON site.store_id=s.id "
                        + "WHERE s.id=? AND s.status='ACTIVE' AND site.site_code=? AND site.status='ACTIVE'",
                in.storeId(), in.siteCode());
        if (storeRows.isEmpty()) return "MASTER_DATA_UNVERIFIED";

        List<Map<String, Object>> skuRows = jdbc.queryForList(
                "SELECT id FROM md_sku WHERE id=? AND status='ACTIVE'", in.skuId());
        if (skuRows.isEmpty()) return "MASTER_DATA_UNVERIFIED";

        List<Map<String, Object>> partnerRows = jdbc.queryForList(
                "SELECT id FROM md_partner_sku WHERE partner_sku=? AND sku_id=? AND site_code=? AND status='ACTIVE'",
                in.partnerSku(), in.skuId(), in.siteCode());
        return partnerRows.isEmpty() ? "MASTER_DATA_UNVERIFIED" : null;
    }

    /** 父订单行信息：只从数据库真实记录取站点、订单号、商品。 */
    private record ParentInfo(String siteCode, String orderNo, Long skuId) { }

    private ParentInfo findParent(Long salOrderItemId)
    {
        if (salOrderItemId == null || salOrderItemId <= 0) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT so.site_code, so.order_no, si.sku_id FROM sal_order_item si "
                        + "JOIN sal_order so ON so.id = si.sal_order_id WHERE si.id=? FOR UPDATE", salOrderItemId);
        if (rows.size() != 1) return null;
        Map<String, Object> row = rows.get(0);
        return new ParentInfo((String) row.get("site_code"), (String) row.get("order_no"),
                ((Number) row.get("sku_id")).longValue());
    }

    /**
     * 服务端按报文现算内容摘要（SHA-256），不信调用方自报指纹。
     * JSON 报文按键递归排序后再摘要：真 MySQL 的 JSON 列会重排字段顺序，
     * 同一业务内容不应因存储层字段顺序变化被误判为不同内容。
     */
    private static String contentDigest(String rawPayload)
    {
        String canonical = normalizePayload(rawPayload);
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * JDBC 对 MySQL JSON 列可能返回 String 或 byte[]，统一还原为文本再比较，
     * 避免驱动差异把合法证据误判为内容不一致。
     */
    private static String textOf(Object value)
    {
        if (value == null) return null;
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }

    /** 同内容判定：规范化报文的 SHA-256 相等。 */
    private static boolean sameContent(Object storedRaw, String incomingRaw)
    {
        return contentDigest(textOf(storedRaw)).equals(contentDigest(incomingRaw));
    }

    /** 解析系统侧留痕报文；非 JSON 对象视为不可核验证据。 */
    private static JsonNode parseJson(String raw)
    {
        if (raw == null) return null;
        try { return JSON.readTree(raw); }
        catch (Exception e) { return null; }
    }

    /** 报文规范化：JSON 按键递归排序（消除 MySQL JSON 列重排差异），非 JSON 仅去空白。 */
    private static String normalizePayload(String raw)
    {
        if (raw == null) return "";
        JsonNode node = parseJson(raw);
        return node == null ? raw.replaceAll("\\s+", "") : sortedJson(node).toString();
    }

    private static JsonNode sortedJson(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode sorted = JSON.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            for (String field : fields) sorted.set(field, sortedJson(node.get(field)));
            return sorted;
        }
        if (node.isArray())
        {
            ArrayNode sorted = JSON.createArrayNode();
            node.forEach(element -> sorted.add(sortedJson(element)));
            return sorted;
        }
        return node.deepCopy();
    }

    private static boolean matchesText(JsonNode payload, String field, String expected)
    {
        JsonNode value = payload.get(field);
        return value != null && value.isTextual() && expected.equals(value.asText());
    }

    private static boolean matchesLong(JsonNode payload, String field, long expected)
    {
        JsonNode value = payload.get(field);
        return value != null && value.canConvertToLong() && value.asLong() == expected;
    }

    private static boolean matchesAmount(JsonNode payload, String field, BigDecimal expected)
    {
        JsonNode value = payload.get(field);
        if (value == null) return false;
        try { return new BigDecimal(value.asText()).compareTo(expected) == 0; }
        catch (NumberFormatException e) { return false; }
    }

    /**
     * 服务端业务指纹：站点 + 订单号 + 门店 + 商品 + Partner SKU + 金额 + 数量 + 币种。
     * 不含整份报文与事件元数据：同一订单的状态变化/报文格式变化不应被误判为不同业务（三次复核意见 4）。
     */
    private static String orderBusinessFingerprint(OrderInput in)
    {
        String amount = in.grossAmount() == null ? "" : in.grossAmount().stripTrailingZeros().toPlainString();
        String canonical = String.join("|", nz(in.siteCode()), nz(in.orderNo()),
                String.valueOf(in.storeId()), String.valueOf(in.skuId()), nz(in.partnerSku()), amount,
                String.valueOf(in.qty()), nz(in.currency()));
        return contentDigest(canonical);
    }

    /** 退货业务键锚定站点 + 订单号 + 平台退货号 + 父订单行；不含事件 ID，避免不同事件误判为不同业务。 */
    private static String returnBizKey(ParentInfo parent, ReturnInput in)
    {
        return "RET:" + parent.siteCode() + ":" + parent.orderNo() + ":"
                + in.sourceReturnNo() + ":" + in.salOrderItemId();
    }

    /**
     * 退货幂等核心业务字段等同判定（四次复核意见）：站点 + 订单号 + 平台退货号 + 父订单行 + 数量。
     * site/order 已由查询条件与父记录锚定；biz_return_key 覆盖站点+订单号+退货号+父行，
     * 再显式比较数量。两个幂等入口（正常重复、并发回读）共用本法，语义完全一致；
     * <b>不比较整份 raw_payload</b>，因此仅事件元数据/报文格式不同的重传仍按幂等 ACK。
     */
    private static boolean sameReturnBusiness(Map<String, Object> row, ParentInfo parent, ReturnInput in)
    {
        return ((Number) row.get("qty")).intValue() == in.qty()
                && ((Number) row.get("sal_order_item_id")).longValue() == in.salOrderItemId()
                && returnBizKey(parent, in).equals(row.get("biz_return_key"));
    }

    private static String nz(String value) { return value == null ? "" : value; }

    /** 隔离到待核对层；原样保留来源标识与报文，不生成任何平台/业务身份。 */
    private Result quarantine(String claimedSite, String claimedOrderNo, String sourceReturnNo,
            String sourceEventId, String entityType, String reasonCode, String detail,
            String rawPayload, long actor)
    {
        String reviewNo = "RV:" + UUID.randomUUID();
        LocalDateTime now = now();
        jdbc.update("INSERT INTO noon_intake_review(review_no,entity_type,site_code_claimed,order_no_claimed,"
                        + "source_return_no,source_event_id,raw_payload,reason_code,reason_detail,status,created_by,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                reviewNo, entityType, claimedSite, claimedOrderNo, sourceReturnNo, sourceEventId,
                rawPayload, reasonCode, detail, "NEEDS_REVIEW", actor, Timestamp.valueOf(now));
        audit(reviewNo, entityType, "QUARANTINED", actor, now,
                "{\"reason\":\"" + reasonCode + "\",\"reviewNo\":\"" + reviewNo + "\"}");
        return new Result(Outcome.NEEDS_REVIEW, null, reviewNo, reasonCode);
    }

    /**
     * 人工解析：只有拿到可信证据的有权限主体才能把待核对记录转为 RESOLVED，
     * 并显式提供已核实的业务键。自动过账路径不存在。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resolve(String reviewNo, String verifiedBizKey)
    {
        Long actor = guard.requireAdministrator();
        if (!hasText(reviewNo) || !hasText(verifiedBizKey))
            throw new IllegalArgumentException("解析必须提供待核对单与已核实业务键");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status FROM noon_intake_review WHERE review_no=? FOR UPDATE", reviewNo);
        if (rows.size() != 1) throw new IllegalArgumentException("待核对单不存在");
        if (!"NEEDS_REVIEW".equals(rows.get(0).get("status")))
            throw new IllegalStateException("待核对单已处理");
        LocalDateTime now = now();
        if (jdbc.update("UPDATE noon_intake_review SET status='RESOLVED',resolution_biz_key=?,resolved_by=?,resolved_at=? "
                        + "WHERE review_no=? AND status='NEEDS_REVIEW'",
                verifiedBizKey, actor, Timestamp.valueOf(now), reviewNo) != 1)
            throw new IllegalStateException("待核对单已变化");
        audit(reviewNo, "REVIEW", "RESOLVED", actor, now,
                "{\"reviewNo\":\"" + reviewNo + "\"}");
    }

    private void audit(String reviewNo, String entityType, String action, long actor, LocalDateTime now, String detail)
    {
        jdbc.update("INSERT INTO noon_intake_audit(audit_no,review_no,entity_type,action,actor_id,act_at,detail_json) "
                        + "VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), reviewNo, entityType, action, actor, Timestamp.valueOf(now), detail);
    }

    private static void validateOrderInput(OrderInput in)
    {
        if (in == null || !hasText(in.siteCode()) || !hasText(in.orderNo())
                || !hasText(in.partnerSku()) || !hasText(in.currency()) || in.qty() <= 0)
            throw new IllegalArgumentException("订单入账参数无效");
    }

    private static void validateReturnInput(ReturnInput in)
    {
        if (in == null || !hasText(in.siteCode()) || !hasText(in.orderNo())
                || in.salOrderItemId() == null || in.qty() <= 0)
            throw new IllegalArgumentException("退货入账参数无效");
    }

    /** 主数据字段存在性：金额允许 0（合法零金额须由可信 payload 佐证，见 verifyOrderEvidence），只拒缺失/负数。 */
    private static boolean hasMasterData(Long storeId, Long skuId, BigDecimal amount)
    {
        return storeId != null && storeId > 0 && skuId != null && skuId > 0
                && amount != null && amount.signum() >= 0;
    }

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }

    private static LocalDateTime now()
    {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    }
}
