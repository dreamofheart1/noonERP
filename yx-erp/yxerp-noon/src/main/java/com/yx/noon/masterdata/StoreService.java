package com.yx.noon.masterdata;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.yx.common.utils.SecurityUtils;
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
 * 店铺主数据（md_store）。权限规则（Codex 审核 2 整改）：
 * <ul>
 *   <li>list：普通用户按授权站点 EXISTS 过滤，空授权零行；超管看全部（可按单站过滤）。</li>
 *   <li>get：超管任意（含无站点行）；普通用户要求店铺有≥1 站点行且与授权站点有交集，否则拒绝。</li>
 *   <li>save/update/setStatus：仅超管。店铺无 site_code，单站用户改店会污染跨站共享店铺，
 *       初始化（建店）也统一由超管执行。</li>
 * </ul>
 * 引用完整性：partner_id 必须存在于 md_partner_account 且 ACTIVE（校验与写入同事务）。
 */
@Service
public class StoreService
{
    private static final String COLUMNS = "id,store_no,partner_id,name_en,name_ar,legal_entity,status";
    static final String ENTITY = "STORE";

    private static final RowMapper<StoreRow> MAPPER = (rs, i) -> new StoreRow(
            rs.getLong("id"),
            rs.getString("store_no"),
            rs.getObject("partner_id", Long.class),
            rs.getString("name_en"),
            rs.getString("name_ar"),
            rs.getString("legal_entity"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final MasterDataAudit audit;

    public StoreService(JdbcTemplate jdbc, SiteAuthGuard guard, MasterDataAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** 兼容旧签名：等价于无筛选条件的分页查询。 */
    public List<StoreRow> list(String siteCode, int offset, int limit)
    {
        return list(siteCode, offset, limit, null).rows();
    }

    /**
     * 分页 + 搜索（契约 v3）：授权条件与检索条件在同一 WHERE，COUNT 在 LIMIT/OFFSET 之外统计，
     * 因此 total 是「授权 + 筛选」下的真实总数。
     * 站点口径：超管不传 siteCode 时全量（含无站点行店铺）；普通用户按授权站点 EXISTS；
     * 传 siteCode 时按该站点过滤（requireSite 先校验），与旧实现一致。
     */
    public PageResult<StoreRow> list(String siteCode, int offset, int limit, StoreQuery q)
    {
        MasterDataRules.offset(offset);
        int size = MasterDataRules.limit(limit);
        Long userId = guard.requireCurrentUserId();
        boolean admin = SecurityUtils.isAdmin(userId);
        String requested = siteCode == null || siteCode.isBlank()
                ? null : guard.requireSite(siteCode.trim().toUpperCase(Locale.ROOT));
        StoreQuery query = q == null ? new StoreQuery(null, null, null) : q;

        List<String> sites = null;
        if (requested != null)
        {
            sites = List.of(requested);
        }
        else if (!admin)
        {
            sites = guard.allowedSiteCodes();
            if (sites.isEmpty()) return PageResult.empty();
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sites != null)
        {
            where.append(" AND EXISTS (SELECT 1 FROM md_site t WHERE t.store_id=s.id AND t.site_code IN (")
                    .append(MasterDataRules.placeholders(sites.size())).append("))");
            args.addAll(sites);
        }
        String storeNo = MasterDataRules.like(query.storeNo());
        if (storeNo != null)
        {
            where.append(" AND LOWER(s.store_no) LIKE ?");
            args.add(storeNo);
        }
        String nameEn = MasterDataRules.like(query.nameEn());
        if (nameEn != null)
        {
            where.append(" AND LOWER(s.name_en) LIKE ?");
            args.add(nameEn);
        }
        String status = MasterDataRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND s.status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_store s" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<StoreRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_store s" + where
                + " ORDER BY s.id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public StoreRow get(long id)
    {
        Long userId = guard.requireCurrentUserId();
        StoreRow row = requireExisting(id);
        if (!SecurityUtils.isAdmin(userId)) requireReadable(row.id());
        return row;
    }

    /** 新增店铺：仅超管；partner 必须存在且 ACTIVE。 */
    @Transactional(rollbackFor = Exception.class)
    public long save(StoreInput in)
    {
        guard.requireAdministrator();
        String storeNo = MasterDataRules.required("storeNo", in.storeNo(), 64);
        MasterDataRules.requiredId("partnerId", in.partnerId());
        requireActivePartner(in.partnerId());
        String nameEn = MasterDataRules.optional("nameEn", in.nameEn(), 256);
        String nameAr = MasterDataRules.optional("nameAr", in.nameAr(), 256);
        String legalEntity = MasterDataRules.optional("legalEntity", in.legalEntity(), 256);
        String status = MasterDataRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_store(store_no,partner_id,name_en,name_ar,legal_entity,status) "
                                + "VALUES(?,?,?,?,?,?)", new String[]{"id"});
                ps.setString(1, storeNo);
                ps.setLong(2, in.partnerId());
                ps.setString(3, nameEn);
                ps.setString(4, nameAr);
                ps.setString(5, legalEntity);
                ps.setString(6, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("店铺新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_store", "uk_store_no", "店铺编号 store_no 已存在");
        }
        audit.record(ENTITY, id, "INSERT", null, find(id));
        return id;
    }

    /** 更新店铺：仅超管。先 SELECT ... FOR UPDATE 取快照，防并发改写后审计 before 失真。 */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, StoreInput in)
    {
        guard.requireAdministrator();
        StoreRow before = lockExisting(id);
        String storeNo = MasterDataRules.required("storeNo", in.storeNo(), 64);
        MasterDataRules.requiredId("partnerId", in.partnerId());
        requireActivePartner(in.partnerId());
        String nameEn = MasterDataRules.optional("nameEn", in.nameEn(), 256);
        String nameAr = MasterDataRules.optional("nameAr", in.nameAr(), 256);
        String legalEntity = MasterDataRules.optional("legalEntity", in.legalEntity(), 256);
        String status = MasterDataRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_store SET store_no=?,partner_id=?,name_en=?,name_ar=?,"
                            + "legal_entity=?,status=? WHERE id=?",
                    storeNo, in.partnerId(), nameEn, nameAr, legalEntity, status, id);
            if (updated == 0) throw new IllegalArgumentException("店铺不存在: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw MasterDataRules.duplicate(e, "md_store", "uk_store_no", "店铺编号 store_no 已存在");
        }
        audit.record(ENTITY, id, "UPDATE", before, find(id));
        return id;
    }

    /** 启停：仅超管；不提供物理删除入口。 */
    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        guard.requireAdministrator();
        StoreRow before = lockExisting(id);
        String normalized = MasterDataRules.status(status);
        int updated = jdbc.update("UPDATE md_store SET status=? WHERE id=?", normalized, id);
        if (updated == 0) throw new IllegalArgumentException("店铺不存在: id=" + id);
        audit.record(ENTITY, id, "STATUS", before, find(id));
    }

    private StoreRow find(long id)
    {
        List<StoreRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_store WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private StoreRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("店铺 id 必须为正数");
        StoreRow row = find(id);
        if (row == null) throw new IllegalArgumentException("店铺不存在: id=" + id);
        return row;
    }

    private StoreRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("店铺 id 必须为正数");
        List<StoreRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_store WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("店铺不存在: id=" + id);
        return rows.get(0);
    }

    /** 普通用户读：店铺必须有站点行且与授权站点有交集；无站点行一律拒绝。 */
    private void requireReadable(long storeId)
    {
        List<String> allowed = guard.allowedSiteCodes();
        List<String> own = jdbc.queryForList("SELECT site_code FROM md_site WHERE store_id=?",
                String.class, storeId);
        if (own.isEmpty() || own.stream().noneMatch(allowed::contains))
            throw new AccessDeniedException("没有该店铺的站点访问权限");
    }

    /** 引用完整性：partner 必须存在且 ACTIVE。 */
    private void requireActivePartner(long partnerId)
    {
        List<String> statuses = jdbc.queryForList("SELECT status FROM md_partner_account WHERE id=?",
                String.class, partnerId);
        if (statuses.isEmpty())
            throw new IllegalArgumentException("partnerId 对应的 Partner 账号不存在: " + partnerId);
        if (!"ACTIVE".equals(statuses.get(0)))
            throw new IllegalArgumentException("partnerId 对应的 Partner 账号已停用: " + partnerId);
    }
}
