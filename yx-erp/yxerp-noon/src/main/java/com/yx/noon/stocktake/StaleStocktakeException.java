package com.yx.noon.stocktake;

/**
 * 盘点账面快照闸门拒绝（契约 v1.2 §5 / §6.1）：apply 时实时台账可用量与建单快照
 * {@code inv_stocktake_item.qty_book} 不一致，说明实盘时刻与账面时刻已错位（建单后发生了真实出库/入库/调拨），
 * 旧实盘数字不能再用来与新的账面配对算差异，否则会凭空盘盈/盘亏。
 *
 * <p>这是<b>业务校验失败</b>，不是技术异常：整单拒绝、不写台账/流水/批次/消耗/状态，也不缓存幂等结果；
 * 用户须按当前账面重新建单（新快照）后再应用。
 *
 * <p>继承 {@link IllegalArgumentException} 使既有 Controller 异常处理直接映射为 400（E2E 可按异常类型或
 * 文案识别）；如需 409 语义，可在 Controller 增加对 {@link StaleStocktakeException} 的专用处理，不必改本类。
 */
public class StaleStocktakeException extends IllegalArgumentException
{
    private static final long serialVersionUID = 1L;

    public StaleStocktakeException(String message)
    {
        super(message);
    }
}
