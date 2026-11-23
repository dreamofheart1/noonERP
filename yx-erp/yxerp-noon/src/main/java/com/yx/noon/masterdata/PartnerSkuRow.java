package com.yx.noon.masterdata;

import java.time.LocalDateTime;

/** Partner SKU 读模型：包含 Noon 同步回读的平台标识与 Offer 状态。 */
public record PartnerSkuRow(long id, String partnerSku, Long skuId, String siteCode,
                            String businessModel, String offerCode, String barcode, String noonSku,
                            Boolean liveStatus, LocalDateTime syncedAt, String status)
{
}
