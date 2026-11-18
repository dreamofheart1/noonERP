package com.yx.framework.configversion;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.framework.configversion.VersionedConfigReader.Row;
import com.yx.framework.security.site.SiteAuthGuard;

/** 内部管理服务；仅超管，所有写路径均持有 key/scope 持久锁至事务结束。 */
@Service
public class RuleConfigWriteService
{
    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public RuleConfigWriteService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    private record Existing(long id, int versionNo, Version version, LocalDateTime revokedAt) { }

    @Transactional(rollbackFor = Exception.class)
    public long create(String key, String scope, String value, LocalDateTime from, LocalDateTime to)
    {
        long actor = guard.requireAdministrator();
        validate(key, scope, value, from, to);
        if (!"GLOBAL".equals(scope)) guard.requireSite(scope);
        lock(key, scope);
        List<Existing> current = currentRows(key, scope);
        Version proposed = new Version(key, scope, value, from, to);
        ConfigIntervalPolicy.requireNoOverlap(proposed, current.stream()
                .map(e -> new Row(e.id(), e.version(), e.revokedAt())).toList());
        int next = Math.addExact(current.stream().mapToInt(Existing::versionNo).max().orElse(0), 1);
        LocalDateTime now = now();
        KeyHolder generated = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO noon_rule_config "
                    + "(config_key,scope,config_value,version_no,effective_from,effective_to,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, key); ps.setString(2, scope); ps.setString(3, value); ps.setInt(4, next);
            ps.setTimestamp(5, Timestamp.valueOf(from));
            if (to == null) ps.setNull(6, java.sql.Types.TIMESTAMP); else ps.setTimestamp(6, Timestamp.valueOf(to));
            ps.setLong(7, actor); ps.setTimestamp(8, Timestamp.valueOf(now));
            return ps;
        }, generated);
        Number id = generated.getKey();
        if (id == null) throw new IllegalStateException("未取得参数版本 ID");
        audit(key, scope, "CREATE", id.longValue(), next, null,
                JSON.toJSONString(Map.of("value", value, "from", from.toString(), "to", to == null ? "" : to.toString())), actor, now);
        return id.longValue();
    }

    /** 仅超管查看指定范围的历史；按 ID 游标分页，不依据当前状态过滤。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String key, String scope, long afterId, int limit)
    {
        guard.requireAdministrator();
        validateKeyScope(key, scope);
        if (!"GLOBAL".equals(scope)) guard.requireSite(scope);
        if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("分页参数无效");
        return jdbc.queryForList("SELECT id,config_key,scope,config_value,version_no,effective_from,effective_to,status,revoked_at,revoked_by,created_by,created_at FROM noon_rule_config WHERE config_key=? AND scope=? AND id>? ORDER BY id LIMIT ?",
                key, scope, afterId, limit);
    }

    /** 撤销从当前北京时间生效；历史值保留，不提供追溯性修改。 */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(long id)
    {
        revokeChecked(id, null);
    }

    /** 面向管理接口的范围保护；检查与撤销处于同一事务。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeManaged(long id, java.util.Set<String> allowedKeys)
    {
        if (allowedKeys == null) throw new IllegalArgumentException("缺少允许的参数键");
        revokeChecked(id, java.util.Set.copyOf(allowedKeys));
    }

    private void revokeChecked(long id, java.util.Set<String> allowedKeys)
    {
        long actor = guard.requireAdministrator();
        if (id <= 0) throw new IllegalArgumentException("版本 ID 无效");
        List<Map<String, Object>> keys = jdbc.queryForList("SELECT config_key,scope FROM noon_rule_config WHERE id=?", id);
        if (keys.size() != 1) throw new IllegalArgumentException("参数版本不存在");
        String key = (String) keys.get(0).get("config_key");
        String scope = (String) keys.get(0).get("scope");
        validateKeyScope(key, scope);
        if (allowedKeys != null && !allowedKeys.contains(key)) throw new IllegalArgumentException("未开放的参数键");
        if (!"GLOBAL".equals(scope)) guard.requireSite(scope);
        lock(key, scope);
        Existing row = currentRows(key, scope).stream().filter(e -> e.id() == id).findFirst()
                .orElseThrow(() -> new IllegalStateException("参数版本已变化"));
        if (row.revokedAt() != null) throw new IllegalStateException("参数版本已撤销");
        LocalDateTime now = now();
        if (jdbc.update("UPDATE noon_rule_config SET revoked_at=?,revoked_by=?,status=0 WHERE id=? AND revoked_at IS NULL", now, actor, id) != 1)
            throw new IllegalStateException("参数版本已变化");
        audit(key, scope, "REVOKE", id, row.versionNo(), "{\"revokedAt\":null}",
                JSON.toJSONString(Map.of("revokedAt", now.toString(), "revokedBy", actor)), actor, now);
    }

    private void lock(String key, String scope)
    {
        jdbc.update("INSERT INTO noon_rule_config_lock(config_key,scope) VALUES (?,?) ON DUPLICATE KEY UPDATE config_key=config_key", key, scope);
        if (jdbc.queryForList("SELECT config_key FROM noon_rule_config_lock WHERE config_key=? AND scope=? FOR UPDATE", key, scope).size() != 1)
            throw new IllegalStateException("参数锁不存在");
    }

    private List<Existing> currentRows(String key, String scope)
    {
        return jdbc.query("SELECT id,version_no,config_value,effective_from,effective_to,revoked_at FROM noon_rule_config WHERE config_key=? AND scope=? FOR UPDATE",
                (rs, n) -> new Existing(rs.getLong("id"), rs.getInt("version_no"),
                        new Version(key, scope, rs.getString("config_value"), rs.getObject("effective_from", LocalDateTime.class),
                                rs.getObject("effective_to", LocalDateTime.class)), rs.getObject("revoked_at", LocalDateTime.class)), key, scope);
    }

    private void audit(String key, String scope, String action, long id, int version, String before, String after, long actor, LocalDateTime now)
    {
        jdbc.update("INSERT INTO noon_rule_config_audit(audit_no,config_key,scope,action,version_id,version_no,before_json,after_json,actor_id,act_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), key, scope, action, id, version, before, after, actor, now);
    }

    private static void validate(String key, String scope, String value, LocalDateTime from, LocalDateTime to)
    {
        validateKeyScope(key, scope);
        if (value == null || value.length() > 1024 || from == null || (to != null && !to.isAfter(from)))
            throw new IllegalArgumentException("参数值或生效区间无效");
        if (from.getNano() % 1_000_000 != 0 || (to != null && to.getNano() % 1_000_000 != 0))
            throw new IllegalArgumentException("参数时间必须为毫秒精度");
    }

    private static void validateKeyScope(String key, String scope)
    {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,127}") || scope == null || !List.of("GLOBAL", "AE", "SA").contains(scope))
            throw new IllegalArgumentException("参数键或范围无效");
    }

    private static LocalDateTime now()
    {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    }
}
