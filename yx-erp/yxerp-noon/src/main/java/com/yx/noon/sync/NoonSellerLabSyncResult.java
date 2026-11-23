package com.yx.noon.sync;

/** 同步结果计数：平台和落库分开，失败在异常中体现，不以空当零。 */
public record NoonSellerLabSyncResult(String siteCode, String kind, int platformRows, int writtenRows)
{
}
