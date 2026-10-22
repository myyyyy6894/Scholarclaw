package com.scholarclaw.common.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import java.time.OffsetDateTime;

/**
 * 全系统统一响应包装（{@code 04} §2.3.1）。
 *
 * <p>字段固定为 {@code code / message / data / trace_id / timestamp}，成功时 {@code code = "OK"}。
 * {@code traceId} 经 Jackson 全局 {@code SNAKE_CASE} 策略序列化为 {@code trace_id}。
 * 禁止新增第二套响应包装类。
 *
 * <p>{@code timestamp} 取应用时区（{@link ScTimeZone}）的带偏移量时间，保证输出形如
 * {@code 2026-10-16T22:45:00.125+08:00}，不随宿主机时区漂移。
 *
 * <p>{@code trace_id} 优先取当前线程绑定的入口追踪号；在无入口上下文（定时任务、单元测试）时
 * 生成 {@code tr_cron_*}，见 {@link TraceContext#currentOrGeneratedTraceId()}。
 */
public record Result<T>(String code, String message, T data, String traceId, OffsetDateTime timestamp) {

    public static final String SUCCESS_CODE = "OK";
    public static final String SUCCESS_MESSAGE = "操作成功";

    public static <T> Result<T> ok(T data) {
        return ok(SUCCESS_MESSAGE, data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(SUCCESS_CODE, message, data,
                TraceContext.currentOrGeneratedTraceId(), ScTimeZone.now());
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.defaultMessage());
    }

    /**
     * @param message 已脱敏的可读原因；不得包含 SQL、堆栈、内部主机名或明文凭据。
     */
    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return failure(errorCode, message, null);
    }

    /** 失败响应可携带结构化业务详情；{@code data} 仍位于同一五字段信封内。 */
    public static <T> Result<T> failure(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.code(), message, data,
                TraceContext.currentOrGeneratedTraceId(), ScTimeZone.now());
    }

    /**
     * 便于服务端分支判断。<b>必须标注 {@code @JsonIgnore}</b>：Jackson 会把 {@code isSuccess()}
     * 识别为可读属性并序列化出额外的 {@code success} 字段，破坏 {@code 04} §2.3.1 锁定的
     * 五字段响应结构。
     */
    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
