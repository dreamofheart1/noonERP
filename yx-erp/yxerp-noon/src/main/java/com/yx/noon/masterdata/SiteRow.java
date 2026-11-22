package com.yx.noon.masterdata;

import java.math.BigDecimal;

/** 站点读模型（id + md_site 业务列，不含 DB 生成时间列）。 */
public record SiteRow(long id, String siteCode, Long storeId, String country, String currency,
                      BigDecimal vatRate, String timezone, String status)
{
}
