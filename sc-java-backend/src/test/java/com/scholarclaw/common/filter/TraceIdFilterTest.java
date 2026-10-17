package com.scholarclaw.common.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 入口追踪过滤器断言（{@code 02} §11、{@code 04} §2.7.1）。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("未携带 X-Trace-Id 时生成合法追踪号并写入响应头")
    void generatesTraceIdWhenInboundHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
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
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        request.addHeader(TraceContext.HEADER_NAME, "tr_0123456789ab");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo("tr_0123456789ab");
    }

    @Test
    @DisplayName("非法入站追踪号被丢弃并重新生成，避免脏值污染日志与调用链")
    void regeneratesWhenInboundTraceIdIsMalformed() throws Exception {
        for (String malformed : new String[]{"abc", "tr_ZZZZZZZZZZZZ", "tr_0123456789", ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
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
    @DisplayName("MDC 在链路内可见、在链路结束后必须清理，防止线程池复用串线")
    void bindsAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        request.setAttribute(TraceContext.REQUEST_ATTRIBUTE_USER_ID, 10001L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();
        AtomicReference<String> userIdInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            traceIdInChain.set(org.slf4j.MDC.get(TraceContext.MDC_TRACE_ID));
            userIdInChain.set(org.slf4j.MDC.get(TraceContext.MDC_USER_ID));
        });

        assertThat(traceIdInChain.get()).matches("tr_[0-9a-f]{12}");
        assertThat(userIdInChain.get()).isEqualTo("10001");
        assertThat(TraceContext.currentTraceId()).isNull();
        assertThat(org.slf4j.MDC.get(TraceContext.MDC_USER_ID)).isNull();
    }

    @Test
    @DisplayName("链路内抛异常时 MDC 仍被清理")
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
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

    private static FilterChain capturingChain(AtomicReference<String> sink) {
        return (req, res) -> sink.set(TraceContext.currentTraceId());
    }
}
