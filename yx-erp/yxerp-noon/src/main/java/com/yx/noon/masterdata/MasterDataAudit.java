package com.yx.noon.masterdata;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.common.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M2 主数据变更审计（FR-01.4）。由各 Service 在**业务写入同一事务**内调用：
 * 业务异常回滚 → 审计行一起回滚，不留"成功假象"；表 append-only，应用无更新/删除入口。
 *
 * <p>操作人取当前若依登录人（{@link SecurityUtils#getLoginUser()}）。取不到时明确降级为
 * operator_id=NULL、operator_name="UNKNOWN"（仅发生在无安全上下文的直接调用；HTTP 入口不会出现），
 * 该降级不静默：JSON 快照照常记录，报告中有说明。
 */
@Component
public class MasterDataAudit
{
    /** 业务时刻统一 UTC+8（与项目口径一致），落 DATETIME(3)。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String UNKNOWN_OPERATOR = "UNKNOWN";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public MasterDataAudit(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条变更。
     *
     * @param entityType STORE/SITE/WAREHOUSE/SKU/PARTNER_SKU
     * @param entityId   业务主键
     * @param action     INSERT/UPDATE/STATUS
     * @param before     变更前读模型（新增传 null）
     * @param after      变更后读模型（含 DB 生成列/默认值，来自同事务回读）
     */
    public void record(String entityType, long entityId, String action, Object before, Object after)
    {
        LoginUser loginUser = currentLoginUserOrNull();
        Long operatorId = loginUser == null ? null : loginUser.getUserId();
        String operatorName = loginUser == null || loginUser.getUsername() == null
                ? UNKNOWN_OPERATOR : loginUser.getUsername();
        LocalDateTime changedAt = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO noon_m2_audit_log(entity_type,entity_id,action,operator_id,"
                        + "operator_name,before_json,after_json,changed_at) VALUES(?,?,?,?,?,?,?,?)",
                entityType, entityId, action, operatorId, operatorName,
                toJson(before), toJson(after), changedAt);
    }

    private static LoginUser currentLoginUserOrNull()
    {
        try
        {
            LoginUser loginUser = SecurityUtils.getLoginUser();
            if (loginUser == null || loginUser.getUserId() == null || loginUser.getUserId() <= 0) return null;
            return loginUser;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private String toJson(Object value)
    {
        if (value == null) return null;
        try
        {
            return json.writeValueAsString(value);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("审计快照序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }
}
