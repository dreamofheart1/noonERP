package com.yx.noon.sync;

import java.sql.Timestamp;

/** 期初建议行：确认前只是建议，不代表已建账。 */
public record OpeningProposalRow(String proposalNo, String siteCode, String partnerSku, Long skuId,
                                 int qtySaleable, int qtyNonSaleable, Timestamp snapshotAt,
                                 String overlapNote, String costStatus, String status)
{
}
