package com.yx.noon.sync;

import com.fasterxml.jackson.databind.JsonNode;

/** Seller Lab Sales 明细平台行；item_nr 仅明细标识，不是整单号。 */
public record SalesItemRow(String siteCode, String itemNr, String partnerSku, String sku,
                           String status, String fulfillmentModel,
                           String orderTs, String shipmentTs, String deliveredTs,
                           String priceAmount, String currencyCode, String awbNr)
{
    public static SalesItemRow from(JsonNode n, String site)
    {
        return new SalesItemRow(site,
                n.path("item_nr").asText(""),
                n.path("partner_sku").asText(""),
                n.path("sku").asText(""),
                n.path("status").asText(""),
                n.path("fulfillment_model").asText(""),
                text(n, "order_timestamp"),
                text(n, "shipment_timestamp"),
                text(n, "delivered_timestamp"),
                n.path("offer_price").isMissingNode() || n.path("offer_price").isNull()
                        ? null : n.path("offer_price").asText(),
                n.path("currency_code").asText(""),
                n.path("awb_nr").asText(""));
    }

    private static String text(JsonNode n, String key)
    {
        JsonNode v = n.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
