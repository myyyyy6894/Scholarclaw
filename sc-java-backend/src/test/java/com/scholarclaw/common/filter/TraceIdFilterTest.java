package com.scholarclaw.common.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 入口追踪与信任边界过滤器断言（{@code 02} §11、{@code 04} §2.7.1、§6.1.2、§8 安全测试要点③）。
 *
 * <p>两条红线：
 * <ul>
 *   <li>{@code trace_id} 的生成、透传与 MDC 清理；</li>
 *   <li><b>外部伪造的 {@code X-User-Id} / {@code X-Internal-Token} 必须在入口被剥离</b>，
 *       且 {@code X-Forwarded-For} 之类的头不能反过来影响身份判定。</li>
 * </ul>
 */
class TraceIdFilterTest {

    private static final String EXTERNAL_PATH = "/api/v1/courses";
    private static final String INTERNAL_PATH = "/internal/api/v1/docs/50001/parse-context";

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("未携带 X-Trace-Id 时生成合法追踪号并写入响应头")
    void generatesTraceIdWhenInboundHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(traceIdInChain));

        String header = response.getHeader(TraceContext.HEADER_NAME);
        assertThat(header).matches("tr_[0-9a-f]{12}");
        assertThat(traceIdInChain.get()).isEqualTo(header);
    }

    @Test
    @DisplayName("合法入站追踪号被原样透传，保证 Web 与 IM 调用链可串联")
    void propagatesAcceptableInboundTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        request.addHeader(TraceContext.HEADER_NAME, "tr_0123456789ab");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo("tr_0123456789ab");
    }

    @Test
    @DisplayName("非法入站追踪号被丢弃并重新生成，避免脏值污染日志与调用链")
    void regeneratesWhenInboundTraceIdIsMalformed() throws Exception {
        for (String malformed : new String[] {"abc", "tr_ZZZZZZZZZZZZ", "tr_0123456789", ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
            request.addHeader(TraceContext.HEADER_NAME, malformed);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

            assertThat(response.getHeader(TraceContext.HEADER_NAME))
                    .as("入站值 %s 应被丢弃", malformed)
                    .matches("tr_[0-9a-f]{12}")
                    .isNotEqualTo(malformed);
        }
    }

    @Test
    @DisplayName("入口只绑定追踪号，user_id 固定为占位符；MDC 在链路结束后清理")
    void bindsPlaceholderUserIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();
        AtomicReference<String> userIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            traceIdInChain.set(org.slf4j.MDC.get(TraceContext.MDC_TRACE_ID));
            userIdInChain.set(org.slf4j.MDC.get(TraceContext.MDC_USER_ID));
        });

        assertThat(traceIdInChain.get()).matches("tr_[0-9a-f]{12}");
        assertThat(userIdInChain.get()).isEqualTo(TraceContext.ABSENT_USER_ID);
        assertThat(TraceContext.currentTraceId()).isNull();
        assertThat(org.slf4j.MDC.get(TraceContext.MDC_USER_ID)).isNull();
    }

    @Test
    @DisplayName("认证过滤器验签成功后绑定的真实 user_id 会覆盖占位符，并进入日志事件字段")
    void authenticatedUserIdOverwritesPlaceholderInLogEvents() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(TraceIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, (req, res) -> {
                logger.info("收到请求，身份尚未验证");
                // 认证过滤器（TASK-202）验签成功后的动作
                TraceContext.bindUserId(10001L);
                logger.info("验签通过");
            });
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .as("验签前的日志必须显示占位符，不能伪造身份")
                .containsEntry(TraceContext.MDC_USER_ID, TraceContext.ABSENT_USER_ID);
        assertThat(appender.list.get(1).getMDCPropertyMap())
                .as("验签后的日志必须带真实用户 ID，而不是一直显示 -")
                .containsEntry(TraceContext.MDC_USER_ID, "10001");
    }

    @Test
    @DisplayName("跨请求不串用户：前一次请求的真实 user_id 不会漏进下一次请求的日志")
    void userIdDoesNotLeakAcrossRequests() throws Exception {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, (req, res) -> TraceContext.bindUserId(10001L));

        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        AtomicReference<String> userIdInSecondRequest = new AtomicReference<>();
        filter.doFilter(secondRequest, secondResponse, (req, res) ->
                userIdInSecondRequest.set(org.slf4j.MDC.get(TraceContext.MDC_USER_ID)));

        assertThat(userIdInSecondRequest.get()).isEqualTo(TraceContext.ABSENT_USER_ID);
    }

    @Test
    @DisplayName("链路内抛异常时 MDC 仍被清理")
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("链路异常");
            });
        } catch (Exception ignored) {
            // 本用例只关心 MDC 是否被清理，异常本身由上层容器处理
        }

        assertThat(TraceContext.currentTraceId()).isNull();
    }

    // ------------------------------------------------------------------
    // 外部伪造身份头的剥离（04 §6.1.2、§8 安全测试要点③）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("外部请求伪造的 X-User-Id / X-Internal-Token 在入口被剥离，下游拿不到")
    void stripsForgedIdentityHeadersOnExternalPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", EXTERNAL_PATH);
        request.addHeader(TraceContext.USER_ID_HEADER, "99999");
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, "forged-token");
        request.addHeader("X-Trace-Id", "tr_0123456789ab");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<List<String>> headerNamesSeen = new AtomicReference<>();
        AtomicReference<String> userIdSeen = new AtomicReference<>();
        AtomicReference<String> internalTokenSeen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletRequest downstreamRequest = (HttpServletRequest) req;
            userIdSeen.set(downstreamRequest.getHeader(TraceContext.USER_ID_HEADER));
            internalTokenSeen.set(downstreamRequest.getHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER));
            headerNamesSeen.set(Collections.list(downstreamRequest.getHeaderNames()));
        });

        assertThat(userIdSeen.get()).isNull();
        assertThat(internalTokenSeen.get()).isNull();
        assertThat(headerNamesSeen.get())
                .doesNotContain(TraceContext.USER_ID_HEADER, InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER);
        assertThat(headerNamesSeen.get())
                .as("非身份类头必须保留，剥离范围不能扩大")
                .contains(TraceContext.HEADER_NAME);
    }

    @Test
    @DisplayName("剥离对大小写不敏感：x-user-id 同样拿不到")
    void stripsForgedIdentityHeadersCaseInsensitively() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        request.addHeader("x-user-id", "99999");
        request.addHeader("x-Internal-Token", "forged-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> remainingHeaderNames = new ArrayList<>();

        filter.doFilter(request, response, (req, res) ->
                remainingHeaderNames.addAll(Collections.list(((HttpServletRequest) req).getHeaderNames())));

        assertThat(remainingHeaderNames)
                .noneMatch(name -> name.equalsIgnoreCase(TraceContext.USER_ID_HEADER))
                .noneMatch(name -> name.equalsIgnoreCase(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER));
    }

    @Test
    @DisplayName("伪造 X-User-Id 无法影响日志身份：MDC 仍是占位符")
    void forgedUserIdNeverReachesMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", EXTERNAL_PATH);
        request.addHeader(TraceContext.USER_ID_HEADER, "99999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> userIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                userIdInChain.set(org.slf4j.MDC.get(TraceContext.MDC_USER_ID)));

        assertThat(userIdInChain.get()).isEqualTo(TraceContext.ABSENT_USER_ID);
    }

    @Test
    @DisplayName("内部路径不剥离：X-Internal-Token 必须留给内部鉴权过滤器，X-User-Id 是合法内部身份载体")
    void keepsIdentityHeadersOnInternalPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", INTERNAL_PATH);
        request.addHeader(TraceContext.USER_ID_HEADER, "10001");
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, "internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> userIdSeen = new AtomicReference<>();
        AtomicReference<String> tokenSeen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletRequest downstreamRequest = (HttpServletRequest) req;
            userIdSeen.set(downstreamRequest.getHeader(TraceContext.USER_ID_HEADER));
            tokenSeen.set(downstreamRequest.getHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER));
        });

        assertThat(userIdSeen.get()).isEqualTo("10001");
        assertThat(tokenSeen.get()).isEqualTo("internal-token");
    }

    private static FilterChain capturingChain(AtomicReference<String> sink) {
        return (req, res) -> sink.set(TraceContext.currentTraceId());
    }
}
