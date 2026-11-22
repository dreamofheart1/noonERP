package com.yx.noon.sales;

import java.math.BigDecimal;
import java.util.List;

/**
 * M5 编排层统一动作结果（契约 §3）。首次执行 {@code replayed=false}；同 requestId + 同 action + 同内容
 * 重放返回首次结果并置 {@code replayed=true}，零副作用。
 *
 * <p>字段按动作取用、未用为 null：订单导入填 {@code orderId/orderItemId}；妥投填 {@code consumptionIds}
 * （FIFO 可拆多批）；退货入账填 {@code returnId}；红冲填 {@code reversalId}；分级处置填
 * {@code grade/restockBatchNo/returnLossAmount/compensationAmount}。
 */
public record SalesResult(
        String requestId,
        String action,
        String siteCode,
        boolean replayed,
        Long entityId,
        Long orderId,
        Long orderItemId,
        Long returnId,
        Long reversalId,
        List<Long> consumptionIds,
        String grade,
        String restockBatchNo,
        BigDecimal returnLossAmount,
        BigDecimal compensationAmount,
        String reasonCode)
{
    public SalesResult
    {
        consumptionIds = consumptionIds == null ? List.of() : List.copyOf(consumptionIds);
    }

    /** 幂等重放：同内容结果只改 {@code replayed} 标记，其余原样。 */
    public SalesResult asReplay()
    {
        return new SalesResult(requestId, action, siteCode, true, entityId, orderId, orderItemId,
                returnId, reversalId, consumptionIds, grade, restockBatchNo, returnLossAmount,
                compensationAmount, reasonCode);
    }
}
