package com.yx.noon.sales;

/**
 * 编排层业务拒绝。继承 {@link IllegalArgumentException}，与既有 Controller 的
 * {@code @ExceptionHandler(IllegalArgumentException.class)} 保持同源（默认映射 400）。
 *
 * <p><b>隔离语义映射 409</b>：内部闸门返回 {@code NEEDS_REVIEW} 时抛本异常，携带
 * {@code reasonCode}/{@code reviewNo}。Controller 需为本类单独注册
 * {@code @ExceptionHandler(SalesFlowException.class)} 返回 409（通用 400 处理会先捕获父类）。
 * 契约 §3 要求"非 200、非静默成功"；本类只承载字段，不改变内部闸门已写入的隔离记录。
 */
public class SalesFlowException extends IllegalArgumentException
{
    private final String action;
    private final String requestId;
    private final String reasonCode;
    private final String reviewNo;

    public SalesFlowException(String action, String requestId, String reasonCode, String reviewNo, String message)
    {
        super(message);
        this.action = action;
        this.requestId = requestId;
        this.reasonCode = reasonCode;
        this.reviewNo = reviewNo;
    }

    public String action() { return action; }

    public String requestId() { return requestId; }

    public String reasonCode() { return reasonCode; }

    /** 待核对单号；可空（如主数据不完整但未隔离时不使用）。 */
    public String reviewNo() { return reviewNo; }
}
