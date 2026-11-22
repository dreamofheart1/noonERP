package com.yx.noon.masterdata;

import java.math.BigDecimal;

/** 站点写入入参（字段名对应 md_site 列 camelCase）。 */
public record SiteInput(String siteCode, Long storeId, String country, String currency,
                        BigDecimal vatRate, String timezone, String status)
{
}
