package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.client.PythonAiEngineClient.SseFrame;
import com.scholarclaw.common.client.PythonAiEngineClient.SseStreamHandle;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.RagQaRequest;
import com.scholarclaw.modules.agent.service.RagModelConfigService.RagModelConfig;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RagQaServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long USER_MESSAGE_ID = 90001L;
    private static final long ASSISTANT_MESSAGE_ID = 90002L;

    private CourseService courseService;
    private KnowledgeDocService knowledgeDocService;
    private RagModelConfigService modelConfigService;
    private ChatMessageService chatMessageService;
    private ChatWindowService chatWindowService;
    private PythonAiEngineClient pythonClient;
    private ScheduledExecutorService scheduler;
    private RagQaService service;

    @BeforeEach
    void setUp() {
        courseService = mock(CourseService.class);
        knowledgeDocService = mock(KnowledgeDocService.class);
        modelConfigService = mock(RagModelConfigService.class);
        chatMessageService = mock(ChatMessageService.class);
        chatWindowService = mock(ChatWindowService.class);
        pythonClient = mock(PythonAiEngineClient.class);
        ExecutorService executor = mock(ExecutorService.class);
        scheduler = mock(ScheduledExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));
        service = new RagQaService(courseService, knowledgeDocService, modelConfigService,
                chatMessageService, chatWindowService, pythonClient,
                new ObjectMapper()
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
                executor, scheduler);
    }

    @Test
    @DisplayName("正常 done 后落库完整回答、引用与耗时")
    void persistsAssistantAfterDone() {
        stubValidCourse();
        when(chatMessageService.appendUser(eq(USER_ID), eq(COURSE_ID), eq("问题"),
                any(), anyString())).thenReturn(USER_MESSAGE_ID);
        when(chatMessageService.recentHistory(USER_ID, COURSE_ID, USER_MESSAGE_ID))
                .thenReturn(List.of());
        when(chatMessageService.appendAssistant(eq(USER_ID), eq(COURSE_ID), eq("答案"),
                any(), eq(false), anyString(), eq(185), eq(1120)))
                .thenReturn(ASSISTANT_MESSAGE_ID);
        when(pythonClient.openSseStream(anyString(), any(), eq(USER_ID), any()))
                .thenReturn(handle(
                        new SseFrame("citation", CITATION),
                        new SseFrame("token", "{\"delta\":\"答\",\"is_general_supplement\":false}"),
                        new SseFrame("token", "{\"delta\":\"案\",\"is_general_supplement\":false}"),
                        new SseFrame("done", DONE)));

        service.stream(USER_ID, new RagQaRequest("2001", "  问题  ", "client-1"));

        verify(chatMessageService).appendAssistant(eq(USER_ID), eq(COURSE_ID), eq("答案"),
                any(), eq(false), anyString(), eq(185), eq(1120));
        verify(chatWindowService).append(USER_ID, COURSE_ID, "USER", "问题");
        verify(chatWindowService).append(USER_ID, COURSE_ID, "ASSISTANT", "答案");
    }

    @Test
    @DisplayName("下游 error 帧不落库助手消息")
    void errorFrameDoesNotPersistAssistant() {
        stubValidCourse();
        when(chatMessageService.appendUser(any(), any(), any(), any(), any()))
                .thenReturn(USER_MESSAGE_ID);
        when(chatMessageService.recentHistory(any(), any(), any())).thenReturn(List.of());
        when(pythonClient.openSseStream(anyString(), any(), anyLong(), any()))
                .thenReturn(handle(new SseFrame("error",
                        "{\"code\":\"E-RAG-03\",\"message\":\"知识库上下文读取暂时不可用\"}")));

        service.stream(USER_ID, new RagQaRequest("2001", "问题", null));

        verify(chatMessageService, never()).appendAssistant(any(), any(), any(),
                any(), eq(false), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("下游提前 EOF 不把半截回答当成功")
    void incompleteStreamDoesNotPersistAssistant() {
        stubValidCourse();
        when(chatMessageService.appendUser(any(), any(), any(), any(), any()))
                .thenReturn(USER_MESSAGE_ID);
        when(chatMessageService.recentHistory(any(), any(), any())).thenReturn(List.of());
        when(pythonClient.openSseStream(anyString(), any(), anyLong(), any()))
                .thenReturn(handle(
                        new SseFrame("token", "{\"delta\":\"半截\",\"is_general_supplement\":false}")));

        service.stream(USER_ID, new RagQaRequest("2001", "问题", null));

        verify(chatMessageService, never()).appendAssistant(any(), any(), any(),
                any(), eq(false), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("拒答且无补充 token 时以诚实声明落库")
    void persistsRejectionNoticeWhenNoSupplementTokens() {
        stubValidCourse();
        when(chatMessageService.appendUser(any(), any(), any(), any(), any()))
                .thenReturn(USER_MESSAGE_ID);
        when(chatMessageService.recentHistory(any(), any(), any())).thenReturn(List.of());
        when(chatMessageService.appendAssistant(eq(USER_ID), eq(COURSE_ID), eq("未找到相关内容"),
                any(), eq(true), anyString(), eq(110), eq(0)))
                .thenReturn(ASSISTANT_MESSAGE_ID);
        when(pythonClient.openSseStream(anyString(), any(), anyLong(), any()))
                .thenReturn(handle(
                        new SseFrame("rejection_notice",
                                "{\"notice_text\":\"未找到相关内容\",\"has_general_supplement\":false}"),
                        new SseFrame("done",
                                "{\"is_rejected\":true,\"retrieval_cost_ms\":110,"
                                        + "\"llm_first_token_ms\":0,\"stripped_citation_count\":0}")));

        service.stream(USER_ID, new RagQaRequest("2001", "问题", null));

        verify(chatMessageService).appendAssistant(eq(USER_ID), eq(COURSE_ID),
                eq("未找到相关内容"), any(), eq(true), anyString(), eq(110), eq(0));
    }

    @Test
    @DisplayName("正常 done 但没有任何正文时不落库")
    void emptyNormalAnswerIsNotPersisted() {
        stubValidCourse();
        when(chatMessageService.appendUser(any(), any(), any(), any(), any()))
                .thenReturn(USER_MESSAGE_ID);
        when(chatMessageService.recentHistory(any(), any(), any())).thenReturn(List.of());
        when(pythonClient.openSseStream(anyString(), any(), anyLong(), any()))
                .thenReturn(handle(new SseFrame("done",
                        "{\"is_rejected\":false,\"retrieval_cost_ms\":1,"
                                + "\"llm_first_token_ms\":2,\"stripped_citation_count\":0}")));

        service.stream(USER_ID, new RagQaRequest("2001", "问题", null));

        verify(chatMessageService, never()).appendAssistant(any(), any(), any(),
                any(), eq(false), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("课程重建且无 READY 文档返回 409")
    void rejectsRebuildingWithoutReadyDocs() {
        CourseNamespaceEntity course = course();
        course.setIndexStatus("REBUILDING");
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course);
        when(knowledgeDocService.validDocIds(USER_ID, COURSE_ID, 3)).thenReturn(List.of());

        assertThatThrownBy(() -> service.stream(USER_ID, new RagQaRequest("2001", "问题", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.COURSE_INDEX_REBUILDING));
    }

    @Test
    @DisplayName("无 READY 文档返回 400")
    void rejectsWithoutReadyDocs() {
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());
        when(knowledgeDocService.validDocIds(USER_ID, COURSE_ID, 3)).thenReturn(List.of());

        assertThatThrownBy(() -> service.stream(USER_ID, new RagQaRequest("2001", "问题", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.COURSE_NO_READY_DOCS));
    }

    @Test
    @DisplayName("模型配置错误在开流前抛出，不写入用户消息")
    void modelConfigFailureHappensBeforeUserMessage() {
        CourseNamespaceEntity course = course();
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course);
        when(knowledgeDocService.validDocIds(USER_ID, COURSE_ID, 3)).thenReturn(List.of(50001L));
        when(modelConfigService.resolve(USER_ID, course))
                .thenThrow(new BusinessException(ErrorCode.E_SYS_01));

        assertThatThrownBy(() -> service.stream(USER_ID, new RagQaRequest("2001", "问题", null)))
                .isInstanceOf(BusinessException.class);
        verify(chatMessageService, never()).appendUser(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("内部请求 JSON 使用 snake_case，与 Python Schema 对齐")
    void internalRequestUsesSnakeCase() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.scholarclaw.common.config.JacksonConfig
                .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                        java.time.ZoneId.of("Asia/Shanghai")).build();
        var request = new com.scholarclaw.modules.agent.dto.RagQaInternalRequest(
                "10001", "2001", "数据库系统概念", 3, List.of("50001"), "问题",
                List.of(new com.scholarclaw.modules.agent.dto.RagQaInternalRequest.HistoryTurn(
                        "USER", "上一问")),
                new com.scholarclaw.modules.agent.dto.RagQaInternalRequest.ChatConfig(
                        "OPENAI_COMPATIBLE", "https://x/v1", "sk-chat", "chat"),
                new com.scholarclaw.modules.agent.dto.RagQaInternalRequest.EmbeddingConfig(
                        "OPENAI_COMPATIBLE", "https://x/v1", "sk-embed", "embed", 3));

        String json = mapper.writeValueAsString(request);

        org.assertj.core.api.Assertions.assertThat(json)
                .contains("\"user_id\":\"10001\"")
                .contains("\"course_index_version\":3")
                .contains("\"valid_doc_ids\":[\"50001\"]")
                .contains("\"chat_history_window\":[{\"role\":\"USER\",\"content\":\"上一问\"}]")
                .contains("\"chat_config\":{")
                .contains("\"embedding_config\":{")
                .contains("\"api_key\":\"sk-chat\"")
                .contains("\"model_name\":\"embed\"")
                .contains("\"dim\":3");
    }

    private void stubValidCourse() {
        CourseNamespaceEntity course = course();
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course);
        when(knowledgeDocService.validDocIds(USER_ID, COURSE_ID, 3)).thenReturn(List.of(50001L));
        when(modelConfigService.resolve(USER_ID, course)).thenReturn(new RagModelConfig(
                new ModelEndpoint("OPENAI_COMPATIBLE", "https://x/v1", "sk-chat", "chat"),
                new ModelEndpoint("OPENAI_COMPATIBLE", "https://x/v1", "sk-embed", "embed"), 3));
    }

    private static CourseNamespaceEntity course() {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setCourseName("数据库系统概念");
        course.setEmbeddingProvider("OPENAI_COMPATIBLE");
        course.setEmbeddingModel("embed");
        course.setEmbeddingDim(3);
        course.setIndexVersion(3);
        course.setIndexStatus("ACTIVE");
        return course;
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

    private static final String CITATION = """
            {"citations":[{"parent_chunk_id":"pc_1","doc_id":"50001","doc_name":"a.pdf",
             "page_start":1,"page_end":2,"section_title":null,"content":"内容",
             "similarity_score":0.9}]}
            """;

    private static final String DONE = """
            {"is_rejected":false,"retrieval_cost_ms":185,"llm_first_token_ms":1120,
             "stripped_citation_count":1}
            """;
}
