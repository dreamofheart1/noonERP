package com.yx.noon.purchasing;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.yx.common.utils.SecurityUtils;
import com.yx.framework.security.site.SiteAuthGuard;
import com.yx.noon.masterdata.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购单（pur_order / pur_order_item）。契约 3 / 4.3 / 5：
 * <ul>
 *   <li>读：普通用户仅授权站点单据，超管全站，空授权零行；get 先查真实 site_code 再 requireSite。</li>
 *   <li>写：创建/修改仅超管；confirm/close 允许该站点内的普通用户（仍按订单真实站点校验）。</li>
 *   <li>状态机：DRAFT --confirm--> CONFIRMED --receive--> PARTIAL_RECEIVED/RECEIVED --close--> CLOSED；
 *       不开放通用 setStatus，非法跳转 400。</li>
 *   <li>成本口径：total_amount = Σ(qty_ordered × unit_price × fx_rate)，只含采购价；CNY fxRate 必须显式 1。</li>
 *   <li>不可变字段：orderNo / site_code / status（由状态机控制）/ qty_received（只由收货流程写）。</li>
 * </ul>
 */
@Service
public class OrderService
{
    private static final String COLUMNS = "id,order_no,supplier_id,site_code,currency,total_amount,status,"
            + "source,source_ref_no,operator_id";
    private static final String ITEM_COLUMNS = "id,pur_order_id,sku_id,qty_ordered,qty_received,"
            + "unit_price,currency,fx_rate";
    static final String ENTITY = PurchasingAudit.ENTITY_ORDER;

    /** 采购单号由服务端生成，客户端传入的 orderNo 一律忽略。 */
    private static final String ORDER_NO_PREFIX = "PO:";
    private static final int ORDER_NO_ATTEMPTS = 3;

    private static final RowMapper<OrderRow> MAPPER = (rs, i) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getObject("supplier_id", Long.class),
            rs.getString("site_code"),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getString("source_ref_no"),
            rs.getObject("operator_id", Long.class),
            List.of());

    private static final RowMapper<OrderItemRow> ITEM_MAPPER = (rs, i) -> new OrderItemRow(
            rs.getLong("id"),
            rs.getLong("pur_order_id"),
            rs.getLong("sku_id"),
            rs.getInt("qty_ordered"),
            rs.getInt("qty_received"),
            rs.getBigDecimal("unit_price"),
            rs.getString("currency"),
            rs.getBigDecimal("fx_rate"));

    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;
    private final PurchasingAudit audit;

    public OrderService(JdbcTemplate jdbc, SiteAuthGuard guard, PurchasingAudit audit)
    {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
    }

    /**
     * 分页 + 搜索（契约 4.3）。站点授权与筛选共用同一 WHERE：普通用户仅授权站点、超管全站、
     * 空授权零行；入参 siteCode 与 Query.siteCode 取交集，COUNT 在 LIMIT/OFFSET 之外统计。
     */
    public PageResult<OrderRow> list(String siteCode, int offset, int limit, OrderQuery q)
    {
        PurchasingRules.offset(offset);
        int size = PurchasingRules.limit(limit);
        Long userId = guard.requireCurrentUserId();
        boolean admin = SecurityUtils.isAdmin(userId);
        OrderQuery query = q == null ? new OrderQuery(null, null, null, null, null) : q;

        List<String> gate;
        if (siteCode != null && !siteCode.isBlank())
            gate = List.of(guard.requireSite(PurchasingRules.siteCode("siteCode", siteCode)));
        else if (!admin)
        {
            gate = guard.allowedSiteCodes();
            if (gate.isEmpty()) return PageResult.empty();
        }
        else gate = null;
        List<String> sites = PurchasingRules.intersectSites(gate, query.siteCode());
        if (sites != null && sites.isEmpty()) return PageResult.empty();

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (sites != null)
        {
            where.append(" AND site_code IN (").append(PurchasingRules.placeholders(sites.size())).append(")");
            args.addAll(sites);
        }
        String orderNo = PurchasingRules.like(query.orderNo());
        if (orderNo != null)
        {
            where.append(" AND LOWER(order_no) LIKE ?");
            args.add(orderNo);
        }
        if (query.supplierId() != null)
        {
            where.append(" AND supplier_id=?");
            args.add(query.supplierId());
        }
        String status = PurchasingRules.orderStatusFilter(query.status());
        if (status != null)
        {
            where.append(" AND status=?");
            args.add(status);
        }
        String source = PurchasingRules.sourceFilter(query.source());
        if (source != null)
        {
            where.append(" AND source=?");
            args.add(source);
        }

        long total = jdbc.queryForObject("SELECT COUNT(*) FROM pur_order" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<OrderRow> heads = jdbc.query("SELECT " + COLUMNS + " FROM pur_order" + where
                + " ORDER BY id LIMIT ? OFFSET ?", MAPPER, pageArgs.toArray());
        return new PageResult<>(withItems(heads), total);
    }

    /** 详情：先读真实 site_code 再 requireSite（不能只信请求体），含明细行。 */
    public OrderRow get(long id)
    {
        OrderRow row = load(id);
        if (row == null) throw new IllegalArgumentException("采购单不存在: id=" + id);
        guard.requireSite(row.siteCode());
        return row;
    }

    /**
     * 新增采购单（契约 4.3）：仅超管，初始 DRAFT。site_code 先 requireSite；
     * orderNo 由服务端生成；供应商可空，非空时必须 ACTIVE；至少 1 行、行 sku ACTIVE、
     * qty/unitPrice>0、fxRate 按币种、行币种等于订单币种。
     */
    @Transactional(rollbackFor = Exception.class)
    public long save(OrderInput in)
    {
        Long operatorId = guard.requireAdministrator();
        String siteCode = PurchasingRules.siteCode("siteCode", in.siteCode());
        guard.requireSite(siteCode);
        PurchasingRefs.requireActiveSupplierIfPresent(jdbc, in.supplierId());
        String currency = PurchasingRules.currency(in.currency());
        String source = PurchasingRules.source(in.source());
        String sourceRefNo = PurchasingRules.optional("sourceRefNo", in.sourceRefNo(), 64);
        List<ItemValues> items = validateItems(in.items(), currency);
        BigDecimal total = total(items);
        long id = 0L;
        for (int attempt = 0; attempt < ORDER_NO_ATTEMPTS; attempt++)
        {
            String orderNo = nextOrderNo(siteCode);
            try
            {
                KeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(con ->
                {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO pur_order(order_no,supplier_id,site_code,currency,total_amount,status,"
                                    + "source,source_ref_no,operator_id) VALUES(?,?,?,?,?,'DRAFT',?,?,?)",
                            new String[]{"id"});
                    ps.setString(1, orderNo);
                    ps.setObject(2, in.supplierId());
                    ps.setString(3, siteCode);
                    ps.setString(4, currency);
                    ps.setObject(5, total);
                    ps.setString(6, source);
                    ps.setString(7, sourceRefNo);
                    ps.setObject(8, operatorId);
                    return ps;
                }, keys);
                Number key = keys.getKey();
                if (key == null) throw new IllegalStateException("采购单新增未返回主键");
                id = key.longValue();
                break;
            }
            catch (DuplicateKeyException e)
            {
                if (attempt == ORDER_NO_ATTEMPTS - 1)
                    throw PurchasingRules.duplicate(e, "pur_order", "uk_pur_no", "采购单号生成冲突");
            }
        }
        if (id == 0L) throw new IllegalStateException("采购单号生成失败");
        insertItems(id, items);
        audit.record(ENTITY, id, PurchasingAudit.ACTION_INSERT, null, load(id));
        return id;
    }

    /**
     * 修改采购单：仅超管且仅 DRAFT。整体替换明细；orderNo/siteCode 不可变更，
     * qty_received 不接收客户端值（已有收货量非零则拒绝，防绕过收货流程）。
     */
    @Transactional(rollbackFor = Exception.class)
    public long update(long id, OrderInput in)
    {
        guard.requireAdministrator();
        OrderRow before = lockOrder(id);
        if (!"DRAFT".equals(before.status()))
            throw new IllegalArgumentException("仅 DRAFT 采购单可修改，当前状态 " + before.status());
        if (in.orderNo() != null && !in.orderNo().isBlank()
                && !before.orderNo().equals(in.orderNo().trim()))
            throw new IllegalArgumentException("采购单号 orderNo 创建后不可变更");
        if (in.siteCode() != null && !in.siteCode().isBlank()
                && !before.siteCode().equals(in.siteCode().trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("站点 siteCode 创建后不可变更");
        Integer received = jdbc.queryForObject("SELECT COALESCE(SUM(qty_received),0) FROM pur_order_item "
                + "WHERE pur_order_id=?", Integer.class, id);
        if (received != null && received > 0)
            throw new IllegalArgumentException("已有收货数量，不可整体修改明细: id=" + id);

        PurchasingRefs.requireActiveSupplierIfPresent(jdbc, in.supplierId());
        String currency = PurchasingRules.currency(in.currency());
        String source = PurchasingRules.source(in.source());
        String sourceRefNo = PurchasingRules.optional("sourceRefNo", in.sourceRefNo(), 64);
        List<ItemValues> items = validateItems(in.items(), currency);
        BigDecimal total = total(items);

        jdbc.update("UPDATE pur_order SET supplier_id=?,currency=?,total_amount=?,source=?,source_ref_no=? "
                + "WHERE id=? AND status='DRAFT'",
                in.supplierId(), currency, total, source, sourceRefNo, id);
        jdbc.update("DELETE FROM pur_order_item WHERE pur_order_id=?", id);
        insertItems(id, items);
        audit.record(ENTITY, id, PurchasingAudit.ACTION_UPDATE, before, load(id));
        return id;
    }

    /**
     * 确认采购单（DRAFT→CONFIRMED）：订单真实站点内的普通用户或超管均可。
     * 前置：至少 1 行、供应商仍 ACTIVE、每行 sku ACTIVE 且数量/单价/汇率合法，否则 400。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(long id)
    {
        OrderRow current = load(id);
        if (current == null) throw new IllegalArgumentException("采购单不存在: id=" + id);
        guard.requireSite(current.siteCode());
        OrderRow before = lockOrder(id);
        if (!"DRAFT".equals(before.status()))
            throw new IllegalArgumentException("仅 DRAFT 采购单可确认，当前状态 " + before.status());
        if (before.supplierId() != null)
            PurchasingRefs.requireActiveSupplierIfPresent(jdbc, before.supplierId());
        if (before.items().isEmpty())
            throw new IllegalArgumentException("采购单至少需要 1 行明细才能确认: id=" + id);
        for (OrderItemRow item : before.items())
        {
            PurchasingRefs.requireActiveSku(jdbc, item.skuId());
            PurchasingRules.qty(item.qtyOrdered(), "qtyOrdered");
            PurchasingRules.positiveMoney(item.unitPrice(), "unitPrice");
            PurchasingRules.fxRate(item.fxRate(), item.currency());
        }
        jdbc.update("UPDATE pur_order SET status='CONFIRMED' WHERE id=? AND status='DRAFT'", id);
        audit.record(ENTITY, id, PurchasingAudit.ACTION_STATUS, before, load(id));
    }

    /** 关单（RECEIVED→CLOSED）：仅全部到货后可关，其他状态 400。 */
    @Transactional(rollbackFor = Exception.class)
    public void close(long id)
    {
        OrderRow current = load(id);
        if (current == null) throw new IllegalArgumentException("采购单不存在: id=" + id);
        guard.requireSite(current.siteCode());
        OrderRow before = lockOrder(id);
        if (!"RECEIVED".equals(before.status()))
            throw new IllegalArgumentException("仅全部到货(RECEIVED)的采购单可关单，当前状态 " + before.status());
        jdbc.update("UPDATE pur_order SET status='CLOSED' WHERE id=? AND status='RECEIVED'", id);
        audit.record(ENTITY, id, PurchasingAudit.ACTION_STATUS, before, load(id));
    }

    // ---------------------------------------------------------------- internals

    private List<OrderRow> withItems(List<OrderRow> heads)
    {
        if (heads.isEmpty()) return heads;
        List<Long> ids = heads.stream().map(OrderRow::id).toList();
        Map<Long, List<OrderItemRow>> byOrder = itemsOf(ids);
        List<OrderRow> rows = new ArrayList<>(heads.size());
        for (OrderRow head : heads)
        {
            rows.add(new OrderRow(head.id(), head.orderNo(), head.supplierId(), head.siteCode(), head.currency(),
                    head.totalAmount(), head.status(), head.source(), head.sourceRefNo(), head.operatorId(),
                    byOrder.getOrDefault(head.id(), List.of())));
        }
        return rows;
    }

    private Map<Long, List<OrderItemRow>> itemsOf(List<Long> orderIds)
    {
        Map<Long, List<OrderItemRow>> result = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>(orderIds);
        List<OrderItemRow> items = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM pur_order_item "
                + "WHERE pur_order_id IN (" + PurchasingRules.placeholders(orderIds.size()) + ") ORDER BY id",
                ITEM_MAPPER, args.toArray());
        for (OrderItemRow item : items)
            result.computeIfAbsent(item.purOrderId(), k -> new ArrayList<>()).add(item);
        return result;
    }

    private OrderRow load(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("采购单 id 必须为正数");
        List<OrderRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM pur_order WHERE id=?", MAPPER, id);
        if (rows.isEmpty()) return null;
        List<OrderItemRow> items = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM pur_order_item "
                + "WHERE pur_order_id=? ORDER BY id", ITEM_MAPPER, id);
        OrderRow head = rows.get(0);
        return new OrderRow(head.id(), head.orderNo(), head.supplierId(), head.siteCode(), head.currency(),
                head.totalAmount(), head.status(), head.source(), head.sourceRefNo(), head.operatorId(), items);
    }

    /**
     * 取订单快照并加行锁：状态机与收货并发都先锁 pur_order，避免并发状态覆盖；
     * qty_received 只由 ReceiptService 在 item 行锁内写。
     */
    private OrderRow lockOrder(long id)
    {
        if (id <= 0) throw new IllegalArgumentException("采购单 id 必须为正数");
        List<OrderRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM pur_order WHERE id=? FOR UPDATE",
                MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("采购单不存在: id=" + id);
        List<OrderItemRow> items = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM pur_order_item "
                + "WHERE pur_order_id=? ORDER BY id FOR UPDATE", ITEM_MAPPER, id);
        OrderRow head = rows.get(0);
        return new OrderRow(head.id(), head.orderNo(), head.supplierId(), head.siteCode(), head.currency(),
                head.totalAmount(), head.status(), head.source(), head.sourceRefNo(), head.operatorId(), items);
    }

    /** 服务端单号：PO:<SITE>:<yyyyMMdd>:<UUID8>，与 uk_pur_no 一起保证唯一。 */
    private static String nextOrderNo(String siteCode)
    {
        return ORDER_NO_PREFIX + siteCode + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ":"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private List<ItemValues> validateItems(List<OrderItemInput> items, String currency)
    {
        if (items == null || items.isEmpty())
            throw new IllegalArgumentException("采购单至少需要 1 行明细");
        List<ItemValues> values = new ArrayList<>(items.size());
        for (OrderItemInput item : items)
        {
            if (item == null) throw new IllegalArgumentException("采购单明细行不能为空");
            PurchasingRules.requiredId("skuId", item.skuId());
            PurchasingRefs.requireActiveSku(jdbc, item.skuId());
            int qty = PurchasingRules.qty(item.qtyOrdered(), "qtyOrdered");
            BigDecimal unitPrice = PurchasingRules.positiveMoney(item.unitPrice(), "unitPrice");
            String lineCurrency = PurchasingRules.currency(item.currency());
            if (!currency.equals(lineCurrency))
                throw new IllegalArgumentException("采购单行币种 " + lineCurrency + " 与订单币种 " + currency + " 不一致");
            BigDecimal fxRate = PurchasingRules.fxRate(item.fxRate(), lineCurrency);
            values.add(new ItemValues(item.skuId(), qty, unitPrice, lineCurrency, fxRate));
        }
        return values;
    }

    /** 成本口径：Σ(qty × unit_price × fx_rate)，4 位小数（与 DECIMAL(18,4) 一致），只含采购价。 */
    private static BigDecimal total(List<ItemValues> items)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (ItemValues item : items)
        {
            BigDecimal line = item.unitPrice()
                    .multiply(item.fxRate())
                    .multiply(BigDecimal.valueOf(item.qty()));
            sum = sum.add(line);
        }
        return PurchasingRules.money(sum);
    }

    private void insertItems(long orderId, List<ItemValues> items)
    {
        List<Object[]> rows = new ArrayList<>(items.size());
        for (ItemValues item : items)
            rows.add(new Object[]{item.skuId(), item.qty(), item.unitPrice(), item.currency(), item.fxRate()});
        jdbc.batchUpdate("INSERT INTO pur_order_item(pur_order_id,sku_id,qty_ordered,qty_received,"
                        + "unit_price,currency,fx_rate) VALUES(?,?,?,0,?,?,?)",
                rows, 200, (ps, args) ->
                {
                    ps.setLong(1, orderId);
                    ps.setObject(2, args[0]);
                    ps.setInt(3, (Integer) args[1]);
                    ps.setObject(4, args[2]);
                    ps.setString(5, (String) args[3]);
                    ps.setObject(6, args[4]);
                });
    }

    /** 规范化后的明细值（校验一次，写入/合计复用）。 */
    private record ItemValues(long skuId, int qty, BigDecimal unitPrice, String currency, BigDecimal fxRate)
    {
    }
}
