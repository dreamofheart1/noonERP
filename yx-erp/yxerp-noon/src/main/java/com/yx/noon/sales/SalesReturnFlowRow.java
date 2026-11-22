package com.yx.noon.sales;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货处置链路流水行：红冲（sign=-1 / RETURN_REVERSAL）与 B/C 级 RETURN_LOSS（sign=+1），
 * 供前端展示 COGS 冲回与计损证据（只读）。
 */
public record SalesReturnFlowRow(
        Long id,
        String moveType,
        Integer sign,
        Integer qty,
        BigDecimal unitCost,
        BigDecimal amount,
        String bizEventKey,
        Long reversalOfId,
        Long batchId,
        String period,
        Integer crossPeriod,
        LocalDateTime consumedAt)
{
}
