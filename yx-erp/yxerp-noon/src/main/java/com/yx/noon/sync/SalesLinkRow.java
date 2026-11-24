package com.yx.noon.sync;

/** 销售明细联动待核行：READY_FOR_FIFO 只表示具备条件，不代表已扣库存。 */
public record SalesLinkRow(String siteCode, String itemNr, String decision, String note)
{
}
