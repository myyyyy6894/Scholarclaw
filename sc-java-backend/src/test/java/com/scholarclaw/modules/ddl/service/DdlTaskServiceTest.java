package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DeleteDdlTaskVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.MybatisTableInfoTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/** TASK-703 DDL 持久化、状态机、所有权与并发控制。 */
class DdlTaskServiceTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final long COURSE_ID = 2001L;

    private DdlTaskMapper mapper;
    private CourseService courseService;
    private UserConfigService userConfigService;
    private DdlDraftStore draftStore;
    private DdlTaskService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisTableInfoTestSupport.init(DdlTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(DdlTaskMapper.class);
        courseService = mock(CourseService.class);
        userConfigService = mock(UserConfigService.class);
        draftStore = mock(DdlDraftStore.class);
        service = new DdlTaskService(mapper, courseService, userConfigService,
                new DdlTimeRuleValidator(), draftStore);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(userConfig("Asia/Shanghai")));
        when(draftStore.listDrafts(USER_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            DdlTaskEntity entity = invocation.getArgument(0);
            if (entity.getTaskId() == null) {
                entity.setTaskId(TASK_ID);
            }
            return 1;
        }).when(mapper).insert(any(DdlTaskEntity.class));
    }

    @Test
    @DisplayName("手动 EXACT 未来任务：写入完整字段、WEB 来源、ACTIVE、请求级幂等键")
    void createExplicitTaskWritesAllFields() {
        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_001", null, "操作系统原理", "提交实验二报告",
                        "EXACT_DATETIME", "2099-12-31", "22:00")).task();

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getTaskId()).isEqualTo(TASK_ID);
        assertThat(entity.getUserId()).isEqualTo(USER_ID);
        assertThat(entity.getCourseId()).isNull();
        assertThat(entity.getCourseName()).isEqualTo("操作系统原理");
        assertThat(entity.getTaskTitle()).isEqualTo("提交实验二报告");
        assertThat(entity.getDeliverableFormat()).isEqualTo("zip");
        assertThat(entity.getSubmissionChannel()).isEqualTo("学习通");
        assertThat(entity.getTimePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(entity.getIsRelativeTime()).isZero();
        assertThat(entity.getDueDate()).isEqualTo(LocalDate.of(2099, 12, 31));
        assertThat(entity.getDueTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(entity.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
        assertThat(entity.getTimeInferred()).isZero();
        assertThat(entity.getUserTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(entity.getConfidence()).isEqualByComparingTo("1.000");
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getFollowUpAt()).isNull();
        assertThat(entity.getFollowUpCount()).isZero();
        assertThat(entity.getRemindedFlags()).isZero();
        assertThat(entity.getSourcePlatform()).isEqualTo("WEB");
        assertThat(entity.getSourceMsgId()).isEqualTo("req_manual_001");
        assertThat(entity.getIdempotencyKey())
                .isEqualTo(DdlIdempotencyKey.forWebRequest(USER_ID, "req_manual_001"));
        assertThat(entity.getContentHash()).isNull();
        assertThat(entity.getRawSourceText()).isNull();
        assertThat(entity.getRawTimeText()).isNull();

        assertThat(vo.taskId()).isEqualTo(TASK_ID);
        assertThat(vo.displayCode()).isEqualTo("#T103");
        assertThat(vo.dueAt()).isEqualTo(OffsetDateTime.parse("2099-12-31T22:00:00+08:00"));
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.timeInferred()).isFalse();
    }

    @Test
    @DisplayName("手动 DATE_ONLY：due_time 为空、due_at 23:59:59、time_inferred=true")
    void createDateOnlyUsesSentinel() {
        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_002", null, "操作系统原理", "提交实验二报告",
                        "DATE_ONLY", "2099-12-31", null)).task();

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getDueTime()).isNull();
        assertThat(entity.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        assertThat(entity.getTimeInferred()).isEqualTo(1);
        assertThat(vo.dueTime()).isNull();
        assertThat(vo.dueAt()).isEqualTo(OffsetDateTime.parse("2099-12-31T23:59:59+08:00"));
        assertThat(vo.timeInferred()).isTrue();
    }

    @Test
    @DisplayName("手动 VAGUE_OR_MISSING：due 字段全空、PENDING_TIME、follow_up_at 非空")
    void createVagueIsPendingTime() {
        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_003", null, "操作系统原理", "尽快提交实验报告",
                        "VAGUE_OR_MISSING", null, null)).task();

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getDueDate()).isNull();
        assertThat(entity.getDueTime()).isNull();
        assertThat(entity.getDueAt()).isNull();
        assertThat(entity.getTimeInferred()).isZero();
        assertThat(entity.getStatus()).isEqualTo("PENDING_TIME");
        assertThat(entity.getFollowUpAt()).isNotNull();
        assertThat(vo.dueAt()).isNull();
        assertThat(vo.followUpAt()).isNotNull();
        assertThat(vo.status()).isEqualTo("PENDING_TIME");
    }

    @Test
    @DisplayName("手动过去时间：PENDING_CONFIRM，保留过去 due_at 供用户确认")
    void createPastTaskIsPendingConfirm() {
        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_004", null, "操作系统原理", "补录旧任务",
                        "EXACT_DATETIME", "2000-01-01", "22:00")).task();

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(entity.getDueAt()).isNotNull();
        assertThat(entity.getFollowUpAt()).isNotNull();
        assertThat(vo.status()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    @DisplayName("传了有效 course_id 时自动对齐课程名称")
    void createAlignsCourseNameWhenCourseIdProvided() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "数据库系统概念"));

        service.createManualTask(USER_ID,
                createRequest("req_manual_005", "2001", "旧名称", "完成 SQL 作业",
                        "DATE_ONLY", "2099-12-31", null));

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getCourseId()).isEqualTo(COURSE_ID);
        assertThat(entity.getCourseName()).isEqualTo("数据库系统概念");
    }

    @Test
    @DisplayName("幂等键唯一冲突时回查已有任务并幂等返回")
    void duplicateIdempotencyKeyReturnsExistingTask() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        CreateDdlTaskRequest request = createRequest("req_manual_001", null, "操作系统原理",
                "提交实验二报告", "EXACT_DATETIME", "2099-12-31", "22:00");
        existing.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        existing.setRequestFingerprint(DdlRequestFingerprint.forManual(request));
        when(mapper.insert(any(DdlTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_ddl_idempotency_key"));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);

        DdlTaskVO vo = service.createManualTask(USER_ID, request).task();

        assertThat(vo.taskId()).isEqualTo(TASK_ID);
        verify(mapper).selectByIdempotencyKey(any(), any());
    }

    @Test
    @DisplayName("同一请求 ID 但手工表单内容变化：409 IDEMPOTENCY_CONFLICT")
    void duplicateIdempotencyKeyWithChangedManualContentConflicts() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        existing.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        existing.setRequestFingerprint(DdlRequestFingerprint.forManual(createRequest(
                "req_manual_001", null, "操作系统原理", "提交实验二报告",
                "EXACT_DATETIME", "2099-12-31", "22:00")));
        when(mapper.insert(any(DdlTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_ddl_idempotency_key"));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT, () -> service.createManualTask(USER_ID,
                createRequest("req_manual_001", null, "操作系统原理", "换成另一个任务",
                        "EXACT_DATETIME", "2099-12-31", "22:00")));
    }

    @Test
    @DisplayName("手工建单与通知提取复用同一 ID：操作类型不同 → 409")
    void manualAndExtractSharingRequestIdConflicts() {
        DdlTaskEntity manual = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        manual.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        manual.setRequestFingerprint("manual-fingerprint");
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(manual);
        when(mapper.insert(any(DdlTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_ddl_idempotency_key"));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT, () ->
                service.createExtractedTask(extractedEntity("request-fingerprint")));
    }

    @Test
    @DisplayName("缺失 client_request_id → IDEMPOTENCY_KEY_REQUIRED，不写库")
    void missingClientRequestIdIsRejected() {
        assertError(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                () -> service.createManualTask(USER_ID,
                        createRequest(null, null, "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
        verify(mapper, never()).insert(any(DdlTaskEntity.class));
    }

    @Test
    @DisplayName("client_request_id 过短 → VALIDATION_ERROR")
    void shortClientRequestIdIsRejected() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.createManualTask(USER_ID,
                        createRequest("short", null, "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("非法 course_id → VALIDATION_ERROR，不查课程")
    void invalidCourseIdIsRejected() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.createManualTask(USER_ID,
                        createRequest("req_manual_006", "abc", "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
        verify(courseService, never()).getActiveCourseForUpdate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("跨用户/已删除课程 → RESOURCE_NOT_FOUND")
    void notOwnedCourseIsRejected() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.createManualTask(USER_ID,
                        createRequest("req_manual_007", "2001", "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("用户时区非法 → VALIDATION_ERROR")
    void invalidUserTimezoneIsRejected() {
        when(userConfigService.findById(USER_ID))
                .thenReturn(Optional.of(userConfig("Mars/Base")));

        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.createManualTask(USER_ID,
                        createRequest("req_manual_008", null, "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("注销中账号 → ACCOUNT_DEACTIVATED")
    void deactivatedUserIsRejected() {
        UserConfigEntity config = userConfig("Asia/Shanghai");
        config.setAccountStatus("DEACTIVATING");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertError(ErrorCode.ACCOUNT_DEACTIVATED,
                () -> service.createManualTask(USER_ID,
                        createRequest("req_manual_009", null, "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("用户不存在 → AUTH_UNAUTHORIZED")
    void unknownUserIsRejected() {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.AUTH_UNAUTHORIZED,
                () -> service.createManualTask(USER_ID,
                        createRequest("req_manual_010", null, "操作系统原理", "任务",
                                "DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("编辑 PENDING_TIME 任务补充未来时间 → ACTIVE，清空 follow_up_at/completed_at")
    void updateActivatesPendingTask() {
        DdlTaskEntity existing = task("PENDING_TIME", null, null, null);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
        assertThat(existing.getFollowUpAt()).isNull();
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(existing.getFollowUpCount()).isZero();
        assertThat(vo.status()).isEqualTo("ACTIVE");
        verify(mapper).update(isNull(), any());
    }

    @Test
    @DisplayName("编辑 COMPLETED 任务：只更新字段，保持完成态与 completed_at，不自动重开")
    void updateCompletedKeepsCompletedStatus() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        LocalDateTime completedAt = LocalDateTime.of(2026, 10, 15, 12, 0);
        existing.setCompletedAt(completedAt);
        existing.setRemindedFlags(7);
        existing.setTimeAmbiguityReason("旧的确认原因");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(vo.status()).isEqualTo("COMPLETED");
        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getCompletedAt()).isEqualTo(completedAt);
        assertThat(existing.getFollowUpAt()).isNull();
        // 完成态延期：旧提醒位对应旧截止时间，必须清零
        assertThat(existing.getRemindedFlags()).isZero();
        assertThat(existing.getTimeAmbiguityReason()).isNull();
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
    }

    @Test
    @DisplayName("编辑为明确未来时间：清空旧确认原因")
    void updateToFutureClearsAmbiguityReason() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        existing.setTimeAmbiguityReason("时间被判定为非法或已过去，需人工确认");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getTimeAmbiguityReason()).isNull();
    }

    @Test
    @DisplayName("编辑仍为过去时间：写入新的明确原因")
    void updateToPastWritesAmbiguityReason() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setTimeAmbiguityReason(null);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2000-01-01", "22:00"));

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getTimeAmbiguityReason()).contains("早于当前时间");
    }

    @Test
    @DisplayName("完成态延期后取消完成：按新剩余时长重算提醒位")
    void uncompleteAfterExtensionRecalculatesFlags() {
        LocalDateTime dueSoon = LocalDateTime.now().plusHours(2).withNano(0);
        DdlTaskEntity existing = task("COMPLETED", dueSoon.toLocalDate(),
                dueSoon.toLocalTime().withNano(0), dueSoon);
        existing.setCompletedAt(LocalDateTime.now().minusHours(1));
        existing.setRemindedFlags(7);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        // 剩余 <6h：T-24h/T-6h 位保留；逾期位清除
        assertThat(existing.getRemindedFlags()).isEqualTo(3);
    }

    @Test
    @DisplayName("取消完成且剩余超过 24h：清除 T-24h/T-6h/逾期位")
    void uncompleteWithLongRemainingClearsAllFlags() {
        LocalDateTime dueLater = LocalDateTime.now().plusHours(30).withNano(0);
        DdlTaskEntity existing = task("COMPLETED", dueLater.toLocalDate(),
                dueLater.toLocalTime().withNano(0), dueLater);
        existing.setRemindedFlags(7);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getRemindedFlags()).isZero();
    }

    @Test
    @DisplayName("编辑为过去时间 → PENDING_CONFIRM，follow_up_at 非空")
    void updateToPastIsPendingConfirm() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2000-01-01", "22:00"));

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getFollowUpAt()).isNotNull();
    }

    @Test
    @DisplayName("编辑为 VAGUE → 清空 due 字段、PENDING_TIME")
    void updateToVagueClearsDueFields() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("VAGUE_OR_MISSING", null, null));

        assertThat(existing.getStatus()).isEqualTo("PENDING_TIME");
        assertThat(existing.getDueDate()).isNull();
        assertThat(existing.getDueTime()).isNull();
        assertThat(existing.getDueAt()).isNull();
        assertThat(existing.getFollowUpAt()).isNotNull();
        assertThat(existing.getRemindedFlags()).isZero();
    }

    @Test
    @DisplayName("延期超过 24h 时重置 T-24h/T-6h/逾期提醒位")
    void updateExtensionResetsReminderFlags() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setRemindedFlags(7);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(existing.getRemindedFlags()).isZero();
    }

    @Test
    @DisplayName("截止时间未变化时保留已发送提醒位，仅清除逾期位")
    void updateUnchangedDueKeepsReminderFlags() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setRemindedFlags(3);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-30", "22:00"));

        assertThat(existing.getRemindedFlags()).isEqualTo(3);
    }

    @Test
    @DisplayName("跨用户或已取消任务编辑 → 404")
    void updateOtherUserOrCanceledIsNotFound() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(null);
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.updateTask(USER_ID, TASK_ID,
                        updateRequest("DATE_ONLY", "2099-12-31", null)));

        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(
                task("CANCELED", null, null, null));
        assertError(ErrorCode.RESOURCE_NOT_FOUND,
                () -> service.updateTask(USER_ID, TASK_ID,
                        updateRequest("DATE_ONLY", "2099-12-31", null)));
    }

    @Test
    @DisplayName("标记完成 → COMPLETED，写入 completed_at 并清空 follow_up_at")
    void completeSetsCompleted() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.setCompletion(USER_ID, TASK_ID, true);

        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getCompletedAt()).isNotNull();
        assertThat(existing.getFollowUpAt()).isNull();
        assertThat(vo.status()).isEqualTo("COMPLETED");
        assertThat(vo.completedAt()).isNotNull();
        verify(draftStore).deleteDraftAfterCommit(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("重复标记完成幂等成功，不重复写库")
    void completeAlreadyCompletedIsIdempotent() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        DdlTaskVO vo = service.setCompletion(USER_ID, TASK_ID, true);

        verify(mapper, never()).update(any(), any());
        assertThat(vo.status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PENDING_CONFIRM 直接取消完成：409 STATE_CONFLICT，零写库")
    void uncompletePendingConfirmWithoutCompletionConflicts() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        existing.setTimeAmbiguityReason("时间早于当前时间或存在歧义，请核对");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.setCompletion(USER_ID, TASK_ID, false));

        verify(mapper, never()).update(any(), any());
        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getTimeAmbiguityReason()).isNotNull();
    }

    @Test
    @DisplayName("PENDING_TIME / ACTIVE 直接取消完成：409 STATE_CONFLICT，零写库")
    void uncompleteNonCompletedStatesConflicts() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID))
                .thenReturn(task("PENDING_TIME", null, null, null));
        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.setCompletion(USER_ID, TASK_ID, false));

        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID))
                .thenReturn(task("ACTIVE", LocalDate.of(2099, 12, 30),
                        LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0)));
        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.setCompletion(USER_ID, TASK_ID, false));

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("PENDING_CONFIRM -> COMPLETED -> 取消完成：恢复 PENDING_CONFIRM，不产生 ACTIVE")
    void uncompleteRestoresPendingConfirmSnapshot() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        existing.setTimeAmbiguityReason("时间早于当前时间或存在歧义，请核对");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, true);

        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getStatusBeforeCompletion()).isEqualTo("PENDING_CONFIRM");

        service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getStatusBeforeCompletion()).isNull();
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(existing.getFollowUpAt()).isNotNull();
        assertThat(existing.getTimeAmbiguityReason()).isNotNull();
    }

    @Test
    @DisplayName("/confirm 专用方法：非待确认状态一律 409，零写库")
    void confirmRejectsNonConfirmableStates() {
        for (String status : List.of("ACTIVE", "OVERDUE", "COMPLETED")) {
            DdlTaskEntity existing = task(status, LocalDate.of(2099, 12, 30),
                    LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
            when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

            assertError(ErrorCode.STATE_CONFLICT, () -> service.confirmTask(USER_ID, TASK_ID,
                    updateRequest("EXACT_DATETIME", "2099-12-31", "22:00")));
        }
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("/confirm 确认 PENDING_CONFIRM：激活为 ACTIVE 并清空确认原因")
    void confirmPendingConfirmActivates() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        existing.setTimeAmbiguityReason("时间早于当前时间或存在歧义，请核对");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.confirmTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getTimeAmbiguityReason()).isNull();
        assertThat(existing.getFollowUpAt()).isNull();
        verify(draftStore).deleteDraftAfterCommit(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("/confirm 确认 PENDING_TIME：补充未来时间后激活")
    void confirmPendingTimeActivates() {
        DdlTaskEntity existing = task("PENDING_TIME", null, null, null);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.confirmTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getFollowUpAt()).isNull();
    }

    @Test
    @DisplayName("/confirm 使用过去时间：VALIDATION_ERROR，保持待确认")
    void confirmWithPastTimeIsRejected() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        assertError(ErrorCode.VALIDATION_ERROR, () -> service.confirmTask(USER_ID, TASK_ID,
                updateRequest("EXACT_DATETIME", "2000-01-02", "22:00")));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("批量确认：任一任务非待确认状态则整批零执行")
    void confirmBatchIsAllOrNothing() {
        DdlTaskEntity first = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        DdlTaskEntity second = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByTaskIdAndUserId(103L, USER_ID)).thenReturn(first);
        when(mapper.selectByTaskIdAndUserId(104L, USER_ID)).thenReturn(second);
        when(mapper.selectByIdForUpdate(103L, USER_ID)).thenReturn(first);
        when(mapper.selectByIdForUpdate(104L, USER_ID)).thenReturn(second);

        assertError(ErrorCode.STATE_CONFLICT, () -> service.confirmBatch(USER_ID,
                List.of(103L, 104L), LocalDate.of(2099, 12, 31), LocalTime.of(22, 0)));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("ACTIVE -> COMPLETED -> 取消完成：恢复 ACTIVE 并清空旧确认原因")
    void uncompleteActiveSnapshotClearsStaleReason() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        // 模拟历史遗留的矛盾数据：ACTIVE 却带着旧确认原因。
        existing.setTimeAmbiguityReason("旧的确认原因");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, true);
        service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getTimeAmbiguityReason()).isNull();
        assertThat(existing.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("旧数据无完成前快照但仍有确认原因：取消完成恢复 PENDING_CONFIRM")
    void legacyCompletedWithReasonRestoresPendingConfirm() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setTimeAmbiguityReason("时间早于当前时间或存在歧义，请核对");
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getTimeAmbiguityReason()).isNotNull();
    }

    @Test
    @DisplayName("批量完成整批执行；批量取消完成遇到非 COMPLETED 整批回滚")
    void batchCompletionIsAllOrNothing() {
        DdlTaskEntity first = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskEntity second = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        when(mapper.selectByIdForUpdate(103L, USER_ID)).thenReturn(first);
        when(mapper.selectByIdForUpdate(104L, USER_ID)).thenReturn(second);
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.setCompletionBatch(USER_ID, List.of(103L, 104L), false));

        // 预校验在写入前完成：不应发生任何 UPDATE。
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("取消完成且 due_at 为空 → PENDING_TIME")
    void uncompleteWithoutDueAtIsPendingTime() {
        DdlTaskEntity existing = task("COMPLETED", null, null, null);
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        existing.setFollowUpCount(2);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("PENDING_TIME");
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(existing.getFollowUpAt()).isNotNull();
        assertThat(existing.getFollowUpCount()).isZero();
        assertThat(vo.status()).isEqualTo("PENDING_TIME");
    }

    @Test
    @DisplayName("取消完成且 due_at 在未来 → ACTIVE")
    void uncompleteWithFutureDueAtIsActive() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(existing.getFollowUpAt()).isNull();
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("取消完成且 due_at 已过 → OVERDUE")
    void uncompleteWithPastDueAtIsOverdue() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.setCompletion(USER_ID, TASK_ID, false);

        assertThat(existing.getStatus()).isEqualTo("OVERDUE");
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(vo.status()).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("取消任务 → CANCELED，清空 follow_up_at")
    void cancelMarksCanceled() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DeleteDdlTaskVO vo = service.cancelTask(USER_ID, TASK_ID);

        assertThat(existing.getStatus()).isEqualTo("CANCELED");
        assertThat(existing.getFollowUpAt()).isNull();
        assertThat(vo.deletedTaskId()).isEqualTo("103");
        assertThat(vo.status()).isEqualTo("CANCELED");
        verify(draftStore).deleteDraftAfterCommit(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("重复取消已取消任务幂等成功，不重复写库")
    void cancelAlreadyCanceledIsIdempotent() {
        DdlTaskEntity existing = task("CANCELED", null, null, null);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        DeleteDdlTaskVO vo = service.cancelTask(USER_ID, TASK_ID);

        verify(mapper, never()).update(any(), any());
        assertThat(vo.status()).isEqualTo("CANCELED");
        verify(draftStore).deleteDraft(USER_ID, TASK_ID);
    }

    @Test
    @DisplayName("取消其他用户任务 → 404")
    void cancelOtherUserTaskIsNotFound() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(null);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.cancelTask(USER_ID, TASK_ID));
    }

    @Test
    @DisplayName("ALL 看板返回待确认/进行中/已完成三组，草案为空")
    void listAllReturnsThreeGroups() {
        DdlTaskEntity pending = task("PENDING_TIME", null, null, null);
        DdlTaskEntity active = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        DdlTaskEntity completed = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        completed.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectList(any())).thenReturn(
                List.of(pending), List.of(active), List.of(completed));

        DdlTaskBoardVO board = service.listTasks(USER_ID, null, null);

        assertThat(board.pendingInboxTasks()).hasSize(1);
        assertThat(board.activeTasks()).hasSize(1);
        assertThat(board.completedTasks()).hasSize(1);
        assertThat(board.pendingChangeDrafts()).isEmpty();
        verify(mapper, times(3)).selectList(any());
    }

    @Test
    @DisplayName("COMPLETED 组只查询一次，其他组为空")
    void listCompletedOnlyQueriesOnce() {
        DdlTaskEntity completed = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        completed.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectList(any())).thenReturn(List.of(completed));

        DdlTaskBoardVO board = service.listTasks(USER_ID, "COMPLETED", null);

        assertThat(board.pendingInboxTasks()).isEmpty();
        assertThat(board.activeTasks()).isEmpty();
        assertThat(board.completedTasks()).hasSize(1);
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("看板包含 Redis 中未过期的变更草案")
    void listIncludesPendingChangeDrafts() {
        DdlTaskEntity active = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectList(any())).thenReturn(List.of(active));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(active);
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(active, TASK_ID, active.getUpdatedAt(),
                "2099-01-01T00:00:00");
        when(draftStore.listDrafts(USER_ID)).thenReturn(List.of(draft));

        DdlTaskBoardVO board = service.listTasks(USER_ID, "ACTIVE_BOARD", null);

        assertThat(board.activeTasks()).hasSize(1);
        assertThat(board.pendingChangeDrafts()).hasSize(1);
        assertThat(board.pendingChangeDrafts().get(0).targetTaskId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("非法 status_group → VALIDATION_ERROR，不查库")
    void invalidStatusGroupIsRejected() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.listTasks(USER_ID, "BOGUS", null));
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("非法 course_id 过滤 → VALIDATION_ERROR")
    void invalidCourseFilterIsRejected() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> service.listTasks(USER_ID, null, "abc"));
    }

    @Test
    @DisplayName("非 Asia/Shanghai 用户：VO due_at 使用用户时区偏移量")
    void voDueAtUsesUserTimezone() {
        when(userConfigService.findById(USER_ID))
                .thenReturn(Optional.of(userConfig("America/New_York")));

        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_011", null, "操作系统原理", "提交实验二报告",
                        "EXACT_DATETIME", "2099-12-31", "22:00")).task();

        assertThat(vo.userTimezone()).isEqualTo("America/New_York");
        assertThat(vo.dueAt())
                .isEqualTo(OffsetDateTime.parse("2099-12-31T22:00:00-05:00"));
    }

    @Test
    @DisplayName("课程删除后确认旧草案：course_id 置 NULL、保留课程名，不重新关联已删除课程")
    void applyDraftUpdateDoesNotReattachDeletedCourse() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        // 课程删除监听器已把任务解绑。
        existing.setCourseId(null);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setCourseName("已删除课程");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                existing.getUpdatedAt(), "2026-10-16T11:40:00");

        service.applyDraftUpdate(USER_ID, TASK_ID, draft);

        assertThat(existing.getCourseId()).isNull();
        assertThat(existing.getCourseName()).isEqualTo("已删除课程");
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59));
    }

    @Test
    @DisplayName("草案版本落后于目标任务：DRAFT_EXPIRED_OR_NOT_FOUND，不覆盖之后的编辑")
    void staleDraftIsRejectedByVersion() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setUpdatedAt(LocalDateTime.of(2026, 10, 16, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                LocalDateTime.of(2026, 10, 16, 11, 40), "2026-10-16T11:40:00");

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.applyDraftUpdate(USER_ID, TASK_ID, draft));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已完成任务不再接受旧变更草案：STATE_CONFLICT")
    void completedTaskRejectsDraftUpdate() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                existing.getUpdatedAt(), "2026-10-16T11:40:00");

        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.applyDraftUpdate(USER_ID, TASK_ID, draft));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("CREATE_NEW 的课程已删除：新任务 course_id 为 NULL，保留课程名")
    void createFromDraftWithDeletedCourseUnbinds() {
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(null);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setCourseName("已删除课程");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                candidate.getUpdatedAt(), "2026-10-16T11:40:00");

        service.createFromDraft(USER_ID, draft, "req_decide_deleted_course");

        DdlTaskEntity inserted = capturedInsert();
        assertThat(inserted.getCourseId()).isNull();
        assertThat(inserted.getCourseName()).isEqualTo("已删除课程");
    }

    @Test
    @DisplayName("CONFIRM_UPDATE 应用草案字段并重置延期提醒位")
    void applyDraftUpdateAppliesFieldsAndResetsFlags() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setRemindedFlags(7);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setTaskTitle("延期任务");
        candidate.setDeliverableFormat("pdf");
        candidate.setSubmissionChannel("邮箱");
        candidate.setRawSourceText("延期通知");
        candidate.setRawTimeText("12月31日 23:59");
        candidate.setConfidence(new BigDecimal("0.980"));
        candidate.setContentHash("new-hash");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                existing.getUpdatedAt(), "2026-10-16T11:40:00");

        DdlTaskVO vo = service.applyDraftUpdate(USER_ID, TASK_ID, draft);

        assertThat(existing.getTaskTitle()).isEqualTo("延期任务");
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59));
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getRemindedFlags()).isZero();
        assertThat(existing.getContentHash()).isEqualTo("new-hash");
        assertThat(existing.getRawSourceText()).isEqualTo("延期通知");
        assertThat(existing.getConfidence()).isEqualByComparingTo("0.980");
        assertThat(existing.getCompletedAt()).isNull();
        assertThat(existing.getFollowUpCount()).isZero();
        assertThat(vo.taskId()).isEqualTo(TASK_ID);
    }

    @Test
    @DisplayName("CONFIRM_UPDATE 改为 VAGUE：清空 due 字段并置 PENDING_TIME")
    void applyDraftUpdateToVagueClearsDueFields() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));

        DdlTaskEntity candidate = task("PENDING_TIME", null, null, null);
        candidate.setTimePrecision("VAGUE_OR_MISSING");
        candidate.setTimeInferred(0);
        candidate.setFollowUpAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                existing.getUpdatedAt(), "2026-10-16T11:40:00");

        service.applyDraftUpdate(USER_ID, TASK_ID, draft);

        assertThat(existing.getStatus()).isEqualTo("PENDING_TIME");
        assertThat(existing.getDueDate()).isNull();
        assertThat(existing.getDueTime()).isNull();
        assertThat(existing.getDueAt()).isNull();
        assertThat(existing.getFollowUpAt()).isNotNull();
        assertThat(existing.getRemindedFlags()).isZero();
    }

    @Test
    @DisplayName("CREATE_NEW 用新的 client_request_id 插入独立任务")
    void createFromDraftInsertsNewTask() {
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(null);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setContentHash("new-hash");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                candidate.getUpdatedAt(), "2026-10-16T11:40:00");

        DdlTaskService.DdlTaskCreationResult result =
                service.createFromDraft(USER_ID, draft, "req_decide_002");

        assertThat(result.created()).isTrue();
        DdlTaskEntity inserted = capturedInsert();
        assertThat(inserted.getTaskId()).isEqualTo(TASK_ID);
        assertThat(inserted.getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getSourceMsgId()).isEqualTo("req_decide_002");
        assertThat(inserted.getIdempotencyKey())
                .isEqualTo(DdlIdempotencyKey.forWebRequest(USER_ID, "req_decide_002"));
        assertThat(inserted.getContentHash()).isEqualTo("new-hash");
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 23, 59));
        assertThat(inserted.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("CREATE_NEW 重试命中新幂等键：返回已有任务，不重复插入")
    void createFromDraftDuplicateReturnsExisting() {
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                candidate.getUpdatedAt(), "2026-10-16T11:40:00");
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        existing.setRequestOperation(DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW);
        existing.setRequestFingerprint(DdlRequestFingerprint.forDraftCreateNew(draft));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);

        DdlTaskService.DdlTaskCreationResult result =
                service.createFromDraft(USER_ID, draft, "req_decide_002");

        assertThat(result.created()).isFalse();
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(mapper, never()).insert(any(DdlTaskEntity.class));
    }

    @Test
    @DisplayName("CREATE_NEW 同 ID 命中但草案内容不同：409")
    void createFromDraftDuplicateWithChangedDraftConflicts() {
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                candidate.getUpdatedAt(), "2026-10-16T11:40:00");
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        existing.setRequestOperation(DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW);
        existing.setRequestFingerprint("another-fingerprint");
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.createFromDraft(USER_ID, draft, "req_decide_002"));
        verify(mapper, never()).insert(any(DdlTaskEntity.class));
    }

    @Test
    @DisplayName("损坏草案（ACTIVE 但缺 due_at）→ DRAFT_EXPIRED_OR_NOT_FOUND")
    void corruptDraftIsExpired() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(
                task("ACTIVE", LocalDate.of(2099, 12, 30),
                        LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0)));
        DdlTaskDraft draft = new DdlTaskDraft(
                TASK_ID, null, COURSE_ID, "操作系统原理", "任务", "EXACT_DATETIME",
                null, null, null, false, null, "ACTIVE", null,
                null, null, "原文", "时间", "Asia/Shanghai",
                new BigDecimal("0.960"), false, "hash", "2026-10-16T11:40:00");

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.applyDraftUpdate(USER_ID, TASK_ID, draft));
    }

    @Test
    @DisplayName("课程删除后同 client_request_id 重试手动建单：返回原任务而不是 404")
    void manualRetryAfterCourseDeletionReturnsExistingTask() {
        CreateDdlTaskRequest request = createRequest("req_manual_retry", "2001", "旧课程",
                "任务", "DATE_ONLY", "2099-12-31", null);
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                null, LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        existing.setCourseId(null);
        existing.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        existing.setRequestFingerprint(DdlRequestFingerprint.forManual(request));
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(existing);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        DdlTaskVO vo = service.createManualTask(USER_ID, request).task();

        assertThat(vo.taskId()).isEqualTo(TASK_ID);
        verify(mapper, never()).insert(any(DdlTaskEntity.class));
        verify(courseService, never()).getActiveCourseForUpdate(anyLong(), anyLong());
    }

    @Test
    @DisplayName("草案生成后截止时间已过去：确认时降级为 PENDING_CONFIRM，不写入过期 ACTIVE")
    void expiredActiveDraftIsAppliedAsPendingConfirm() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                existing.getUpdatedAt(), "2026-10-16T11:40:00");

        service.applyDraftUpdate(USER_ID, TASK_ID, draft);

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2000, 1, 1, 22, 0));
        assertThat(existing.getFollowUpAt()).isNotNull();
        assertThat(existing.getTimeAmbiguityReason()).contains("已过去");
    }

    @Test
    @DisplayName("草案 due_at 与 due_date/due_time 不一致：拒绝应用")
    void inconsistentDraftTimeIsRejected() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        DdlTaskDraft draft = new DdlTaskDraft(
                TASK_ID, existing.getUpdatedAt().toString(), COURSE_ID, "操作系统原理",
                "提交实验二报告", "EXACT_DATETIME", "2099-12-31", "22:00:00",
                "2099-12-30T22:00", false, null, "ACTIVE", null, "zip", "学习通",
                "原文", "12月31日", "Asia/Shanghai", new BigDecimal("0.960"), false,
                "hash", "2026-10-16T11:40:00");

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.applyDraftUpdate(USER_ID, TASK_ID, draft));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("CREATE_NEW 的草案截止时间已过去：新任务为 PENDING_CONFIRM")
    void expiredActiveDraftCreateNewIsPendingConfirm() {
        when(mapper.selectByIdempotencyKey(any(), any())).thenReturn(null);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID,
                candidate.getUpdatedAt(), "2026-10-16T11:40:00");

        service.createFromDraft(USER_ID, draft, "req_decide_expired");

        DdlTaskEntity inserted = capturedInsert();
        assertThat(inserted.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(inserted.getFollowUpAt()).isNotNull();
        assertThat(inserted.getTimeAmbiguityReason()).contains("已过去");
    }

    @Test
    @DisplayName("提取建单时课程已被并发删除：课程行锁后发现 404，任务解绑但保留课程名")
    void createExtractedTaskUnbindsDeletedCourse() {
        DdlTaskEntity entity = extractedEntity("fingerprint");
        entity.setCourseId(COURSE_ID);
        entity.setCourseName("已删除课程");
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        DdlTaskService.DdlTaskCreationResult result = service.createExtractedTask(entity);

        assertThat(result.created()).isTrue();
        assertThat(entity.getCourseId()).isNull();
        assertThat(entity.getCourseName()).isEqualTo("已删除课程");
    }

    @Test
    @DisplayName("第一次取消完成恢复后，再次 completed=false 返回 409（当前接口无请求级回执）")
    void repeatedUncompleteReturnsStateConflict() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 15, 12, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.setCompletion(USER_ID, TASK_ID, false);
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");

        assertError(ErrorCode.STATE_CONFLICT,
                () -> service.setCompletion(USER_ID, TASK_ID, false));
    }

    @Test
    @DisplayName("编辑/确认/草案应用使用课程行锁，闭合删课并发窗口")
    void writePathsLockCourseRow() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2000, 1, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2000, 1, 1, 22, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));

        service.confirmTask(USER_ID, TASK_ID,
                new UpdateDdlTaskRequest("2001", "操作系统原理", "提交实验二报告",
                        "EXACT_DATETIME", "2099-12-31", "22:00", "zip", "学习通"));

        verify(courseService).getActiveCourseForUpdate(USER_ID, COURSE_ID);
    }

    // ---------------------- 编辑时间语义保持（P7 第四轮审阅） ----------------------

    @Test
    @DisplayName("只改标题：保留 INVALID_OR_PAST 精度、确认原因、状态与 due_at")
    void titleOnlyEditPreservesInvalidOrPastSemantics() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2026, 10, 1),
                LocalTime.of(22, 0), LocalDateTime.of(2026, 10, 1, 22, 0));
        existing.setTimePrecision("INVALID_OR_PAST");
        existing.setTimeAmbiguityReason("原时间被判定为非法或已过期，请核对后重新确认时间");
        existing.setFollowUpAt(LocalDateTime.of(2026, 10, 28, 11, 40));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                null, "操作系统原理", "提交实验二报告（改名）", "EXACT_DATETIME",
                "2026-10-01", "22:00", "zip", "学习通"));

        assertThat(existing.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(existing.getTimePrecision()).isEqualTo("INVALID_OR_PAST");
        assertThat(existing.getTimeAmbiguityReason()).contains("非法或已过期");
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 22, 0));
        assertThat(vo.status()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    @DisplayName("用户资料时区改变后只改标题：不隐式挪动 due_at 或任务时区")
    void titleOnlyEditKeepsStoredTimezone() {
        when(userConfigService.findById(USER_ID))
                .thenReturn(Optional.of(userConfig("America/New_York")));
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        existing.setUserTimezone("Asia/Shanghai");
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                null, "操作系统原理", "提交实验二报告（改名）", "EXACT_DATETIME",
                "2099-12-31", "22:00", "zip", "学习通"));

        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
        assertThat(existing.getUserTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("补充未来时间：PENDING_TIME → ACTIVE 并清空确认原因")
    void explicitTimeChangeActivatesPendingTask() {
        DdlTaskEntity existing = task("PENDING_TIME", null, null, null);
        existing.setTimePrecision("VAGUE_OR_MISSING");
        existing.setFollowUpAt(LocalDateTime.of(2026, 10, 28, 11, 40));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                null, "操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "22:00", "zip", "学习通"));

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getTimePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(existing.getTimeAmbiguityReason()).isNull();
        assertThat(existing.getDueAt()).isEqualTo(LocalDateTime.of(2099, 12, 31, 22, 0));
        assertThat(existing.getFollowUpAt()).isNull();
    }

    @Test
    @DisplayName("显式重新确认：字段不变也允许激活并清空确认原因")
    void explicitReconfirmActivatesWithoutChangingFields() {
        DdlTaskEntity existing = task("PENDING_CONFIRM", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        existing.setTimePrecision("INVALID_OR_PAST");
        existing.setTimeAmbiguityReason("旧确认原因");
        existing.setFollowUpAt(LocalDateTime.of(2026, 10, 28, 11, 40));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                null, "操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "22:00", "zip", "学习通", true));

        assertThat(existing.getStatus()).isEqualTo("ACTIVE");
        assertThat(existing.getTimePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(existing.getTimeAmbiguityReason()).isNull();
        assertThat(existing.getFollowUpAt()).isNull();
    }

    @Test
    @DisplayName("COMPLETED 任务编辑：仍为 COMPLETED，completed_at 不丢失")
    void completedEditKeepsCompletedStatus() {
        DdlTaskEntity existing = task("COMPLETED", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        existing.setCompletedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskVO vo = service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                null, "操作系统原理", "提交实验二报告（改名）", "EXACT_DATETIME",
                "2099-12-31", "22:00", "zip", "学习通"));

        assertThat(vo.status()).isEqualTo("COMPLETED");
        assertThat(existing.getStatus()).isEqualTo("COMPLETED");
        assertThat(existing.getCompletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    @Test
    @DisplayName("编辑/确认锁序：先课程行、后任务行，避免与删课形成反向锁序")
    void editLocksCourseBeforeTask() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(COURSE_ID, "操作系统原理"));
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.updateTask(USER_ID, TASK_ID, new UpdateDdlTaskRequest(
                "2001", "操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "22:00", "zip", "学习通"));

        org.mockito.InOrder order = inOrder(courseService, mapper);
        order.verify(courseService).getActiveCourseForUpdate(USER_ID, COURSE_ID);
        order.verify(mapper).selectByIdForUpdate(TASK_ID, USER_ID);
    }

    @Test
    @DisplayName("已取消任务按 ID 查询返回 404，与列表排除 CANCELED 的口径一致")
    void canceledTaskIsNotFoundById() {
        DdlTaskEntity canceled = task("CANCELED", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        when(mapper.selectByTaskIdAndUserId(TASK_ID, USER_ID)).thenReturn(canceled);

        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.getTask(USER_ID, TASK_ID));
    }

    @Test
    @DisplayName("批量完成对已 COMPLETED 任务不重复写库")
    void batchCompletionSkipsAlreadyCompletedRows() {
        DdlTaskEntity first = task("COMPLETED", LocalDate.of(2099, 12, 31),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 31, 22, 0));
        DdlTaskEntity second = task("COMPLETED", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        second.setTaskId(104L);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(first);
        when(mapper.selectByIdForUpdate(104L, USER_ID)).thenReturn(second);

        List<DdlTaskVO> result = service.setCompletionBatch(USER_ID,
                List.of(TASK_ID, 104L), true);

        assertThat(result).hasSize(2);
        verify(mapper, never()).update(any(), any());
    }

    private DdlTaskEntity capturedInsert() {
        ArgumentCaptor<DdlTaskEntity> captor = ArgumentCaptor.forClass(DdlTaskEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static CreateDdlTaskRequest createRequest(String clientRequestId, String courseId,
                                                      String courseName, String taskTitle,
                                                      String precision, String dueDate,
                                                      String dueTime) {
        return new CreateDdlTaskRequest(clientRequestId, courseId, courseName, taskTitle,
                precision, dueDate, dueTime, "zip", "学习通");
    }

    private static UpdateDdlTaskRequest updateRequest(String precision, String dueDate, String dueTime) {
        return new UpdateDdlTaskRequest(null, "操作系统原理", "提交实验二报告",
                precision, dueDate, dueTime, "zip", "学习通");
    }

    private static UserConfigEntity userConfig(String timezone) {
        UserConfigEntity config = new UserConfigEntity();
        config.setAccountStatus("ACTIVE");
        config.setTimezone(timezone);
        return config;
    }

    private static CourseNamespaceEntity course(Long courseId, String courseName) {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(courseId);
        course.setCourseName(courseName);
        return course;
    }

    private static DdlTaskEntity task(String status, LocalDate dueDate, LocalTime dueTime,
                                      LocalDateTime dueAt) {
        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setUserId(USER_ID);
        entity.setCourseId(COURSE_ID);
        entity.setCourseName("操作系统原理");
        entity.setTaskTitle("提交实验二报告");
        entity.setTimePrecision("EXACT_DATETIME");
        entity.setIsRelativeTime(0);
        entity.setDueDate(dueDate);
        entity.setDueTime(dueTime);
        entity.setDueAt(dueAt);
        entity.setTimeInferred(0);
        entity.setUserTimezone("Asia/Shanghai");
        entity.setConfidence(new BigDecimal("0.960"));
        entity.setStatus(status);
        entity.setFollowUpAt(null);
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform("WEB");
        entity.setSourceMsgId("req_old_001");
        entity.setIdempotencyKey("old-key");
        entity.setCreatedAt(LocalDateTime.of(2026, 10, 15, 11, 40));
        entity.setUpdatedAt(LocalDateTime.of(2026, 10, 15, 11, 40));
        return entity;
    }

    private static DdlTaskEntity extractedEntity(String fingerprint) {
        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setUserId(USER_ID);
        entity.setCourseName("操作系统原理");
        entity.setTaskTitle("通知提取任务");
        entity.setTimePrecision("DATE_ONLY");
        entity.setDueDate(LocalDate.of(2099, 12, 31));
        entity.setDueAt(LocalDateTime.of(2099, 12, 31, 23, 59, 59));
        entity.setTimeInferred(1);
        entity.setUserTimezone("Asia/Shanghai");
        entity.setStatus("ACTIVE");
        entity.setSourcePlatform("WEB");
        entity.setSourceMsgId("req_manual_001");
        entity.setRequestOperation(DdlRequestFingerprint.OPERATION_EXTRACT);
        entity.setRequestFingerprint(fingerprint);
        entity.setIdempotencyKey(DdlIdempotencyKey.forWebRequest(USER_ID, "req_manual_001"));
        return entity;
    }

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(expected));
    }
}
