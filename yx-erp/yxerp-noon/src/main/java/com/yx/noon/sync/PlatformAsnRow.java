package com.yx.noon.sync;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Noon 平台 ASN 行（只读）。平台编号 platformAsnNr 与 ERP 内部 ASN 编号分开保存，
 * 本次不自动关联、不生成入库、不过账。明细仅保留平台可得字段。
 */
public record PlatformAsnRow(String siteCode, String platformAsnNr, String status,
                             String dstWarehouseCode, Integer expectedQty, Integer qtyReceived,
                             Integer qtyPutaway, String platformUpdatedAt, String itemsJson)
{
    public static PlatformAsnRow from(JsonNode n, String site)
    {
        JsonNode items = n.path("items");
        return new PlatformAsnRow(site,
                n.path("asn_nr").asText(""),
                n.path("status").asText(""),
                n.path("dst_warehouse_code").asText(""),
                integer(n, "expected_qty"),
                integer(n, "qty_received"),
                integer(n, "qty_putaway"),
                text(n, "updated_at"),
                items.isMissingNode() || items.isNull() ? null : items.toString());
    }

    private static Integer integer(JsonNode n, String key)
    {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }

    private static String text(JsonNode n, String key)
    {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
