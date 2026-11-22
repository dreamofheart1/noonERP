package com.yx.noon.purchasing;

import java.util.List;

/**
 * 部分收货入参（契约 4.4 / v2 §9.1）：一次可提交多行，同一行不得重复出现。
 *
 * <p>{@code requestId} 是客户端幂等键：一次收货提交动作生成一个，重试原样复用，真实第二次收货必须换新值。
 * 必填，长度 8–64，只允许 {@code [A-Za-z0-9_-]}；缺失或非法一律 400「收货请求缺少有效的 requestId」。
 */
public record ReceiptInput(Long orderId, List<ReceiptLineInput> lines, String requestId)
{
}
