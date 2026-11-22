package com.yx.noon.sales;

/**
 * 退货红冲入参（契约 §3）。{@code originalConsumptionId} 必须指向 {@code sign=+1 && move_type='SALE'}
 * 的原消耗行（内部服务以 FOR UPDATE 当前读校验）。
 */
public record ReturnReverseInput(
        String requestId,
        String siteCode,
        Long salReturnId,
        Long originalConsumptionId,
        Integer qty)
{
}
