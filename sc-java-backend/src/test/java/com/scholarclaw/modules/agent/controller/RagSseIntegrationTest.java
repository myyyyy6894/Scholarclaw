package com.scholarclaw.modules.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.client.PythonAiEngineClient.SseFrame;
import com.scholarclaw.common.client.PythonAiEngineClient.SseStreamHandle;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.agent.service.ChatMessageService;
import com.scholarclaw.modules.agent.service.ChatWindowService;
import com.scholarclaw.modules.agent.service.RagModelConfigService;
import com.scholarclaw.modules.agent.service.RagModelConfigService.RagModelConfig;
import com.scholarclaw.modules.agent.service.RagQaService;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RagSseIntegrationTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CourseService courseService = mock(CourseService.class);
        KnowledgeDocService knowledgeDocService = mock(KnowledgeDocService.class);
        RagModelConfigService modelConfigService = mock(RagModelConfigService.class);
        ChatMessageService chatMessageService = mock(ChatMessageService.class);
        ChatWindowService chatWindowService = mock(ChatWindowService.class);
        PythonAiEngineClient pythonClient = mock(PythonAiEngineClient.class);
        ExecutorService executor = mock(ExecutorService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));

        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setCourseName("数据库系统概念");
        course.setEmbeddingProvider("OPENAI_COMPATIBLE");
        course.setEmbeddingModel("embed");
        course.setEmbeddingDim(3);
        course.setIndexVersion(3);
        course.setIndexStatus("ACTIVE");
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course);
        when(knowledgeDocService.validDocIds(USER_ID, COURSE_ID, 3))
                .thenReturn(List.of(50001L));
        when(modelConfigService.resolve(USER_ID, course)).thenReturn(new RagModelConfig(
                new ModelEndpoint("OPENAI_COMPATIBLE", "https://x/v1", "sk-chat", "chat"),
                new ModelEndpoint("OPENAI_COMPATIBLE", "https://x/v1", "sk-embed", "embed"), 3));
        when(chatMessageService.appendUser(any(), any(), any(), any(), any()))
                .thenReturn(90001L);
        when(chatMessageService.recentHistory(any(), any(), any())).thenReturn(List.of());
        when(chatMessageService.appendAssistant(any(), any(), any(), any(), eq(false),
                anyString(), anyInt(), anyInt())).thenReturn(90002L);
        when(pythonClient.openSseStream(anyString(), any(), anyLong(), any()))
                .thenReturn(handle(
                        new SseFrame("citation", """
                                {"citations":[{"parent_chunk_id":"pc_1","doc_id":"50001",
                                 "doc_name":"a.pdf","page_start":1,"page_end":2,
                                 "section_title":"5.1","content":"内容",
                                 "similarity_score":0.9}]}
                                """),
                        new SseFrame("token",
                                "{\"delta\":\"答案\",\"is_general_supplement\":false}"),
                        new SseFrame("done", """
                                {"is_rejected":false,"retrieval_cost_ms":185,
                                 "llm_first_token_ms":1120,"stripped_citation_count":1}
                                """)));

        RagQaService ragQaService = new RagQaService(courseService, knowledgeDocService,
                modelConfigService, chatMessageService, chatWindowService, pythonClient,
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
                executor, scheduler);
        mockMvc = MockMvcBuilders.standaloneSetup(new RagController(ragQaService,
                        chatMessageService, chatWindowService, courseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
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
    @DisplayName("SSE 输出 citation → token → done，并补全消息 ID 与状态")
    void streamsContractFrames() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/rag/qa/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":\"2001\",\"query\":\"问题\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String body = new String(result.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:citation")
                .contains("\"user_message_id\":\"90001\"")
                .contains("\"course_index_status\":\"ACTIVE\"")
                .contains("event:token")
                .contains("\"delta\":\"答案\"")
                .contains("event:done")
                .contains("\"assistant_message_id\":\"90002\"")
                .contains("\"trace_id\":\"tr_");
        org.assertj.core.api.Assertions.assertThat(body.indexOf("event:citation"))
                .isLessThan(body.indexOf("event:token"));
        org.assertj.core.api.Assertions.assertThat(body.indexOf("event:token"))
                .isLessThan(body.indexOf("event:done"));
    }

    private static SseStreamHandle handle(SseFrame... frames) {
        Deque<SseFrame> queue = new ArrayDeque<>(List.of(frames));
        return new SseStreamHandle() {
            @Override
            public SseFrame next() {
                return queue.poll();
            }

            @Override
            public void close() {
            }
        };
    }
}
