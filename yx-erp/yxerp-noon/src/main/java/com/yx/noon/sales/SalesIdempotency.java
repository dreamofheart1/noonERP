package com.yx.noon.sales;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 动作级幂等（契约 §3 / 契约 §8）：{@code noon_m5_sales_request} 主键 {@code (request_id, action)}。
 *
 * <p>与内部服务的<b>事件级幂等并存、互不替代</b>：这里管"一次 HTTP 动作"（PENDING 占位 → DONE 回填
 * {@code result_json}，重放原样返回），内部服务管"一次可信事件"（ORDER/RETURN/SALE_DELIVERY/
 * RETURN_REVERSAL 事件键 + DB 唯一键）。
 *
 * <p>顺序与 asn 包 D1 同口径：授权 → 指纹 → DONE 重放 → PENDING 占位；PENDING 并发 400「处理中」；
 * 异常整事务回滚（PENDING 占位一并消失，同键可安全重试）；DONE 重放零副作用。同键换内容 → 400。
 */
final class SalesIdempotency
{
    static final String PENDING = "PENDING";
    static final String DONE = "DONE";

    /** 结果只有 {@link SalesResult}（无时间字段），普通 ObjectMapper 即可原样重放。 */
    private static final ObjectMapper RESULT_JSON = new ObjectMapper();

    private SalesIdempotency()
    {
    }

    /** 幂等表一行（PENDING 期 {@code entity_id=0}）。 */
    record RequestRow(String action, long entityId, String siteCode, String fingerprint, String status,
                      String resultJson)
    {
    }

    /** DONE 重放：未命中返回 null；命中但 PENDING 也返回 null（由 claim 统一抛「处理中」）。 */
    static SalesResult replayDone(JdbcTemplate jdbc, String requestId, String action, String fingerprint)
    {
        List<RequestRow> rows = requestRows(jdbc, requestId, action, false);
        if (rows.isEmpty()) return null;
        RequestRow row = rows.get(0);
        requireSameContent(requestId, action, row, fingerprint);
        if (!DONE.equals(row.status())) return null;
        return readResult(row.resultJson()).asReplay();
    }

    /**
     * PENDING 占位 / 撞键回读。首次占位返回 null；并发同键已 DONE 则返回重放结果；
     * 仍 PENDING 抛 400「处理中」。指纹不一致一律 400（同 requestId 换内容）。
     */
    static SalesResult claim(JdbcTemplate jdbc, String requestId, String action, String siteCode,
                             Long operatorId, String fingerprint)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m5_sales_request"
                    + "(request_id,action,entity_id,site_code,operator_id,content_fingerprint,status) "
                    + "VALUES(?,?,0,?,?,?,'PENDING')",
                    requestId, action, siteCode, operatorId, fingerprint);
            return null;
        }
        catch (DuplicateKeyException duplicate)
        {
            // 当前读避免 REPEATABLE READ 旧快照（与 asn/transfer 同口径）。
            List<RequestRow> rows = requestRows(jdbc, requestId, action, true);
            if (rows.isEmpty()) throw new IllegalArgumentException("同一请求正在处理中，请稍后重试");
            RequestRow row = rows.get(0);
            requireSameContent(requestId, action, row, fingerprint);
            if (!DONE.equals(row.status()))
                throw new IllegalArgumentException("同一请求正在处理中，请稍后重试（requestId=" + requestId + "）");
            return readResult(row.resultJson()).asReplay();
        }
    }

    /** DONE 回填：结果 JSON + 实体 id；更新不到行视为幂等表被并发破坏，整事务回滚。 */
    static void markDone(JdbcTemplate jdbc, String requestId, String action, long entityId, SalesResult result)
    {
        String json;
        try
        {
            json = RESULT_JSON.writeValueAsString(result);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("动作结果序列化失败: requestId=" + requestId, e);
        }
        int updated = jdbc.update("UPDATE noon_m5_sales_request SET entity_id=?,status='DONE',result_json=? "
                + "WHERE request_id=? AND action=?", entityId, json, requestId, action);
        if (updated == 0)
            throw new IllegalStateException("幂等记录状态更新失败: requestId=" + requestId + "，action=" + action);
    }

    /**
     * 失败释放：内部闸门隔离（NEEDS_REVIEW）时不缓存失败，删掉 PENDING 占位，使同 requestId 可在
     * 修复数据后重试；隔离记录与事件留痕由调用方事务提交保留（D8）。
     */
    static void release(JdbcTemplate jdbc, String requestId, String action)
    {
        jdbc.update("DELETE FROM noon_m5_sales_request WHERE request_id=? AND action=? AND status='PENDING'",
                requestId, action);
    }

    /** 结果回读：不存在 400；PENDING 400「处理中」；DONE 返回原结果（{@code replayed=false}，回读非重放）。 */
    static RequestRow load(JdbcTemplate jdbc, String requestId, String action)
    {
        List<RequestRow> rows = requestRows(jdbc, requestId, action, false);
        if (rows.isEmpty())
            throw new IllegalArgumentException("未找到该请求结果: requestId=" + requestId + "，action=" + action);
        return rows.get(0);
    }

    static SalesResult readResult(String json)
    {
        if (json == null || json.isBlank())
            throw new IllegalStateException("幂等记录缺少结果 JSON");
        try
        {
            return RESULT_JSON.readValue(json, SalesResult.class);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private static List<RequestRow> requestRows(JdbcTemplate jdbc, String requestId, String action, boolean forUpdate)
    {
        return jdbc.query("SELECT action,entity_id,site_code,content_fingerprint,status,result_json "
                        + "FROM noon_m5_sales_request WHERE request_id=? AND action=?"
                        + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new RequestRow(rs.getString("action"), rs.getLong("entity_id"),
                        rs.getString("site_code"), rs.getString("content_fingerprint"),
                        rs.getString("status"), rs.getString("result_json")), requestId, action);
    }

    private static void requireSameContent(String requestId, String action, RequestRow row, String fingerprint)
    {
        if (!action.equals(row.action()))
            throw new IllegalArgumentException("requestId 已用于其他动作: requestId=" + requestId
                    + "，已存在 action=" + row.action());
        if (row.fingerprint() == null || !row.fingerprint().equals(fingerprint))
            throw new IllegalArgumentException("requestId 对应的请求内容与原请求不一致（action=" + action
                    + "，requestId=" + requestId + "）");
    }

    /** 仅供测试/诊断：任意动作行是否存在（不做内容判定）。 */
    static Map<String, Object> anyRow(JdbcTemplate jdbc, String requestId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_id,action,entity_id,site_code,status,content_fingerprint "
                        + "FROM noon_m5_sales_request WHERE request_id=?", requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
