package com.yx.noon.stocktake;

/**
 * 盘点结果（契约 §2）。{@code create}/{@code apply} 首次执行 {@code replayed=false}；
 * 同 requestId 同内容重放原样返回首次结果并标记 {@code replayed=true}（不重复写任何业务数据）；
 * {@code resultOf} 只读查询恒返回 {@code replayed=true}。
 */
public record StocktakeResult(long stocktakeId, String stocktakeNo, String status, boolean replayed)
{
}
