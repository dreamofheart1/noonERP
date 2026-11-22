package com.yx.noon.sales;

import java.math.BigDecimal;

/**
 * A/B/C 分级处置入参（契约 §6）。
 *
 * <p>{@code grade} 必填且只允许 A/B/C（人工质检结果）。{@code restockCost} 仅 A 级使用，可空——
 * 空则取被红冲原 SALE 消耗层成本（裁决点 D2，PRD {@code return_restock_cost=ORIGINAL}）。
 * <b>单位</b>：与 {@code inv_batch.unit_cost} 同口径的单件成本（本位币 CNY，采购价，不含头程/关税/包材，
 * 见 PRD FR-03.3）。<b>来源/校验</b>：显式传入必须 &gt; 0 且<b>必须等于系统计算的原 SALE 层加权成本</b>
 * ——A 级为资产转移、不产生损益，任何偏离原价的覆盖（含正数）均 400 拒绝
 * （{@code RESTOCK_COST_OVERRIDE_FORBIDDEN}），杜绝资产与原红冲成本脱钩导致利润失真。
 * <b>重估待决</b>：质检重估价须有明确业务授权 + 完整资产差额处理，需求未确认，本批不臆造阈值或审批流
 * （契约 §9.2/§13.2）；来源写入审计 {@code restockCostSource=ORIGINAL_LAYER|CLIENT_OVERRIDE}。
 * <b>权限</b>：复用站点授权 + 处置动作菜单权限。
 * {@code compensationAmount}/{@code refundAmount} 只登记到 {@code sal_return}，
 * **不写平台费流水、不从 RETURN_LOSS 扣减**（C-2b 防双计）。
 * {@code qty} 可空，默认退货单 qty；显式传入时不得超过该退货单 qty 与已红冲数量（裁决点 D3）。
 */
public record DisposeInput(
        String requestId,
        String siteCode,
        Long salReturnId,
        String grade,
        BigDecimal restockCost,
        BigDecimal compensationAmount,
        BigDecimal refundAmount,
        Integer qty)
{
}
