package com.scholarclaw.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 在 Spring Security 过滤器链里直接产出统一信封的错误响应（{@code 04} §2.3）。
 *
 * <p><b>为什么需要它：</b>认证/授权失败发生在 DispatcherServlet 之前，`@RestControllerAdvice`
 * 根本接不到；若不显式处理，客户端会收到 Spring 默认的 HTML 错误页或空 401，
 * 与"所有响应都必须是 `Result<T>` 信封"的契约冲突。
 */
@Component
public class SecurityErrorResponder {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponder.class);

    private final ObjectMapper objectMapper;

    public SecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 按错误码写出统一信封；响应体不包含异常堆栈或内部细节。 */
    public void respond(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        respond(response, errorCode, errorCode.defaultMessage());
    }

    public void respond(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        int status = errorCode.httpStatus() == null ? 500 : errorCode.httpStatus().value();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.failure(errorCode, message)));
        log.debug("安全过滤器链直接返回错误响应 code={} status={}", errorCode.code(), status);
    }
}
