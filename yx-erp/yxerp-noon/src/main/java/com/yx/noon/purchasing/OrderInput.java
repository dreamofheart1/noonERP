package com.yx.noon.purchasing;

import java.util.List;

/**
 * 采购单新增/修改入参（契约 4.3）。items 至少 1 行；行 currency 必须与订单 currency 一致。
 * orderNo 由服务端生成，客户端传入值在新增时忽略、修改时只用于一致性校验；
 * supplierId 可空，非空时必须引用 ACTIVE 供应商；sourceRefNo 只记录上游建议单号。
 */
public record OrderInput(String orderNo, Long supplierId, String siteCode, String currency,
                         String source, String sourceRefNo, List<OrderItemInput> items)
{
}
