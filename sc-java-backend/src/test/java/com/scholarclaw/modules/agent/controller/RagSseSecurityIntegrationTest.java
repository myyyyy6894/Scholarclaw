package com.scholarclaw.modules.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.RagQaRequest;
import com.scholarclaw.modules.agent.service.ChatMessageService;
import com.scholarclaw.modules.agent.service.ChatWindowService;
import com.scholarclaw.modules.agent.service.RagQaService;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.modules.course.service.CourseService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 端点在**完整 Spring Security 过滤链**下的集成测试（TASK-604 复核修复）。
 *
 * <p>覆盖：首次请求 JWT 鉴权、ASYNC 二次分派放行、正常/拒答/error 三种流，以及
 * `Accept: text/event-stream` 下 401/404/400/409 建流前错误必须是标准 JSON 信封。
 */
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        "sc.db.migration.enabled=false",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/scholarclaw_unit_test",
        "sc.dependencies.mysql.pool-probe=false",
        "sc.dependencies.redis.pool-probe=false",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE="
})
@AutoConfigureMockMvc
class RagSseSecurityIntegrationTest {

    private static final long USER_ID = 10001L;
    private static final String BODY = "{\"course_id\":\"2001\",\"query\":\"问题\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private RagQaService ragQaService;
    @MockBean
    private ChatMessageService chatMessageService;
    @MockBean
    private ChatWindowService chatWindowService;
    @MockBean
    private CourseService courseService;
    @MockBean
    private TokenRevocationService tokenRevocationService;

    private String token;

    @BeforeEach
    void setUp() {
        token = jwtService.issue(USER_ID, "e2e_student", "STUDENT").token();
        when(tokenRevocationService.check(anyLong(), any()))
                .thenReturn(new TokenRevocationService.TokenStatus(false, false));
    }

    @Test
    @DisplayName("完整安全链：正常流 citation → token → done，ASYNC 分派不重新鉴权")
    void normalStreamPassesFullSecurityChain() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class))).thenReturn(emitter(
                frame("citation", "{\"citations\":[]}"),
                frame("token", "{\"delta\":\"答案\",\"is_general_supplement\":false}"),
                frame("done", "{\"is_rejected\":false}")));

        String body = dispatch(performStream());
        assertThat(body).contains("event:citation").contains("event:token").contains("event:done");
    }

    @Test
    @DisplayName("完整安全链：拒答流")
    void rejectionStreamPassesFullSecurityChain() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class))).thenReturn(emitter(
                frame("rejection_notice",
                        "{\"notice_text\":\"未找到相关内容\",\"has_general_supplement\":true}"),
                frame("done", "{\"is_rejected\":true}")));

        String body = dispatch(performStream());
        assertThat(body).contains("event:rejection_notice").contains("event:done");
    }

    @Test
    @DisplayName("完整安全链：error 流")
    void errorStreamPassesFullSecurityChain() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class))).thenReturn(emitter(
                frame("error", "{\"code\":\"E-RAG-03\",\"message\":\"上下文不可用\","
                        + "\"trace_id\":\"tr_0123456789ab\"}")));

        String body = dispatch(performStream());
        assertThat(body).contains("event:error").contains("E-RAG-03");
    }

    @Test
    @DisplayName("未登录请求仍返回 401 JSON")
    void unauthenticatedReturnsJson() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"AUTH_UNAUTHORIZED\"");
    }

    @Test
    @DisplayName("Accept: text/event-stream 下 404 跨课程返回 JSON 信封")
    void crossCourseReturnsJson() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        assertPreStreamError(404, "RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("Accept: text/event-stream 下无 READY 课件返回 400 JSON")
    void noReadyDocsReturnsJson() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.COURSE_NO_READY_DOCS));
        assertPreStreamError(400, "COURSE_NO_READY_DOCS");
    }

    @Test
    @DisplayName("Accept: text/event-stream 下模型配置缺失返回 400 JSON")
    void missingModelConfigReturnsJson() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.E_SYS_01));
        assertPreStreamError(400, "E-SYS-01");
    }

    @Test
    @DisplayName("Accept: text/event-stream 下课程重建返回 409 JSON")
    void rebuildingReturnsJson() throws Exception {
        when(ragQaService.stream(eq(USER_ID), any(RagQaRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.COURSE_INDEX_REBUILDING));
        assertPreStreamError(409, "COURSE_INDEX_REBUILDING");
    }

    @Test
    @DisplayName("Accept: text/event-stream 下请求体校验失败返回 400 JSON")
    void validationErrorReturnsJson() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":\"2001\",\"query\":\"   \"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"VALIDATION_ERROR\"");
    }

    private MvcResult performStream() throws Exception {
        return mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String dispatch(MvcResult started) throws Exception {
        MvcResult result = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void assertPreStreamError(int status, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"" + code + "\"");
    }

    private static SseEmitter.SseEventBuilder frame(String event, String data) {
        return SseEmitter.event().name(event).data(data, MediaType.TEXT_PLAIN);
    }

    private static SseEmitter emitter(SseEmitter.SseEventBuilder... frames) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            for (SseEmitter.SseEventBuilder frame : frames) {
                emitter.send(frame);
            }
            emitter.complete();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return emitter;
    }
}
