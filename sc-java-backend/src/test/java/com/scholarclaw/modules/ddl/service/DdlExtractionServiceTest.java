package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.DdlExtractOutcome;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DraftDecisionRequest;
import com.scholarclaw.modules.ddl.dto.ExtractDdlTaskRequest;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.support.MybatisTableInfoTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** TASK-704 提取建单、三层幂等、变更草案与决策。 */
class DdlExtractionServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long TASK_ID = 103L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(Jackson2ObjectMapperBuilder.json(), ZONE).build();
    private static final ModelEndpoint CHAT = new ModelEndpoint(
            "OPENAI_COMPATIBLE", "https://api.example.com/v1", "sk-test", "chat-model");

    private PythonAiEngineClient pythonClient;
    private CourseService courseService;
    private DdlTaskMapper mapper;
    private DdlTaskService taskService;
    private DdlDraftStore draftStore;
    private DdlUserConfigResolver userConfigResolver;
    private DdlDecisionReceiptStore decisionReceiptStore;
    private DdlExtractionService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisTableInfoTestSupport.init(DdlTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonAiEngineClient.class);
        courseService = mock(CourseService.class);
        mapper = mock(DdlTaskMapper.class);
        taskService = mock(DdlTaskService.class);
        draftStore = mock(DdlDraftStore.class);
        userConfigResolver = mock(DdlUserConfigResolver.class);
        decisionReceiptStore = mock(DdlDecisionReceiptStore.class);
        service = new DdlExtractionService(pythonClient, courseService, mapper, taskService,
                draftStore, new DdlSemanticMatcher(), userConfigResolver,
                new DdlTimeRuleValidator(), decisionReceiptStore);

        when(userConfigResolver.resolve(USER_ID)).thenReturn(new ResolvedUser(
                new UserConfigEntity(), "Asia/Shanghai", ZONE));
        when(userConfigResolver.resolveChatEndpoint(any())).thenReturn(CHAT);
        when(courseService.listCourses(USER_ID)).thenReturn(List.of(
                new CourseNamespaceVO(COURSE_ID, "操作系统原理", null, false,
                        "OPENAI_COMPATIBLE", "embed", 1024, 1, "ACTIVE", 0, 0, 0,
                        "2026-10-15T11:40:00+08:00", "2026-10-15T11:40:00+08:00")));
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.acquired("request-token"));
        when(draftStore.acquireContentLock(eq(USER_ID), any())).thenReturn("content-token");
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), any())).thenReturn(Optional.empty());
        when(draftStore.loadDecisionReceipt(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(draftStore.acquireDraftDecisionLock(eq(USER_ID), any())).thenReturn("decision-token");
        when(decisionReceiptStore.find(eq(USER_ID), any())).thenReturn(Optional.empty());
        when(mapper.selectList(any())).thenReturn(List.of());
        when(taskService.createExtractedTask(any())).thenAnswer(invocation -> {
            DdlTaskEntity entity = invocation.getArgument(0);
            entity.setTaskId(TASK_ID);
            return new DdlTaskService.DdlTaskCreationResult(
                    DdlTaskService.toVO(entity, ZONE), true);
        });
    }

    @Test
    @DisplayName("提取成功：Python 结果落库为 ACTIVE，写入幂等键/content_hash，标记 PROCESSED")
    void extractsAndCreatesTask() {
        stubPython(payload("操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "22:00:00", 0.96, false, false));

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(entity.getCourseName()).isEqualTo("操作系统原理");
        assertThat(entity.getTaskTitle()).isEqualTo("提交实验二报告");
        assertThat(entity.getTimePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(entity.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getSourcePlatform()).isEqualTo("WEB");
        assertThat(entity.getSourceMsgId()).isEqualTo("req_extract_001");
        assertThat(entity.getIdempotencyKey())
                .isEqualTo(DdlIdempotencyKey.forWebRequest(USER_ID, "req_extract_001"));
        assertThat(entity.getContentHash()).isNotBlank();
        assertThat(entity.getConfidence()).isEqualByComparingTo("0.960");
        verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                any(DdlRequestReceipt.class), anyBoolean());
        verify(draftStore, never()).releaseRequest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("IM 平台提取：source_platform/source_msg_id 与平台幂等键、Redis 键均按平台隔离")
    void imPlatformExtractionUsesPlatformIdentity() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        when(draftStore.acquireRequestLock(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_777")))
                .thenReturn(DdlDraftStore.RequestLock.acquired("request-token"));
        when(draftStore.loadReceipt(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_777")))
                .thenReturn(Optional.empty());

        DdlExtractVO result = service.extractForPlatform(USER_ID, "QQ_ONEBOT", "msg_777",
                "请于2099年12月31日提交实验报告", null, "2026-10-15T11:40:00+08:00", false);

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getSourcePlatform()).isEqualTo("QQ_ONEBOT");
        assertThat(entity.getSourceMsgId()).isEqualTo("msg_777");
        assertThat(entity.getIdempotencyKey()).isEqualTo(
                DdlIdempotencyKey.forRequest(USER_ID, "QQ_ONEBOT", "msg_777"));
        verify(draftStore).markProcessed(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_777"), any(), anyBoolean());
        verify(pythonClient).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("DDL 提取读取超默认 60s，避免真实供应商模型重试被 15s 截断")
    void extractionReadTimeoutAllowsSlowProvider() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        when(draftStore.acquireRequestLock(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_timeout")))
                .thenReturn(DdlDraftStore.RequestLock.acquired("request-token"));
        when(draftStore.loadReceipt(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_timeout")))
                .thenReturn(Optional.empty());

        service.extractForPlatform(USER_ID, "QQ_ONEBOT", "msg_timeout",
                "请于2099年12月31日提交实验报告", null, "2026-10-15T11:40:00+08:00", false);

        ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
        verify(pythonClient).postJson(any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("路由模式落库：直接复用已解析 payload，不再调用 Python")
    void persistRoutedSkipsPython() {
        DdlExtractedPayload routed = DdlExtractedPayload.fromData(data(payload(
                "操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false)));
        when(draftStore.acquireRequestLock(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_778")))
                .thenReturn(DdlDraftStore.RequestLock.acquired("request-token"));
        when(draftStore.loadReceipt(eq("QQ_ONEBOT"), eq(USER_ID), eq("msg_778")))
                .thenReturn(Optional.empty());

        DdlExtractVO result = service.persistRouted(USER_ID, "QQ_ONEBOT", "msg_778",
                "请于2099年12月31日提交实验报告", null, false, routed);

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        assertThat(capturedCreatedEntity().getSourcePlatform()).isEqualTo("QQ_ONEBOT");
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("请求级幂等命中：不再调用 Python，直接返回 IDEMPOTENT_HIT")
    void dbIdempotencyHitSkipsPython() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.IDEMPOTENT_HIT);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
        verify(draftStore, never()).acquireRequestLock(any(), any(), any());
    }

    @Test
    @DisplayName("Redis PROCESSING：返回 409 REQUEST_IN_PROGRESS，不调用 Python")
    void redisProcessingRejectsConcurrentRequest() {
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processing());

        assertError(ErrorCode.REQUEST_IN_PROGRESS, () -> service.extract(USER_ID, request(false, null)));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis PROCESSED + 终态回执：原样重放，不调用 Python")
    void redisProcessedWithReceiptReplays() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        DdlRequestReceipt receipt = DdlRequestReceipt.of(fingerprint(false),
                DdlExtractOutcome.IDEMPOTENT_HIT.name(), TASK_ID, null,
                "2099-12-31T22:00:00");
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(null);
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(existing);
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receipt));

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.IDEMPOTENT_HIT);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("近 14 天完全相同 content_hash：返回 SEMANTIC_DUPLICATE_HINT，不建单")
    void exactContentHashReturnsSemanticHint() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                null, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        when(mapper.selectOne(any())).thenReturn(existing);

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(taskService, never()).createExtractedTask(any());
        verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                any(DdlRequestReceipt.class), anyBoolean());
    }

    @Test
    @DisplayName("同课程相似任务且截止时间变更：生成 Redis 草案并返回 CHANGE_DRAFT_CONFIRM_REQUIRED")
    void similarTaskWithChangedDueCreatesDraft() {
        stubPython(payload("操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "23:59:00", 0.96, false, false));
        DdlTaskEntity similar = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectList(any())).thenReturn(List.of(similar));

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome())
                .isEqualTo(DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        assertThat(result.changeDraft()).isNotNull();
        assertThat(result.changeDraft().targetTaskId()).isEqualTo(TASK_ID);
        assertThat(result.changeDraft().newDueDate()).isEqualTo("2099-12-31");
        ArgumentCaptor<DdlTaskDraft> captor = ArgumentCaptor.forClass(DdlTaskDraft.class);
        verify(draftStore).saveDraft(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().targetTaskId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().dueAt()).isEqualTo("2099-12-31T23:59");
        verify(taskService, never()).createExtractedTask(any());
    }

    @Test
    @DisplayName("同课程相似任务且无变更：返回 SEMANTIC_DUPLICATE_HINT")
    void similarTaskWithoutChangesReturnsHint() {
        stubPython(payload("操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-30", "22:00:00", 0.96, false, false));
        DdlTaskEntity similar = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        similar.setDeliverableFormat("zip");
        similar.setSubmissionChannel("学习通");
        when(mapper.selectList(any())).thenReturn(List.of(similar));

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT);
        verify(draftStore, never()).saveDraft(any(), any());
        verify(taskService, never()).createExtractedTask(any());
    }

    @Test
    @DisplayName("force_create=true 跳过第 3 层语义检测，直接建单")
    void forceCreateSkipsSemanticChecks() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        DdlExtractVO result = service.extract(USER_ID, request(true, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        verify(mapper, never()).selectOne(any());
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("Python 失败：释放 Redis 锁并传播稳定错误码")
    void pythonFailureReleasesLock() {
        when(pythonClient.postJson(any(), any(), any(), any(Duration.class)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));

        assertError(ErrorCode.UPSTREAM_TIMEOUT, () -> service.extract(USER_ID, request(false, null)));
        verify(draftStore).releaseRequest(eq("WEB"), eq(USER_ID), eq("req_extract_001"), any());
        verify(draftStore, never()).markProcessed(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Python 返回非法枚举：UPSTREAM_MODEL_ERROR，释放锁")
    void invalidPythonPayloadReleasesLock() {
        Map<String, Object> payload = payload("操作系统原理", "任务", "SOMETIME",
                "2099-12-31", null, 0.96, false, false);
        stubPython(payload);

        assertError(ErrorCode.UPSTREAM_MODEL_ERROR,
                () -> service.extract(USER_ID, request(false, null)));
        verify(draftStore).releaseRequest(eq("WEB"), eq(USER_ID), eq("req_extract_001"), any());
    }

    @Test
    @DisplayName("DATE_ONLY：due_time 为空、due_at 23:59:59、time_inferred=1")
    void dateOnlyMapping() {
        stubPython(payload("操作系统原理", "交实验报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        service.extract(USER_ID, request(false, null));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getDueTime()).isNull();
        assertThat(entity.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        assertThat(entity.getTimeInferred()).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("VAGUE_OR_MISSING：due 字段全空、PENDING_TIME、follow_up_at 非空")
    void vagueMapping() {
        stubPython(payload("操作系统原理", "尽快交", "VAGUE_OR_MISSING",
                null, null, 0.96, true, false));

        service.extract(USER_ID, request(false, null));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getDueDate()).isNull();
        assertThat(entity.getDueTime()).isNull();
        assertThat(entity.getDueAt()).isNull();
        assertThat(entity.getStatus()).isEqualTo("PENDING_TIME");
        assertThat(entity.getFollowUpAt()).isNotNull();
        assertThat(entity.getTimeAmbiguityReason()).isEqualTo("需人工确认");
    }

    @Test
    @DisplayName("INVALID_OR_PAST 有日期：保留过去 due_at，PENDING_CONFIRM")
    void invalidPastMapping() {
        stubPython(payload("操作系统原理", "补录旧任务", "INVALID_OR_PAST",
                "2000-01-01", null, 0.96, true, false));

        service.extract(USER_ID, request(false, null));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getDueAt()).isEqualTo(LocalDateTime.of(2000, 1, 1, 23, 59, 59));
        assertThat(entity.getTimeInferred()).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(entity.getFollowUpAt()).isNotNull();
    }

    @Test
    @DisplayName("低置信度/歧义：即使时间明确也 PENDING_CONFIRM")
    void lowConfidenceOrAmbiguityIsPendingConfirm() {
        stubPython(payload("操作系统原理", "交实验报告", "DATE_ONLY",
                "2099-12-31", null, 0.55, false, false));

        service.extract(USER_ID, request(false, null));

        assertThat(capturedCreatedEntity().getStatus()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    @DisplayName("显式 course_id 优先，课程名自动对齐")
    void explicitCourseIdWins() {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setCourseName("数据库系统概念");
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course);
        stubPython(payload("操作系统原理", "交实验报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        service.extract(USER_ID, request(false, "2001"));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(entity.getCourseName()).isEqualTo("数据库系统概念");
    }

    @Test
    @DisplayName("无法匹配课程名：保留文本、course_id 为空")
    void unmatchedCourseNameKeepsText() {
        stubPython(payload("未知课程", "交实验报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        service.extract(USER_ID, request(false, null));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getCourseId()).isNull();
        assertThat(entity.getCourseName()).isEqualTo("未知课程");
    }

    @Test
    @DisplayName("缺失课程名：回退“未分类课程”，不任意绑定课程")
    void missingCourseNameFallsBackToUnclassified() {
        stubPython(payload(null, "交实验报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, true, false));

        service.extract(USER_ID, request(false, null));

        DdlTaskEntity entity = capturedCreatedEntity();
        assertThat(entity.getCourseId()).isNull();
        assertThat(entity.getCourseName()).isEqualTo("未分类课程");
    }

    @Test
    @DisplayName("缺失任务标题：用原文首行作为不虚构的回退标题")
    void missingTitleFallsBackToRawText() {
        stubPython(payload("操作系统原理", null, "DATE_ONLY",
                "2099-12-31", null, 0.96, true, false));

        service.extract(USER_ID, request(false, null));

        assertThat(capturedCreatedEntity().getTaskTitle()).startsWith("请于");
    }

    @Test
    @DisplayName("CONFIRM_UPDATE：调用事务更新并删除草案")
    void confirmUpdateAppliesDraftAndDeletesIt() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService).applyDraftUpdate(USER_ID, TASK_ID, draft);
        verify(draftStore).releaseDraftDecisionLockAfterCompletion(
                USER_ID, TASK_ID, "decision-token");
        verify(draftStore, never()).releaseDraftDecisionLock(
                any(), any(), any());
        verify(draftStore).deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
        verify(draftStore).saveDecisionReceipt(eq(USER_ID), eq("req_decide_001"),
                any(DdlDecisionReceipt.class));
    }

    @Test
    @DisplayName("CREATE_NEW：使用新的 client_request_id 建单并删除草案")
    void createNewCreatesIndependentTask() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.createFromDraft(USER_ID, draft, "req_decide_002"))
                .thenReturn(new DdlTaskService.DdlTaskCreationResult(
                        DdlTaskService.toVO(target, ZONE), true));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CREATE_NEW", "req_decide_002"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService).createFromDraft(USER_ID, draft, "req_decide_002");
        verify(draftStore).deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("DISCARD：删除草案并返回原任务")
    void discardDeletesDraftAndReturnsTarget() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID))
                .thenReturn(Optional.of(draft(target)));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("DISCARD", "req_decide_003"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(draftStore).deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("草案缺失 + CREATE_NEW 已建单：按新幂等键返回已有任务")
    void missingDraftCreateNewIsIdempotent() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskEntity created = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        created.setRequestOperation(DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.empty());
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(created);

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CREATE_NEW", "req_decide_004"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService, never()).createFromDraft(any(), any(), any());
    }

    @Test
    @DisplayName("草案缺失 + 非 CREATE_NEW：DRAFT_EXPIRED_OR_NOT_FOUND")
    void missingDraftOtherDecisionIsExpired() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_005")));
    }

    @Test
    @DisplayName("跨用户/已取消目标任务：404")
    void crossUserOrCanceledTargetIsNotFound() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(null);
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("DISCARD", "req_decide_006")));

        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID))
                .thenReturn(task("CANCELED", null, null, null));
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("DISCARD", "req_decide_007")));
    }

    @Test
    @DisplayName("非法 decision / 过短原文 / 非法 reference_time / 缺 client_request_id")
    void requestValidation() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("BOGUS", "req_decide_008")));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.extract(USER_ID, new ExtractDdlTaskRequest(
                        "短", "req_extract_001", false, null, null)));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.extract(USER_ID, new ExtractDdlTaskRequest(
                        "请于2099年12月31日提交实验报告", "req_extract_001", false,
                        null, "not-a-time")));
        assertError(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                () -> service.extract(USER_ID, new ExtractDdlTaskRequest(
                        "请于2099年12月31日提交实验报告", null, false, null, null)));
    }

    @Test
    @DisplayName("手工建单占用同一请求 ID：通知提取必须 409，不能返回手工任务")
    void manualTaskWithSameIdempotencyKeyConflicts() {
        DdlTaskEntity manual = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        manual.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        manual.setRequestFingerprint("manual-fingerprint");
        manual.setRawSourceText(null);
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(manual);

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.extract(USER_ID, request(false, null)));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("回执已过期（Redis 删除）后旧 ID 改为 force_create=true：仍必须 409")
    void forceCreateAfterReceiptExpiryStillConflicts() {
        // 数据库里仍是第一次请求（force_create=false）的指纹；Redis 回执已过期。
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);
        when(draftStore.loadReceipt(any(), any(), any())).thenReturn(Optional.empty());

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.extract(USER_ID, request(true, null)));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("语义提示终态回执指向已取消任务：接管后重新处理，不返回已取消卡片")
    void semanticReceiptForCanceledTaskIsReprocessed() {
        DdlTaskEntity canceled = task("CANCELED", LocalDate.of(2099, 12, 31),
                null, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        DdlRequestReceipt receipt = DdlRequestReceipt.of(fingerprint(false),
                DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT.name(), TASK_ID, null,
                "2099-12-31T22:00:00");
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receipt));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(canceled);
        when(draftStore.takeOverProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                any())).thenReturn("takeover-token");
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        verify(draftStore).takeOverProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                eq("receipt-json"));
        verify(taskService).createExtractedTask(any());
    }

    @Test
    @DisplayName("语义提示后同 ID 重试：重放终态回执，模型只被调用一次")
    void semanticHintRetryReplaysReceiptWithoutModelCall() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                null, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(service.extract(USER_ID, request(false, null)).extractOutcome())
                .isEqualTo(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT);
        ArgumentCaptor<DdlRequestReceipt> receiptCaptor =
                ArgumentCaptor.forClass(DdlRequestReceipt.class);
        verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                receiptCaptor.capture(), anyBoolean());

        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receiptCaptor.getValue()));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(existing);

        DdlExtractVO replayed = service.extract(USER_ID, request(false, null));

        assertThat(replayed.extractOutcome()).isEqualTo(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT);
        assertThat(replayed.task().taskId()).isEqualTo(TASK_ID);
        verify(pythonClient, times(1)).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("草案后同 ID 重试：重放草案，不重新提取也不覆盖草案")
    void draftRetryReplaysWithoutReextractingOrOverwriting() {
        stubPython(payload("操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "23:59:00", 0.96, false, false));
        DdlTaskEntity similar = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectList(any())).thenReturn(List.of(similar));

        assertThat(service.extract(USER_ID, request(false, null)).extractOutcome())
                .isEqualTo(DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED);
        ArgumentCaptor<DdlRequestReceipt> receiptCaptor =
                ArgumentCaptor.forClass(DdlRequestReceipt.class);
        ArgumentCaptor<DdlTaskDraft> draftCaptor = ArgumentCaptor.forClass(DdlTaskDraft.class);
        verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                receiptCaptor.capture(), anyBoolean());
        verify(draftStore, times(1)).saveDraft(eq(USER_ID), draftCaptor.capture());

        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receiptCaptor.getValue()));
        when(draftStore.loadDraft(USER_ID, TASK_ID))
                .thenReturn(Optional.of(draftCaptor.getValue()));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(similar);

        DdlExtractVO replayed = service.extract(USER_ID, request(false, null));

        assertThat(replayed.extractOutcome())
                .isEqualTo(DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED);
        assertThat(replayed.changeDraft()).isNotNull();
        verify(pythonClient, times(1)).postJson(any(), any(), any(), any(Duration.class));
        verify(draftStore, times(1)).saveDraft(any(), any());
    }

    @Test
    @DisplayName("语义提示后旧 ID + force_create=true：幂等冲突，不建单")
    void forceCreateOnOldRequestIdConflicts() {
        DdlRequestReceipt receipt = DdlRequestReceipt.of(fingerprint(false),
                DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT.name(), TASK_ID, null,
                "2099-12-31T22:00:00");
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receipt));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.extract(USER_ID, request(true, null)));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
        verify(taskService, never()).createExtractedTask(any());
    }

    @Test
    @DisplayName("同一请求 ID 换原文：幂等冲突，不重新执行")
    void changedContentForSameRequestIdConflicts() {
        DdlRequestReceipt receipt = DdlRequestReceipt.of(fingerprint(false),
                DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT.name(), TASK_ID, null,
                "2099-12-31T22:00:00");
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(DdlDraftStore.RequestLock.processed("receipt-json"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receipt));

        ExtractDdlTaskRequest changed = new ExtractDdlTaskRequest(
                "请于2099年12月31日23:00前提交实验报告", "req_extract_001", false, null,
                "2026-10-15T11:40:00+08:00");

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.extract(USER_ID, changed));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("同用户同 content_hash 并发：内容锁忙返回 REQUEST_IN_PROGRESS，不建单")
    void concurrentSameContentIsSerialized() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        when(draftStore.acquireContentLock(eq(USER_ID), any())).thenReturn(null);

        assertError(ErrorCode.REQUEST_IN_PROGRESS,
                () -> service.extract(USER_ID, request(false, null)));
        verify(taskService, never()).createExtractedTask(any());
    }

    @Test
    @DisplayName("CONFIRM_UPDATE 同 ID 重试：重放决策回执，不重复更新")
    void confirmDecisionRetryReplaysReceipt() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(draftStore.loadDecisionReceipt(USER_ID, "req_decide_001")).thenReturn(
                Optional.of(DdlDecisionReceipt.of("CONFIRM_UPDATE:" + TASK_ID,
                        "CONFIRM_UPDATE", TASK_ID, TASK_ID, "2099-12-31T22:00:00")));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(target);

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
        verify(draftStore, never()).loadDraft(any(), any());
    }

    @Test
    @DisplayName("同一决策 ID 换 decision：幂等冲突")
    void decisionReusedForDifferentDecisionConflicts() {
        when(draftStore.loadDecisionReceipt(USER_ID, "req_decide_001")).thenReturn(
                Optional.of(DdlDecisionReceipt.of("DISCARD:" + TASK_ID,
                        "DISCARD", TASK_ID, TASK_ID, "2099-12-31T22:00:00")));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001")));
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("同一请求重试：数据库已存在任务时统一返回 IDEMPOTENT_HIT，模型只调用一次")
    void createdRetryReturnsIdempotentHit() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        DdlExtractVO first = service.extract(USER_ID, request(false, null));
        assertThat(first.extractOutcome()).isEqualTo(DdlExtractOutcome.CREATED);
        DdlTaskEntity created = capturedCreatedEntity();
        ArgumentCaptor<DdlRequestReceipt> receiptCaptor =
                ArgumentCaptor.forClass(DdlRequestReceipt.class);
        verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID), eq("req_extract_001"),
                receiptCaptor.capture(), eq(false));

        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(created);
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), eq("req_extract_001")))
                .thenReturn(Optional.of(receiptCaptor.getValue()));

        DdlExtractVO second = service.extract(USER_ID, request(false, null));

        assertThat(second.extractOutcome()).isEqualTo(DdlExtractOutcome.IDEMPOTENT_HIT);
        assertThat(second.task().taskId()).isEqualTo(TASK_ID);
        verify(pythonClient, times(1)).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("内容锁覆盖“语义判定 + 建单 + 回执”：顺序为 acquire → create → markProcessed → release")
    void contentLockCoversCreateAndReceipt() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));

        service.extract(USER_ID, request(false, null));

        InOrder order = inOrder(draftStore, taskService);
        order.verify(draftStore).acquireContentLock(eq(USER_ID), any());
        order.verify(taskService).createExtractedTask(any());
        order.verify(draftStore).markProcessed(eq("WEB"), eq(USER_ID),
                eq("req_extract_001"), any(DdlRequestReceipt.class), eq(false));
        order.verify(draftStore).releaseContentLock(eq(USER_ID), any(), eq("content-token"));
    }

    @Test
    @DisplayName("并发不同请求 ID 相同内容：内容锁保证只建一单，另一个 REQUEST_IN_PROGRESS")
    void concurrentSameContentCreatesAtMostOneTask() throws Exception {
        CountDownLatch bothExtracted = new CountDownLatch(2);
        when(pythonClient.postJson(any(), any(), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    bothExtracted.countDown();
                    bothExtracted.await(5, TimeUnit.SECONDS);
                    return data(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                            "2099-12-31", null, 0.96, false, false));
                });
        when(draftStore.acquireRequestLock(eq("WEB"), eq(USER_ID), anyString()))
                .thenReturn(DdlDraftStore.RequestLock.acquired("request-token"));
        when(draftStore.loadReceipt(eq("WEB"), eq(USER_ID), anyString()))
                .thenReturn(Optional.empty());
        AtomicBoolean first = new AtomicBoolean(false);
        when(draftStore.acquireContentLock(eq(USER_ID), any()))
                .thenAnswer(invocation -> first.compareAndSet(false, true) ? "token-1" : null);
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectList(any())).thenReturn(List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<DdlExtractVO> a = pool.submit(() -> {
            start.await();
            return service.extract(USER_ID, request("req_concurrent_a", false));
        });
        Future<DdlExtractVO> b = pool.submit(() -> {
            start.await();
            return service.extract(USER_ID, request("req_concurrent_b", false));
        });
        start.countDown();

        int created = 0;
        int rejected = 0;
        for (Future<DdlExtractVO> future : List.of(a, b)) {
            try {
                if (future.get(5, TimeUnit.SECONDS).extractOutcome()
                        == DdlExtractOutcome.CREATED) {
                    created++;
                }
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof BusinessException business
                        && business.getErrorCode() == ErrorCode.REQUEST_IN_PROGRESS) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();

        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        verify(taskService, times(1)).createExtractedTask(any());
    }

    @Test
    @DisplayName("语义提示回执写入失败：失败关闭 INTERNAL_ERROR")
    void semanticReceiptWriteFailureIsClosed() {
        stubPython(payload("操作系统原理", "提交实验二报告", "DATE_ONLY",
                "2099-12-31", null, 0.96, false, false));
        when(mapper.selectOne(any())).thenReturn(task("ACTIVE",
                LocalDate.of(2099, 12, 31), null,
                LocalDateTime.of(2099, 12, 31, 23, 59, 59)));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(draftStore).markProcessed(anyString(), eq(USER_ID), anyString(),
                        any(DdlRequestReceipt.class), eq(true));

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> service.extract(USER_ID, request(false, null)));
    }

    @Test
    @DisplayName("并发不同请求 ID / 不同 decision 处理同一草案：只允许一个进入，只产生一种业务效果")
    void concurrentDifferentRequestIdsOnSameDraftOnlyOneWins() throws Exception {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));
        when(taskService.createFromDraft(USER_ID, draft, "req_decide_new"))
                .thenReturn(new DdlTaskService.DdlTaskCreationResult(
                        DdlTaskService.toVO(target, ZONE), true));
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicBoolean first = new AtomicBoolean(false);
        when(draftStore.acquireDraftDecisionLock(eq(USER_ID), eq(TASK_ID)))
                .thenAnswer(invocation -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return first.compareAndSet(false, true) ? "draft-lock" : null;
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<DdlTaskVO> update = pool.submit(() -> {
            start.await();
            return service.decideDraft(USER_ID, TASK_ID,
                    new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_update"));
        });
        Future<DdlTaskVO> create = pool.submit(() -> {
            start.await();
            return service.decideDraft(USER_ID, TASK_ID,
                    new DraftDecisionRequest("CREATE_NEW", "req_decide_new"));
        });
        start.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<DdlTaskVO> future : List.of(update, create)) {
            try {
                future.get(5, TimeUnit.SECONDS);
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof BusinessException business
                        && business.getErrorCode() == ErrorCode.REQUEST_IN_PROGRESS) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // 只允许一种业务效果：要么更新原任务，要么新建任务，不能两者都发生。
        int effects = org.mockito.Mockito.mockingDetails(taskService).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("applyDraftUpdate")
                        || invocation.getMethod().getName().equals("createFromDraft"))
                .toList().size();
        assertThat(effects).isEqualTo(1);
    }

    @Test
    @DisplayName("持久化决策回执命中：直接重放，不再次执行数据库动作")
    void durableDecisionReceiptReplaysWithoutRepeatingAction() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(decisionReceiptStore.find(USER_ID, "req_decide_001")).thenReturn(Optional.of(
                DdlDecisionReceipt.of("CONFIRM_UPDATE:" + TASK_ID, "CONFIRM_UPDATE",
                        TASK_ID, TASK_ID, "2099-12-31T22:00:00")));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(target);

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
        verify(taskService, never()).createFromDraft(any(), any(), any());
        verify(draftStore, never()).acquireDraftDecisionLock(any(), any());
    }

    @Test
    @DisplayName("CREATE_NEW 也在事务内锁定目标任务行，并校验版本")
    void createNewLocksTargetRowAndChecksVersion() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        target.setUpdatedAt(LocalDateTime.of(2026, 10, 16, 12, 0));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(target, TASK_ID,
                LocalDateTime.of(2026, 10, 16, 11, 40), "2026-10-16T11:40:00");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CREATE_NEW", "req_decide_stale")));
        verify(mapper).selectByIdForUpdate(TASK_ID, USER_ID);
        verify(taskService, never()).createFromDraft(any(), any(), any());
    }

    @Test
    @DisplayName("决策处理中锁忙：409，不执行第二次决策")
    void decisionLockBusyRejects() {
        when(draftStore.acquireDraftDecisionLock(USER_ID, TASK_ID)).thenReturn(null);

        assertError(ErrorCode.REQUEST_IN_PROGRESS,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("DISCARD", "req_decide_001")));
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
        verify(draftStore, never()).deleteDraftAfterCommitStrict(any(), any());
    }

    @Test
    @DisplayName("并发同一决策 ID：处理中锁保证只有一次执行，另一个 REQUEST_IN_PROGRESS")
    void concurrentDecisionExecutesOnce() throws Exception {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicBoolean first = new AtomicBoolean(false);
        when(draftStore.acquireDraftDecisionLock(eq(USER_ID), eq(TASK_ID)))
                .thenAnswer(invocation -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return first.compareAndSet(false, true) ? "lock-token" : null;
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<DdlTaskVO> a = pool.submit(() -> {
            start.await();
            return service.decideDraft(USER_ID, TASK_ID,
                    new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_conc"));
        });
        Future<DdlTaskVO> b = pool.submit(() -> {
            start.await();
            return service.decideDraft(USER_ID, TASK_ID,
                    new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_conc"));
        });
        start.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<DdlTaskVO> future : List.of(a, b)) {
            try {
                future.get(5, TimeUnit.SECONDS);
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof BusinessException business
                        && business.getErrorCode() == ErrorCode.REQUEST_IN_PROGRESS) {
                    rejected++;
                } else {
                    throw e;
                }
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        verify(taskService, times(1)).applyDraftUpdate(USER_ID, TASK_ID, draft);
    }

    @Test
    @DisplayName("持久化决策回执写入失败：失败关闭，且不删除草案（可重试收敛）")
    void decisionReceiptWriteFailureKeepsDraft() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(decisionReceiptStore).saveStrict(eq(USER_ID), eq("req_decide_001"),
                        any(DdlDecisionReceipt.class));

        assertError(ErrorCode.INTERNAL_ERROR,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001")));
        verify(draftStore, never()).deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("Redis 决策回执缓存写入失败只是缓存失效，不影响已持久化的决策")
    void decisionCacheWriteFailureDoesNotFailRequest() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));
        doThrow(new RuntimeException("redis down"))
                .when(draftStore).saveDecisionReceipt(eq(USER_ID), eq("req_decide_001"),
                        any(DdlDecisionReceipt.class));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(decisionReceiptStore).saveStrict(eq(USER_ID), eq("req_decide_001"),
                any(DdlDecisionReceipt.class));
        verify(draftStore).deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("同一决策 ID 已被用于另一 decision：409，不执行")
    void decisionReceiptMismatchRejects() {
        when(draftStore.loadDecisionReceipt(USER_ID, "req_decide_001")).thenReturn(
                Optional.of(DdlDecisionReceipt.of("DISCARD:" + TASK_ID,
                        "DISCARD", TASK_ID, TASK_ID, "2099-12-31T22:00:00")));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001")));
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
    }

    private static ExtractDdlTaskRequest request(String clientRequestId, boolean forceCreate) {
        return new ExtractDdlTaskRequest("请于2099年12月31日22:00前提交实验报告",
                clientRequestId, forceCreate, null, "2026-10-15T11:40:00+08:00");
    }

    private static String fingerprint(boolean forceCreate) {
        return DdlRequestFingerprint.forExtract(
                "请于2099年12月31日22:00前提交实验报告", null, forceCreate);
    }

    private void stubPython(Map<String, Object> payload) {
        when(pythonClient.postJson(
                eq(PythonAiEngineClient.ROUTE_AND_EXTRACT_PATH), any(), eq(USER_ID),
                any(Duration.class))).thenReturn(data(payload));
    }

    private static ExtractDdlTaskRequest request(boolean forceCreate, String courseId) {
        return new ExtractDdlTaskRequest("请于2099年12月31日22:00前提交实验报告",
                "req_extract_001", forceCreate, courseId, "2026-10-15T11:40:00+08:00");
    }

    private static Map<String, Object> payload(String courseName, String taskTitle,
                                               String precision, String dueDate,
                                               String dueTime, double confidence,
                                               boolean hasAmbiguity, boolean relative) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_name", courseName);
        payload.put("task_title", taskTitle);
        payload.put("raw_time_text", "2099年12月31日22:00前");
        payload.put("time_precision", precision);
        payload.put("is_relative_time", relative);
        payload.put("parsed_due_date", dueDate);
        payload.put("parsed_due_time", dueTime);
        payload.put("deliverable_format", "zip");
        payload.put("submission_channel", "学习通");
        payload.put("confidence", confidence);
        payload.put("has_ambiguity", hasAmbiguity);
        payload.put("ambiguity_reason", hasAmbiguity ? "需人工确认" : null);
        return payload;
    }

    private static JsonNode data(Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intent", "DDL_EXTRACT");
        body.put("intent_confidence", 0.98);
        body.put("ddl_payload", payload);
        return MAPPER.valueToTree(body);
    }

    private DdlTaskEntity capturedCreatedEntity() {
        ArgumentCaptor<DdlTaskEntity> captor = ArgumentCaptor.forClass(DdlTaskEntity.class);
        verify(taskService).createExtractedTask(captor.capture());
        return captor.getValue();
    }

    private static DdlTaskEntity task(String status, LocalDate dueDate, LocalTime dueTime,
                                      LocalDateTime dueAt) {
        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setUserId(USER_ID);
        entity.setCourseId(COURSE_ID);
        entity.setCourseName("操作系统原理");
        entity.setTaskTitle("提交实验二报告");
        entity.setDeliverableFormat("zip");
        entity.setSubmissionChannel("学习通");
        entity.setTimePrecision("EXACT_DATETIME");
        entity.setIsRelativeTime(0);
        entity.setDueDate(dueDate);
        entity.setDueTime(dueTime);
        entity.setDueAt(dueAt);
        entity.setTimeInferred(0);
        entity.setUserTimezone("Asia/Shanghai");
        entity.setConfidence(new BigDecimal("0.960"));
        entity.setStatus(status);
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform("WEB");
        entity.setSourceMsgId("req_old_001");
        entity.setRequestOperation(DdlRequestFingerprint.OPERATION_EXTRACT);
        entity.setRequestFingerprint(DdlRequestFingerprint.forExtract(
                "请于2099年12月31日22:00前提交实验报告", null, false));
        entity.setIdempotencyKey("old-key");
        entity.setContentHash("old-hash");
        entity.setCreatedAt(LocalDateTime.of(2026, 10, 15, 11, 40));
        entity.setUpdatedAt(LocalDateTime.of(2026, 10, 15, 11, 40));
        return entity;
    }

    private static DdlTaskDraft draft(DdlTaskEntity target) {
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        return DdlTaskDraft.fromEntity(candidate, target.getTaskId(),
                target.getUpdatedAt(), "2026-10-16T11:40:00");
    }

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(expected));
    }
}
