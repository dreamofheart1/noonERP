package com.yx.noon.transfer;

import java.util.List;

/**
 * 调拨发货入参（契约 §3）。不含 SKU/成本/批次号/源批次 id：全部服务端按 {@code itemId}
 * （= {@code inv_transfer_item.id}）从调拨单行与 {@code inv_batch} 权威带出，客户端夹带字段被 record 丢弃。
 *
 * @param transferId      调拨单 id
 * @param fromWarehouseId 源仓（须与调拨单真实源仓一致，否则 400）
 * @param toWarehouseId   目标仓（须与调拨单真实目标仓一致，否则 400）
 * @param siteCode        仅展示校验：非空时必须与源仓站点一致，不参与授权
 * @param lines           发货明细，必须覆盖全部调拨单行
 * @param requestId       业务幂等键（SHIP 命名空间，UUID）
 */
public record TransferShipInput(Long transferId, Long fromWarehouseId, Long toWarehouseId,
                                String siteCode, List<TransferShipLine> lines, String requestId)
{
}
