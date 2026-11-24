package com.yx.noon.sync;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seller Lab 只读查询结果落库：FBN 库存快照与 Sales 明细。
 * 只写独立快照表，不覆盖 ERP 业务账表、不过账；按 (site, 平台键) 幂等 upsert。
 * 未同步/失败可见，空结果不当作 ERP 库存为 0。
 */
@Service
public class NoonSellerLabSyncService
{
    private final NoonSellerLabClient client;
    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public NoonSellerLabSyncService(NoonSellerLabClient client, JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.client = client;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** 手动/任务触发同步；siteCode 必须 AE/SA 且通过站点闸门。 */
    public NoonSellerLabSyncResult syncFbnInventory(String siteCode)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        List<FbnInventoryRow> rows = client.listFbnInventory(site);
        int written = persistFbn(site, rows);
        return new NoonSellerLabSyncResult(site, "FBN_INVENTORY", rows.size(), written);
    }

    public NoonSellerLabSyncResult syncSales(String siteCode, String fromDate, String toDate, int maxPages)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        List<SalesItemRow> rows = client.listSales(site, fromDate, toDate, maxPages);
        int written = persistSales(site, rows);
        return new NoonSellerLabSyncResult(site, "SALES", rows.size(), written, client.isLastSalesTruncated());
    }

    /**
     * 库存对比：官方快照行 vs 系统 FBN 仓台账。
     * 口径：系统只取 is_fbn=1 的仓库，按同站点 + sku_id 汇总（跨 FBN 仓合并），
     * 不再把本地仓计入；仓库码无法与平台匹配时标注不可比较，**不做差**。
     */
    public NoonSellerLabCompare compare(String siteCode, int offset, int limit)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        // 1) 官方最新快照，按 partner_sku 汇总各仓（同 SKU 多仓相加），保留仓库清单
        String snapSql = "SELECT f.partner_sku,f.sku AS noon_sku,s.id AS sku_id,s.sku_code,"
                + "SUM(f.saleable) AS official_saleable,SUM(f.non_saleable) AS official_non_saleable,"
                + "SUM(f.qty) AS official_qty,COUNT(DISTINCT f.warehouse_code) AS official_wh_count,"
                + "MAX(f.captured_at) AS captured_at "
                + "FROM noon_fbn_inventory_snapshot f "
                + "JOIN md_partner_sku p ON p.partner_sku=f.partner_sku AND p.site_code=f.site_code AND p.business_model='noon' "
                + "LEFT JOIN md_sku s ON s.id=p.sku_id "
                + "WHERE f.site_code=? AND f.captured_at=(SELECT MAX(captured_at) FROM noon_fbn_inventory_snapshot WHERE site_code=?) "
                + "GROUP BY f.partner_sku,f.sku,s.id,s.sku_code "
                + "ORDER BY f.partner_sku LIMIT ? OFFSET ?";
        List<NoonSellerLabCompare.Row> rows = jdbc.query(snapSql, (rs, i) ->
        {
            Long skuId = (Long) rs.getObject("sku_id");
            String skuCode = rs.getString("sku_code");
            String note = "";
            Integer avail = null, unsellable = null, transit = null;
            if (skuId == null || skuCode == null)
            {
                note = "系统未维护对应 SKU，无法比较（不按 0 处理）";
            }
            else
            {
                // 2) 系统只统计 FBN 仓（is_fbn=1），按 sku_id + site 汇总，避免本地仓参与相减
                java.util.Map<String, Object> led = jdbc.queryForMap(
                        "SELECT COALESCE(SUM(qty_available),0) AS a,COALESCE(SUM(qty_unsellable),0) AS u,"
                                + "COALESCE(SUM(qty_in_transit),0) AS t,COUNT(*) AS n "
                                + "FROM inv_stock_ledger l JOIN md_warehouse w ON w.id=l.warehouse_id "
                                + "WHERE l.sku_id=? AND l.site_code=? AND w.site_code=? AND w.is_fbn=1",
                        skuId, site, site);
                long n = ((Number) led.get("n")).longValue();
                if (n == 0)
                    note = "系统无 FBN 仓台账记录（未发生入库/直发收货），不可比较，差异不代表丢货";
                else
                {
                    avail = ((Number) led.get("a")).intValue();
                    unsellable = ((Number) led.get("u")).intValue();
                    transit = ((Number) led.get("t")).intValue();
                }
            }
            return new NoonSellerLabCompare.Row(
                    rs.getString("partner_sku"), rs.getString("noon_sku"), skuCode,
                    rs.getInt("official_saleable"), rs.getInt("official_non_saleable"), rs.getInt("official_qty"),
                    avail, unsellable, transit,
                    "FBN仓x" + rs.getInt("official_wh_count"), rs.getTimestamp("captured_at"), note);
        }, site, site, limit, offset);
        return new NoonSellerLabCompare(site, rows);
    }

    /** Sales 列表：item_nr 作编号，按 (site, item_nr) 唯一；保留来源与同步时间。 */
    public com.yx.noon.masterdata.PageResult<com.yx.noon.sync.SalesItemRow> listSales(String siteCode, int offset, int limit)
    {
        return listSales(siteCode, offset, limit, null, null);
    }

    public com.yx.noon.masterdata.PageResult<com.yx.noon.sync.SalesItemRow> listSales(String siteCode, int offset, int limit,
                                                                                       String fromDate, String toDate)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        StringBuilder where = new StringBuilder(" WHERE site_code=?");
        List<Object> args = new ArrayList<>();
        args.add(site);
        if (fromDate != null && !fromDate.isBlank())
        {
            where.append(" AND order_ts>=?");
            args.add(java.sql.Timestamp.valueOf(fromDate + " 00:00:00"));
        }
        if (toDate != null && !toDate.isBlank())
        {
            where.append(" AND order_ts<?");
            args.add(Timestamp.valueOf(java.time.LocalDate.parse(toDate).plusDays(1).atStartOfDay()));
        }
        String w = where.toString();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM noon_sales_item" + w, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<SalesItemRow> rows = jdbc.query(
                "SELECT item_nr,partner_sku,sku,status,fulfillment_model,order_ts,shipment_ts,delivered_ts,"
                        + "price_amount,currency_code,awb_nr FROM noon_sales_item" + w
                        + " ORDER BY item_nr LIMIT ? OFFSET ?",
                (rs, i) -> new SalesItemRow(site, rs.getString("item_nr"), rs.getString("partner_sku"),
                        rs.getString("sku"), rs.getString("status"), rs.getString("fulfillment_model"),
                        ts(rs.getTimestamp("order_ts")), ts(rs.getTimestamp("shipment_ts")),
                        ts(rs.getTimestamp("delivered_ts")), decimal(rs.getBigDecimal("price_amount")),
                        rs.getString("currency_code"), rs.getString("awb_nr")),
                pageArgs.toArray());
        return new com.yx.noon.masterdata.PageResult<>(rows, total);
    }

    private static String ts(java.sql.Timestamp t) { return t == null ? null : t.toString(); }
    private static String decimal(java.math.BigDecimal d) { return d == null ? null : d.toPlainString(); }

    /** ASN 只读同步：按站点 + 平台 asn_nr 幂等 upsert，不关联 ERP 内部 ASN、不过账。 */
    public NoonSellerLabSyncResult syncAsn(String siteCode, int maxPages)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        List<PlatformAsnRow> rows = client.listAsn(site, maxPages);
        int written = persistAsn(site, rows);
        return new NoonSellerLabSyncResult(site, "ASN", rows.size(), written, client.isLastAsnTruncated());
    }

    @Transactional(rollbackFor = Exception.class)
    int persistAsn(String site, List<PlatformAsnRow> rows)
    {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int count = 0;
        for (PlatformAsnRow r : rows)
        {
            if (r.platformAsnNr() == null || r.platformAsnNr().isBlank()) continue;
            count += affected(jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO noon_asn_platform(site_code,platform_asn_nr,status,dst_warehouse_code,"
                                + "expected_qty,qty_received,qty_putaway,platform_updated_at,items_json,source,synced_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?, 'NOON_SELLERLAB',?) "
                                + "ON DUPLICATE KEY UPDATE status=VALUES(status),dst_warehouse_code=VALUES(dst_warehouse_code),"
                                + "expected_qty=VALUES(expected_qty),qty_received=VALUES(qty_received),"
                                + "qty_putaway=VALUES(qty_putaway),platform_updated_at=VALUES(platform_updated_at),"
                                + "items_json=VALUES(items_json),source=VALUES(source),synced_at=VALUES(synced_at)");
                ps.setString(1, site);
                ps.setString(2, r.platformAsnNr());
                ps.setString(3, emptyToNull(r.status()));
                ps.setString(4, emptyToNull(r.dstWarehouseCode()));
                setInt(ps, 5, r.expectedQty());
                setInt(ps, 6, r.qtyReceived());
                setInt(ps, 7, r.qtyPutaway());
                setTs(ps, 8, r.platformUpdatedAt());
                ps.setString(9, r.itemsJson());
                ps.setTimestamp(10, now);
                return ps;
            }));
        }
        return count;
    }

    /** ASN 列表：仅平台快照，不关联 ERP 内部 ASN。 */
    public com.yx.noon.masterdata.PageResult<PlatformAsnRow> listAsn(String siteCode, int offset, int limit)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM noon_asn_platform WHERE site_code=?", Long.class, site);
        List<PlatformAsnRow> rows = jdbc.query(
                "SELECT platform_asn_nr,status,dst_warehouse_code,expected_qty,qty_received,qty_putaway,platform_updated_at "
                        + "FROM noon_asn_platform WHERE site_code=? ORDER BY platform_asn_nr LIMIT ? OFFSET ?",
                (rs, i) -> new PlatformAsnRow(site, rs.getString("platform_asn_nr"), rs.getString("status"),
                        rs.getString("dst_warehouse_code"), integer(rs.getObject("expected_qty")),
                        integer(rs.getObject("qty_received")), integer(rs.getObject("qty_putaway")),
                        ts(rs.getTimestamp("platform_updated_at")), null),
                site, limit, offset);
        return new com.yx.noon.masterdata.PageResult<>(rows, total);
    }

    private static Integer integer(Object v) { return v == null ? null : ((Number) v).intValue(); }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws java.sql.SQLException
    {
        if (value == null) ps.setNull(index, java.sql.Types.INTEGER); else ps.setInt(index, value);
    }

    @Transactional(rollbackFor = Exception.class)
    int persistFbn(String site, List<FbnInventoryRow> rows)
    {
        // captured_at 是平台数据归档日（按天）；synced_at 才是真实同步时刻。
        // 若 captured_at 每次同步都不同，唯一键会失效并重复入库。
        Timestamp captured = Timestamp.valueOf(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0));
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int count = 0;
        for (FbnInventoryRow r : rows)
        {
            // MySQL ON DUPLICATE KEY UPDATE 返回 1(insert)/2(update)，按“处理 1 行”归一计数
            count += affected(jdbc.update("INSERT INTO noon_fbn_inventory_snapshot"
                            + "(site_code,partner_sku,sku,warehouse_code,saleable,non_saleable,qty,captured_at,source,synced_at) "
                            + "VALUES(?,?,?,?,?,?,?,?, 'NOON_SELLERLAB', ?) "
                            + "ON DUPLICATE KEY UPDATE saleable=VALUES(saleable),non_saleable=VALUES(non_saleable),"
                            + "qty=VALUES(qty),sku=VALUES(sku),source=VALUES(source),synced_at=VALUES(synced_at)",
                    site, r.partnerSku(), r.sku(), r.warehouseCode(),
                    r.saleable(), r.nonSaleable(), r.qty(), captured, now));
        }
        return count;
    }

    @Transactional(rollbackFor = Exception.class)
    int persistSales(String site, List<SalesItemRow> rows)
    {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int count = 0;
        for (SalesItemRow r : rows)
        {
            if (r.itemNr() == null || r.itemNr().isBlank()) continue; // 无明细标识不入库
            count += affected(jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO noon_sales_item(site_code,item_nr,partner_sku,sku,status,fulfillment_model,"
                                + "order_ts,shipment_ts,delivered_ts,price_amount,currency_code,awb_nr,source,synced_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'NOON_SELLERLAB',?) "
                                + "ON DUPLICATE KEY UPDATE partner_sku=VALUES(partner_sku),sku=VALUES(sku),"
                                + "status=VALUES(status),fulfillment_model=VALUES(fulfillment_model),"
                                + "order_ts=VALUES(order_ts),shipment_ts=VALUES(shipment_ts),delivered_ts=VALUES(delivered_ts),"
                                + "price_amount=VALUES(price_amount),currency_code=VALUES(currency_code),"
                                + "awb_nr=VALUES(awb_nr),source=VALUES(source),synced_at=VALUES(synced_at)");
                ps.setString(1, site);
                ps.setString(2, r.itemNr());
                ps.setString(3, emptyToNull(r.partnerSku()));
                ps.setString(4, emptyToNull(r.sku()));
                ps.setString(5, emptyToNull(r.status()));
                ps.setString(6, emptyToNull(r.fulfillmentModel()));
                setTs(ps, 7, r.orderTs());
                setTs(ps, 8, r.shipmentTs());
                setTs(ps, 9, r.deliveredTs());
                setDecimal(ps, 10, r.priceAmount());
                ps.setString(11, emptyToNull(r.currencyCode()));
                ps.setString(12, emptyToNull(r.awbNr()));
                ps.setTimestamp(13, now);
                return ps;
            }));
        }
        return count;
    }

    /** MySQL upsert 影响行数归一：1/2 都算处理 1 行，避免重复同步计数翻倍。 */
    private static int affected(int jdbcCount)
    {
        return jdbcCount > 0 ? 1 : 0;
    }

    private static void setTs(PreparedStatement ps, int index, String value) throws java.sql.SQLException
    {
        if (value == null || value.isBlank())
        {
            ps.setNull(index, java.sql.Types.TIMESTAMP);
            return;
        }
        try
        {
            ps.setTimestamp(index, Timestamp.valueOf(value.replace('T', ' ').replaceAll("Z$", "")));
        }
        catch (DateTimeParseException e)
        {
            ps.setNull(index, java.sql.Types.TIMESTAMP); // 平台时间格式异常不伪造
        }
    }

    private static void setDecimal(PreparedStatement ps, int index, String value) throws java.sql.SQLException
    {
        if (value == null || value.isBlank())
        {
            ps.setNull(index, java.sql.Types.DECIMAL);
            return;
        }
        try
        {
            ps.setBigDecimal(index, new java.math.BigDecimal(value));
        }
        catch (NumberFormatException e)
        {
            ps.setNull(index, java.sql.Types.DECIMAL);
        }
    }

    private static String emptyToNull(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }
}
