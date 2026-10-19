package com.scholarclaw.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入口追踪与信任边界过滤器（{@code 02} §11、{@code 04} §2.7.1 与 §6.1.2）。
 *
 * <p><b>职责一：追踪</b>——为每个请求确定 {@code trace_id}（接受合法的 {@code X-Trace-Id} 入站值，
 * 否则重新生成），写入 MDC 供日志输出，并在响应头原样返回。必须在其他过滤器之前执行，
 * 保证后续过滤器（如内部令牌校验）产生的错误响应也带有追踪号。
 *
 * <p><b>职责二：剥离伪造身份头</b>——按 {@code 04} §6.1.2 与 §8 安全测试要点③，外部请求
 * （非 {@code /internal/**}）携带的 {@code X-User-Id} 与 {@code X-Internal-Token} 一律在此剥离，
 * 使下游任何代码（控制器、日志、转发）都拿不到这两个头，只能依赖验签后的权威身份。
 * 剥离用 {@link HttpServletRequestWrapper} 实现，而不是"记得别读"的约定——
 * 约定会被后续新增的代码无意破坏，包装器不会。
 *
 * <p><b>为什么不对 {@code /internal/**} 剥离：</b>内部调用的 {@code X-Internal-Token} 正是
 * {@link InternalTokenAuthFilter} 的校验对象，{@code X-User-Id} 也是 {@code 04} §6.1.2 规定的
 * 合法内部身份载体（仅在令牌校验通过后才可信）。本过滤器排在内部鉴权过滤器之前，
 * 若一并剥离会让内部接口全部失效。
 *
 * <p>MDC 在 {@code finally} 中清理：Servlet 容器与线程池复用线程，不清理会导致追踪号串线。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    /** 外部请求不得携带的身份类请求头（{@code 04} §6.1.2）。 */
    private static final Set<String> UNTRUSTED_EXTERNAL_HEADERS =
            Set.of(TraceContext.USER_ID_HEADER, InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String inbound = request.getHeader(TraceContext.HEADER_NAME);
        String traceId = TraceContext.isAcceptableInboundTraceId(inbound) ? inbound : TraceContext.generate();

        // 入口只绑定 trace_id：此时身份尚未验证，user_id 固定为占位符。
        // 真实用户 ID 由认证过滤器（TASK-202）验签成功后调用 TraceContext.bindUserId 覆盖。
        TraceContext.bind(traceId);
        response.setHeader(TraceContext.HEADER_NAME, traceId);

        HttpServletRequest downstream = stripUntrustedIdentityHeaders(request);
        try {
            filterChain.doFilter(downstream, response);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 外部请求返回剥离了身份头的包装请求；内部请求原样返回。
     *
     * <p>剥离动作只记 {@code debug}：伪造这两个头零成本，若记 {@code warn} 等于给攻击者
     * 一个廉价刷日志的入口（{@code 04} §8 的安全测试只要求"被剥离忽略"）。
     */
    private static HttpServletRequest stripUntrustedIdentityHeaders(HttpServletRequest request) {
        if (resolvePath(request).startsWith(INTERNAL_PATH_PREFIX)) {
            return request;
        }
        if (UNTRUSTED_EXTERNAL_HEADERS.stream().noneMatch(name -> request.getHeader(name) != null)) {
            return request;
        }
        log.debug("外部请求携带身份类请求头，已按 04 §6.1.2 在入口剥离 path={}", resolvePath(request));
        return new UntrustedIdentityHeadersStrippedRequest(request);
    }

    private static String resolvePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path == null ? "" : path;
    }

    /**
     * 隐藏身份类请求头的只读包装器（大小写不敏感：HTTP 头名不区分大小写，
     * 只按文档里的写法匹配会被 {@code x-user-id} 绕过）。
     */
    private static final class UntrustedIdentityHeadersStrippedRequest extends HttpServletRequestWrapper {

        UntrustedIdentityHeadersStrippedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isStripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isStripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            if (names == null) {
                return Collections.emptyEnumeration();
            }
            List<String> visible = new ArrayList<>();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (!isStripped(name)) {
                    visible.add(name);
                }
            }
            return Collections.enumeration(visible);
        }

        private static boolean isStripped(String name) {
            return name != null && UNTRUSTED_EXTERNAL_HEADERS.stream().anyMatch(name::equalsIgnoreCase);
        }
    }
}
