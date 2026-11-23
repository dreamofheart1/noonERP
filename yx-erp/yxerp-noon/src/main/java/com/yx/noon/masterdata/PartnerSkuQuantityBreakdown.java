package com.yx.noon.masterdata;

import java.util.List;

/**
 * Partner SKU 映射行展开的数量分布。所有阶段均为「站点 + 系统 SKU」口径，
 * 归属限制与待接入阶段在 scopeNote/pendingStages 中显式说明，不臆造分摊或零值。
 */
public record PartnerSkuQuantityBreakdown(String siteCode, long skuId, String skuCode, String partnerSku,
                                          int sharedMappingCount, String scopeNote,
                                          List<StageQuantity> stages, List<PendingStage> pendingStages)
{
}
