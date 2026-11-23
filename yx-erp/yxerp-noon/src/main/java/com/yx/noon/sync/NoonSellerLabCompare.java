package com.yx.noon.sync;

import java.sql.Timestamp;
import java.util.List;

/** 库存简单数量对比结果：官方与系统并列；系统无记录不等于 0，不认定丢货。 */
public record NoonSellerLabCompare(String siteCode, List<Row> rows)
{
    public record Row(String partnerSku, String noonSku, String skuCode,
                      int officialSaleable, int officialNonSaleable, int officialQty,
                      Integer systemAvailable, Integer systemUnsellable, Integer systemInTransit,
                      String warehouseCode, Timestamp capturedAt, String note)
    {
    }
}
