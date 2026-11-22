package com.yx.noon.shipment;

/**
 * 费用列表/详情查询条件（契约 §2）。全部可选；空白/为 null 表示不过滤。
 */
public record CostQuery(String feeType, Long asnId, String period)
{
}
