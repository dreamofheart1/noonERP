package com.yx.noon.transfer;

import java.util.List;

/** 调拨单详情（契约 §3）：头 + 行（行带子批次血缘便于前端展示在途/收货状态）。 */
public record TransferDetail(TransferRow head, List<TransferItemRow> items)
{
    public TransferDetail
    {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
