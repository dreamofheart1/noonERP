package com.yx.noon.masterdata;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
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
 * 站点主数据（md_site）。site_code 仅 AE/SA，既是业务站点也是授权边界；
 * 每次详情/写入都用记录真实 site_code 过 {@link SiteAuthGuard#requireSite}，不信任入参。
 *
 * <p>引用完整性：store_id 必须存在且 md_store.status=ACTIVE（与写入同事务）。
 * site_code / store_id 建后不可变：更新走 SELECT ... FOR UPDATE 取快照后比对，传 null 沿用原值。
 */
@Service
public class SiteService
{
    private static final String COLUMNS = "id,site_code,store_id,country,currency,vat_rate,timezone,status";
    private static final String DEFAULT_TIMEZONE = "Asia/Dubai";
    static final String ENTITY = "SITE";

    private static final RowMapper<SiteRow> MAPPER = (rs, i) -> new SiteRow(
            rs.getLong("id"),
            rs.getString("site_code"),
            rs.getObject("store_id", Long.class),
            rs.getString("country"),
            rs.getString("currency"),
            rs.getBigDecimal("vat_rate"),
            rs.getString("timezone"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final MasterDataAudit audit;

    public SiteService(JdbcTemplate jdbc, SiteAuthGuard guard, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** 兼容旧签名：等价于无筛选条件的分页查询。 */
    public List<SiteRow> list(String siteCode, int offset, int limit)
    {
        return list(siteCode, offset, limit, null).rows();
    }

    /**
     * 分页 + 搜索（契约 v3）。入参 siteCode 是授权闸门，同时与 Query.siteCode 取交集作为站点筛选
     * （两者相同只绑定一次，不产生重复/冲突条件）。授权与筛选共用同一 WHERE 参与 COUNT 与 SELECT。
     */
    public PageResult<SiteRow> list(String siteCode, int offset, int limit, SiteQuery q)
    {
        MasterDataRules.offset(offset);
        int size = MasterDataRules.limit(limit);
        SiteQuery query = q == null ? new SiteQuery(null, null, null) : q;
        List<String> sites = MasterDataRules.intersectSites(MasterDataRules.sites(siteCode, guard), query.siteCode());
        if (sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE site_code IN (")
                .append(MasterDataRules.placeholders(sites.size())).append(")");
        List<Object> args = new ArrayList<>(sites);
        if (query.storeId() != null)
        {
            MasterDataRules.requiredId("storeId", query.storeId());
            where.append(" AND store_id=?");
            args.add(query.storeId());
        }
        String status = MasterDataRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_site" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<SiteRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_site" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public SiteRow get(long id)
    {
        SiteRow row = requireExisting(id);
        guard.requireSite(row.siteCode());
        return row;
    }

    /** 新增站点：要求站点在本人授权范围内，store_id 存在且 ACTIVE。 */
    @Transactional(rollbackFor = Exception.class)
    public long save(SiteInput in)
    {
        String siteCode = MasterDataRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        MasterDataRules.requiredId("storeId", in.storeId());
        requireActiveStore(in.storeId());
        String country = MasterDataRules.optional("country", in.country(), 64);
        String currency = currency(in.currency());
        BigDecimal vatRate = vatRate(in.vatRate());
        String timezone = timezone(in.timezone());
        String status = MasterDataRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_site(site_code,store_id,country,currency,vat_rate,timezone,status) "
                                + "VALUES(?,?,?,?,?,?,?)", new String[]{"id"});
                ps.setString(1, siteCode);
                ps.setLong(2, in.storeId());
                ps.setString(3, country);
                ps.setString(4, currency);
                ps.setBigDecimal(5, vatRate);
                ps.setString(6, timezone);
                ps.setString(7, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("站点新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_site", "uk_store_site", "同一店铺下该站点已存在");
        }
        audit.record(ENTITY, id, "INSERT", null, find(id));
        return id;
    }

    /**
     * 更新站点：site_code / store_id 建后不可变（变更授权主体/店铺归属会导致授权错位）；
     * 传 null 沿用原值，传不同值直接拒绝。其余列可改。
     */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, SiteInput in)
    {
        SiteRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        if (in.siteCode() != null && !in.siteCode().isBlank())
        {
            String requested = MasterDataRules.siteCode("siteCode", in.siteCode());
            if (!requested.equals(existing.siteCode()))
                throw new IllegalArgumentException("站点编码创建后不可变更");
        }
        long storeId = existing.storeId();
        if (in.storeId() != null)
        {
            MasterDataRules.requiredId("storeId", in.storeId());
            if (!java.util.Objects.equals(in.storeId(), existing.storeId()))
                throw new IllegalArgumentException("店铺归属创建后不可变更");
        }
        requireActiveStore(storeId);
        String country = MasterDataRules.optional("country", in.country(), 64);
        String currency = currency(in.currency());
        BigDecimal vatRate = vatRate(in.vatRate());
        String timezone = timezone(in.timezone());
        String status = MasterDataRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_site SET site_code=?,store_id=?,country=?,currency=?,"
                            + "vat_rate=?,timezone=?,status=? WHERE id=? AND site_code=? AND store_id=?",
                    existing.siteCode(), storeId, country, currency, vatRate, timezone, status,
                    id, existing.siteCode(), existing.storeId());
            if (updated == 0) throw new IllegalArgumentException("站点不存在或不可变字段已被并发修改: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_site", "uk_store_site", "同一店铺下该站点已存在");
        }
        audit.record(ENTITY, id, "UPDATE", existing, find(id));
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        SiteRow existing = lockExisting(id);
        guard.requireSite(existing.siteCode());
        String normalized = MasterDataRules.status(status);
        int updated = jdbc.update("UPDATE md_site SET status=? WHERE id=? AND site_code=?",
                normalized, id, existing.siteCode());
        if (updated == 0) throw new IllegalArgumentException("站点不存在: id=" + id);
        audit.record(ENTITY, id, "STATUS", existing, find(id));
    }

    private SiteRow find(long id)
    {
        List<SiteRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_site WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SiteRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("站点 id 必须为正数");
        SiteRow row = find(id);
        if (row == null) throw new IllegalArgumentException("站点不存在: id=" + id);
        return row;
    }

    private SiteRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("站点 id 必须为正数");
        List<SiteRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_site WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("站点不存在: id=" + id);
        return rows.get(0);
    }

    /** 引用完整性：store_id 必须存在且 ACTIVE（停用店铺不得再挂新站点）。 */
    private void requireActiveStore(long storeId)
    {
        List<String> statuses = jdbc.queryForList("SELECT status FROM md_store WHERE id=?",
                String.class, storeId);
        if (statuses.isEmpty())
            throw new IllegalArgumentException("storeId 对应的店铺不存在: " + storeId);
        if (!"ACTIVE".equals(statuses.get(0)))
            throw new IllegalArgumentException("storeId 对应的店铺已停用: " + storeId);
    }

    private static String currency(String value)
    {
        String code = MasterDataRules.required("currency", value, 3).toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("currency 必须是 3 位字母币种，如 AED/SAR");
        return code;
    }

    private static BigDecimal vatRate(BigDecimal value)
    {
        if (value == null) return BigDecimal.ZERO;
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("vatRate 必须在 0~1 之间（AE 0.05 / SA 0.15）");
        return value;
    }

    private static String timezone(String value)
    {
        String zone = MasterDataRules.optional("timezone", value, 64);
        return zone == null ? DEFAULT_TIMEZONE : zone;
    }
}
