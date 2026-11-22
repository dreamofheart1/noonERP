package com.yx.noon.purchasing;

/**
 * 报价列表筛选（契约 4.2）：skuId/supplierId 精确；
 * effectiveFrom/effectiveTo 作为「生效起始日」区间的下/上界（含端点），空白不过滤。
 */
public record QuotationQuery(Long skuId, Long supplierId, String effectiveFrom, String effectiveTo)
{
}
