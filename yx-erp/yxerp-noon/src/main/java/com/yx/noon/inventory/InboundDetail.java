package com.yx.noon.inventory;

import java.util.List;

/** 入库单详情（契约 §3）：头 + 行。 */
public record InboundDetail(InboundRow head, List<InboundItemRow> items)
{
}
