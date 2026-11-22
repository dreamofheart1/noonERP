package com.yx.noon.shipment;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * M4 第三轮第二批「头程费用登记」后端内核（契约 v1 §2–§6，owner：豆包）。
 *
 * <p><b>create（登记一笔头程/关税/包材费用）</b>：入参校验（站点格式）→ operatorId →
 * <b>{@code guard.requireSite(siteCode)}（授权先于一切判重，越权恒 403）</b> → 字段校验（feeType /
 * amount / currency / fxRate / period / allocationFlag / remark / asnId）→ 带 asnId 时校验 ASN 存在且
 * {@code log_asn.site_code == siteCode}（跨站/不存在 400）→ CREATE 内容指纹 → DONE 重放 →
 * PENDING 占位（{@code cost_id=0}）→ 写 {@code log_shipment_cost} → 同事务审计 → DONE 回填真实 id。
 *
 * <p><b>resultOf（只读幂等结果）</b>：按幂等行 {@code site_code} 取真实站点 → {@code requireSite}
 * → 才返回 DONE 缓存结果（{@code replayed=true}）。无权 403、非法/PENDING/不存在返回 null，不写任何表。
 * 授权在读取任何结果之前完成，撤销授权/跨站者即便持合法 requestId 也拿不到结果。
 *
 * <p><b>list / get</b>：站点授权过滤（传 siteCode 先 {@code requireSite} 再单站过滤；未传返回全部授权站点、
 * 空授权零行），total 为同条件真实总数；get 先取费用行真实 {@code site_code} 再 {@code requireSite}。
 *
 * <p><b>边界（契约 §0）</b>：纯登记，<b>不动库存/批次/台账/COGS</b>；{@code allocation_flag} 的
 * PARTIAL/FULL 仅作标记，本批绝不实际分摊（分摊算法留 M6）。登记不可变（不支持修改/删除）。
 *
 * <p><b>防伪造</b>：入参 record 无 {@code id/cost_no/status/created_at}，结构上不接收；{@code cost_no}
 * 由服务端 {@code "SC:<SITE>:" + UUID8} 生成，与 {@code uk_cost_no} 天然幂等。
 */
@Service
public class ShipmentCostService
{
    static final String ACTION_CREATE = "CREATE";
    static final String COST_NO_PREFIX = "SC:";

    /** 幂等表 {@code cost_id} 列为 NOT NULL（登记时真实 id 尚未生成），PENDING 期用占位值（契约 §4）。 */
    private static final long PENDING_COST_ID = 0L;
    private static final int COST_NO_ATTEMPTS = 3;

    /** result_json 只缓存 {@link ShipmentCostResult}（无时间字段），普通 ObjectMapper 即可原样重放。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private static final String ROW_COLUMNS = "id,cost_no,asn_id,site_code,period,fee_type,amount,currency,"
            + "fx_rate,allocation_flag,created_at";
    private static final String DETAIL_COLUMNS = ROW_COLUMNS + ",remark";

    private static final RowMapper<ShipmentCostRow> ROW_MAPPER = (rs, i) -> new ShipmentCostRow(
            rs.getLong("id"),
            rs.getString("cost_no"),
            rs.getObject("asn_id", Long.class),
            rs.getString("site_code"),
            rs.getString("period"),
            rs.getString("fee_type"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate"),
            rs.getString("allocation_flag"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ShipmentCostDetail> DETAIL_MAPPER = (rs, i) -> new ShipmentCostDetail(
            rs.getLong("id"),
            rs.getString("cost_no"),
            rs.getObject("asn_id", Long.class),
            rs.getString("site_code"),
            rs.getString("period"),
            rs.getString("fee_type"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate"),
            rs.getString("allocation_flag"),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final ShipmentCostAudit audit;

    public ShipmentCostService(JdbcTemplate jdbc, SiteAuthGuard guard, ShipmentCostAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    // ----------------------------------------------------------------- create

    /**
     * 登记一笔头程/关税/包材费用（契约 §4/§5）。
     *
     * <p>顺序：站点格式校验 → operatorId → <b>requireSite</b> → 字段校验 → ASN 同站点校验 → 指纹 →
     * DONE 重放 → PENDING 占位 → 写费用行 + 审计 → 回填 id + DONE。异常整事务回滚（PENDING 占位一并消失，
     * 同 requestId 可安全重试）；重放零写入（{@code replayed=true}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ShipmentCostResult create(ShipmentCostInput in)
    {
        if (in == null) throw new IllegalArgumentException("费用登记请求不能为空");
        // 站点先做格式校验（缺失/非 AE-SA 为 400），再走授权；授权先于任何业务状态判定。
        String siteCode = ShipmentCostRules.siteCode("siteCode", in.siteCode());
        Long operatorId = guard.requireCurrentUserId();
        guard.requireSite(siteCode);

        String feeType = ShipmentCostRules.feeType(in.feeType());
        BigDecimal amount = ShipmentCostRules.amount(in.amount());
        String currency = ShipmentCostRules.currency(in.currency());
        BigDecimal fxRate = ShipmentCostRules.fxRate(in.fxRate(), currency);
        String period = ShipmentCostRules.period(in.period());
        String allocationFlag = ShipmentCostRules.allocationFlag(in.allocationFlag());
        String remark = ShipmentCostRules.remark(in.remark());
        Long asnId = ShipmentCostRules.asnId(in.asnId());
        String requestId = ShipmentCostRules.requireRequestId(in.requestId());

        // 契约 §3：带 asnId 时必须存在且与登记站点一致（跨站/不存在 400），且发生在任何写入之前。
        if (asnId != null) requireSameSiteAsn(asnId, siteCode);

        String fingerprint = ShipmentCostRules.createFingerprint(asnId, siteCode, period, feeType, amount,
                currency, fxRate, allocationFlag, remark);
        ShipmentCostResult replayed = replayDone(requestId, siteCode, fingerprint);
        if (replayed != null) return replayed;

        replayed = claimRequest(requestId, siteCode, operatorId, fingerprint);
        if (replayed != null) return replayed;

        LocalDateTime now = ShipmentCostRules.now();
        String resolvedPeriod = period == null ? ShipmentCostRules.periodOf(now) : period;
        Inserted inserted = insertCost(asnId, siteCode, resolvedPeriod, feeType, amount, currency, fxRate,
                allocationFlag, remark, now);

        audit.record(inserted.id(), ACTION_CREATE,
                "costNo=" + inserted.costNo() + ",site=" + siteCode + ",period=" + resolvedPeriod
                        + ",feeType=" + feeType + ",amount=" + amount.toPlainString() + ",currency=" + currency
                        + ",fxRate=" + fxRate.toPlainString() + ",allocation=" + allocationFlag
                        + ",asnId=" + (asnId == null ? "" : asnId),
                operatorId);

        // 提交前写回首次结果：cost_id=真实 id + status=DONE + result_json（replayed=false）。异常时整事务回滚。
        ShipmentCostResult result = new ShipmentCostResult(inserted.id(), inserted.costNo(), false);
        markDone(requestId, inserted.id(), result);
        return result;
    }

    // ---------------------------------------------------------------- read-only

    /**
     * 只读幂等结果查询（契约 §3/§6）：按 requestId 取幂等行 → 用行内 {@code site_code} 走
     * <b>真实站点</b> {@code requireSite} → 才返回 DONE 缓存结果（{@code replayed=true}）。
     * requestId 非法/不存在/未完成（PENDING）返回 null；费用行缺失（数据异常）返回 null；无权 403。
     */
    @Transactional(readOnly = true)
    public ShipmentCostResult resultOf(String requestId)
    {
        String rid;
        try
        {
            rid = ShipmentCostRules.requireRequestId(requestId);
        }
        catch (IllegalArgumentException invalid)
        {
            return null;
        }
        List<RequestRow> rows = requestRows(rid, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);

        // 授权口径与 create 一致：以幂等行 site_code 为唯一依据，先授权再读结果。
        guard.requireSite(row.siteCode());

        if (!"DONE".equals(row.status())) return null;
        if (row.costId() <= 0) return null;
        if (loadDetailOrNull(row.costId()) == null) return null;
        return replayResult(row);
    }

    /**
     * 费用分页列表（契约 §3）：传 siteCode 先 {@code requireSite} 再单站过滤；未传返回全部授权站点
     * （空授权必须零行）。feeType/asnId/period 可选过滤与 COUNT 共用同一 WHERE，total 为真实总数。
     */
    public PageResult<ShipmentCostRow> list(String siteCode, int offset, int limit, CostQuery q)
    {
        ShipmentCostRules.offset(offset);
        int size = ShipmentCostRules.limit(limit);
        List<String> gate = siteGate(siteCode);
        if (gate.isEmpty()) return PageResult.empty();

        String feeType = q == null ? null : ShipmentCostRules.feeTypeFilter(q.feeType());
        Long asnId = q == null ? null : ShipmentCostRules.asnId(q.asnId());
        String period = q == null ? null : ShipmentCostRules.periodFilter(q.period());

        StringBuilder where = new StringBuilder(" WHERE site_code IN (")
                .append(ShipmentCostRules.placeholders(gate.size())).append(')');
        List<Object> args = new ArrayList<>(gate);
        if (feeType != null)
        {
            where.append(" AND fee_type=?");
            args.add(feeType);
        }
        if (asnId != null)
        {
            where.append(" AND asn_id=?");
            args.add(asnId);
        }
        if (period != null)
        {
            where.append(" AND period=?");
            args.add(period);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM log_shipment_cost" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<ShipmentCostRow> rows = jdbc.query("SELECT " + ROW_COLUMNS + " FROM log_shipment_cost" + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?", ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    /** 费用详情（契约 §3）：先按 id 取费用行真实 {@code site_code} 再 {@code requireSite}，授权后才返回。 */
    @Transactional(readOnly = true)
    public ShipmentCostDetail get(Long id)
    {
        if (id == null || id <= 0) throw new IllegalArgumentException("id 必须为正整数");
        ShipmentCostDetail detail = loadDetailOrNull(id);
        if (detail == null) throw new IllegalArgumentException("费用登记不存在: id=" + id);
        guard.requireSite(detail.siteCode());
        return detail;
    }

    // ---------------------------------------------------------------- 校验辅助

    /** ASN 存在且同站点：不存在 400、跨站 400（契约 §3）；只读，不修改 ASN。 */
    private void requireSameSiteAsn(long asnId, String siteCode)
    {
        List<String> sites = jdbc.query("SELECT site_code FROM log_asn WHERE id=?",
                (rs, i) -> rs.getString("site_code"), asnId);
        if (sites.isEmpty())
            throw new IllegalArgumentException("ASN 不存在: asnId=" + asnId);
        String asnSite = sites.get(0);
        if (asnSite == null || !asnSite.equals(siteCode))
            throw new IllegalArgumentException("ASN 站点 " + asnSite + " 与登记站点 " + siteCode
                    + " 不一致，不允许跨站关联: asnId=" + asnId);
    }

    /**
     * 站点闸门：指定 siteCode 先格式校验 + {@code requireSite} 再单站过滤；未指定返回全部授权站点
     * （超管=SUPPORTED_SITES、普通用户=已授权；空集合表示零行，调用方不得省略 WHERE）。
     */
    private List<String> siteGate(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
            return List.of(guard.requireSite(ShipmentCostRules.siteCode("siteCode", siteCode)));
        return guard.allowedSiteCodes();
    }

    // ---------------------------------------------------------------- 幂等

    /** 命中 DONE 且站点/内容一致 → 重放（replayed=true）；无行/PENDING → null；冲突 → 400。 */
    private ShipmentCostResult replayDone(String requestId, String siteCode, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameRequest(row, siteCode, fingerprint);
        if (!"DONE".equals(row.status())) return null;
        return replayResult(row);
    }

    /**
     * 申请 requestId 处理权：返回 null 表示本次是新请求（已占位 PENDING，{@code cost_id=0}）；
     * 非 null 表示命中已有 DONE 请求，直接重放首次结果。
     */
    private ShipmentCostResult claimRequest(String requestId, String siteCode, Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m4_shipment_cost_request"
                    + "(request_id,cost_id,site_code,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,?,?,?,'PENDING')",
                    requestId, PENDING_COST_ID, siteCode, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 主键冲突：同键已存在（处理中或已完成）。当前读避免 RR 快照看不到并发提交。
            return replayOf(requestId, siteCode, fingerprint);
        }
    }

    private ShipmentCostResult replayOf(String requestId, String siteCode, String fingerprint)
    {
        List<RequestRow> rows = requestRows(requestId, true);
        if (rows.isEmpty())
            throw new IllegalArgumentException("同一费用登记请求正在处理中，请稍后重试");
        RequestRow row = rows.get(0);
        requireSameRequest(row, siteCode, fingerprint);
        if (!"DONE".equals(row.status()))
            throw new IllegalArgumentException("同一费用登记请求正在处理中，请稍后重试");
        return replayResult(row);
    }

    /** 同键必须同站点、同内容：先判站点，再比内容指纹（冲突 400）。 */
    private static void requireSameRequest(RequestRow row, String siteCode, String fingerprint)
    {
        if (row.siteCode() == null || !row.siteCode().equals(siteCode))
            throw new IllegalArgumentException("requestId 已用于其他站点的费用登记请求");
        if (row.contentFingerprint() == null || !row.contentFingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的费用登记内容与原请求不一致");
    }

    private List<RequestRow> requestRows(String requestId, boolean forUpdate)
    {
        return jdbc.query("SELECT cost_id,site_code,content_fingerprint,status,result_json "
                        + "FROM noon_m4_shipment_cost_request WHERE request_id=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getLong("cost_id"), rs.getString("site_code"),
                        rs.getString("content_fingerprint"), rs.getString("status"), rs.getString("result_json")),
                requestId);
    }

    private void markDone(String requestId, long costId, ShipmentCostResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("费用登记结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m4_shipment_cost_request "
                + "SET cost_id=?,status='DONE',result_json=? WHERE request_id=?", costId, json, requestId);
        if (updated == 0)
            throw new IllegalStateException("费用登记幂等记录状态更新失败: requestId=" + requestId);
    }

    /** 重放结果固定 replayed=true（首次缓存中的 replayed=false 只代表首次返回）。 */
    private ShipmentCostResult replayResult(RequestRow row)
    {
        if (row.resultJson() == null || row.resultJson().isBlank())
            throw new IllegalStateException("费用登记幂等记录缺少结果快照");
        try
        {
            ShipmentCostResult stored = RESULT_JSON.readValue(row.resultJson(), ShipmentCostResult.class);
            if (stored == null) throw new IllegalStateException("费用登记幂等结果反序列化为空");
            return new ShipmentCostResult(stored.costId(), stored.costNo(), true);
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IllegalStateException("费用登记幂等结果反序列化失败", e);
        }
    }

    // ---------------------------------------------------------------- 读取/写入

    private ShipmentCostDetail loadDetailOrNull(long id)
    {
        List<ShipmentCostDetail> rows = jdbc.query("SELECT " + DETAIL_COLUMNS
                + " FROM log_shipment_cost WHERE id=?", DETAIL_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写费用行（契约 §4）：{@code cost_no = "SC:<SITE>:" + UUID 前 8 位大写}；与 {@code uk_cost_no} 天然幂等，
     * 撞键换新号重试（理论上概率极低，仍做兜底）。
     */
    private Inserted insertCost(Long asnId, String siteCode, String period, String feeType, BigDecimal amount,
                                String currency, BigDecimal fxRate, String allocationFlag, String remark,
                                LocalDateTime now)
    {
        for (int attempt = 0; attempt < COST_NO_ATTEMPTS; attempt++)
        {
            String costNo = COST_NO_PREFIX + siteCode + ":"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
            KeyHolder keys = new GeneratedKeyHolder();
            try
            {
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO log_shipment_cost(cost_no,asn_id,site_code,period,fee_type,amount,"
                                    + "currency,fx_rate,allocation_flag,remark,created_at) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            new String[]{"id"});
                    ps.setString(1, costNo);
                    if (asnId == null) ps.setObject(2, null); else ps.setLong(2, asnId);
                    ps.setString(3, siteCode);
                    ps.setString(4, period);
                    ps.setString(5, feeType);
                    ps.setBigDecimal(6, amount);
                    ps.setString(7, currency);
                    ps.setBigDecimal(8, fxRate);
                    ps.setString(9, allocationFlag);
                    ps.setString(10, remark);
                    ps.setObject(11, now);
                    return ps;
                }, keys);
            }
            catch (DuplicateKeyException duplicate)
            {
                continue;
            }
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("费用登记未返回主键");
            return new Inserted(key.longValue(), costNo);
        }
        throw new IllegalStateException("费用单号生成冲突，重试 " + COST_NO_ATTEMPTS + " 次仍失败");
    }

    private record Inserted(long id, String costNo)
    {
    }

    private record RequestRow(long costId, String siteCode, String contentFingerprint, String status,
                              String resultJson)
    {
    }
}
