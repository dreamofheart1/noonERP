package com.yx.noon.masterdata;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库主数据（md_warehouse）。site_code 必须授权且在 md_site 存在且 ACTIVE；
 * fulfillment_mode 创建后不可变更；is_fbn 必须与 warehouse_type=FBN 一致。
 * 更新先 SELECT ... FOR UPDATE，并以 fulfillment_mode 作 CAS 条件，并发改不可绕过。
 */
@Service
public class WarehouseService
{
    private static final String COLUMNS =
            "id,warehouse_code,site_code,warehouse_name,warehouse_type,fulfillment_mode,is_fbn,status";
    static final String ENTITY = "WAREHOUSE";

    private static final RowMapper<WarehouseRow> MAPPER = (rs, i) -> new WarehouseRow(
            rs.getLong("id"),
            rs.getString("warehouse_code"),
            rs.getString("site_code"),
            rs.getString("warehouse_name"),
            rs.getString("warehouse_type"),
            rs.getString("fulfillment_mode"),
            rs.getBoolean("is_fbn"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final MasterDataAudit audit;

    public WarehouseService(JdbcTemplate jdbc, SiteAuthGuard guard, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** 兼容旧签名：等价于无筛选条件的分页查询。 */
    public List<WarehouseRow> list(String siteCode, int offset, int limit)
    {
        return list(siteCode, offset, limit, null).rows();
    }

    /**
     * 分页 + 搜索（契约 v3）。入参 siteCode 是授权闸门，与 Query.siteCode 取交集作为站点筛选；
     * warehouseCode 模糊、status 精确，均与授权条件共用同一 WHERE 并参与 COUNT。
     */
    public PageResult<WarehouseRow> list(String siteCode, int offset, int limit, WarehouseQuery q)
    {
        MasterDataRules.offset(offset);
        int size = MasterDataRules.limit(limit);
        WarehouseQuery query = q == null ? new WarehouseQuery(null, null, null) : q;
        List<String> sites = MasterDataRules.intersectSites(MasterDataRules.sites(siteCode, guard), query.siteCode());
        if (sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE site_code IN (")
                .append(MasterDataRules.placeholders(sites.size())).append(")");
        List<Object> args = new ArrayList<>(sites);
        String warehouseCode = MasterDataRules.like(query.warehouseCode());
        if (warehouseCode != null)
        {
            where.append(" AND LOWER(warehouse_code) LIKE ?");
            args.add(warehouseCode);
        }
        String status = MasterDataRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_warehouse" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<WarehouseRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_warehouse" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public WarehouseRow get(long id)
    {
        WarehouseRow row = requireExisting(id);
        guard.requireSite(row.siteCode());
        return row;
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(WarehouseInput in)
    {
        String siteCode = MasterDataRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        requireActiveSite(siteCode);
        String warehouseCode = MasterDataRules.required("warehouseCode", in.warehouseCode(), 64);
        String warehouseName = MasterDataRules.optional("warehouseName", in.warehouseName(), 256);
        String warehouseType = warehouseType(in.warehouseType());
        String fulfillmentMode = fulfillmentMode(in.fulfillmentMode());
        boolean fbn = fbn(in.isFbn(), warehouseType);
        String status = MasterDataRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_warehouse(warehouse_code,site_code,warehouse_name,warehouse_type,"
                                + "fulfillment_mode,is_fbn,status) VALUES(?,?,?,?,?,?,?)", new String[]{"id"});
                ps.setString(1, warehouseCode);
                ps.setString(2, siteCode);
                ps.setString(3, warehouseName);
                ps.setString(4, warehouseType);
                ps.setString(5, fulfillmentMode);
                ps.setBoolean(6, fbn);
                ps.setString(7, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("仓库新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_warehouse", "uk_wh_code", "同一站点下仓库编码已存在");
        }
        audit.record(ENTITY, id, "INSERT", null, find(id));
        return id;
    }

    /** 更新仓库：fulfillment_mode 不可变更，传空表示沿用原值。 */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, WarehouseInput in)
    {
        WarehouseRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        String siteCode = MasterDataRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        requireActiveSite(siteCode);
        String warehouseCode = MasterDataRules.required("warehouseCode", in.warehouseCode(), 64);
        String warehouseName = MasterDataRules.optional("warehouseName", in.warehouseName(), 256);
        String warehouseType = warehouseType(in.warehouseType());
        String mode = in.fulfillmentMode();
        if (mode != null && !mode.isBlank())
        {
            String requested = fulfillmentMode(mode);
            if (!requested.equals(existing.fulfillmentMode()))
                throw new IllegalArgumentException("fulfillment_mode 创建后不可变更（当前 "
                        + existing.fulfillmentMode() + "，请求 " + requested + "）");
        }
        boolean fbn = fbn(in.isFbn(), warehouseType);
        String status = MasterDataRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_warehouse SET warehouse_code=?,site_code=?,warehouse_name=?,"
                            + "warehouse_type=?,is_fbn=?,status=? WHERE id=? AND fulfillment_mode=?",
                    warehouseCode, siteCode, warehouseName, warehouseType, fbn, status,
                    id, existing.fulfillmentMode());
            if (updated == 0) throw new IllegalArgumentException("仓库不存在或不可变字段已被并发修改: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_warehouse", "uk_wh_code", "同一站点下仓库编码已存在");
        }
        audit.record(ENTITY, id, "UPDATE", existing, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        WarehouseRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        String normalized = MasterDataRules.status(status);
        int updated = jdbc.update("UPDATE md_warehouse SET status=? WHERE id=? AND fulfillment_mode=?",
                normalized, id, existing.fulfillmentMode());
        if (updated == 0) throw new IllegalArgumentException("仓库不存在: id=" + id);
        audit.record(ENTITY, id, "STATUS", existing, find(id));
    }

    private WarehouseRow find(long id)
    {
        List<WarehouseRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_warehouse WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private WarehouseRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("仓库 id 必须为正数");
        WarehouseRow row = find(id);
        if (row == null) throw new IllegalArgumentException("仓库不存在: id=" + id);
        return row;
    }

    private WarehouseRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("仓库 id 必须为正数");
        List<WarehouseRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_warehouse WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("仓库不存在: id=" + id);
        return rows.get(0);
    }

    /** 引用完整性：warehouse.site_code 必须在 md_site 存在且 ACTIVE。 */
    private void requireActiveSite(String siteCode)
    {
        List<String> statuses = jdbc.queryForList("SELECT status FROM md_site WHERE site_code=?",
                String.class, siteCode);
        if (statuses.isEmpty())
            throw new IllegalArgumentException("站点未维护，禁止挂仓库: " + siteCode);
        if (!"ACTIVE".equals(statuses.get(0)))
            throw new IllegalArgumentException("站点已停用，禁止挂仓库: " + siteCode);
    }

    private static String warehouseType(String value)
    {
        String type = MasterDataRules.required("warehouseType", value, 24).toUpperCase(Locale.ROOT);
        if (!MasterDataRules.WAREHOUSE_TYPES.contains(type))
            throw new IllegalArgumentException("warehouseType 只允许 LOCAL/TRANSIT/FBN/3PL");
        return type;
    }

    private static String fulfillmentMode(String value)
    {
        if (value == null || value.isBlank()) return "FBN";
        String mode = value.trim().toUpperCase(Locale.ROOT);
        if (!MasterDataRules.FULFILLMENT_MODES.contains(mode))
            throw new IllegalArgumentException("fulfillmentMode 只允许 FBN/GLOBAL/FBP");
        return mode;
    }

    /** is_fbn 与 warehouse_type 一致：FBN 仓库必须 is_fbn=1，其余必须为 0。 */
    private static boolean fbn(Boolean value, String warehouseType)
    {
        boolean expected = "FBN".equals(warehouseType);
        if (value == null) return expected;
        if (!Objects.equals(value, expected))
            throw new IllegalArgumentException(expected
                    ? "warehouseType=FBN 时 isFbn 必须为 true"
                    : "warehouseType=" + warehouseType + " 时 isFbn 必须为 false");
        return expected;
    }
}
