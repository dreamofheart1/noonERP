package com.yx.noon.masterdata;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SKU 主数据（md_sku）。SKU 是跨站共享主数据，权限按 Codex 审核 5 整改：
 * list/get 要求至少 1 个授权站点（登录不等于业务授权，空授权拒绝；siteCode 参数忽略）；
 * save/update/setStatus 仅超管。
 */
@Service
public class SkuService
{
    private static final String COLUMNS = "id,sku_code,spu_id,name_en,name_ar,category_code,hs_code,"
            + "origin_country,brand,spec_json,status";
    static final String ENTITY = "SKU";

    private static final RowMapper<SkuRow> MAPPER = (rs, i) -> new SkuRow(
            rs.getLong("id"),
            rs.getString("sku_code"),
            rs.getString("spu_id"),
            rs.getString("name_en"),
            rs.getString("name_ar"),
            rs.getString("category_code"),
            rs.getString("hs_code"),
            rs.getString("origin_country"),
            rs.getString("brand"),
            rs.getString("spec_json"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final MasterDataAudit audit;

    public SkuService(JdbcTemplate jdbc, SiteAuthGuard guard, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** siteCode 参数按契约接受但忽略：SKU 为全局主数据，不做站点过滤；但要求至少 1 个授权站点。 */
    public List<SkuRow> list(String siteCode, int offset, int limit)
    {
        return list(siteCode, offset, limit, null).rows();
    }

    /**
     * 分页 + 搜索（契约 v3）：siteCode 入参仍被忽略（SKU 跨站共享），但读权限仍要求至少 1 个授权站点；
     * skuCode/nameEn/brand 模糊、status 精确，筛选条件与 COUNT 共用同一 WHERE。
     */
    public PageResult<SkuRow> list(String siteCode, int offset, int limit, SkuQuery q)
    {
        requireSiteReader();
        MasterDataRules.offset(offset);
        int size = MasterDataRules.limit(limit);
        SkuQuery query = q == null ? new SkuQuery(null, null, null, null) : q;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String skuCode = MasterDataRules.like(query.skuCode());
        if (skuCode != null)
        {
            where.append(" AND LOWER(sku_code) LIKE ?");
            args.add(skuCode);
        }
        String nameEn = MasterDataRules.like(query.nameEn());
        if (nameEn != null)
        {
            where.append(" AND LOWER(name_en) LIKE ?");
            args.add(nameEn);
        }
        String brand = MasterDataRules.like(query.brand());
        if (brand != null)
        {
            where.append(" AND LOWER(brand) LIKE ?");
            args.add(brand);
        }
        String status = MasterDataRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_sku" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<SkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public SkuRow get(long id)
    {
        requireSiteReader();
        return requireExisting(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(SkuInput in)
    {
        guard.requireAdministrator();
        String skuCode = MasterDataRules.required("skuCode", in.skuCode(), 64);
        String spuId = MasterDataRules.optional("spuId", in.spuId(), 64);
        String nameEn = MasterDataRules.optional("nameEn", in.nameEn(), 512);
        String nameAr = MasterDataRules.optional("nameAr", in.nameAr(), 512);
        String categoryCode = MasterDataRules.optional("categoryCode", in.categoryCode(), 64);
        String hsCode = MasterDataRules.optional("hsCode", in.hsCode(), 32);
        String originCountry = MasterDataRules.optional("originCountry", in.originCountry(), 32);
        String brand = MasterDataRules.optional("brand", in.brand(), 128);
        String specJson = MasterDataRules.json("specJson", in.specJson());
        String status = MasterDataRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_sku(sku_code,spu_id,name_en,name_ar,category_code,hs_code,"
                                + "origin_country,brand,spec_json,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        new String[]{"id"});
                ps.setString(1, skuCode);
                ps.setString(2, spuId);
                ps.setString(3, nameEn);
                ps.setString(4, nameAr);
                ps.setString(5, categoryCode);
                ps.setString(6, hsCode);
                ps.setString(7, originCountry);
                ps.setString(8, brand);
                ps.setString(9, specJson);
                ps.setString(10, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("SKU 新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_sku", "uk_sku_code", "SKU 编码 sku_code 已存在");
        }
        audit.record(ENTITY, id, "INSERT", null, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public long update(long id, SkuInput in)
    {
        guard.requireAdministrator();
        SkuRow before = lockExisting(id);
        String skuCode = MasterDataRules.required("skuCode", in.skuCode(), 64);
        String spuId = MasterDataRules.optional("spuId", in.spuId(), 64);
        String nameEn = MasterDataRules.optional("nameEn", in.nameEn(), 512);
        String nameAr = MasterDataRules.optional("nameAr", in.nameAr(), 512);
        String categoryCode = MasterDataRules.optional("categoryCode", in.categoryCode(), 64);
        String hsCode = MasterDataRules.optional("hsCode", in.hsCode(), 32);
        String originCountry = MasterDataRules.optional("originCountry", in.originCountry(), 32);
        String brand = MasterDataRules.optional("brand", in.brand(), 128);
        String specJson = MasterDataRules.json("specJson", in.specJson());
        String status = MasterDataRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_sku SET sku_code=?,spu_id=?,name_en=?,name_ar=?,"
                            + "category_code=?,hs_code=?,origin_country=?,brand=?,spec_json=?,status=? "
                            + "WHERE id=? AND sku_code=?",
                    skuCode, spuId, nameEn, nameAr, categoryCode, hsCode, originCountry, brand,
                    specJson, status, id, before.skuCode());
            if (updated == 0) throw new IllegalArgumentException("SKU 不存在或编码已被并发修改: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_sku", "uk_sku_code", "SKU 编码 sku_code 已存在");
        }
        audit.record(ENTITY, id, "UPDATE", before, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        guard.requireAdministrator();
        SkuRow before = lockExisting(id);
        String normalized = MasterDataRules.status(status);
        int updated = jdbc.update("UPDATE md_sku SET status=? WHERE id=? AND sku_code=?",
                normalized, id, before.skuCode());
        if (updated == 0) throw new IllegalArgumentException("SKU 不存在: id=" + id);
        audit.record(ENTITY, id, "STATUS", before, find(id));
    }

    private void requireSiteReader()
    {
        if (guard.allowedSiteCodes().isEmpty())
            throw new AccessDeniedException("至少需要一个授权站点才能访问 SKU 主数据");
    }

    private SkuRow find(long id)
    {
        List<SkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SkuRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("SKU id 必须为正数");
        SkuRow row = find(id);
        if (row == null) throw new IllegalArgumentException("SKU 不存在: id=" + id);
        return row;
    }

    private SkuRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("SKU id 必须为正数");
        List<SkuRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku WHERE id=? FOR UPDATE", MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("SKU 不存在: id=" + id);
        return rows.get(0);
    }
}
