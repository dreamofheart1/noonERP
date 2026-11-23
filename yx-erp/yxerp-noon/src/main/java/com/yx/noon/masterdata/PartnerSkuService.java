package com.yx.noon.masterdata;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner SKU 映射（md_partner_sku）：partner_sku ↔ sku_id ↔ 站点/业务模式。
 * 引用完整性（与写入同事务）：sku_id 存在且 ACTIVE；site_code 必须在 md_site 存在且 ACTIVE。
 * 唯一性由 uk_partner_sku(partner_sku, site_code, business_model) 保证。
 * Offer 回读列（live_status/offer_issues/synced_at）本模块不写入、不伪造，仅由后续 Offer 同步负责。
 */
@Service
public class PartnerSkuService
{
    private static final String COLUMNS =
            "id,partner_sku,sku_id,site_code,business_model,offer_code,barcode,noon_sku,live_status,synced_at,status";
    static final String ENTITY = "PARTNER_SKU";

    private static final RowMapper<PartnerSkuRow> MAPPER = (rs, i) -> new PartnerSkuRow(
            rs.getLong("id"),
            rs.getString("partner_sku"),
            rs.getObject("sku_id", Long.class),
            rs.getString("site_code"),
            rs.getString("business_model"),
            rs.getString("offer_code"),
            rs.getString("barcode"),
            rs.getString("noon_sku"),
            rs.getObject("live_status", Boolean.class),
            rs.getObject("synced_at", LocalDateTime.class),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final MasterDataAudit audit;

    public PartnerSkuService(JdbcTemplate jdbc, SiteAuthGuard guard, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** 兼容旧签名：等价于无筛选条件的分页查询。 */
    public List<PartnerSkuRow> list(String siteCode, int offset, int limit)
    {
        return list(siteCode, offset, limit, null).rows();
    }

    /**
     * 分页 + 搜索（契约 v3）。入参 siteCode 是授权闸门，与 Query.siteCode 取交集作为站点筛选；
     * partnerSku 模糊、skuId 精确，与授权条件共用同一 WHERE 并参与 COUNT。
     */
    public PageResult<PartnerSkuRow> list(String siteCode, int offset, int limit, PartnerSkuQuery q)
    {
        MasterDataRules.offset(offset);
        int size = MasterDataRules.limit(limit);
        PartnerSkuQuery query = q == null ? new PartnerSkuQuery(null, null, null, null) : q;
        List<String> sites = MasterDataRules.intersectSites(MasterDataRules.sites(siteCode, guard), query.siteCode());
        if (sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE site_code IN (")
                .append(MasterDataRules.placeholders(sites.size())).append(")");
        List<Object> args = new ArrayList<>(sites);
        String partnerSku = MasterDataRules.like(query.partnerSku());
        if (partnerSku != null)
        {
            where.append(" AND LOWER(partner_sku) LIKE ?");
            args.add(partnerSku);
        }
        if (query.skuId() != null)
        {
            MasterDataRules.requiredId("skuId", query.skuId());
            where.append(" AND sku_id=?");
            args.add(query.skuId());
        }
        String status = MasterDataRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_partner_sku" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<PartnerSkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_partner_sku" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public PartnerSkuRow get(long id)
    {
        PartnerSkuRow row = requireExisting(id);
        guard.requireSite(row.siteCode());
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(PartnerSkuInput in)
    {
        String siteCode = MasterDataRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        requireActiveSite(siteCode);
        String partnerSku = MasterDataRules.required("partnerSku", in.partnerSku(), 128);
        MasterDataRules.requiredId("skuId", in.skuId());
        requireActiveSku(in.skuId());
        String businessModel = businessModel(in.businessModel());
        String offerCode = MasterDataRules.optional("offerCode", in.offerCode(), 128);
        String barcode = MasterDataRules.optional("barcode", in.barcode(), 128);
        String status = MasterDataRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_partner_sku(partner_sku,sku_id,site_code,business_model,"
                                + "offer_code,barcode,status) VALUES(?,?,?,?,?,?,?)", new String[]{"id"});
                ps.setString(1, partnerSku);
                ps.setLong(2, in.skuId());
                ps.setString(3, siteCode);
                ps.setString(4, businessModel);
                ps.setString(5, offerCode);
                ps.setString(6, barcode);
                ps.setString(7, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("Partner SKU 新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_partner_sku", "uk_partner_sku",
                    "同一站点/业务模式下 Partner SKU 已存在");
        }
        audit.record(ENTITY, id, "INSERT", null, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public long update(long id, PartnerSkuInput in)
    {
        PartnerSkuRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        String siteCode = MasterDataRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        requireActiveSite(siteCode);
        String partnerSku = MasterDataRules.required("partnerSku", in.partnerSku(), 128);
        MasterDataRules.requiredId("skuId", in.skuId());
        requireActiveSku(in.skuId());
        String businessModel = businessModel(in.businessModel());
        String offerCode = MasterDataRules.optional("offerCode", in.offerCode(), 128);
        String barcode = MasterDataRules.optional("barcode", in.barcode(), 128);
        String status = MasterDataRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_partner_sku SET partner_sku=?,sku_id=?,site_code=?,"
                            + "business_model=?,offer_code=?,barcode=?,status=? WHERE id=? AND site_code=?",
                    partnerSku, in.skuId(), siteCode, businessModel, offerCode, barcode, status,
                    id, existing.siteCode());
            if (updated == 0) throw new IllegalArgumentException("Partner SKU 不存在或站点已被并发修改: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_partner_sku", "uk_partner_sku",
                    "同一站点/业务模式下 Partner SKU 已存在");
        }
        audit.record(ENTITY, id, "UPDATE", existing, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        PartnerSkuRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        String normalized = MasterDataRules.status(status);
        int updated = jdbc.update("UPDATE md_partner_sku SET status=? WHERE id=? AND site_code=?",
                normalized, id, existing.siteCode());
        if (updated == 0) throw new IllegalArgumentException("Partner SKU 不存在: id=" + id);
        audit.record(ENTITY, id, "STATUS", existing, find(id));
    }

    private PartnerSkuRow find(long id)
    {
        List<PartnerSkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_partner_sku WHERE id=?",
                MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PartnerSkuRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("Partner SKU id 必须为正数");
        PartnerSkuRow row = find(id);
        if (row == null) throw new IllegalArgumentException("Partner SKU 不存在: id=" + id);
        return row;
    }

    private PartnerSkuRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("Partner SKU id 必须为正数");
        List<PartnerSkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_partner_sku WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Partner SKU 不存在: id=" + id);
        return rows.get(0);
    }

    /** 引用完整性：sku_id 必须存在且 ACTIVE，禁止把停用 SKU 继续映射。 */
    private void requireActiveSku(long skuId)
    {
        List<String> statuses = jdbc.queryForList("SELECT status FROM md_sku WHERE id=?",
                String.class, skuId);
        if (statuses.isEmpty())
            throw new IllegalArgumentException("skuId 对应的 SKU 不存在: " + skuId);
        if (!"ACTIVE".equals(statuses.get(0)))
            throw new IllegalArgumentException("skuId 对应的 SKU 已停用: " + skuId);
    }

    /** 引用完整性：映射站点必须在 md_site 存在且 ACTIVE（授权之外再验主数据）。 */
    private void requireActiveSite(String siteCode)
    {
        List<String> statuses = jdbc.queryForList("SELECT status FROM md_site WHERE site_code=?",
                String.class, siteCode);
        if (statuses.isEmpty())
            throw new IllegalArgumentException("站点未维护，禁止建映射: " + siteCode);
        if (!"ACTIVE".equals(statuses.get(0)))
            throw new IllegalArgumentException("站点已停用，禁止建映射: " + siteCode);
    }

    /** 业务模式规范化：noon / supermall / Global（大小写不敏感，落库用 DDL 注释中的标准写法）。 */
    private static String businessModel(String value)
    {
        if (value == null || value.isBlank()) return "noon";
        switch (value.trim().toLowerCase(Locale.ROOT))
        {
            case "noon": return "noon";
            case "supermall": return "supermall";
            case "global": return "Global";
            default: throw new IllegalArgumentException("businessModel 只允许 noon/supermall/Global");
        }
    }
}
