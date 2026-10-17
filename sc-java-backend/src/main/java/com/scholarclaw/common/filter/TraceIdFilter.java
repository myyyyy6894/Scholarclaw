package com.scholarclaw.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入口追踪过滤器（{@code 02} §11、{@code 04} §2.7.1）。
 *
 * <p>职责：为每个请求确定 {@code trace_id}（接受合法的 {@code X-Trace-Id} 入站值，否则重新生成），
 * 写入 MDC 供日志输出，并在响应头原样返回。必须在其他过滤器之前执行，保证后续过滤器
 * （如内部令牌校验）产生的错误响应也带有追踪号。
 *
 * <p>MDC 在 {@code finally} 中清理：Servlet 容器与线程池复用线程，不清理会导致追踪号串线。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String inbound = request.getHeader(TraceContext.HEADER_NAME);
        String traceId = TraceContext.isAcceptableInboundTraceId(inbound) ? inbound : TraceContext.generate();
        Object userIdAttribute = request.getAttribute(TraceContext.REQUEST_ATTRIBUTE_USER_ID);
        Long userId = userIdAttribute instanceof Long value ? value : null;

        TraceContext.bind(traceId, userId);
        response.setHeader(TraceContext.HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
