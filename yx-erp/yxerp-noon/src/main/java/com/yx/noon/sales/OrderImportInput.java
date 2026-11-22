package com.yx.noon.sales;

import java.math.BigDecimal;

/**
 * 订单导入入参（契约 §3）。{@code requestId} 为动作级幂等号；门店/商品/金额/数量/币种均参与可信 payload，
 * 与内部 {@code IntakeService.verifyOrderEvidence} 逐项比对。{@code sourceChannel} 可空（默认 WEBHOOK）。
 */
public record OrderImportInput(
        String requestId,
        String siteCode,
        String orderNo,
        Long storeId,
        Long skuId,
        String partnerSku,
        BigDecimal grossAmount,
        Integer qty,
        String currency,
        String sourceChannel)
{
}
