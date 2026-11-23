package com.yx.noon.sync;

/**
 * Offer API 回读结果（只取 AE + noon 业务模式的那条 offer）。
 * issuesJson 为 offer_issues 原始数组，禁止改写或伪造；barcode 来自 FBN 台账导出。
 */
public record NoonOfferInfo(String partnerSku, String noonSku, String barcode, String title, String brand,
                            String offerCode, boolean isActive, boolean liveStatus,
                            String issuesJson, String priceAmount, String currency)
{
}
