package com.yx.noon.shipment;

/**
 * 费用登记结果（契约 §2 / §6）。{@code replayed}：create 首次为 false，同键同内容重放为 true；
 * {@code resultOf} 恒为 true（只读查询，不存在"首次"语义）。
 */
public record ShipmentCostResult(long costId, String costNo, boolean replayed)
{
}
