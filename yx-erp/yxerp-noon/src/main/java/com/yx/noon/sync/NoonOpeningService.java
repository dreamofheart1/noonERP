package com.yx.noon.sync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 期初库存：先生成"建议 + 待核项"，由用户查看确认后才建 inv_batch / inv_stock_ledger。
 *
 * <p>安全边界：
 * <ul>
 *   <li>generate() 只读平台快照与 ERP 现状，绝不写库存账；</li>
 *   <li>重叠（已有真实入库/测试数据/已销售/在途）只标注，不自动抵扣或删除；</li>
 *   <li>成本未知标 UNKNOWN，不允许以 0 成本建批；</li>
 *   <li>confirm() 必须显式 proposalNo + 操作人，且逐行校验仍 PENDING；</li>
 *   <li>建账按 proposal 唯一约束幂等，重复确认不会重复计入。</li>
 * </ul>
 */
@Service
public class NoonOpeningService
{
    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public NoonOpeningService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** 生成建议（只读，不改库存）。返回建议行，含与 ERP 现状的重叠/待核说明。 */
    public List<OpeningProposalRow> generate(String siteCode, String proposalNo, String asOfDate)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        if (proposalNo == null || proposalNo.isBlank())
            throw new IllegalArgumentException("proposalNo 不能为空");

        // 平台最新 FBN 快照（按 partner_sku 汇总可售/不可售），按 asOfDate 截断
        String sql = "SELECT f.partner_sku,p.sku_id,"
                + "SUM(f.saleable) AS saleable,SUM(f.non_saleable) AS non_saleable,MAX(f.captured_at) AS snap "
                + "FROM noon_fbn_inventory_snapshot f "
                + "LEFT JOIN md_partner_sku p ON p.partner_sku=f.partner_sku AND p.site_code=f.site_code AND p.business_model='noon' "
                + "WHERE f.site_code=? AND (? IS NULL OR DATE(f.captured_at)<=DATE(?)) "
                + "GROUP BY f.partner_sku,p.sku_id ORDER BY f.partner_sku";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, site, asOfDate, asOfDate);
        List<OpeningProposalRow> out = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            String partnerSku = (String) r.get("partner_sku");
            Long skuId = (Long) r.get("sku_id");
            int saleable = ((Number) r.get("saleable")).intValue();
            int nonSaleable = ((Number) r.get("non_saleable")).intValue();
            Object snapObj = r.get("snap");
            Timestamp snap = snapObj instanceof Timestamp t ? t
                    : snapObj instanceof java.time.LocalDateTime l ? Timestamp.valueOf(l) : null;
            if (snap == null) throw new IllegalStateException("平台快照缺少有效时间，不能生成期初建议");

            // 待核项：已有 FBN 台账、已销售明细、在途 ASN，均只标注不抵扣
            List<String> notes = new ArrayList<>();
            int ledgerRows = skuId == null ? 0 : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inv_stock_ledger l JOIN md_warehouse w ON w.id=l.warehouse_id "
                            + "WHERE l.sku_id=? AND l.site_code=? AND w.site_code=? AND w.is_fbn=1",
                    Integer.class, skuId, site, site);
            if (ledgerRows > 0) notes.add("已有 FBN 台账记录 " + ledgerRows + " 仓，需核实是否重复旧货");
            int sold = skuId == null ? 0 : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM noon_sales_link l JOIN sal_order_item i ON i.id=l.sal_order_item_id "
                            + "WHERE i.sku_id=? AND l.site_code=?", Integer.class, skuId, site);
            if (sold > 0) notes.add("已关联销售明细 " + sold + " 条，需核实是否已含于快照");
            int transit = skuId == null ? 0 : jdbc.queryForObject(
                    "SELECT COUNT(*) FROM inv_stock_ledger l JOIN md_warehouse w ON w.id=l.warehouse_id "
                            + "WHERE l.sku_id=? AND l.site_code=? AND w.site_code=? AND w.is_fbn=1 AND l.qty_in_transit>0",
                    Integer.class, skuId, site, site);
            if (transit > 0) notes.add("存在 FBN 在途 " + transit + " 仓，需核实起点时点");
            if (skuId == null) notes.add("未匹配系统 SKU，不可比较也不可建账");
            if (notes.isEmpty()) notes.add("未发现明显重叠，仍需人工确认");

            OpeningProposalRow row = new OpeningProposalRow(proposalNo, site, partnerSku, skuId,
                    saleable, nonSaleable, snap, String.join("；", notes), "UNKNOWN", "PENDING");
            // 建议只落建议表；同键幂等，不覆盖用户已确认/拒绝的行
            jdbc.update("INSERT INTO noon_opening_proposal"
                            + "(proposal_no,site_code,partner_sku,sku_id,qty_saleable,qty_non_saleable,snapshot_at,overlap_note,cost_status,status,source) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,'PENDING','NOON_SELLERLAB') "
                            + "ON DUPLICATE KEY UPDATE overlap_note=VALUES(overlap_note),"
                            + "qty_saleable=VALUES(qty_saleable),qty_non_saleable=VALUES(qty_non_saleable)",
                    proposalNo, site, partnerSku, skuId, saleable, nonSaleable, snap,
                    row.overlapNote(), "UNKNOWN");
            out.add(row);
        }
        return out;
    }

    /** 用户确认后建账：需显式 proposalNo、站点、操作人、unitCost；成本为 null 拒绝（不造零成本）。 */
    @Transactional(rollbackFor = Exception.class)
    public int confirm(String siteCode, String proposalNo, String operator, java.math.BigDecimal unitCost)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        if (proposalNo == null || proposalNo.isBlank()) throw new IllegalArgumentException("proposalNo 不能为空");
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("确认人不能为空");
        if (unitCost == null || unitCost.signum() <= 0)
            throw new IllegalArgumentException("期初成本未知或为 0，禁止建账（须先补真实成本）");

        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT * FROM noon_opening_proposal WHERE proposal_no=? AND site_code=? AND status='PENDING'",
                proposalNo, site);
        int built = 0;
        for (Map<String, Object> p : pending)
        {
            Long skuId = (Long) p.get("sku_id");
            String skuCode = jdbc.queryForObject("SELECT sku_code FROM md_sku WHERE id=?", String.class, skuId);
            int qty = ((Number) p.get("qty_saleable")).intValue();
            if (qty <= 0) continue; // 无可售量不建批；不可售另按待补处理，不伪造
            String batchNo = "OPEN:" + site + ":" + proposalNo + ":" + skuId;
            int inserted = jdbc.update("INSERT IGNORE INTO inv_batch"
                            + "(batch_no,sku_id,warehouse_id,site_code,received_at,qty_in,qty_remaining,"
                            + "unit_cost,currency,fx_rate,source_type,status) "
                            + "SELECT ?,?,w.id,?,?,?,?,?,'CNY',1,'ADJUSTMENT','OPEN' "
                            + "FROM md_warehouse w WHERE w.site_code=? AND w.is_fbn=1 LIMIT 1",
                    batchNo, skuId, site, p.get("snapshot_at"), qty, qty, unitCost, site);
            if (inserted > 0)
            {
                jdbc.update("INSERT INTO inv_stock_ledger(sku_id,warehouse_id,site_code,qty_available,qty_unsellable,qty_in_transit) "
                                + "SELECT ?,w.id,?,?,0,0 FROM md_warehouse w WHERE w.site_code=? AND w.is_fbn=1 LIMIT 1 "
                                + "ON DUPLICATE KEY UPDATE qty_available=qty_available+?",
                        skuId, site, qty, site, qty);
                built++;
            }
            jdbc.update("UPDATE noon_opening_proposal SET status='CONFIRMED',cost_status='PROVIDED',unit_cost=?,"
                            + "confirmed_by=?,confirmed_at=? WHERE id=?",
                    unitCost, operator, Timestamp.valueOf(LocalDateTime.now()), p.get("id"));
            jdbc.update("INSERT INTO noon_opening_audit(proposal_no,site_code,action,operator,detail_json) "
                            + "VALUES(?,?, 'CONFIRM',?,?)", proposalNo, site, operator,
                    "{\"skuId\":" + skuId + ",\"qty\":" + qty + "}");
        }
        return built;
    }
}
