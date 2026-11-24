package com.yx.noon.sync;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售明细 → ERP 业务联动：按平台状态推进，但**不伪造 ERP 正式订单或出库**。
 *
 * <p>规则（用户 2026-11-24 决定）：
 * <ul>
 *   <li>送达：只有已建立 sal_order_item 且有系统侧事件证据，才交 FifoConsumptionService 扣库存/成本；
 *       否则落 NO_ORDER_LINK / NO_EVIDENCE 待核，不自动建单、不自动扣库存；</li>
 *   <li>状态倒退：已送达的记录不再回退，落 STATE_REGRESSION 待核；</li>
 *   <li>取消/未匹配/缺成本：分别标注，不扣不加；</li>
 *   <li>退货：只有平台确认"重新入仓且可售"的证据才允许后续 ReturnDispositionService(A) 恢复；
 *       缺证据落 INSUFFICIENT_EVIDENCE，保持待核、不加库存。</li>
 * </ul>
 */
@Service
public class NoonSalesLinkService
{
    private final JdbcTemplate jdbc;
    private final SiteAuthGuard guard;

    public NoonSalesLinkService(JdbcTemplate jdbc, SiteAuthGuard guard)
    {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** 扫描 noon_sales_item 快照，生成/更新联动待核项；不直接改库存。 */
    @Transactional(rollbackFor = Exception.class)
    public List<SalesLinkRow> refresh(String siteCode)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT item_nr,partner_sku,sku,status,order_ts,delivered_ts FROM noon_sales_item WHERE site_code=?",
                site);
        List<SalesLinkRow> out = new ArrayList<>();
        for (Map<String, Object> i : items)
        {
            String itemNr = (String) i.get("item_nr");
            String status = i.get("status") == null ? "" : String.valueOf(i.get("status"));
            String decision;
            String note;
            Long orderItemId = null;
            String storedStatus;

            List<Map<String, Object>> linked = jdbc.queryForList(
                    "SELECT sal_order_item_id,status,delivered_at FROM noon_sales_link WHERE site_code=? AND item_nr=?",
                    site, itemNr);
            if (!linked.isEmpty())
            {
                String old = String.valueOf(linked.get(0).get("status"));
                Object oldDeliveredAt = linked.get(0).get("delivered_at");
                boolean wasDelivered = oldDeliveredAt != null
                        || old.contains("DELIVER") || "READY_FOR_FIFO".equals(old);
                orderItemId = (Long) linked.get(0).get("sal_order_item_id");
                if (wasDelivered && !isDelivered(status))
                {
                    decision = "STATE_REGRESSION";
                    storedStatus = old; // 保留已送达事实，不回退
                    note = "平台状态倒退，保持已送达事实并标待核，不回退库存";
                }
                else
                {
                    decision = isDelivered(status) || wasDelivered ? "READY_FOR_FIFO" : "TRACKING";
                    storedStatus = decision;
                    note = isDelivered(status) || wasDelivered
                            ? "已送达：若存在正式订单行与系统事件证据，可走 FIFO 扣减；本服务不自动扣" : "未送达，继续跟踪";
                }
            }
            else
            {
                decision = isDelivered(status) ? "NO_ORDER_LINK" : "TRACKING";
                storedStatus = decision;
                note = isDelivered(status)
                        ? "已送达但无 ERP 正式订单行关联，禁止臆造订单/扣库存" : "未送达，继续跟踪";
            }

            jdbc.update("INSERT INTO noon_sales_link(site_code,item_nr,sal_order_id,sal_order_item_id,status,delivered_at,synced_at) "
                            + "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),delivered_at=VALUES(delivered_at),synced_at=VALUES(synced_at)",
                    site, itemNr, orderItemId, orderItemId, storedStatus,
                    i.get("delivered_ts"), Timestamp.valueOf(LocalDateTime.now()));
            out.add(new SalesLinkRow(site, itemNr, decision, note));
        }
        return out;
    }

    /** 退货证据登记：默认 PENDING，不会加库存；只有 verified=true 且有 evidence 才标 VERIFIED。 */
    @Transactional(rollbackFor = Exception.class)
    public int registerReturn(String siteCode, String itemNr, String sourceReturnNo,
                              boolean verifiedRestockSaleable, String evidenceNote)
    {
        String site = NoonSellerLabClient.requireSite(siteCode);
        guard.requireSite(site);
        if (sourceReturnNo == null || sourceReturnNo.isBlank())
            throw new IllegalArgumentException("退货来源编号不能为空");
        boolean ok = verifiedRestockSaleable && evidenceNote != null && !evidenceNote.isBlank();
        String status = ok ? "VERIFIED" : "INSUFFICIENT_EVIDENCE";
        jdbc.update("INSERT INTO noon_return_evidence(site_code,item_nr,source_return_no,status,evidence_note,synced_at) "
                        + "VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),evidence_note=VALUES(evidence_note),synced_at=VALUES(synced_at)",
                site, itemNr, sourceReturnNo, status,
                ok ? evidenceNote : "缺平台确认重新入仓且可售的证据，保持待核、不加库存",
                Timestamp.valueOf(LocalDateTime.now()));
        return ok ? 1 : 0;
    }

    private static boolean isDelivered(String status)
    {
        if (status == null) return false;
        String s = status.trim().toLowerCase();
        return s.contains("deliver") || s.contains("completed");
    }
}
