package com.yx.noon.purchasing;

import java.util.List;

/**
 * 采购单新增/修改入参（契约 4.3）。items 至少 1 行；行 currency 必须与订单 currency 一致。
 * sourceRefNo 只记录上游建议单号，不校验上游单据存在。
 */
public record OrderInput(String orderNo, Long supplierId, String siteCode, String currency,
                         String source, String sourceRefNo, List<OrderItemInput> items)
{
}
