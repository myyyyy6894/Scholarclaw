package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
                        "EXACT_DATETIME", "2099-12-31", "22:00"));

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
                        "DATE_ONLY", "2099-12-31", null));

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
                        "VAGUE_OR_MISSING", null, null));

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
                        "EXACT_DATETIME", "2000-01-01", "22:00"));

        DdlTaskEntity entity = capturedInsert();
        assertThat(entity.getStatus()).isEqualTo("PENDING_CONFIRM");
        assertThat(entity.getDueAt()).isNotNull();
        assertThat(entity.getFollowUpAt()).isNotNull();
        assertThat(vo.status()).isEqualTo("PENDING_CONFIRM");
    }

    @Test
    @DisplayName("传了有效 course_id 时自动对齐课程名称")
    void createAlignsCourseNameWhenCourseIdProvided() {
        when(courseService.getActiveCourse(USER_ID, COURSE_ID))
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
        when(mapper.insert(any(DdlTaskEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_ddl_idempotency_key"));
        when(mapper.selectByIdempotencyKey(any())).thenReturn(existing);

        DdlTaskVO vo = service.createManualTask(USER_ID,
                createRequest("req_manual_001", null, "操作系统原理", "提交实验二报告",
                        "EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(vo.taskId()).isEqualTo(TASK_ID);
        verify(mapper).selectByIdempotencyKey(any());
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
        verify(courseService, never()).getActiveCourse(anyLong(), anyLong());
    }

    @Test
    @DisplayName("跨用户/已删除课程 → RESOURCE_NOT_FOUND")
    void notOwnedCourseIsRejected() {
        when(courseService.getActiveCourse(USER_ID, COURSE_ID))
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
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(active, TASK_ID, "2026-10-16T11:40:00");
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
                        "EXACT_DATETIME", "2099-12-31", "22:00"));

        assertThat(vo.userTimezone()).isEqualTo("America/New_York");
        assertThat(vo.dueAt())
                .isEqualTo(OffsetDateTime.parse("2099-12-31T22:00:00-05:00"));
    }

    @Test
    @DisplayName("CONFIRM_UPDATE 应用草案字段并重置延期提醒位")
    void applyDraftUpdateAppliesFieldsAndResetsFlags() {
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 30),
                LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0));
        existing.setRemindedFlags(7);
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);

        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setTaskTitle("延期任务");
        candidate.setDeliverableFormat("pdf");
        candidate.setSubmissionChannel("邮箱");
        candidate.setRawSourceText("延期通知");
        candidate.setRawTimeText("12月31日 23:59");
        candidate.setConfidence(new BigDecimal("0.980"));
        candidate.setContentHash("new-hash");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID, "2026-10-16T11:40:00");

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

        DdlTaskEntity candidate = task("PENDING_TIME", null, null, null);
        candidate.setTimePrecision("VAGUE_OR_MISSING");
        candidate.setTimeInferred(0);
        candidate.setFollowUpAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID, "2026-10-16T11:40:00");

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
        when(mapper.selectByIdempotencyKey(any())).thenReturn(null);
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        candidate.setContentHash("new-hash");
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID, "2026-10-16T11:40:00");

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
        DdlTaskEntity existing = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        when(mapper.selectByIdempotencyKey(any())).thenReturn(existing);
        DdlTaskEntity candidate = task("ACTIVE", LocalDate.of(2099, 12, 31),
                LocalTime.of(23, 59), LocalDateTime.of(2099, 12, 31, 23, 59));
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(candidate, TASK_ID, "2026-10-16T11:40:00");

        DdlTaskService.DdlTaskCreationResult result =
                service.createFromDraft(USER_ID, draft, "req_decide_002");

        assertThat(result.created()).isFalse();
        assertThat(result.task().taskId()).isEqualTo(TASK_ID);
        verify(mapper, never()).insert(any(DdlTaskEntity.class));
    }

    @Test
    @DisplayName("损坏草案（ACTIVE 但缺 due_at）→ DRAFT_EXPIRED_OR_NOT_FOUND")
    void corruptDraftIsExpired() {
        when(mapper.selectByIdForUpdate(TASK_ID, USER_ID)).thenReturn(
                task("ACTIVE", LocalDate.of(2099, 12, 30),
                        LocalTime.of(22, 0), LocalDateTime.of(2099, 12, 30, 22, 0)));
        DdlTaskDraft draft = new DdlTaskDraft(
                TASK_ID, COURSE_ID, "操作系统原理", "任务", "EXACT_DATETIME",
                null, null, null, false, "ACTIVE", null,
                null, null, "原文", "时间", "Asia/Shanghai",
                new BigDecimal("0.960"), false, "hash", "2026-10-16T11:40:00");

        assertError(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                () -> service.applyDraftUpdate(USER_ID, TASK_ID, draft));
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

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(expected));
    }
}
