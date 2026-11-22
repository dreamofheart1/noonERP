package com.yx.noon.sales;

import java.util.List;

/**
 * 订单详情：订单头 + 明细行（部分妥投/退货的守恒状态在前端可见）。
 */
public record SalesOrderDetail(SalesOrderRow order, List<SalesOrderItemRow> items)
{
    public SalesOrderDetail
    {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
