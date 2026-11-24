package com.yx.noon.sync;

/** 同步结果计数；truncated=true 表示受 maxPages 限制可能未取全，不能当作完整快照。 */
public record NoonSellerLabSyncResult(String siteCode, String kind, int platformRows, int writtenRows, boolean truncated)
{
    public NoonSellerLabSyncResult(String siteCode, String kind, int platformRows, int writtenRows)
    {
        this(siteCode, kind, platformRows, writtenRows, false);
    }
}
