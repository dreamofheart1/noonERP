package com.yx.noon.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M4 库存变更审计（契约 §1 / §2.10）。由 Service 在**业务写入同一事务**内调用：
 * 业务异常回滚 → 审计行一起回滚，不留「成功假象」；表 append-only，应用无更新/删除入口。
 *
 * <p>列与 {@code yx-erp/sql/noon_m4_inventory.sql} 一致：biz_type / biz_id / action / detail /
 * operator_id / created_at。{@code detail} 是摘要文本（VARCHAR(1024)），不写完整大字段。
 */
@Component
public class InventoryAudit
{
    static final String BIZ_INBOUND = "INBOUND";
    static final String ACTION_CONFIRM = "CONFIRM";

    private final JdbcTemplate jdbc;

    public InventoryAudit(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条库存变更审计。
     *
     * @param bizType    INBOUND
     * @param bizId      业务主键（inbound_id 字符串）
     * @param action     CONFIRM
     * @param detail     摘要（&lt;= 1024 字符）
     * @param operatorId 当前若依登录人
     */
    public void record(String bizType, String bizId, String action, String detail, Long operatorId)
    {
        jdbc.update("INSERT INTO noon_m4_inventory_audit(biz_type,biz_id,action,detail,operator_id,created_at) "
                        + "VALUES(?,?,?,?,?,?)",
                bizType, bizId, action, detail, operatorId, InventoryRules.now());
    }
}
