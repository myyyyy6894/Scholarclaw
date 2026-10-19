package com.scholarclaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 完整 Spring 上下文下的对外契约测试。
 *
 * <p><b>为什么需要这一层：</b>已有的断言都用 {@code MockMvcBuilders.standaloneSetup} 构造，
 * 它不加载 Spring Boot 的静态资源处理与 MVC 自动配置。于是「访问不存在的路径」在容器里实测返回
 * {@code 500 INTERNAL_ERROR}——因为 {@code NoResourceFoundException} 被兜底的
 * {@code @ExceptionHandler(Exception.class)} 接住了，而 standalone 测试根本不产生这个异常。
 * 这类"只有真实装配才暴露"的缺陷必须由本类守住。
 */
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        // 本测试只验证 Web/信封契约，不连真库：关掉启动期迁移，并给一个语法合法但不会被连接的
        // JDBC URL（Hikari 懒连接，上下文启动不会尝试建连）。
        "sc.db.migration.enabled=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/scholarclaw_unit_test",
        // 连接池级 MySQL 探针需要真库，本测试没有：关掉后回退到"未注入主机即不注册"的语义
        "sc.dependencies.mysql.pool-probe=false",
        "sc.dependencies.redis.pool-probe=false",
        // JwtService 失败关闭：密钥缺失会拒绝启动，测试给一个 >=32 字节的值
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class ScholarClawApplicationContractTest {

    private static final String INTERNAL_HEALTH_PATH = "/internal/api/v1/health";
    private static final String INTERNAL_TOKEN = "test-internal-token-value";
    private static final String REMOTE_ADDR = "10.0.0.7";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor remoteAddr(String addr) {
        return request -> {
            request.setRemoteAddr(addr);
            return request;
        };
    }

    @Test
    @DisplayName("未知路径返回 404 + RESOURCE_NOT_FOUND 信封，而不是 500")
    void unknownPathWithoutTokenReturnsAuthEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // TASK-202 起安全链先于 MVC：未认证请求统一 401（不通过 404/403 差异暴露路径是否存在）。
        // "未认证也不能 500 / 不能返回 HTML 错误页"这一条仍然被断言；
        // "未知路径必须 404 而不是 500"的 MVC 语义改由 GlobalExceptionHandlerTest 守护。
        assertThat(result.getResponse().getStatus())
                .as("未认证的未知路径应返回 401 信封，绝不能报成服务器错误")
                .isEqualTo(401);
        assertThat(body).contains("\"code\":\"AUTH_UNAUTHORIZED\"")
                .contains("\"data\":null")
                .contains("\"trace_id\":\"tr_")
                .doesNotContain("NoResourceFoundException");
    }

    @Test
    @DisplayName("方法不允许等 MVC 级拒绝同样不得报成 500")
    void unsupportedRequestIsNotReportedAsServerError() throws Exception {
        int status = mockMvc.perform(post("/api/v1/does-not-exist"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isLessThan(500);
    }

    @Test
    @DisplayName("响应时间戳为毫秒精度且带 +08:00 偏移（04 §2.5.2）")
    void responseTimestampUsesMillisecondPrecision() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).containsPattern(
                "\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?\\+08:00\"");
    }

    @Test
    @DisplayName("连错误响应也必须回写 X-Trace-Id 响应头（04 §2.7.1 出方向必填）")
    void errorResponseCarriesTraceIdHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        String header = result.getResponse().getHeader("X-Trace-Id");
        assertThat(header).matches("tr_[0-9a-f]{12}");
        assertThat(body).contains("\"trace_id\":\"" + header + "\"");
    }

    @Test
    @DisplayName("响应体恒为五字段信封，不因异常路径而变形")
    void errorEnvelopeKeepsDocumentedShape() throws Exception {
        String body = mockMvc.perform(get("/api/v1/does-not-exist"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":").contains("\"message\":").contains("\"data\":")
                .contains("\"trace_id\":").contains("\"timestamp\":");
    }

    @Test
    @DisplayName("内部健康检查：环回探针免令牌，返回 service/status/dependencies（04 §4.9.8）")
    void internalHealthFromLoopbackNeedsNoToken() throws Exception {
        MvcResult result = mockMvc.perform(get(INTERNAL_HEALTH_PATH)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("\"code\":\"OK\"");
        assertThat(body).contains("\"service\":\"sc-java-backend\"");
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"dependencies\":");
        assertThat(body).contains("\"message\":\"Healthy\"");
    }

    @Test
    @DisplayName("内部健康检查：非环回来源缺令牌 → 403，带正确令牌 → 200")
    void internalHealthFromRemoteRequiresToken() throws Exception {
        int withoutToken = mockMvc.perform(get(INTERNAL_HEALTH_PATH).with(remoteAddr(REMOTE_ADDR)))
                .andReturn().getResponse().getStatus();
        int withToken = mockMvc.perform(get(INTERNAL_HEALTH_PATH)
                        .with(remoteAddr(REMOTE_ADDR))
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andReturn().getResponse().getStatus();

        assertThat(withoutToken).isEqualTo(403);
        assertThat(withToken).isEqualTo(200);
    }

    @Test
    @DisplayName("环回豁免不覆盖其他内部接口：/internal/ai/** 即使来自环回也需令牌")
    void loopbackExemptionDoesNotCoverOtherInternalPaths() throws Exception {
        int status = mockMvc.perform(get("/internal/ai/v1/model/probe")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }
}
