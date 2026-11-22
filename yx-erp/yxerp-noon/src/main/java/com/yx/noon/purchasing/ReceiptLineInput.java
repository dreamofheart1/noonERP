package com.yx.noon.purchasing;

/** 收货行入参（契约 4.4）：itemId 必须属于该采购单，qty > 0。 */
public record ReceiptLineInput(Long itemId, Integer qty)
{
}
