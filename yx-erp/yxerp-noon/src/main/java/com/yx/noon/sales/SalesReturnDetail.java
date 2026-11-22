package com.yx.noon.sales;

import java.util.List;

/**
 * 退货详情：退货头 + 处置链路流水（红冲 + RETURN_LOSS），用于核对 C-2/C-2b 数值。
 */
public record SalesReturnDetail(SalesReturnRow row, List<SalesReturnFlowRow> flows)
{
    public SalesReturnDetail
    {
        flows = flows == null ? List.of() : List.copyOf(flows);
    }
}
