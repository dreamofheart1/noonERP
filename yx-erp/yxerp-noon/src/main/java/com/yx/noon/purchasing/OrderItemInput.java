package com.yx.noon.purchasing;

import java.math.BigDecimal;

/**
 * 采购单行入参（契约 4.3）。unitPrice 为提交时快照（历史价口径，不随报价变动）；
 * fxRate 按币种规则：CNY 必须显式 1，外币必须 > 0。
 */
public record OrderItemInput(Long skuId, Integer qtyOrdered, BigDecimal unitPrice,
                             String currency, BigDecimal fxRate)
{
}
