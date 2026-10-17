package com.scholarclaw.common.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口鉴权过滤器（{@code NFR-SEC-04}、{@code 04} §2.7.1）。
 *
 * <p>只作用于 {@code /internal/**}；校验 {@code X-Internal-Token} 是否等于
 * {@code SC_INTERNAL_SERVICE_TOKEN}，使用 {@link MessageDigest#isEqual} 做常量时间比较，
 * 不匹配立即返回 {@code 403 AUTH_FORBIDDEN}。
 *
 * <p><b>失败关闭（fail-closed）：</b>若配置令牌为空（环境变量未注入），不是"跳过校验"，
 * 而是拒绝所有 {@code /internal/**} 请求——否则部署时漏注入变量就等于内部接口完全裸奔。
 *
 * <p>本过滤器不校验、不解析任何用户身份；{@code X-User-Id} 的信任边界处理见 {@code 04} §2.7.1：
 * 外部请求携带的 {@code X-User-Id} 必须被忽略/剥离，该逻辑在 TASK-202 的认证过滤器中落地。
 *
 * <p>安全日志：只记录拒绝原因与来源路径，<b>绝不</b>记录令牌值（明文比对内容）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalTokenAuthFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenAuthFilter.class);
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private final String configuredToken;
    private final ObjectMapper objectMapper;

    public InternalTokenAuthFilter(@Value("${sc.internal.token:}") String configuredToken,
                                   ObjectMapper objectMapper) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !resolvePath(request).startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (configuredToken.isBlank()) {
            log.error("内部接口鉴权令牌未注入（SC_INTERNAL_SERVICE_TOKEN 为空），按失败关闭拒绝访问 path={}",
                    resolvePath(request));
            reject(response);
            return;
        }

        String provided = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (provided == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("内部接口鉴权失败，已拒绝访问 path={} method={}", resolvePath(request), request.getMethod());
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.failure(ErrorCode.AUTH_FORBIDDEN)));
    }

    private static String resolvePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
