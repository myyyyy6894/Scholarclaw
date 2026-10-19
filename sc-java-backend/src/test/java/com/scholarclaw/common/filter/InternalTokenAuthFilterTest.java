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
 * 内部接口鉴权过滤器断言（{@code NFR-SEC-04}、{@code 04} §2.7.1 / §6.1、{@code API-CONF-03}）。
 *
 * <p>核心红线：业务内部接口在缺失或错误令牌时必须是 403 且不进入链路；令牌未注入时必须失败关闭。
 *
 * <p>健康检查的**环回例外**（TASK-105 引入）另有专门断言，其中最关键的一条是
 * **例外范围仅限健康检查这一个路径**——这是 TASK-105 验收条件明确要求的。
 */
class InternalTokenAuthFilterTest {

    private static final String CONFIGURED_TOKEN = "test-internal-token-4a7f1c9d";
    private static final String BUSINESS_INTERNAL_PATH = "/internal/api/v1/docs/50001/parse-context";
    private static final String HEALTH_PATH = "/internal/api/v1/health";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String REMOTE = "10.0.0.7";

    private final ObjectMapper objectMapper =
            JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(), ScTimeZone.zoneId()).build();

    @Test
    @DisplayName("非 /internal/** 路径不校验令牌，正常放行")
    void skipsNonInternalPaths() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = request("GET", "/api/v1/courses", REMOTE);
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
        MockHttpServletRequest request = request("GET", BUSINESS_INTERNAL_PATH, REMOTE);
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, CONFIGURED_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("业务内部接口：缺失或错误令牌返回 403 AUTH_FORBIDDEN 且不进入链路")
    void rejectsMissingOrWrongTokenOnBusinessPath() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);

        for (String token : new String[] {null, "", "wrong-token", CONFIGURED_TOKEN + "x"}) {
            MockHttpServletRequest request = request("GET", BUSINESS_INTERNAL_PATH, REMOTE);
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
        MockHttpServletRequest request = request("GET", BUSINESS_INTERNAL_PATH, REMOTE);
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
        MockHttpServletRequest request = request("GET", BUSINESS_INTERNAL_PATH, REMOTE);
        String attempted = "attempted-secret-value";
        request.addHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, attempted);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(attempted)
                .doesNotContain(CONFIGURED_TOKEN);
    }

    // ------------------------------------------------------------------
    // 健康检查环回例外（04 API-CONF-03）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("健康检查：来自环回地址时免令牌放行（IPv4 / IPv6 / IPv4-mapped 三种写法都认）")
    void healthProbeFromLoopbackIsAllowed() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);

        for (String remoteAddr : new String[] {"127.0.0.1", "::1", "::ffff:127.0.0.1", "127.0.0.5"}) {
            MockHttpServletRequest request = request("GET", HEALTH_PATH, remoteAddr);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).as("来源 %s 应被放行", remoteAddr).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("健康检查：来自非环回地址时仍必须携带合法令牌")
    void healthProbeFromRemoteStillRequiresToken() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = request("GET", HEALTH_PATH, REMOTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("环回例外的范围仅限健康检查路径：其他内部接口即使来自环回也必须带令牌")
    void loopbackExemptionIsLimitedToHealthPath() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);

        for (String path : new String[] {
                BUSINESS_INTERNAL_PATH,
                "/internal/api/v1/rag/parent-chunks/batch-get",
                "/internal/ai/v1/model/probe"}) {
            MockHttpServletRequest request = request("GET", path, LOOPBACK);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).as("路径 %s 不应获得环回豁免", path).isNull();
            assertThat(response.getStatus()).as("路径 %s 应返回 403", path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("不得依据可伪造的 X-Forwarded-For 获得环回豁免")
    void forwardedHeaderCannotGrantLoopbackExemption() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(CONFIGURED_TOKEN, objectMapper);
        MockHttpServletRequest request = request("GET", HEALTH_PATH, REMOTE);
        request.addHeader("X-Forwarded-For", LOOPBACK);
        request.addHeader("X-Real-IP", LOOPBACK);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("令牌未注入时，环回健康探针仍可放行（例外不依赖令牌配置）")
    void loopbackHealthProbeWorksWithoutConfiguredToken() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter("", objectMapper);
        MockHttpServletRequest request = request("GET", HEALTH_PATH, LOOPBACK);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String method, String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
