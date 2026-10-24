package com.scholarclaw.modules.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * SSE 端点的建流前异常响应（TASK-604 复核修复）。
 *
 * <p>为什么不能只依赖全局 {@code @RestControllerAdvice}：`API-RAG-001` 的 `Accept` 被
 * {@code 04} 锁定为 `text/event-stream`。异常处理器返回 {@code ResponseEntity<Result<T>>} 时，
 * Spring 的内容协商会因“可生产 application/json、但客户端只接受 text/event-stream”抛出
 * {@code HttpMediaTypeNotAcceptableException}，最终变成空 500。
 *
 * <p>本解析器只在 SSE 路径生效，直接以 UTF-8 JSON 写响应，绕过内容协商；其他接口继续走
 * 全局异常处理器。
 */
@Component
public class SseExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SseExceptionResolver.class);
    private static final String SSE_QA_PATH = "/api/v1/rag/qa/stream";

    private final ObjectMapper objectMapper;

    public SseExceptionResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (!isSseQaRequest(request) || response.isCommitted()) {
            return null;
        }
        ErrorCode errorCode;
        String message;
        Object data = null;
        if (ex instanceof BusinessException business) {
            errorCode = business.getErrorCode();
            message = business.getMessage();
            data = business.getData();
        } else if (ex instanceof MethodArgumentNotValidException validation) {
            errorCode = ErrorCode.VALIDATION_ERROR;
            message = ErrorCode.VALIDATION_ERROR.defaultMessage() + " "
                    + validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.joining("; "));
        } else if (ex instanceof HandlerMethodValidationException validation) {
            errorCode = ErrorCode.VALIDATION_ERROR;
            message = ErrorCode.VALIDATION_ERROR.defaultMessage();
        } else if (ex instanceof ErrorResponse errorResponse
                && errorResponse.getStatusCode().is4xxClientError()) {
            errorCode = ErrorCode.VALIDATION_ERROR;
            message = ErrorCode.VALIDATION_ERROR.defaultMessage();
        } else {
            errorCode = ErrorCode.INTERNAL_ERROR;
            message = ErrorCode.INTERNAL_ERROR.defaultMessage();
            log.error("SSE 建流前未捕获异常", ex);
        }
        int status = errorCode.httpStatus() == null ? 500 : errorCode.httpStatus().value();
        if (status >= 500) {
            log.error("SSE 建流前失败 code={} status={}", errorCode.code(), status, ex);
        } else {
            log.warn("SSE 建流前拒绝 code={} status={}", errorCode.code(), status);
        }
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.failure(errorCode, message, data)));
        } catch (IOException writeError) {
            log.error("SSE 错误响应写出失败 status={}", status, writeError);
            return null;
        }
        return new ModelAndView();
    }

    private static boolean isSseQaRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.endsWith(SSE_QA_PATH);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
