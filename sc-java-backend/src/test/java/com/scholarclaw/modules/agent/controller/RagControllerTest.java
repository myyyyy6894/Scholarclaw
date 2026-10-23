package com.scholarclaw.modules.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.agent.dto.ChatMessagePageVO;
import com.scholarclaw.modules.agent.dto.ChatMessageVO;
import com.scholarclaw.modules.agent.dto.CitationItemVO;
import com.scholarclaw.modules.agent.service.ChatMessageService;
import com.scholarclaw.modules.agent.service.ChatWindowService;
import com.scholarclaw.modules.agent.service.RagQaService;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RagControllerTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private RagQaService ragQaService;
    private ChatMessageService chatMessageService;
    private ChatWindowService chatWindowService;
    private CourseService courseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ragQaService = mock(RagQaService.class);
        chatMessageService = mock(ChatMessageService.class);
        chatWindowService = mock(ChatWindowService.class);
        courseService = mock(CourseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RagController(ragQaService, chatMessageService,
                                chatWindowService, courseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        when(courseService.getActiveCourse(eq(USER_ID), eq(COURSE_ID))).thenReturn(course);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new ScUserPrincipal(USER_ID, "zhangsan_cs", "STUDENT", "jti",
                                Instant.now().plusSeconds(60)), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("会话历史返回字符串 ID 与引用卡片")
    void messagesUseSnakeCaseAndStringIds() throws Exception {
        when(chatMessageService.page(eq(USER_ID), eq(COURSE_ID), eq(90010L), eq(20)))
                .thenReturn(new ChatMessagePageVO(List.of(new ChatMessageVO(
                        90011L, COURSE_ID, "WEB", "ASSISTANT", "KB_QA", "答案",
                        List.of(new CitationItemVO("pc_1", 50001L, "a.pdf", 1, 2, "5.1", "内容")),
                        false, "tr_0123456789ab", 185, 1120, null)), null, false));

        mockMvc.perform(get("/api/v1/courses/2001/messages")
                        .param("before_message_id", "90010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].message_id").value("90011"))
                .andExpect(jsonPath("$.data.items[0].course_id").value("2001"))
                .andExpect(jsonPath("$.data.items[0].citations[0].doc_id").value("50001"))
                .andExpect(jsonPath("$.data.items[0].retrieval_cost_ms").value(185));
    }

    @Test
    @DisplayName("会话分页 limit 越界返回 400")
    void rejectsInvalidPageLimit() throws Exception {
        mockMvc.perform(get("/api/v1/courses/2001/messages").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("清空会话返回删除数量并清理 Redis 窗口")
    void clearsMessages() throws Exception {
        when(chatMessageService.clear(USER_ID, COURSE_ID)).thenReturn(12);

        mockMvc.perform(delete("/api/v1/courses/2001/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course_id").value("2001"))
                .andExpect(jsonPath("$.data.deleted_count").value(12));

        verify(chatWindowService).clear(USER_ID, COURSE_ID);
    }

    @Test
    @DisplayName("SSE 请求空白 query 在开流前返回 400")
    void rejectsBlankQuery() throws Exception {
        mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .contentType("application/json")
                        .content("{\"course_id\":\"2001\",\"query\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(ragQaService, never()).stream(anyLong(), any());
    }
}
