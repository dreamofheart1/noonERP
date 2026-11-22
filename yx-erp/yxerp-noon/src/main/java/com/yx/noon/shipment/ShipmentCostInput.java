package com.yx.noon.shipment;

import java.math.BigDecimal;

/**
 * 头程/关税/包材费用登记入参（契约 §2）。
 *
 * <p><b>结构上不接受</b> {@code id / costNo / status / createdAt}：这些字段没有分量，
 * 服务端生成（防客户端伪造主键、单号与登记时刻）。
 *
 * @param asnId          可空；非空时必须是同站点存在的 ASN（契约 §3 / §5）
 * @param siteCode       必填；AE/SA，授权依据（不允许自报提权）
 * @param period         可空；YYYY-MM，空则取 UTC+8 业务月（契约 §5 裁决点）
 * @param feeType        FREIGHT / DUTY / CUSTOMS / PACKAGING / LABEL
 * @param amount         原币金额，&gt; 0，落库 4 位 HALF_UP
 * @param currency       3 位大写币种（显式，不隐含 CNY）
 * @param fxRate         CNY 必须显式 1；外币 &gt; 0；缺失/非法 400（不兜底 1）
 * @param allocationFlag 空默认 NONE；NONE / PARTIAL / FULL，仅标记不实际分摊
 * @param remark         ≤ 512
 * @param requestId      动作级幂等键，8–64 位 [A-Za-z0-9_-]
 */
public record ShipmentCostInput(
        Long asnId,
        String siteCode,
        String period,
        String feeType,
        BigDecimal amount,
        String currency,
        BigDecimal fxRate,
        String allocationFlag,
        String remark,
        String requestId)
{
}
