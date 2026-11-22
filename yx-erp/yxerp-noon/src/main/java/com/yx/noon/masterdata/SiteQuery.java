package com.yx.noon.masterdata;

/** 站点列表查询条件（契约 v3）。siteCode 传入时同时作为授权闸门与站点筛选，不做二次冲突校验。 */
public record SiteQuery(String siteCode, Long storeId, String status)
{
}
