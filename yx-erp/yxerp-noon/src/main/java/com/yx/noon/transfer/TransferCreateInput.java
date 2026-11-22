package com.yx.noon.transfer;

import java.util.List;

/**
 * 调拨建单入参（契约 v1.2 §9.1）。站点授权只按两仓真实 {@code md_warehouse.site_code}，
 * {@code siteCode} 仅展示校验（非空时必须与源仓站点一致，不参与授权，禁止自报站点提权）。
 *
 * @param fromWarehouseId 源仓（必须存在且 ACTIVE，与目标仓同站点）
 * @param toWarehouseId   目标仓（必须存在且 ACTIVE，不得与源仓相同）
 * @param siteCode        仅展示校验，不参与授权
 * @param lines           计划明细：每条按 {@code (skuId,batchNo)} 引用源仓一个 OPEN 批次
 * @param requestId       业务幂等键（CREATE 命名空间，UUID；ship/receive 用独立幂等表）
 */
public record TransferCreateInput(Long fromWarehouseId, Long toWarehouseId, String siteCode,
                                  List<TransferCreateLine> lines, String requestId)
{
}
