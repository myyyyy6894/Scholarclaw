package com.scholarclaw.common.exception;

/**
 * 可预期业务失败的统一载体，由 {@link GlobalExceptionHandler} 映射为
 * {@code Result<Void>} 与对应 HTTP 状态。
 *
 * <p>业务代码只允许抛出本异常表达“可预期的失败”；不得返回伪成功、静默吞异常，
 * 也不得用 {@link RuntimeException} 冒充业务错误（那会被兜底为 500）。
 *
 * <p>{@code message} 会直接进入对外响应的 {@code message} 字段，因此调用方必须保证其已脱敏：
 * 禁止拼接 SQL、堆栈、容器 IP、文件绝对路径、上游凭据或用户私有内容。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** 携带结构化业务详情（如重复上传的已有 `doc_id`），仍由同一 `Result` 信封返回。 */
    public BusinessException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getData() {
        return data;
    }
}
