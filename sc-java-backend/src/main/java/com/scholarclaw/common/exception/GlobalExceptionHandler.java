package com.scholarclaw.common.exception;

import com.scholarclaw.common.response.Result;
import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常映射（{@code 04} §2.3.2 六类异常）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>不把未捕获异常包装成 200 伪成功；不得静默吞异常；</li>
 *   <li>对外 {@code message} 一律使用 {@link ErrorCode} 的脱敏文案，绝不回传
 *       {@code ex.getMessage()}（其中可能含 SQL、内部主机名或路径）；</li>
 *   <li>堆栈只写日志，且日志经 {@code SensitiveDataMaskingConverter} 脱敏。</li>
 * </ul>
 *
 * <p>{@code 04} §4.5 允许的“HTTP 200 + 业务字段”表达未应用结果仅适用于文档明确要求的内部回调场景，
 * 属后续任务的自有契约，不在此泛化。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = resolveStatus(errorCode);
        if (status.is5xxServerError()) {
            log.error("业务异常映射到服务端错误码 code={} status={}", errorCode.code(), status.value(), ex);
        } else {
            log.warn("业务处理失败 code={} status={} message={}", errorCode.code(), status.value(), ex.getMessage());
        }
        return ResponseEntity.status(status).body(Result.failure(errorCode, ex.getMessage()));
    }

    /** JSR-380 请求体校验失败：{@code 04} §2.3.2 第 1 类。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return validationFailure(detail);
    }

    /** 方法参数级校验失败（Spring 6.1 {@code @Valid} 直接标注在方法参数上）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String detail = ex.getAllValidationResults().stream()
                .map(result -> result.getMethodParameter().getParameterName())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        return validationFailure(detail.isBlank() ? null : "参数不合法: " + detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        return validationFailure(detail);
    }

    /** 超过 Spring multipart 上限：按契约 `400 FILE_SIZE_EXCEEDED`，不能落成 500。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过 multipart 上限");
        return ResponseEntity.status(resolveStatus(ErrorCode.FILE_SIZE_EXCEEDED))
                .body(Result.failure(ErrorCode.FILE_SIZE_EXCEEDED));
    }

    /** 请求体无法解析（JSON 语法错误、类型不匹配）同样归类为参数校验失败。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return validationFailure(null);
    }

    /**
     * Spring MVC 自身抛出、且携带明确 HTTP 状态的异常：未匹配路由
     * （{@code NoResourceFoundException}）、方法不允许、媒体类型不支持、请求参数缺失等。
     *
     * <p><b>为什么必须有这个处理器：</b>若只留 {@link #handleUnexpectedException}，
     * 未匹配路由的 {@code NoResourceFoundException} 会被它接住并返回 {@code 500}，
     * 于是「访问不存在的路径」变成服务器错误——TASK-104 在容器里实测时发现的真实缺陷。
     *
     * <p>写法说明：这些异常统一继承 {@code jakarta.servlet.ServletException} 并实现
     * {@code org.springframework.web.ErrorResponse}。{@code @ExceptionHandler} 只接受
     * {@code Throwable} 子类，不能直接写接口类型，因此按 {@code ServletException} 捕获、
     * 再用 {@code ErrorResponse} 取状态码；不是 {@code ErrorResponse} 的仍按 500 处理。
     *
     * <p>状态码原样保留，错误码按 {@code 04} 第 7 章就近映射：{@code 404 → RESOURCE_NOT_FOUND}、
     * {@code 403 → AUTH_FORBIDDEN}，其余 4xx 归 {@code VALIDATION_ERROR}（第 7 章未为 405/415
     * 这类状态定义专门错误码，此处不擅自发明）。
     */
    @ExceptionHandler(ServletException.class)
    public ResponseEntity<Result<Void>> handleServletException(ServletException ex) {
        if (!(ex instanceof ErrorResponse errorResponse)) {
            return handleUnexpectedException(ex);
        }
        HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
        ErrorCode errorCode = switch (status.value()) {
            case 403 -> ErrorCode.AUTH_FORBIDDEN;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
        };
        if (status.is5xxServerError()) {
            log.error("Spring 异常映射到服务端错误码 status={} code={}", status.value(), errorCode.code(), ex);
        } else {
            log.warn("请求被拒绝 status={} code={} type={}", status.value(), errorCode.code(),
                    ex.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).body(Result.failure(errorCode));
    }

    /** 兜底：任何未预期异常统一返回 {@code 500 INTERNAL_ERROR}，只回脱敏文案 + trace_id。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception ex) {
        log.error("未捕获异常，已按 INTERNAL_ERROR 返回 code={}", ErrorCode.INTERNAL_ERROR.code(), ex);
        return ResponseEntity.status(resolveStatus(ErrorCode.INTERNAL_ERROR))
                .body(Result.failure(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<Result<Void>> validationFailure(String detail) {
        String message = (detail == null || detail.isBlank())
                ? ErrorCode.VALIDATION_ERROR.defaultMessage()
                : ErrorCode.VALIDATION_ERROR.defaultMessage() + " " + detail;
        log.warn("参数校验失败: {}", message);
        return ResponseEntity.status(resolveStatus(ErrorCode.VALIDATION_ERROR))
                .body(Result.failure(ErrorCode.VALIDATION_ERROR, message));
    }

    private static HttpStatus resolveStatus(ErrorCode errorCode) {
        HttpStatus status = errorCode.httpStatus();
        return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
