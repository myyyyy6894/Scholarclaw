package com.scholarclaw.common.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.config.ScTimeZone;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 内部接口鉴权过滤器断言（{@code NFR-SEC-04}、{@code 04} §2.7.1）。
 *
 * <p>核心红线：{@code /internal/**} 在缺失或错误令牌时必须是 403 且不进入链路；
 * 令牌未注入时也必须失败关闭，而不是"跳过校验"。
 */
class InternalTokenAuthFilterTest {

    private static final String CONFIGURED_TOKEN = "test-internal-token-4a7f1c9d";

    private final ObjectMapper objectMapper =
            JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(), ScTimeZone.zoneId()).build();

    @Test
    @DisplayName("非 /internal/** 路径不校验令牌，正常放行")
    void skipsNonInternalPaths() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("令牌正确时放行内部接口")
    void allowsRequestWithCorrectToken() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("缺失或错误令牌返回 403 AUTH_FORBIDDEN 且不进入链路")
    void rejectsMissingOrWrongToken() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);

        for (String token : new String[]{null, "", "wrong-token", CONFIGURED_TOKEN + "x"}) {
            MockHttpServletRequest request = internalRequest();
            if (token != null) {
                request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).as("令牌 %s 不应被放行", token).isNull();
            assertThat(response.getStatus()).as("令牌 %s 应返回 403", token).isEqualTo(403);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"AUTH_FORBIDDEN\"");
        }
    }

    @Test
    @DisplayName("配置令牌未注入时失败关闭：即使请求带了看似合法的令牌也一律拒绝")
    void failsClosedWhenConfiguredTokenIsBlank() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter("  ", objectMapper);
        MockHttpServletRequest request = internalRequest();
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("拒绝响应体不得回显任何令牌内容")
    void rejectionBodyNeverEchoesToken() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = internalRequest();
        String attempted = "attempted-secret-value";
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, attempted);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(attempted)
                .doesNotContain(CONFIGURED_TOKEN);
    }

    private static MockHttpServletRequest internalRequest() {
        return new MockHttpServletRequest("GET", "/internal/api/v1/health");
    }
}
