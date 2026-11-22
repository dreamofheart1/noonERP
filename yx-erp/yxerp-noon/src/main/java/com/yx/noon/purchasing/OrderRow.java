package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购单读模型（契约 4.3），含明细行。totalAmount 为 Σ(qtyOrdered × unitPrice × fxRate)，
 * 只含采购价（不含头程/关税/包材）。
 */
public record OrderRow(long id, String orderNo, Long supplierId, String siteCode, String currency,
                       BigDecimal totalAmount, String status, String source, String sourceRefNo,
                       Long operatorId, List<OrderItemRow> items)
{
    public OrderRow
    {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
