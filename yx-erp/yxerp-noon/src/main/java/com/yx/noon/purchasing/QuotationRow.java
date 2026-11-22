package com.yx.noon.purchasing;

import java.math.BigDecimal;

/** 报价读模型（契约 4.2）。日期以 yyyy-MM-dd 字符串回传，null 表示无结束日（长期有效）。 */
public record QuotationRow(long id, Long skuId, Long supplierId, BigDecimal unitPrice, String currency,
                           Integer moq, Integer leadTimeDays, String effectiveFrom, String effectiveTo)
{
}
