package com.yx.noon.shipment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 头程费用登记审计（契约 §6）。由 {@link ShipmentCostService} 在<b>业务写入同一事务</b>内调用：
 * 业务异常回滚 → 审计行一起回滚，不留"成功假象"；append-only，应用无更新/删除入口。
 *
 * <p><b>选择说明</b>：本批迁移 {@code noon_m4_shipment_cost.sql}（WorkBuddy owner）只新增幂等表，
 * 不新增审计表；故复用 M4 既有共享审计表 {@code noon_m4_inventory_audit}（{@code biz_type} 列本就
 * 为多业务类型设计），以 {@code biz_type='SHIPMENT_COST'} 区分，不新增 DDL、不越 owner 边界。
 * 表结构：biz_type / biz_id（费用行 id）/ action='CREATE' / detail（摘要）/ operator_id / created_at。
 */
@Component
public class ShipmentCostAudit
{
    static final String BIZ_TYPE = "SHIPMENT_COST";
    static final String ACTION_CREATE = "CREATE";

    private final JdbcTemplate jdbc;

    public ShipmentCostAudit(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条费用登记审计。
     *
     * @param bizId      费用行主键
     * @param action     CREATE
     * @param detail     摘要（&lt;= 1024 字符）
     * @param operatorId 当前若依登录人（由服务层经 {@code guard.requireCurrentUserId()} 取得）
     */
    public void record(long bizId, String action, String detail, Long operatorId)
    {
        jdbc.update("INSERT INTO noon_m4_inventory_audit(biz_type,biz_id,action,detail,operator_id,created_at) "
                        + "VALUES(?,?,?,?,?,?)",
                BIZ_TYPE, String.valueOf(bizId), action, detail, operatorId, ShipmentCostRules.now());
    }
}
