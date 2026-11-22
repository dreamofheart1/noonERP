package com.yx.noon.purchasing;

import java.math.BigDecimal;

/**
 * SKU 供应商报价入参（契约 4.2）。历史价按 effectiveFrom 版本化：
 * 改价用新 effectiveFrom 插入新行，不物理删除旧行。
 */
public record QuotationInput(Long skuId, Long supplierId, BigDecimal unitPrice, String currency,
                             Integer moq, Integer leadTimeDays, String effectiveFrom, String effectiveTo)
{
}
