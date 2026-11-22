package com.yx.noon.purchasing;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.masterdata.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商档案（md_supplier）。契约 3 权限口径：
 * <ul>
 *   <li>list：siteCode 仅作闸门——传值先 requireSite；非超管至少 1 个授权站点，空授权零行。
 *       供应商无 site_code（全局档案），因此不做行级站点过滤。</li>
 *   <li>get：同样要求至少 1 个授权站点（登录不等于业务授权）。</li>
 *   <li>save/update/setStatus：仅超管。</li>
 * </ul>
 * 唯一键 supplier_no 冲突转 400 中文提示；置 INACTIVE 不影响历史采购单（旧单保留 supplier_id 快照）。
 */
@Service
public class SupplierService
{
    private static final String COLUMNS = "id,supplier_no,supplier_name,contact_json,status";
    static final String ENTITY = PurchasingAudit.ENTITY_SUPPLIER;

    private static final RowMapper<SupplierRow> MAPPER = (rs, i) -> new SupplierRow(
            rs.getLong("id"),
            rs.getString("supplier_no"),
            rs.getString("supplier_name"),
            rs.getString("contact_json"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final PurchasingAudit audit;

    public SupplierService(JdbcTemplate jdbc, SiteAuthGuard guard, PurchasingAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /**
     * 分页 + 搜索（契约 4.1）：筛选条件与 COUNT 共用同一 WHERE，total 为筛选后真实总数。
     * siteCode 只做闸门，不进 WHERE（供应商是跨站全局档案）。
     */
    public PageResult<SupplierRow> list(String siteCode, int offset, int limit, SupplierQuery q)
    {
        PurchasingRules.offset(offset);
        int size = PurchasingRules.limit(limit);
        if (!readable(siteCode)) return PageResult.empty();
        SupplierQuery query = q == null ? new SupplierQuery(null, null, null) : q;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String supplierNo = PurchasingRules.like(query.supplierNo());
        if (supplierNo != null)
        {
            where.append(" AND LOWER(supplier_no) LIKE ?");
            args.add(supplierNo);
        }
        String supplierName = PurchasingRules.like(query.supplierName());
        if (supplierName != null)
        {
            where.append(" AND LOWER(supplier_name) LIKE ?");
            args.add(supplierName);
        }
        String status = PurchasingRules.statusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_supplier" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<SupplierRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_supplier" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public SupplierRow get(long id)
    {
        if (!readable(null)) throw new AccessDeniedException("至少需要一个授权站点才能访问供应商档案");
        return requireExisting(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(SupplierInput in)
    {
        guard.requireAdministrator();
        String supplierNo = PurchasingRules.required("supplierNo", in.supplierNo(), 64);
        String supplierName = PurchasingRules.required("supplierName", in.supplierName(), 256);
        String contactJson = PurchasingRules.json("contactJson", in.contactJson());
        String status = PurchasingRules.status(in.status());
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_supplier(supplier_no,supplier_name,contact_json,status) "
                                + "VALUES(?,?,?,?)", new String[]{"id"});
                ps.setString(1, supplierNo);
                ps.setString(2, supplierName);
                ps.setString(3, contactJson);
                ps.setString(4, status);
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("供应商新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw PurchasingRules.duplicate(e, "md_supplier", "uk_supplier_no", "供应商编号 supplier_no 已存在");
        }
        audit.record(ENTITY, id, PurchasingAudit.ACTION_INSERT, null, find(id));
        return id;
    }

    /** 更新供应商：仅超管。先 FOR UPDATE 取快照，防并发改写后审计 before 失真。 */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, SupplierInput in)
    {
        guard.requireAdministrator();
        SupplierRow before = lockExisting(id);
        String supplierNo = PurchasingRules.required("supplierNo", in.supplierNo(), 64);
        String supplierName = PurchasingRules.required("supplierName", in.supplierName(), 256);
        String contactJson = PurchasingRules.json("contactJson", in.contactJson());
        String status = PurchasingRules.status(in.status());
        try
        {
            int updated = jdbc.update("UPDATE md_supplier SET supplier_no=?,supplier_name=?,contact_json=?,status=? "
                    + "WHERE id=? AND supplier_no=?",
                    supplierNo, supplierName, contactJson, status, id, before.supplierNo());
            if (updated == 0) throw new IllegalArgumentException("供应商不存在或编号已被并发修改: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw PurchasingRules.duplicate(e, "md_supplier", "uk_supplier_no", "供应商编号 supplier_no 已存在");
        }
        audit.record(ENTITY, id, PurchasingAudit.ACTION_UPDATE, before, find(id));
        return id;
    }

    /** 启停：仅超管；不提供物理删除入口。停用不影响历史采购单。 */
    @Transactional(rollbackFor = Exception.class)
    public void setStatus(long id, String status)
    {
        guard.requireAdministrator();
        SupplierRow before = lockExisting(id);
        String normalized = PurchasingRules.status(status);
        int updated = jdbc.update("UPDATE md_supplier SET status=? WHERE id=? AND supplier_no=?",
                normalized, id, before.supplierNo());
        if (updated == 0) throw new IllegalArgumentException("供应商不存在: id=" + id);
        audit.record(ENTITY, id, PurchasingAudit.ACTION_STATUS, before, find(id));
    }

    /** 读闸门：传 siteCode 先 requireSite；否则要求至少 1 个授权站点（空授权零行）。 */
    private boolean readable(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
        {
            guard.requireSite(PurchasingRules.siteCode("siteCode", siteCode));
            return true;
        }
        return !guard.allowedSiteCodes().isEmpty();
    }

    private SupplierRow find(long id)
    {
        List<SupplierRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_supplier WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private SupplierRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("供应商 id 必须为正数");
        SupplierRow row = find(id);
        if (row == null) throw new IllegalArgumentException("供应商不存在: id=" + id);
        return row;
    }

    private SupplierRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("供应商 id 必须为正数");
        List<SupplierRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_supplier WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("供应商不存在: id=" + id);
        return rows.get(0);
    }

}
