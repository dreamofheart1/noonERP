package com.yx.noon.sales;

/**
 * 妥投出库入参（契约 §3）。{@code salOrderItemId} 必须指向本单真实订单行；SKU 由编排层从订单行带出
 * （不信任调用方），明细只按数量表达。
 */
public record DeliverInput(
        String requestId,
        String siteCode,
        Long salOrderItemId,
        Integer qty)
{
}
