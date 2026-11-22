package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
 * SKU 供应商报价（md_sku_supplier），历史价口径（契约 4.2 / 5 + v2 §9.2）：
 * <ul>
 *   <li>按 effective_from 版本化：改价用新的 effective_from 插入新行，不物理删除旧行；
 *       唯一键 (sku_id,supplier_id,effective_from) 冲突 → 400。</li>
 *   <li>区间重叠校验：同一组合内 [effective_from, effective_to) 不得交叉；effective_to 为空表示长期有效。</li>
 *   <li><b>组合串行化（v2 §9.2）</b>：save/update 在 validate 之前先对 (sku_id,supplier_id) 加锁
 *       （noon_m3_quotation_lock 建锁行 + SELECT ... FOR UPDATE 持锁到提交），杜绝并发「先查后插」产生交叉区间；
 *       update 改组合时按 (skuId,supplierId) 升序锁 old 与 new 两个组合防死锁。唯一键与 requireNoOverlap 作第二道防线。</li>
 *   <li>引用完整性：sku 存在且 ACTIVE、supplier 存在且 ACTIVE（与写入同事务）。</li>
 *   <li>读：至少 1 个授权站点即可读全部，空授权零行；写：仅超管。</li>
 * </ul>
 */
@Service
public class QuotationService
{
    private static final String COLUMNS = "id,sku_id,supplier_id,unit_price,currency,moq,"
            + "lead_time_days,effective_from,effective_to";
    static final String ENTITY = PurchasingAudit.ENTITY_QUOTATION;

    private static final RowMapper<QuotationRow> MAPPER = (rs, i) -> new QuotationRow(
            rs.getLong("id"),
            rs.getObject("sku_id", Long.class),
            rs.getObject("supplier_id", Long.class),
            rs.getBigDecimal("unit_price"),
            rs.getString("currency"),
            rs.getObject("moq", Integer.class),
            rs.getObject("lead_time_days", Integer.class),
            dateText(rs.getDate("effective_from")),
            dateText(rs.getDate("effective_to")));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final PurchasingAudit audit;

    public QuotationService(JdbcTemplate jdbc, SiteAuthGuard guard, PurchasingAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /** 分页 + 搜索（契约 4.2）：siteCode 仅闸门；skuId/supplierId 精确，生效起始日区间含端点。 */
    public PageResult<QuotationRow> list(String siteCode, int offset, int limit, QuotationQuery q)
    {
        PurchasingRules.offset(offset);
        int size = PurchasingRules.limit(limit);
        if (!readable(siteCode)) return PageResult.empty();
        QuotationQuery query = q == null ? new QuotationQuery(null, null, null, null) : q;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query.skuId() != null)
        {
            where.append(" AND sku_id=?");
            args.add(query.skuId());
        }
        if (query.supplierId() != null)
        {
            where.append(" AND supplier_id=?");
            args.add(query.supplierId());
        }
        LocalDate from = PurchasingRules.date("effectiveFrom", query.effectiveFrom(), false);
        if (from != null)
        {
            where.append(" AND effective_from>=?");
            args.add(from);
        }
        LocalDate to = PurchasingRules.date("effectiveTo", query.effectiveTo(), false);
        if (to != null)
        {
            where.append(" AND effective_from<=?");
            args.add(to);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM md_sku_supplier" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<QuotationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku_supplier" + where
                + " ORDER BY sku_id,supplier_id,effective_from DESC,id DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, total);
    }

    public QuotationRow get(long id)
    {
        if (!readable(null)) throw new AccessDeniedException("至少需要一个授权站点才能访问报价");
        return requireExisting(id);
    }

    /**
     * 指定日期有效报价：effective_from <= at 且（effective_to 为空或 > at）中 effective_from 最大的一条。
     * 无匹配返回 null（Controller 直接回 null，由前端决定提示；采购单 save 不使用该查询，信任提交快照）。
     */
    public QuotationRow effectivePrice(Long skuId, Long supplierId, LocalDate at)
    {
        if (!readable(null)) throw new AccessDeniedException("至少需要一个授权站点才能查询有效报价");
        PurchasingRules.requiredId("skuId", skuId);
        PurchasingRules.requiredId("supplierId", supplierId);
        LocalDate day = at == null ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : at;
        List<QuotationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku_supplier "
                + "WHERE sku_id=? AND supplier_id=? AND effective_from<=? "
                + "AND (effective_to IS NULL OR effective_to>?) "
                + "ORDER BY effective_from DESC,id DESC LIMIT 1",
                MAPPER, skuId, supplierId, day, day);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(QuotationInput in)
    {
        guard.requireAdministrator();
        if (in == null) throw new IllegalArgumentException("报价入参不能为空");
        PurchasingRules.requiredId("skuId", in.skuId());
        PurchasingRules.requiredId("supplierId", in.supplierId());
        // v2 §9.2：validate（含 requireNoOverlap）之前先串行化整个 (sku,supplier) 组合。
        lockCombinations(List.of(new Combo(in.skuId(), in.supplierId())));
        Values v = validate(in, 0L);
        long id;
        try
        {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con ->
            {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO md_sku_supplier(sku_id,supplier_id,unit_price,currency,moq,"
                                + "lead_time_days,effective_from,effective_to) VALUES(?,?,?,?,?,?,?,?)",
                        new String[]{"id"});
                ps.setObject(1, v.skuId());
                ps.setObject(2, v.supplierId());
                ps.setObject(3, v.unitPrice());
                ps.setString(4, v.currency());
                ps.setObject(5, v.moq());
                ps.setObject(6, v.leadTimeDays());
                ps.setObject(7, v.effectiveFrom());
                ps.setObject(8, v.effectiveTo());
                return ps;
            }, keys);
            Number key = keys.getKey();
            if (key == null) throw new IllegalStateException("报价新增未返回主键");
            id = key.longValue();
        }
        catch (DuplicateKeyException e)
        {
            throw PurchasingRules.duplicate(e, "md_sku_supplier", "uk_sku_sup_eff",
                    "同一 SKU + 供应商在该 effective_from 已存在报价；改价请用新的生效日期新增一行");
        }
        audit.record(ENTITY, id, PurchasingAudit.ACTION_INSERT, null, find(id));
        return id;
    }

    /** 更新报价：仅超管；语义为修正同一条历史价版本（新增价格版本请用 save）。 */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, QuotationInput in)
    {
        guard.requireAdministrator();
        if (in == null) throw new IllegalArgumentException("报价入参不能为空");
        QuotationRow before = lockExisting(id);
        PurchasingRules.requiredId("skuId", in.skuId());
        PurchasingRules.requiredId("supplierId", in.supplierId());
        // v2 §9.2：改组合时 old 与 new 都要锁，按 (skuId,supplierId) 升序依次加锁防死锁。
        lockCombinations(List.of(new Combo(before.skuId(), before.supplierId()),
                new Combo(in.skuId(), in.supplierId())));
        Values v = validate(in, id);
        try
        {
            int updated = jdbc.update("UPDATE md_sku_supplier SET sku_id=?,supplier_id=?,unit_price=?,"
                            + "currency=?,moq=?,lead_time_days=?,effective_from=?,effective_to=? WHERE id=?",
                    v.skuId(), v.supplierId(), v.unitPrice(), v.currency(), v.moq(), v.leadTimeDays(),
                    v.effectiveFrom(), v.effectiveTo(), id);
            if (updated == 0) throw new IllegalArgumentException("报价不存在: id=" + id);
        }
        catch (DuplicateKeyException e)
        {
            throw PurchasingRules.duplicate(e, "md_sku_supplier", "uk_sku_sup_eff",
                    "同一 SKU + 供应商在该 effective_from 已存在报价；改价请用新的生效日期新增一行");
        }
        audit.record(ENTITY, id, PurchasingAudit.ACTION_UPDATE, before, find(id));
        return id;
    }

    /**
     * v2 §9.2 组合串行化锁：按 (skuId,supplierId) 升序、去重后依次加锁，避免 update 改组合时交叉死锁。
     * 调用方已在自身事务内（save/update 均 @Transactional），锁持有到事务提交。
     */
    private void lockCombinations(List<Combo> combos)
    {
        List<Combo> ordered = combos.stream().distinct()
                .sorted(Comparator.comparingLong(Combo::skuId).thenComparingLong(Combo::supplierId))
                .toList();
        for (Combo combo : ordered) lockCombination(combo.skuId(), combo.supplierId());
    }

    /**
     * 单个组合加锁：先 try INSERT 建锁行（已存在说明别的事务已建有锁行，忽略冲突），
     * 再 SELECT ... FOR UPDATE 锁该行到事务提交。锁行不存在的极端情况视为锁失效，直接失败。
     */
    private void lockCombination(long skuId, long supplierId)
    {
        try
        {
            jdbc.update("INSERT INTO noon_m3_quotation_lock(sku_id,supplier_id) VALUES(?,?)", skuId, supplierId);
        }
        catch (DuplicateKeyException ignored)
        {
            // 组合锁行已存在：继续走 FOR UPDATE 等待持锁。
        }
        List<Long> locked = jdbc.query("SELECT sku_id FROM noon_m3_quotation_lock "
                        + "WHERE sku_id=? AND supplier_id=? FOR UPDATE",
                (rs, i) -> rs.getLong(1), skuId, supplierId);
        if (locked.isEmpty())
            throw new IllegalStateException("报价组合锁未生效: skuId=" + skuId + "，supplierId=" + supplierId);
    }

    /** 校验入参并返回规范化值；selfId=0 表示新增，update 时排除自身行做重叠校验。 */
    private Values validate(QuotationInput in, long selfId)
    {
        PurchasingRules.requiredId("skuId", in.skuId());
        PurchasingRules.requiredId("supplierId", in.supplierId());
        PurchasingRefs.requireActiveSku(jdbc, in.skuId());
        PurchasingRefs.requireActiveSupplier(jdbc, in.supplierId());
        BigDecimal unitPrice = PurchasingRules.positiveMoney(in.unitPrice(), "unitPrice");
        String currency = PurchasingRules.currency(in.currency());
        int moq = in.moq() == null ? 0 : in.moq();
        if (moq < 0) throw new IllegalArgumentException("moq 不能为负数");
        int leadTimeDays = in.leadTimeDays() == null ? 0 : in.leadTimeDays();
        if (leadTimeDays < 0) throw new IllegalArgumentException("leadTimeDays 不能为负数");
        LocalDate from = PurchasingRules.date("effectiveFrom", in.effectiveFrom(), true);
        LocalDate to = PurchasingRules.date("effectiveTo", in.effectiveTo(), false);
        PurchasingRules.effectiveRange(from, to);
        requireNoOverlap(selfId, in.skuId(), in.supplierId(), from, to);
        return new Values(in.skuId(), in.supplierId(), unitPrice, currency, moq, leadTimeDays, from, to);
    }

    /**
     * 区间重叠：同组合内 (from,to) 与已有 [from2,to2) 相交即拒绝。
     * to 为空视为无穷远；to2 为空同理。
     */
    private void requireNoOverlap(long selfId, long skuId, long supplierId, LocalDate from, LocalDate to)
    {
        LocalDate farFuture = LocalDate.of(9999, 12, 31);
        List<Long> conflicts = jdbc.query("SELECT id FROM md_sku_supplier "
                        + "WHERE sku_id=? AND supplier_id=? AND id<>? "
                        + "AND effective_from<? AND (effective_to IS NULL OR effective_to>?)",
                (rs, i) -> rs.getLong("id"), skuId, supplierId, selfId, to == null ? farFuture : to, from);
        if (!conflicts.isEmpty())
            throw new IllegalArgumentException("报价区间与已有记录重叠: id=" + conflicts.get(0)
                    + "（同一 SKU + 供应商的生效区间不得交叉）");
    }

    private boolean readable(String siteCode)
    {
        if (siteCode != null && !siteCode.isBlank())
        {
            guard.requireSite(PurchasingRules.siteCode("siteCode", siteCode));
            return true;
        }
        return !guard.allowedSiteCodes().isEmpty();
    }

    private QuotationRow find(long id)
    {
        List<QuotationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku_supplier WHERE id=?",
                MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private QuotationRow requireExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("报价 id 必须为正数");
        QuotationRow row = find(id);
        if (row == null) throw new IllegalArgumentException("报价不存在: id=" + id);
        return row;
    }

    private QuotationRow lockExisting(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("报价 id 必须为正数");
        List<QuotationRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM md_sku_supplier WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("报价不存在: id=" + id);
        return rows.get(0);
    }

    private static String dateText(Date date)
    {
        return date == null ? null : date.toLocalDate().toString();
    }

    /** 规范化后的报价入参。 */
    private record Values(long skuId, long supplierId, BigDecimal unitPrice, String currency,
                          int moq, int leadTimeDays, LocalDate effectiveFrom, LocalDate effectiveTo)
    {
    }

    /** 报价串行化键：一个 (sku_id, supplier_id) 组合。 */
    private record Combo(long skuId, long supplierId)
    {
    }
}
