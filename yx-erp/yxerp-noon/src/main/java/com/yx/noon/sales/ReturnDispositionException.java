package com.yx.noon.sales;

/**
 * 分级处置拒绝（400）。文案必须含可诊断字段 returnId/grade/reasonCode，便于前端与人工核对。
 *
 * <p>与 {@link SalesFlowException} 同源（父类 {@link IllegalArgumentException}）：处置不经过内部闸门，
 * 不使用隔离层，因此恒为 400，不占用 409。
 */
public class ReturnDispositionException extends IllegalArgumentException
{
    private final Long salReturnId;
    private final String grade;
    private final String reasonCode;

    public ReturnDispositionException(Long salReturnId, String grade, String reasonCode, String message)
    {
        super(message + "（returnId=" + salReturnId + ", grade=" + grade + ", reasonCode=" + reasonCode + "）");
        this.salReturnId = salReturnId;
        this.grade = grade;
        this.reasonCode = reasonCode;
    }

    public Long salReturnId() { return salReturnId; }

    public String grade() { return grade; }

    public String reasonCode() { return reasonCode; }
}
