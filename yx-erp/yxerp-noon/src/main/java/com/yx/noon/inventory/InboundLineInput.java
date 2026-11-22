package com.yx.noon.inventory;

/**
 * 入库明细入参（契约 §3）：只提交采购来源行 + 本次合格量。
 *
 * <p>SKU、单位成本（来自 {@code noon_m3_receipt.unit_cost_cny}）、批次号全部由服务端生成，
 * 客户端夹带 unitCost / skuId / batchNo / qty 不进入本 record，绑定阶段即丢弃。
 */
public record InboundLineInput(Long purOrderItemId, int qtyQualified)
{
}
