package com.scholarclaw.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.common.filter.TraceIdFilter;
import com.scholarclaw.common.response.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局异常映射断言（{@code 04} §2.3.2）。
 *
 * <p>最关键的一条：未捕获异常必须返回 500 + 脱敏文案，绝不把异常原文（可能含 JDBC 连接串、
 * 密码、内部主机名）回传给客户端。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper =
                JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(), ScTimeZone.zoneId()).build();
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("业务异常按错误码字典映射 HTTP 状态与 code")
    void businessExceptionMapsToDocumentedStatusAndCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/business")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"E-SYS-02\"");
        assertThat(body).contains("\"data\":null");
    }

    @Test
    @DisplayName("未捕获异常返回 500 INTERNAL_ERROR，且绝不回传异常原文")
    void unexpectedExceptionIsSanitised() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/unexpected")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(body).contains(ErrorCode.INTERNAL_ERROR.defaultMessage());
        assertThat(body)
                .doesNotContain("jdbc:")
                .doesNotContain("topsecret")
                .doesNotContain("IllegalStateException")
                .doesNotContain("sc-mysql");
    }

    @Test
    @DisplayName("请求体校验失败返回 400 VALIDATION_ERROR 并带字段级提示")
    void requestBodyValidationFailureReturnsValidationError() throws Exception {
        MvcResult result = mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"VALIDATION_ERROR\"");
        assertThat(body).contains("username");
    }

    @Test
    @DisplayName("请求体非法 JSON 归类为参数校验失败而非 500")
    void malformedJsonIsTreatedAsValidationFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":\"VALIDATION_ERROR\"");
    }

    @Test
    @DisplayName("成功响应的 trace_id 与响应头 X-Trace-Id 一致")
    void successResponseSharesTraceIdWithHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/ok")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String header = result.getResponse().getHeader(TraceContext.HEADER_NAME);
        assertThat(header).matches("tr_[0-9a-f]{12}");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"OK\"")
                .contains("\"trace_id\":\"" + header + "\"");
    }

    /** 仅用于测试的探针控制器：不承载任何业务语义。 */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/ok")
        Result<Map<String, String>> ok() {
            return Result.ok(Map.of("course_id", "2001"));
        }

        @GetMapping("/business")
        Result<Void> business() {
            throw new BusinessException(ErrorCode.E_SYS_02);
        }

        @GetMapping("/unexpected")
        Result<Void> unexpected() {
            throw new IllegalStateException("内部细节 jdbc:mysql://sc-mysql:3306/sc?password=topsecret");
        }

        @PostMapping("/validate")
        Result<Void> validate(@Valid @RequestBody ProbeRequest request) {
            return Result.ok(null);
        }
    }

    record ProbeRequest(@NotBlank String username) {
    }

    @Test
    @DisplayName("未知路径（NoResourceFoundException）：404 信封，绝不能报成 500")
    void unknownPathIsNotFoundNotServerError() throws Exception {
        // TASK-104 曾在真实容器里暴露过"未知路径返回 500"：catch-all 的 Exception 处理器
        // 吞掉了 Spring 的 NoResourceFoundException（它同时是 ServletException 与 ErrorResponse）。
        // TASK-202 起未认证请求会先被安全链拦成 401，因此这条 MVC 语义单独在这里守住。
        var response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/does-not-exist"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"code\":\"RESOURCE_NOT_FOUND\"");
    }
}
