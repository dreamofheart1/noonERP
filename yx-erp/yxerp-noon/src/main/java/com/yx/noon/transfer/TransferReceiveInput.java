package com.yx.noon.transfer;

import java.util.List;

/**
 * 调拨收货入参（契约 §3）。站点授权只按目标仓真实站点，不接受请求体自报站点（该 record 无 siteCode）。
 *
 * @param transferId    调拨单 id
 * @param toWarehouseId 目标仓（须与调拨单真实目标仓一致，否则 400）
 * @param lines         收货明细，每条 itemId 只能收一次
 * @param requestId     业务幂等键（RECEIVE 命名空间，UUID；与 ship 的 requestId 不可复用）
 */
public record TransferReceiveInput(Long transferId, Long toWarehouseId,
                                   List<TransferReceiveLine> lines, String requestId)
{
}
