package com.yx.noon.sync;

import com.fasterxml.jackson.databind.JsonNode;

/** Seller Lab FBN 库存平台原始行（只取需要回读的列，不输出敏感数据）。 */
public record FbnInventoryRow(String siteCode, String partnerSku, String sku, String warehouseCode,
                              int saleable, int nonSaleable, int qty)
{
    public static FbnInventoryRow from(JsonNode n, String site)
    {
        return new FbnInventoryRow(site,
                n.path("partner_sku").asText(""),
                n.path("sku").asText(""),
                n.path("warehouse_code").asText(""),
                n.path("saleable").asInt(0),
                n.path("non_saleable").asInt(0),
                n.path("qty").asInt(0));
    }
}
