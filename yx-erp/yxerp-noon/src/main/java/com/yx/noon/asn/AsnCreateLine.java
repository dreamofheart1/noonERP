package com.yx.noon.asn;

/**
 * ASN 建单明细行（契约 §3；M6 §3.1 扩展）。
 *
 * <p>{@code LOCAL_BATCH}（默认）：{@code batchNo} 必须是本站点本地仓的 OPEN 成本批次（承载成本），
 * {@code purOrderItemId} 忽略。
 *
 * <p>{@code DIRECT_PURCHASE}：{@code purOrderItemId} 必填（来源采购明细，成本/收货量以服务端
 * {@code noon_m3_receipt} 为准），{@code batchNo} 由服务端生成 {@code DP:<poItemId>:<uuid8>}。
 * {@code skuId} 可选：传了必须与采购明细 SKU 一致，不传取采购明细 SKU。
 *
 * <p>{@code qtyShipped} 为计划/实发量；{@code partnerSku} 可空（Noon 侧货号，仅登记不参与成本）。
 */
public record AsnCreateLine(Long skuId, String batchNo, Integer qtyShipped, String partnerSku,
                            Long purOrderItemId)
{
    /** 兼容构造器：M4/M5 旧调用（本地批次模式，无采购明细）。 */
    public AsnCreateLine(Long skuId, String batchNo, Integer qtyShipped, String partnerSku)
    {
        this(skuId, batchNo, qtyShipped, partnerSku, null);
    }
}
