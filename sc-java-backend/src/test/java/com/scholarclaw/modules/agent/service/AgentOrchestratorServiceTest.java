package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentIntent;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.agent.dto.PendingAction;
import com.scholarclaw.modules.agent.dto.PendingIntent;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.DdlExtractOutcome;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.service.DdlExtractionService;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** TASK-706 编排层：规则优先、单次分类、待确认上下文与确定性工具执行。 */
class AgentOrchestratorServiceTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 102L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(Jackson2ObjectMapperBuilder.json(), ZONE).build();
    private static final ModelEndpoint CHAT = new ModelEndpoint(
            "OPENAI_COMPATIBLE", "https://api.example.com/v1", "sk-test", "chat-model");

    private PendingIntentStore pendingIntentStore;
    private PendingActionStore pendingActionStore;
    private PythonAiEngineClient pythonClient;
    private DdlUserConfigResolver userConfigResolver;
    private CourseService courseService;
    private DdlTaskService ddlTaskService;
    private DdlExtractionService ddlExtractionService;
    private AgentOrchestratorService service;

    @BeforeEach
    void setUp() {
        pendingIntentStore = mock(PendingIntentStore.class);
        pendingActionStore = mock(PendingActionStore.class);
        pythonClient = mock(PythonAiEngineClient.class);
        userConfigResolver = mock(DdlUserConfigResolver.class);
        courseService = mock(CourseService.class);
        ddlTaskService = mock(DdlTaskService.class);
        ddlExtractionService = mock(DdlExtractionService.class);
        service = new AgentOrchestratorService(new SlashCommandRouter(),
                pendingIntentStore, pendingActionStore, pythonClient, userConfigResolver,
                courseService, ddlTaskService, ddlExtractionService,
                new NaturalTaskIntentParser());

        when(userConfigResolver.resolve(USER_ID))
                .thenReturn(new ResolvedUser(new UserConfigEntity(), "Asia/Shanghai", ZONE));
        when(userConfigResolver.resolveChatEndpoint(any())).thenReturn(CHAT);
        when(courseService.listCourses(USER_ID)).thenReturn(List.of(
                new CourseNamespaceVO(2001L, "操作系统原理", null, false,
                        "OPENAI_COMPATIBLE", "embed", 1024, 1, "ACTIVE", 0, 0, 0,
                        "2026-10-15T11:40:00+08:00", "2026-10-15T11:40:00+08:00")));
        when(pendingIntentStore.load(USER_ID)).thenReturn(Optional.empty());
        when(pendingActionStore.load(USER_ID)).thenReturn(Optional.empty());
        when(ddlTaskService.listTasks(eq(USER_ID), isNull(), isNull()))
                .thenReturn(new DdlTaskBoardVO(List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("/done：确定性执行，零 LLM，带用户身份")
    void slashDoneExecutesWithoutLlm() {
        when(ddlTaskService.setCompletion(USER_ID, TASK_ID, true))
                .thenReturn(taskVO(TASK_ID, "COMPLETED"));

        AgentReply reply = service.handle(message("/done T102"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_DONE);
        assertThat(reply.task().displayCode()).isEqualTo("#T102");
        assertThat(reply.intent()).isEqualTo(AgentIntent.TASK_MANAGE);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
        verify(ddlTaskService).setCompletion(USER_ID, TASK_ID, true);
    }

    @Test
    @DisplayName("/list：零 LLM，返回看板摘要")
    void slashListReturnsBoard() {
        when(ddlTaskService.listTasks(USER_ID, null, null)).thenReturn(new DdlTaskBoardVO(
                List.of(), List.of(), List.of(taskVO(TASK_ID, "ACTIVE")), List.of()));

        AgentReply reply = service.handle(message("/list"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_LIST);
        assertThat(reply.board()).isNotNull();
        assertThat(reply.replyText()).contains("进行中/已逾期 1 条");
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("/confirm：解析日期时刻后走专用 confirmTask，零 LLM")
    void slashConfirmUpdatesTask() {
        when(ddlTaskService.getTask(USER_ID, 104L))
                .thenReturn(taskVO(104L, "PENDING_CONFIRM"));
        when(ddlTaskService.confirmTask(eq(USER_ID), eq(104L), any(UpdateDdlTaskRequest.class)))
                .thenReturn(taskVO(104L, "ACTIVE"));

        AgentReply reply = service.handle(message("/confirm T104 2026-10-25 22:00"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_CONFIRM);
        ArgumentCaptor<UpdateDdlTaskRequest> captor =
                ArgumentCaptor.forClass(UpdateDdlTaskRequest.class);
        verify(ddlTaskService).confirmTask(eq(USER_ID), eq(104L), captor.capture());
        assertThat(captor.getValue().timePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(captor.getValue().dueDate()).isEqualTo("2026-10-25");
        assertThat(captor.getValue().dueTime()).isEqualTo("22:00");
        verify(ddlTaskService, never()).updateTask(any(), any(), any());
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("/confirm 作用在 ACTIVE 任务：传播 409 STATE_CONFLICT，零写库")
    void slashConfirmOnActiveTaskConflicts() {
        when(ddlTaskService.getTask(USER_ID, 104L)).thenReturn(taskVO(104L, "ACTIVE"));
        when(ddlTaskService.confirmTask(eq(USER_ID), eq(104L), any(UpdateDdlTaskRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.STATE_CONFLICT));

        assertThatThrownBy(() -> service.handle(message("/confirm T104 2026-10-25 22:00")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("/ddl：以平台消息身份调用提取，不再走自然语言分类")
    void slashDdlUsesPlatformIdentity() {
        when(ddlExtractionService.extractForPlatform(eq(USER_ID), eq("QQ_ONEBOT"),
                eq("msg_1"), eq("本周五下午五点前交实验报告"), isNull(),
                eq("2026-10-25T12:00:00+08:00"), eq(false)))
                .thenReturn(new DdlExtractVO(DdlExtractOutcome.CREATED,
                        taskVO(TASK_ID, "ACTIVE"), null));

        AgentReply reply = service.handle(message("/ddl 本周五下午五点前交实验报告"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.DDL_EXTRACT);
        assertThat(reply.task().displayCode()).isEqualTo("#T102");
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("低置信度自然语言：暂存 pending_intent 并追问，不执行任何工具")
    void lowConfidenceSavesPendingIntent() {
        stubRoute("CHITCHAT_HELP", 0.4, null);

        AgentReply reply = service.handle(message("帮我看看这个怎么办"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.CLARIFY);
        assertThat(reply.replyText()).contains("回复【1】");
        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(pendingIntentStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().rawText()).isEqualTo("帮我看看这个怎么办");
        assertThat(captor.getValue().platform()).isEqualTo("QQ_ONEBOT");
        assertThat(captor.getValue().sourceMsgId()).isEqualTo("msg_1");
        verifyNoInteractions(ddlTaskService, ddlExtractionService);
    }

    @Test
    @DisplayName("自然语言 DDL_EXTRACT：一次分类后复用 payload 落库")
    void naturalDdlPersistsRoutedPayload() {
        stubRoute("DDL_EXTRACT", 0.9, payload());
        when(ddlExtractionService.persistRouted(eq(USER_ID), eq("QQ_ONEBOT"), eq("msg_1"),
                eq("周五下午五点前交操作系统实验二报告"), isNull(), eq(false), any()))
                .thenReturn(new DdlExtractVO(DdlExtractOutcome.CREATED,
                        taskVO(TASK_ID, "ACTIVE"), null));

        AgentReply reply = service.handle(message("周五下午五点前交操作系统实验二报告"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.DDL_EXTRACT);
        verify(ddlExtractionService).persistRouted(eq(USER_ID), eq("QQ_ONEBOT"),
                eq("msg_1"), eq("周五下午五点前交操作系统实验二报告"), isNull(), eq(false), any());
    }

    @Test
    @DisplayName("自然语言删除：先写 pending_action 要求二次确认，不直接取消")
    void naturalDeleteRequiresConfirmation() {
        stubRoute("TASK_MANAGE", 0.9, null);

        AgentReply reply = service.handle(message("删除 #T102"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(captor.getValue().taskId()).isEqualTo(TASK_ID);
        verify(ddlTaskService, never()).cancelTask(any(), any());
    }

    @Test
    @DisplayName("回复确认后执行待确认取消动作")
    void confirmationExecutesPendingAction() {
        when(pendingActionStore.load(USER_ID)).thenReturn(Optional.of(
                PendingAction.cancel(TASK_ID, "删除 #T102", "now")));

        AgentReply reply = service.handle(message("确认"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        verify(pendingActionStore).clear(USER_ID);
        verify(ddlTaskService).cancelTask(USER_ID, TASK_ID);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("未确认动作不跨无关消息保留：查询后再回复“确认”不会误取消任务")
    void pendingActionDoesNotSurviveUnrelatedMessage() {
        when(pendingActionStore.load(USER_ID)).thenReturn(Optional.of(
                PendingAction.cancel(TASK_ID, "删除 #T102", "now")));
        when(ddlTaskService.listTasks(eq(USER_ID), isNull(), isNull()))
                .thenReturn(new DdlTaskBoardVO(List.of(), List.of(), List.of(), List.of()));

        service.handle(message("/list"));

        // 无关消息进入正常路由前清除 pending_action，避免旧删除动作被后续“确认”触发。
        verify(pendingActionStore).clear(USER_ID);
        verify(ddlTaskService, never()).cancelTask(any(), any());
    }

    @Test
    @DisplayName("查询已取消任务：回复“不存在或已取消”，不抛异常")
    void queryCanceledTaskReturnsHint() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.getTask(USER_ID, TASK_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        AgentReply reply = service.handle(message("查询 T102"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(reply.replyText()).contains("不存在或已取消");
    }

    @Test
    @DisplayName("自然语言完成：单任务一步执行")
    void naturalDoneIsOneStep() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.setCompletion(USER_ID, TASK_ID, true))
                .thenReturn(taskVO(TASK_ID, "COMPLETED"));

        AgentReply reply = service.handle(message("把 #T102 标记完成"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_DONE);
        verify(ddlTaskService).setCompletion(USER_ID, TASK_ID, true);
    }

    @Test
    @DisplayName("查询 T36：返回单任务详情（TASK_QUERY），零写操作")
    void naturalQueryReturnsTaskDetail() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.getTask(USER_ID, 36L)).thenReturn(taskVO(36L, "ACTIVE"));

        AgentReply reply = service.handle(message("查询 T36"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(reply.replyText()).contains("#T36").contains("状态：ACTIVE");
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
        verify(ddlTaskService, never()).updateTask(any(), any(), any());
    }

    @Test
    @DisplayName("取消 T37：进入二次确认，不直接取消")
    void naturalCancelEntersConfirmation() {
        stubRoute("TASK_MANAGE", 0.9, null);

        AgentReply reply = service.handle(message("取消 T37"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_CANCEL);
        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo(37L);
        verify(ddlTaskService, never()).cancelTask(any(), any());
    }

    @Test
    @DisplayName("完成 T36 T37：进入批量二次确认，确认前零执行")
    void naturalBatchWriteRequiresConfirmation() {
        stubRoute("TASK_MANAGE", 0.9, null);

        AgentReply reply = service.handle(message("完成 T36 T37"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_DONE);
        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().taskIds()).containsExactly(36L, 37L);
        assertThat(captor.getValue().isBatch()).isTrue();
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
        verify(ddlTaskService, never()).setCompletionBatch(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("批量确认后整批执行，不只处理第一个")
    void batchConfirmationExecutesAllTasks() {
        when(pendingActionStore.load(USER_ID)).thenReturn(Optional.of(
                PendingAction.done(java.util.List.of(36L, 37L), "完成 T36 T37", "now")));
        when(ddlTaskService.setCompletionBatch(USER_ID, java.util.List.of(36L, 37L), true))
                .thenReturn(java.util.List.of(taskVO(36L, "COMPLETED"), taskVO(37L, "COMPLETED")));

        AgentReply reply = service.handle(message("确认"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_DONE);
        verify(ddlTaskService).setCompletionBatch(USER_ID, java.util.List.of(36L, 37L), true);
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("/done T95 T96：进入批量确认，不静默只完成第一个")
    void slashDoneWithMultipleTasksRequiresConfirmation() {
        AgentReply reply = service.handle(message("/done T95 T96"));

        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().taskIds()).containsExactly(95L, 96L);
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("/edit 日期后仍带第二个任务编号：进入批量确认，不忽略第二个编号")
    void slashEditWithTrailingTaskIdRequiresConfirmation() {
        AgentReply reply = service.handle(message("/edit T85 2026-12-01 22:00 T86"));

        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().taskIds()).containsExactly(85L, 86L);
        assertThat(captor.getValue().dueDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 1));
        verify(ddlTaskService, never()).updateTask(any(), any(), any());
    }

    @Test
    @DisplayName("否定句不写库：不完成 / 不用删除 / 无需完成")
    void negativeSentencesNeverWrite() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.getTask(USER_ID, 87L)).thenReturn(taskVO(87L, "ACTIVE"));
        when(ddlTaskService.getTask(USER_ID, 37L)).thenReturn(taskVO(37L, "ACTIVE"));

        assertThat(service.handle(message("不完成 T87")).action())
                .isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(service.handle(message("不用删除 T37")).action())
                .isEqualTo(AgentToolAction.TASK_QUERY);
        assertThat(service.handle(message("无需完成")).action())
                .isEqualTo(AgentToolAction.CLARIFY);

        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
        verify(ddlTaskService, never()).cancelTask(any(), any());
        verify(ddlTaskService, never()).updateTask(any(), any(), any());
    }

    @Test
    @DisplayName("“T37 没有完成”是查询，绝不能触发完成写操作")
    void negationDoesNotComplete() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.getTask(USER_ID, 37L)).thenReturn(taskVO(37L, "PENDING_TIME"));

        AgentReply reply = service.handle(message("T37 没有完成"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_QUERY);
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("“T102 完成了吗”是查询，不触发完成")
    void questionDoesNotComplete() {
        stubRoute("TASK_MANAGE", 0.9, null);
        when(ddlTaskService.getTask(USER_ID, TASK_ID)).thenReturn(taskVO(TASK_ID, "ACTIVE"));

        AgentReply reply = service.handle(message("T102 完成了吗"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.TASK_QUERY);
        verify(ddlTaskService, never()).setCompletion(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("低置信度自然语言修改：进入二次确认而不是直接写库")
    void lowConfidenceModifyRequiresConfirmation() {
        stubRoute("TASK_MANAGE", 0.7, null);

        AgentReply reply = service.handle(message("把 #T102 改到 2026-10-25 22:00"));

        assertThat(reply.requiresConfirmation()).isTrue();
        ArgumentCaptor<PendingAction> captor = ArgumentCaptor.forClass(PendingAction.class);
        verify(pendingActionStore).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AgentToolAction.TASK_UPDATE);
        verify(ddlTaskService, never()).updateTask(any(), any(), any());
    }

    @Test
    @DisplayName("路由失败：抛出稳定错误，不写库、不伪造成功")
    void routingFailureDoesNotFakeSuccess() {
        when(pythonClient.postJson(any(), any(), any(), any(Duration.class)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));

        assertThatThrownBy(() -> service.handle(message("随便说点什么")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
        verifyNoInteractions(ddlTaskService, ddlExtractionService);
    }

    @Test
    @DisplayName("非法意图：UPSTREAM_MODEL_ERROR，不执行工具")
    void invalidIntentIsUpstreamError() {
        stubRoute("CODE_SCAFFOLD", 0.9, null);

        assertThatThrownBy(() -> service.handle(message("帮我搭个工程")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
        verifyNoInteractions(ddlTaskService, ddlExtractionService);
    }

    @Test
    @DisplayName("KB_QA：明确指引，不伪造 RAG 执行")
    void kbQaIsGuidanceOnly() {
        stubRoute("KB_QA", 0.9, null);

        AgentReply reply = service.handle(message("课件里两阶段锁是什么"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.KB_QA_GUIDE);
        assertThat(reply.replyText()).contains("Web 工作台");
        verifyNoInteractions(ddlTaskService, ddlExtractionService);
    }

    @Test
    @DisplayName("pending_intent 回复 1：用暂存原文执行提取")
    void pendingIntentChoiceOneExtractsStoredText() {
        when(pendingIntentStore.load(USER_ID)).thenReturn(Optional.of(new PendingIntent(
                "本周五下午五点前交实验报告", "2026-10-25T12:00:00+08:00",
                "QQ_ONEBOT", "msg_old", "2001", "now")));
        when(ddlExtractionService.extractForPlatform(eq(USER_ID), eq("QQ_ONEBOT"),
                eq("msg_old"), eq("本周五下午五点前交实验报告"), eq("2001"),
                eq("2026-10-25T12:00:00+08:00"), eq(false)))
                .thenReturn(new DdlExtractVO(DdlExtractOutcome.CREATED,
                        taskVO(TASK_ID, "ACTIVE"), null));

        AgentReply reply = service.handle(message("1"));

        assertThat(reply.action()).isEqualTo(AgentToolAction.DDL_EXTRACT);
        verify(pendingIntentStore).clear(USER_ID);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("结构性约束：编排层不导入 RabbitMQ 客户端")
    void orchestratorHasNoMqDependency() throws Exception {
        List<Path> sources = List.of(
                Path.of("src/main/java/com/scholarclaw/modules/agent/service/AgentOrchestratorService.java"),
                Path.of("src/main/java/com/scholarclaw/modules/agent/service/SlashCommandRouter.java"),
                Path.of("src/main/java/com/scholarclaw/modules/agent/service/PendingIntentStore.java"),
                Path.of("src/main/java/com/scholarclaw/modules/agent/service/PendingActionStore.java"));
        for (Path path : sources) {
            String source = Files.readString(path);
            assertThat(source).doesNotContain("RabbitTemplate")
                    .doesNotContain("basicPublish")
                    .doesNotContain("org.springframework.amqp");
        }
    }

    private void stubRoute(String intent, double confidence, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", intent);
        body.put("intent_confidence", confidence);
        if (payload != null) {
            body.put("ddl_payload", payload);
        }
        JsonNode node = MAPPER.valueToTree(body);
        when(pythonClient.postJson(any(), any(), eq(USER_ID), any(Duration.class)))
                .thenReturn(node);
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_name", "操作系统原理");
        payload.put("task_title", "提交实验二报告");
        payload.put("raw_time_text", "本周五下午五点前");
        payload.put("time_precision", "EXACT_DATETIME");
        payload.put("is_relative_time", true);
        payload.put("parsed_due_date", "2026-10-25");
        payload.put("parsed_due_time", "17:00:00");
        payload.put("deliverable_format", "zip");
        payload.put("submission_channel", "学习通");
        payload.put("confidence", 0.96);
        payload.put("has_ambiguity", false);
        payload.put("ambiguity_reason", null);
        return payload;
    }

    private static AgentInboundMessage message(String rawText) {
        return new AgentInboundMessage(USER_ID, "QQ_ONEBOT", "msg_1", rawText,
                "2026-10-25T12:00:00+08:00", null, false);
    }

    private static DdlTaskVO taskVO(long taskId, String status) {
        return new DdlTaskVO(
                taskId,
                "#T" + taskId,
                2001L,
                "操作系统原理",
                "提交实验二报告",
                "zip",
                "学习通",
                null,
                "本周五下午五点前",
                "EXACT_DATETIME",
                true,
                "2099-12-31",
                "22:00:00",
                OffsetDateTime.parse("2099-12-31T22:00:00+08:00"),
                false,
                null,
                "Asia/Shanghai",
                new BigDecimal("0.960"),
                status,
                null,
                0,
                false,
                false,
                false,
                "QQ_ONEBOT",
                null,
                OffsetDateTime.parse("2026-10-15T11:40:00+08:00"),
                OffsetDateTime.parse("2026-10-15T11:40:00+08:00"));
    }
}
