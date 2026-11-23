package com.yx.noon.masterdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Partner SKU 映射行的「站点 + 系统 SKU」数量分布。
 *
 * <p>归属粒度：采购明细、ASN 明细、库存台账都只记录系统 sku_id + site_code，
 * 不记录 md_partner_sku.id；同一站点多条映射指向同一系统 SKU 时无法归属到单条映射，
 * 因此统一标注为「站点 + 系统 SKU 汇总」，禁止臆造分摊。</p>
 *
 * <p>阶段口径（各阶段互斥，不重复计同一批货）：
 * 采购中 = 已下单未收货（排除 DRAFT/CLOSED）；
 * 头程在途 = ASN 已发运未官方收货（仅 SEALED，DRAFT 未发运、RECEIVED 已收完）；
 * FBN 仓可用 / 本地仓可用 = 四态台账按仓库 is_fbn 区分。</p>
 *
 * <p>Noon 官方实时库存未接入，明确列为「待接入」，不以 0 冒充。</p>
 */
@Service
public class PartnerSkuQuantityService
{
    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public PartnerSkuQuantityService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    public PartnerSkuQuantityBreakdown breakdown(long mappingId)
    {
        if (mappingId <= 0) throw new IllegalArgumentException("Partner SKU 映射 id 必须为正数");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT partner_sku,sku_id,site_code FROM md_partner_sku WHERE id=?", mappingId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Partner SKU 映射不存在: id=" + mappingId);
        String siteCode = (String) rows.get(0).get("site_code");
        guard.requireSite(siteCode); // 服务端站点闸门，不信任前端
        long skuId = ((Number) rows.get(0).get("sku_id")).longValue();
        String partnerSku = (String) rows.get(0).get("partner_sku");

        List<String> skuCodes = jdbc.queryForList("SELECT sku_code FROM md_sku WHERE id=?", String.class, skuId);
        String skuCode = skuCodes.isEmpty() ? null : skuCodes.get(0);

        int shared = jdbc.queryForObject(
                "SELECT COUNT(*) FROM md_partner_sku WHERE sku_id=? AND site_code=? AND business_model='noon'",
                Integer.class, skuId, siteCode);
        String scopeNote = shared > 1
                ? "本站点有 " + shared + " 条 Partner SKU 映射到同一系统 SKU（" + skuCode
                        + "）。采购与库存仅按系统 SKU 记账、无法归属到单条映射，以下为该站点 + 系统 SKU 汇总，非本映射独有。"
                : "口径：站点 " + siteCode + " + 系统 SKU " + skuCode
                        + "；本站点当前仅此一条映射，汇总即本映射对应商品。";

        List<StageQuantity> stages = new ArrayList<>();
        stages.add(purchasing(skuId, siteCode));
        stages.add(inTransit(skuId, siteCode));
        stages.add(warehouseAvailable(skuId, siteCode, true));
        stages.add(warehouseAvailable(skuId, siteCode, false));

        List<PendingStage> pending = List.of(
                new PendingStage("NOON_FBN_LIVE", "Noon 官方仓实时库存",
                        "当前未接入 Noon FBN 实时库存 API；上方 FBN 仓可用为系统内部四态台账，不等同平台实时库存。"));

        return new PartnerSkuQuantityBreakdown(
                siteCode, skuId, skuCode, partnerSku, shared, scopeNote, stages, pending);
    }

    private StageQuantity purchasing(long skuId, String siteCode)
    {
        String sql = "SELECT o.id AS doc_id,o.order_no AS doc_no,"
                + "(i.qty_ordered-i.qty_received) AS qty "
                + "FROM pur_order_item i JOIN pur_order o ON o.id=i.pur_order_id "
                + "WHERE i.sku_id=? AND o.site_code=? AND o.status NOT IN ('DRAFT','CLOSED') "
                + "AND i.qty_ordered>i.qty_received ORDER BY o.id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, skuId, siteCode);
        return stage("PURCHASING", "采购中（已下单未收货）", rows,
                "pur_order_item.qty_ordered − qty_received；排除 DRAFT/CLOSED");
    }

    private StageQuantity inTransit(long skuId, String siteCode)
    {
        String sql = "SELECT a.id AS doc_id,a.asn_no AS doc_no,"
                + "(i.qty_shipped-i.qty_received) AS qty "
                + "FROM log_asn_item i JOIN log_asn a ON a.id=i.asn_id "
                + "WHERE i.sku_id=? AND a.site_code=? AND a.status='SEALED' "
                + "AND i.qty_shipped>i.qty_received ORDER BY a.id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, skuId, siteCode);
        return stage("IN_TRANSIT", "头程在途（已发运未官方收货）", rows,
                "log_asn_item.qty_shipped − qty_received，仅 ASN SEALED；DRAFT 未发运、RECEIVED 已收完");
    }

    private StageQuantity warehouseAvailable(long skuId, String siteCode, boolean fbn)
    {
        String sql = "SELECT w.id AS doc_id,w.warehouse_name AS doc_no,l.qty_available AS qty "
                + "FROM inv_stock_ledger l JOIN md_warehouse w ON w.id=l.warehouse_id "
                + "WHERE l.sku_id=? AND l.site_code=? AND w.site_code=? AND w.is_fbn=? "
                + "AND l.qty_available>0 ORDER BY w.id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, skuId, siteCode, siteCode, fbn ? 1 : 0);
        return stage(fbn ? "FBN_AVAILABLE" : "LOCAL_AVAILABLE",
                fbn ? "FBN 仓可用" : "本地仓可用（非 FBN）", rows,
                fbn ? "inv_stock_ledger.qty_available（is_fbn=1，系统内部台账）"
                        : "inv_stock_ledger.qty_available（is_fbn=0，不计入 FBN 阶段）");
    }

    private StageQuantity stage(String code, String label, List<Map<String, Object>> rows, String source)
    {
        List<QuantityDoc> docs = new ArrayList<>();
        int total = 0;
        for (Map<String, Object> row : rows)
        {
            int qty = ((Number) row.get("qty")).intValue();
            docs.add(new QuantityDoc(
                    String.valueOf(row.get("doc_no")),
                    ((Number) row.get("doc_id")).longValue(),
                    null, qty));
            total += qty;
        }
        return new StageQuantity(code, label, total, source, docs);
    }
}
