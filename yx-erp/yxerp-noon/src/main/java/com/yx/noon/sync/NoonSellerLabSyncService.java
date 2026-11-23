package com.yx.noon.sync;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
        return new NoonSellerLabSyncResult(site, "SALES", rows.size(), written);
    }

    /** 简单数量对比：最新官方快照 vs 系统四态台账（按站点 + partner_sku 映射后的 sku_id）。 */
    public NoonSellerLabCompare compare(String siteCode, int offset, int limit)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        String sql = "SELECT p.partner_sku,f.sku AS noon_sku,s.id AS sku_id,s.sku_code,"
                + "f.saleable,f.non_saleable,f.qty AS official_qty,f.warehouse_code,f.captured_at,"
                + "l.qty_available,l.qty_unsellable,l.qty_in_transit "
                + "FROM noon_fbn_inventory_snapshot f "
                + "JOIN md_partner_sku p ON p.partner_sku=f.partner_sku AND p.site_code=f.site_code AND p.business_model='noon' "
                + "LEFT JOIN md_sku s ON s.id=p.sku_id "
                + "LEFT JOIN inv_stock_ledger l ON l.sku_id=s.id AND l.site_code=f.site_code "
                + "WHERE f.site_code=? AND f.captured_at=(SELECT MAX(captured_at) FROM noon_fbn_inventory_snapshot WHERE site_code=?) "
                + "ORDER BY p.partner_sku LIMIT ? OFFSET ?";
        List<NoonSellerLabCompare.Row> rows = jdbc.query(sql, (rs, i) ->
        {
            boolean hasLedger = rs.getObject("qty_available") != null;
            String note = hasLedger ? "" : "系统无台账记录（未发生入库/直发收货），差异不代表丢货";
            return new NoonSellerLabCompare.Row(
                    rs.getString("partner_sku"), rs.getString("noon_sku"), rs.getString("sku_code"),
                    rs.getInt("saleable"), rs.getInt("non_saleable"), rs.getInt("official_qty"),
                    hasLedger ? rs.getInt("qty_available") : null,
                    hasLedger ? rs.getInt("qty_unsellable") : null,
                    hasLedger ? rs.getInt("qty_in_transit") : null,
                    rs.getString("warehouse_code"), rs.getTimestamp("captured_at"), note);
        }, site, site, limit, offset);
        return new NoonSellerLabCompare(site, rows);
    }

    /** Sales 列表：item_nr 作编号，按 (site, item_nr) 唯一；保留来源与同步时间。 */
    public com.yx.noon.masterdata.PageResult<com.yx.noon.sync.SalesItemRow> listSales(String siteCode, int offset, int limit)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM noon_sales_item WHERE site_code=?", Long.class, site);
        List<SalesItemRow> rows = jdbc.query(
                "SELECT item_nr,partner_sku,sku,status,fulfillment_model,order_ts,shipment_ts,delivered_ts,"
                        + "price_amount,currency_code,awb_nr FROM noon_sales_item WHERE site_code=? "
                        + "ORDER BY item_nr LIMIT ? OFFSET ?",
                (rs, i) -> new SalesItemRow(site, rs.getString("item_nr"), rs.getString("partner_sku"),
                        rs.getString("sku"), rs.getString("status"), rs.getString("fulfillment_model"),
                        ts(rs.getTimestamp("order_ts")), ts(rs.getTimestamp("shipment_ts")),
                        ts(rs.getTimestamp("delivered_ts")), decimal(rs.getBigDecimal("price_amount")),
                        rs.getString("currency_code"), rs.getString("awb_nr")),
                site, limit, offset);
        return new com.yx.noon.masterdata.PageResult<>(rows, total);
    }

    private static String ts(java.sql.Timestamp t) { return t == null ? null : t.toString(); }
    private static String decimal(java.math.BigDecimal d) { return d == null ? null : d.toPlainString(); }

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
