package com.yx.noon.masterdata;

/** 店铺列表查询条件（契约 v3）。空/null/空白表示不过滤；字符串按大小写不敏感模糊匹配。 */
public record StoreQuery(String storeNo, String nameEn, String status)
{
}
