package com.yx.noon.sync;

/**
 * Offer API 回读结果（只取 AE + noon 业务模式的那条 offer）。
 * issuesJson 为 offer_issues 原始数组，禁止改写或伪造；barcode 来自 FBN 台账导出。
 * imageUrl 来自 Noon Content API 的 images[]（按 sort 取首张可见有效图），没有则 null。
 */
public record NoonOfferInfo(String partnerSku, String noonSku, String barcode, String title, String brand,
                            String offerCode, boolean isActive, boolean liveStatus,
                            String issuesJson, String priceAmount, String currency, String imageUrl)
{
}
