package com.yx.noon.sync;

/** 同步结果计数：所有数字来自真实写库行数，失败项单独计数并保留原因。 */
public record NoonSkuSyncResult(String siteCode, int catalogSkus, int offersFound,
                                int skuCreated, int skuUpdated, int mappingCreated, int mappingUpdated,
                                int skipped, int failed, String message)
{
    public static NoonSkuSyncResult empty(String siteCode, String message)
    {
        return new NoonSkuSyncResult(siteCode, 0, 0, 0, 0, 0, 0, 0, 0, message);
    }
}
