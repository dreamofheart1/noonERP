package com.yx.noon.sales;

/**
 * 退货入账入参（契约 §3）。{@code sourceReturnNo} 为平台退货单号（不得臆造）；{@code orderNo} 只用于
 * 与父订单行交叉校验（内部服务以数据库真实值为准）。
 */
public record ReturnImportInput(
        String requestId,
        String siteCode,
        String orderNo,
        String sourceReturnNo,
        Long salOrderItemId,
        Integer qty)
{
}
