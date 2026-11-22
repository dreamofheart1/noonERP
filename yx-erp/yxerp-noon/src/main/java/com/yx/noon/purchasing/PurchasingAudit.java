package com.yx.noon.purchasing;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yx.common.core.domain.model.LoginUser;
import com.yx.common.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M3 供应商与采购变更审计（契约 1 第 5 项 / 4.4 第 7 步）。由各 Service 在**业务写入同一事务**内调用：
 * 业务异常回滚 → 审计行一起回滚，不留"成功假象"；表 append-only，应用无更新/删除入口。
 *
 * <p>entity_type 允许 SUPPLIER/QUOTATION/PUR_ORDER/RECEIPT，action 允许 INSERT/UPDATE/STATUS/RECEIVE
 * （与 noon_m3_audit_log 的 CHECK 一致）。操作人取当前若依登录人；取不到时明确降级为
 * operator_id=NULL、operator_name="UNKNOWN"（仅发生在无安全上下文的直接调用）。
 */
@Component
public class PurchasingAudit
{
    static final String ENTITY_SUPPLIER = "SUPPLIER";
    static final String ENTITY_QUOTATION = "QUOTATION";
    static final String ENTITY_ORDER = "PUR_ORDER";
    static final String ENTITY_RECEIPT = "RECEIPT";

    static final String ACTION_INSERT = "INSERT";
    static final String ACTION_UPDATE = "UPDATE";
    static final String ACTION_STATUS = "STATUS";
    static final String ACTION_RECEIVE = "RECEIVE";

    /** 业务时刻统一 UTC+8（与项目口径一致），落 DATETIME(3)。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String UNKNOWN_OPERATOR = "UNKNOWN";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public PurchasingAudit(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条变更。
     *
     * @param entityType SUPPLIER/QUOTATION/PUR_ORDER/RECEIPT
     * @param entityId   业务主键（RECEIPT 用采购单 id，便于按单据回查整批收货）
     * @param action     INSERT/UPDATE/STATUS/RECEIVE
     * @param before     变更前读模型（新增传 null）
     * @param after      变更后读模型
     */
    public void record(String entityType, long entityId, String action, Object before, Object after)
    {
        LoginUser loginUser = currentLoginUserOrNull();
        Long operatorId = loginUser == null ? null : loginUser.getUserId();
        String operatorName = loginUser == null || loginUser.getUsername() == null
                ? UNKNOWN_OPERATOR : loginUser.getUsername();
        jdbc.update("INSERT INTO noon_m3_audit_log(entity_type,entity_id,action,operator_id,"
                        + "operator_name,before_json,after_json,changed_at) VALUES(?,?,?,?,?,?,?,?)",
                entityType, entityId, action, operatorId, operatorName,
                toJson(before), toJson(after), now());
    }

    /** 业务时刻（UTC+8，毫秒截断）：审计与收货明细共用同一口径。 */
    static LocalDateTime now()
    {
        return LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.MILLIS);
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
