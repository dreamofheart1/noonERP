package com.yx.noon.stocktake;

/**
 * 零成本盘盈拒绝（契约 v1.2 §6.3）：盘盈行在该 (sku, 仓库) 无任何 OPEN 批次，或 OPEN 批次加权平均
 * 单位成本 {@code <= 0}，无法给出可追溯成本。
 *
 * <p>不得让零成本批次进入 FIFO（后续销售 COGS=0、毛利虚高，且无人确认过这一估值）；整单拒绝，提示业务先补录
 * 采购价或确认估值口径后再重新应用。本批结构上不接受客户端传成本。
 *
 * <p>继承 {@link IllegalArgumentException} 使既有 Controller 异常处理直接映射为 400（E2E 可按异常类型或
 * 文案识别）；如需 409 语义，可在 Controller 增加专用处理，不必改本类。
 */
public class ZeroCostGainException extends IllegalArgumentException
{
    private static final long serialVersionUID = 1L;

    public ZeroCostGainException(String message)
    {
        super(message);
    }
}
