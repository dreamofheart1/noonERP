package com.yx.noon.purchasing;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 引用完整性校验（契约 5）：报价/采购单引用的 SKU、供应商必须存在且 ACTIVE。
 * 校验与写入在同一事务内（调用方 @Transactional），不依赖上游缓存。
 */
final class PurchasingRefs
{
    private PurchasingRefs()
    {
    }

    static void requireActiveSupplier(JdbcTemplate jdbc, Long supplierId)
    {
        PurchasingRules.requiredId("supplierId", supplierId);
        requireActiveSupplierIfPresent(jdbc, supplierId);
    }

    /** 采购单供应商可选：null 直接放行；非 null 时必须存在且 ACTIVE。报价仍走上面的严格校验。 */
    static void requireActiveSupplierIfPresent(JdbcTemplate jdbc, Long supplierId)
    {
        if (supplierId == null) return;
        List<String> status = jdbc.query("SELECT status FROM md_supplier WHERE id=?",
                (rs, i) -> rs.getString("status"), supplierId);
        if (status.isEmpty()) throw new IllegalArgumentException("供应商不存在: id=" + supplierId);
        if (!"ACTIVE".equals(status.get(0)))
            throw new IllegalArgumentException("供应商已停用，不能用于报价或采购单: id=" + supplierId);
    }

    static void requireActiveSku(JdbcTemplate jdbc, Long skuId)
    {
        PurchasingRules.requiredId("skuId", skuId);
        List<String> status = jdbc.query("SELECT status FROM md_sku WHERE id=?",
                (rs, i) -> rs.getString("status"), skuId);
        if (status.isEmpty()) throw new IllegalArgumentException("SKU 不存在: id=" + skuId);
        if (!"ACTIVE".equals(status.get(0)))
            throw new IllegalArgumentException("SKU 已停用，不能用于报价或采购单: id=" + skuId);
    }
}
