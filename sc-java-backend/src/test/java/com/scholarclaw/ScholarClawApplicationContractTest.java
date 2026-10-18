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

/**
 * 完整 Spring 上下文下的对外契约测试。
 *
 * <p><b>为什么需要这一层：</b>已有的断言都用 {@code MockMvcBuilders.standaloneSetup} 构造，
 * 它不加载 Spring Boot 的静态资源处理与 MVC 自动配置。于是「访问不存在的路径」在容器里实测返回
 * {@code 500 INTERNAL_ERROR}——因为 {@code NoResourceFoundException} 被兜底的
 * {@code @ExceptionHandler(Exception.class)} 接住了，而 standalone 测试根本不产生这个异常。
 * 这类"只有真实装配才暴露"的缺陷必须由本类守住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScholarClawApplicationContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未知路径返回 404 + RESOURCE_NOT_FOUND 信封，而不是 500")
    void unknownPathReturnsNotFoundEnvelope() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/does-not-exist")).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(result.getResponse().getStatus())
                .as("不存在的路径属于客户端错误，绝不能报成服务器错误")
                .isEqualTo(404);
        assertThat(body).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertThat(body).contains("\"data\":null");
        assertThat(body).doesNotContain("NoResourceFoundException");
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
}
