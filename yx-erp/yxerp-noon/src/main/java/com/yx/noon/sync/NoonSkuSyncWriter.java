package com.yx.noon.sync;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.yx.noon.masterdata.MasterDataAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Noon SKU 同步落库器。所有写入在同一事务内完成，任一异常全部回滚；
 * 变更同步写入 M2 主数据审计。幂等键：md_sku.sku_code 与
 * md_partner_sku(partner_sku, site_code, business_model)。
 */
@Component
public class NoonSkuSyncWriter
{
    private final JdbcTemplate jdbc;
    private final MasterDataAudit audit;

    public NoonSkuSyncWriter(JdbcTemplate jdbc, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** 平台不存在的字段（中文名/类目/HS/规格/状态）一律保留系统原值；只回写标题与品牌。 */
    @Transactional(rollbackFor = Exception.class)
    public Counters persist(String siteCode, List<NoonOfferInfo> offers)
    {
        Counters counters = new Counters();
        for (NoonOfferInfo offer : offers)
        {
            long skuId = upsertSku(offer, counters);
            upsertMapping(siteCode, offer, skuId, counters);
        }
        return counters;
    }

    private long upsertSku(NoonOfferInfo offer, Counters counters)
    {
        String code = offer.partnerSku();
        if (code == null || code.isBlank() || code.length() > 64)
            throw new IllegalArgumentException("partner_sku 不能为空且不得超过 64 字符: " + code);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,status,name_en,brand FROM md_sku WHERE sku_code=?", code);
        if (rows.isEmpty())
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_sku(sku_code,name_en,brand,status) VALUES(?,?,?,'ACTIVE')",
                        new String[]{"id"});
                ps.setString(1, code);
                ps.setString(2, value(offer.title()));
                ps.setString(3, value(offer.brand()));
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("SKU 新增未返回主键: " + code);
            long id = key.longValue();
            audit.record("SKU", id, "INSERT", null, findSku(id));
            counters.skuCreated++;
            return id;
        }

        Map<String, Object> before = rows.get(0);
        long id = ((Number) before.get("id")).longValue();
        String oldName = (String) before.get("name_en");
        String oldBrand = (String) before.get("brand");
        String newName = hasText(offer.title()) ? offer.title().trim() : oldName;
        String newBrand = hasText(offer.brand()) ? offer.brand().trim() : oldBrand;
        if (!eq(oldName, newName) || !eq(oldBrand, newBrand))
        {
            jdbc.update("UPDATE md_sku SET name_en=?,brand=? WHERE id=?", value(newName), value(newBrand), id);
            audit.record("SKU", id, "UPDATE", findSkuSnapshot(id, before), findSku(id));
            counters.skuUpdated++;
        }
        return id;
    }

    private void upsertMapping(String siteCode, NoonOfferInfo offer, long skuId, Counters counters)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,sku_id,offer_code,barcode,live_status,offer_issues,synced_at,noon_sku "
                        + "FROM md_partner_sku WHERE partner_sku=? AND site_code=? AND business_model='noon'",
                offer.partnerSku(), siteCode);
        Timestamp syncedAt = Timestamp.valueOf(LocalDateTime.now());
        if (rows.isEmpty())
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_partner_sku(partner_sku,sku_id,site_code,business_model,"
                                + "offer_code,barcode,noon_sku,live_status,offer_issues,status,synced_at)"
                                + " VALUES(?,?,?,'noon',?,?,?,?,?,'ACTIVE',?)", new String[]{"id"});
                ps.setString(1, offer.partnerSku());
                ps.setLong(2, skuId);
                ps.setString(3, siteCode);
                ps.setString(4, value(offer.offerCode()));
                ps.setString(5, value(barcode(offer)));
                ps.setString(6, value(offer.noonSku()));
                ps.setObject(7, offer.liveStatus());
                ps.setString(8, offer.issuesJson() == null ? "[]" : offer.issuesJson());
                ps.setTimestamp(9, syncedAt);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("Partner SKU 新增未返回主键: " + offer.partnerSku());
            audit.record("PARTNER_SKU", key.longValue(), "INSERT", null, findMapping(key.longValue()));
            counters.mappingCreated++;
            return;
        }

        long id = ((Number) rows.get(0).get("id")).longValue();
        Map<String, Object> before = findMapping(id);
        jdbc.update("UPDATE md_partner_sku SET sku_id=?,offer_code=?,barcode=?,noon_sku=?,"
                        + "live_status=?,offer_issues=?,synced_at=? WHERE id=? AND site_code=?",
                skuId, value(offer.offerCode()), value(barcode(offer)), value(offer.noonSku()),
                offer.liveStatus(), offer.issuesJson() == null ? "[]" : offer.issuesJson(),
                syncedAt, id, siteCode);
        audit.record("PARTNER_SKU", id, "UPDATE", before, findMapping(id));
        counters.mappingUpdated++;
    }

    /** 条码优先取平台 partner_barcode，缺失时退回 Noon SKU；不得写入空串冒充。 */
    private static String barcode(NoonOfferInfo offer)
    {
        if (hasText(offer.barcode())) return offer.barcode().trim();
        return hasText(offer.noonSku()) ? offer.noonSku().trim() : null;
    }

    private Map<String, Object> findSku(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,sku_code,spu_id,name_en,name_ar,category_code,hs_code,origin_country,brand,spec_json,status FROM md_sku WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> findSkuSnapshot(long id, Map<String, Object> before)
    {
        return before;
    }

    private Map<String, Object> findMapping(long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,partner_sku,sku_id,site_code,business_model,offer_code,barcode,noon_sku,"
                        + "live_status,offer_issues,status,synced_at FROM md_partner_sku WHERE id=?", id);
        if (rows.isEmpty()) return null;
        // 审计快照用普通 ObjectMapper 序列化：LocalDateTime 先转字符串，避免引入额外 Jackson 模块。
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>(rows.get(0));
        snapshot.replaceAll((k, v) -> v instanceof java.time.LocalDateTime t ? t.toString() : v);
        return snapshot;
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private static String value(String value)
    {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean eq(String a, String b)
    {
        return a == null ? b == null : a.equals(b);
    }

    /** 写库计数，避免把外部 API 行数直接当作成功数。 */
    public static class Counters
    {
        int skuCreated;
        int skuUpdated;
        int mappingCreated;
        int mappingUpdated;

        public int skuCreated() { return skuCreated; }
        public int skuUpdated() { return skuUpdated; }
        public int mappingCreated() { return mappingCreated; }
        public int mappingUpdated() { return mappingUpdated; }
    }
}
