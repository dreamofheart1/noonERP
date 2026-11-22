package com.yx.noon.transfer;

/**
 * 调拨建单计划行（契约 v1.2 §9.1）。record 结构上只有选择键 {@code (skuId,batchNo)} 与计划量 {@code qty}：
 * 成本/币种/汇率/来源批次 id 全部服务端从 {@code inv_batch} 权威带出，客户端夹带字段被 Jackson 丢弃。
 *
 * @param skuId   SKU（必须与来源批次真实 SKU 一致，否则 400）
 * @param batchNo 来源父批次号（必须在源仓为唯一 OPEN 批次）
 * @param qty     计划发货量（&gt;0，且不超过来源批次当前剩余量）
 */
public record TransferCreateLine(Long skuId, String batchNo, int qty)
{
}
