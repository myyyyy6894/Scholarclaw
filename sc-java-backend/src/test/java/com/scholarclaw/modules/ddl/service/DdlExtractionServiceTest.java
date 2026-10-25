package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.scholarclaw.modules.ddl.service.DdlDraftStore.LockState;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        service = new DdlExtractionService(pythonClient, courseService, mapper, taskService,
                draftStore, new DdlSemanticMatcher(), userConfigResolver,
                new DdlTimeRuleValidator());

        when(userConfigResolver.resolve(USER_ID)).thenReturn(new ResolvedUser(
                new UserConfigEntity(), "Asia/Shanghai", ZONE));
        when(userConfigResolver.resolveChatEndpoint(any())).thenReturn(CHAT);
        when(courseService.listCourses(USER_ID)).thenReturn(List.of(
                new CourseNamespaceVO(COURSE_ID, "操作系统原理", null, false,
                        "OPENAI_COMPATIBLE", "embed", 1024, 1, "ACTIVE", 0, 0, 0,
                        "2026-10-15T11:40:00+08:00", "2026-10-15T11:40:00+08:00")));
        when(draftStore.acquireWebRequest(USER_ID, "req_extract_001"))
                .thenReturn(LockState.ACQUIRED);
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
        verify(draftStore).markProcessed(USER_ID, "req_extract_001");
        verify(draftStore, never()).release(USER_ID, "req_extract_001");
    }

    @Test
    @DisplayName("请求级幂等命中：不再调用 Python，直接返回 IDEMPOTENT_HIT")
    void dbIdempotencyHitSkipsPython() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(mapper.selectByIdempotencyKey(any())).thenReturn(existing);

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.IDEMPOTENT_HIT);
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
        verify(draftStore, never()).acquireWebRequest(any(), any());
    }

    @Test
    @DisplayName("Redis PROCESSING：返回 409 REQUEST_IN_PROGRESS，不调用 Python")
    void redisProcessingRejectsConcurrentRequest() {
        when(draftStore.acquireWebRequest(USER_ID, "req_extract_001"))
                .thenReturn(LockState.PROCESSING);

        assertError(ErrorCode.REQUEST_IN_PROGRESS, () -> service.extract(USER_ID, request(false, null)));
        verify(pythonClient, never()).postJson(any(), any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis PROCESSED 且数据库已有任务：返回 IDEMPOTENT_HIT")
    void redisProcessedWithTaskReturnsIdempotentHit() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(mapper.selectByIdempotencyKey(any())).thenReturn(null, existing);
        when(draftStore.acquireWebRequest(USER_ID, "req_extract_001"))
                .thenReturn(LockState.PROCESSED);

        DdlExtractVO result = service.extract(USER_ID, request(false, null));

        assertThat(result.extractOutcome()).isEqualTo(DdlExtractOutcome.IDEMPOTENT_HIT);
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
        verify(draftStore).markProcessed(USER_ID, "req_extract_001");
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
        verify(draftStore).release(USER_ID, "req_extract_001");
        verify(draftStore, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("Python 返回非法枚举：UPSTREAM_MODEL_ERROR，释放锁")
    void invalidPythonPayloadReleasesLock() {
        Map<String, Object> payload = payload("操作系统原理", "任务", "SOMETIME",
                "2099-12-31", null, 0.96, false, false);
        stubPython(payload);

        assertError(ErrorCode.UPSTREAM_MODEL_ERROR,
                () -> service.extract(USER_ID, request(false, null)));
        verify(draftStore).release(USER_ID, "req_extract_001");
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
        when(mapper.selectOne(any())).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.applyDraftUpdate(USER_ID, TASK_ID, draft))
                .thenReturn(DdlTaskService.toVO(target, ZONE));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_001"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService).applyDraftUpdate(USER_ID, TASK_ID, draft);
        verify(draftStore).deleteDraft(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("CREATE_NEW：使用新的 client_request_id 建单并删除草案")
    void createNewCreatesIndependentTask() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskDraft draft = draft(target);
        when(mapper.selectOne(any())).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.of(draft));
        when(taskService.createFromDraft(USER_ID, draft, "req_decide_002"))
                .thenReturn(new DdlTaskService.DdlTaskCreationResult(
                        DdlTaskService.toVO(target, ZONE), true));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("CREATE_NEW", "req_decide_002"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(taskService).createFromDraft(USER_ID, draft, "req_decide_002");
        verify(draftStore).deleteDraft(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("DISCARD：删除草案并返回原任务")
    void discardDeletesDraftAndReturnsTarget() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectOne(any())).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID))
                .thenReturn(Optional.of(draft(target)));

        DdlTaskVO result = service.decideDraft(USER_ID, TASK_ID,
                new DraftDecisionRequest("DISCARD", "req_decide_003"));

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        verify(draftStore).deleteDraft(USER_ID, TASK_ID);
        verify(taskService, never()).applyDraftUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("草案缺失 + CREATE_NEW 已建单：按新幂等键返回已有任务")
    void missingDraftCreateNewIsIdempotent() {
        DdlTaskEntity target = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskEntity created = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        when(mapper.selectOne(any())).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.empty());
        when(mapper.selectByIdempotencyKey(any())).thenReturn(created);

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
        when(mapper.selectOne(any())).thenReturn(target);
        when(draftStore.loadDraft(USER_ID, TASK_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("CONFIRM_UPDATE", "req_decide_005")));
    }

    @Test
    @DisplayName("跨用户/已取消目标任务：404")
    void crossUserOrCanceledTargetIsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.decideDraft(USER_ID, TASK_ID,
                        new DraftDecisionRequest("DISCARD", "req_decide_006")));

        when(mapper.selectOne(any())).thenReturn(task("CANCELED", null, null, null));
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
                "2026-10-16T11:40:00");
    }

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(expected));
    }
}
