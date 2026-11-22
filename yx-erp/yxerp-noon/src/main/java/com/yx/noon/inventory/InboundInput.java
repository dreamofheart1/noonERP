package com.yx.noon.inventory;

import java.util.List;

/**
 * 入库确认入参（契约 §3）。
 *
 * <p>{@code siteCode} 仅前端带出展示；服务端以 {@code md_warehouse} 的归属站点为准，
 * 非空时必须与仓库站点一致，否则 400。禁止用请求体自报站点授权。
 *
 * <p>{@code lines} 只允许 {@link InboundLineInput}（来源行 + 合格量）；skuId / unitCost /
 * batchNo / qty 一律由后端从 M3 收货记录与采购单行权威带出，record 结构上就不接收。
 *
 * <p>{@code requestId} 为调用方生成的幂等键（UUID，8–64 位 {@code [A-Za-z0-9_-]}）。
 */
public record InboundInput(Long purOrderId, Long warehouseId, String siteCode,
                           List<InboundLineInput> lines, String requestId)
{
}
