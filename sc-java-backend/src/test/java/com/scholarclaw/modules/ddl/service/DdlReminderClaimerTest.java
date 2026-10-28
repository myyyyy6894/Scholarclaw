package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.notification.service.DdlReminderNotification;
import com.scholarclaw.modules.notification.service.NotificationBizType;
import com.scholarclaw.modules.notification.service.NotificationService;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** TASK-801 提醒领取的位掩码、状态流转、并发 CAS 与免打扰行为。 */
class DdlReminderClaimerTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DND_START = LocalTime.of(23, 30);
    private static final LocalTime DND_END = LocalTime.of(7, 30);
    private static final String TRACE_ID = "tr_cron_0123456789ab";

    private DdlTaskMapper mapper;
    private NotificationService notificationService;
    private DdlReminderClaimer claimer;

    @BeforeEach
    void setUp() {
        mapper = mock(DdlTaskMapper.class);
        notificationService = mock(NotificationService.class);
        claimer = new DdlReminderClaimer(mapper, new DdlReminderPolicy(), notificationService);
    }

    @Test
    @DisplayName("剩余 10h：只领取 T-24h，不提前领取 T-6h")
    void claimsT24OnlyBeforeT6Window() {
        DdlTaskEntity task = activeTask(NOW.plusHours(10));
        when(mapper.markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(1);

        List<DdlReminderDispatch> result = claimer.claim(
                task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID);

        assertThat(result).singleElement().satisfies(dispatch -> {
            assertThat(dispatch.type()).isEqualTo(DdlReminderType.DDL_T24H);
            assertThat(dispatch.taskId()).isEqualTo(TASK_ID);
            assertThat(dispatch.userId()).isEqualTo(USER_ID);
            assertThat(dispatch.followUpCount()).isZero();
            assertThat(dispatch.dueAt()).isEqualTo(task.getDueAt());
            assertThat(dispatch.dueAtUserZone()).isEqualTo(task.getDueAt());
            assertThat(dispatch.traceId()).isEqualTo(TRACE_ID);
        });
        verify(mapper).markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW);
        verify(mapper, never()).markT6Reminded(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).markOverdueReminded(anyLong(), anyLong(), any(), any());
        DdlReminderNotification notification = capturedNotification();
        assertThat(notification.bizType()).isEqualTo(NotificationBizType.DDL_T24H);
        assertThat(notification.taskId()).isEqualTo(TASK_ID);
        assertThat(notification.dueAtUserZone()).isEqualTo(task.getDueAt());
        assertThat(notification.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("剩余 5h：只领取 T-6h（SQL 同时补记 T-24h 位），不额外补发过期 T-24h")
    void claimsT6AndSilentlyMarksT24InSql() {
        DdlTaskEntity task = activeTask(NOW.plusHours(5));
        when(mapper.markT6Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(1);

        List<DdlReminderDispatch> result = claimer.claim(
                task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID);

        assertThat(result).singleElement()
                .extracting(DdlReminderDispatch::type).isEqualTo(DdlReminderType.DDL_T6H);
        verify(mapper).markT6Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW);
        verify(mapper, never()).markT24Reminded(anyLong(), anyLong(), any(), any());
        assertThat(capturedNotification().bizType()).isEqualTo(NotificationBizType.DDL_T6H);
    }

    @Test
    @DisplayName("T-6h 落入免打扰：在提前时刻（22:30）即可领取")
    void claimsT6AtAdvancedTimeInsideDndRule() {
        // due 2026-10-29 08:00 -> T-6h 02:00 落在窗口 -> 提前到 2026-10-28 22:30
        DdlTaskEntity task = activeTask(LocalDateTime.of(2026, 10, 29, 8, 0));
        when(mapper.markT6Reminded(TASK_ID, USER_ID, task.getDueAt(),
                LocalDateTime.of(2026, 10, 28, 22, 30))).thenReturn(1);

        List<DdlReminderDispatch> result = claimer.claim(task,
                LocalDateTime.of(2026, 10, 28, 22, 30),
                SHANGHAI, DND_START, DND_END, TRACE_ID);

        assertThat(result).singleElement()
                .extracting(DdlReminderDispatch::type).isEqualTo(DdlReminderType.DDL_T6H);
    }

    @Test
    @DisplayName("已过 due_at：流转 OVERDUE 并只发一条逾期提醒")
    void transitionsToOverdueAndClaimsSingleOverdueReminder() {
        DdlTaskEntity task = activeTask(NOW.minusMinutes(1));
        when(mapper.markOverdueReminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(1);

        List<DdlReminderDispatch> result = claimer.claim(
                task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID);

        assertThat(result).singleElement()
                .extracting(DdlReminderDispatch::type).isEqualTo(DdlReminderType.DDL_OVERDUE);
        verify(mapper).markOverdueReminded(TASK_ID, USER_ID, task.getDueAt(), NOW);
        verify(mapper, never()).markT24Reminded(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).markT6Reminded(anyLong(), anyLong(), any(), any());
        assertThat(capturedNotification().bizType()).isEqualTo(NotificationBizType.DDL_OVERDUE);
    }

    @Test
    @DisplayName("条件更新影响 0 行（已被其他实例领取/任务已变化）时不产生提醒")
    void doesNotDispatchWhenClaimLosesRace() {
        DdlTaskEntity task = activeTask(NOW.plusHours(10));
        when(mapper.markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(0);

        assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
    }

    @Test
    @DisplayName("待确认任务：跟进计数 +1、下一次顺延 24h")
    void claimsFollowUpAndAdvancesCount() {
        DdlTaskEntity task = pendingTask(LocalDateTime.of(2026, 10, 28, 9, 0), 0);
        when(mapper.markFollowUpReminded(TASK_ID, USER_ID,
                task.getFollowUpAt(), 0, NOW.plusHours(24), NOW)).thenReturn(1);

        List<DdlReminderDispatch> result = claimer.claim(
                task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID);

        assertThat(result).singleElement().satisfies(dispatch -> {
            assertThat(dispatch.type()).isEqualTo(DdlReminderType.DDL_FOLLOW_UP);
            assertThat(dispatch.followUpCount()).isEqualTo(1);
            assertThat(dispatch.dueAt()).isNull();
        });
        verify(mapper).markFollowUpReminded(TASK_ID, USER_ID,
                task.getFollowUpAt(), 0, NOW.plusHours(24), NOW);
        DdlReminderNotification notification = capturedNotification();
        assertThat(notification.bizType()).isEqualTo(NotificationBizType.DDL_FOLLOW_UP);
        assertThat(notification.followUpCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("待确认任务跟进落在免打扰窗口时不领取，等窗口结束")
    void doesNotClaimFollowUpInsideDnd() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 2, 0);
        DdlTaskEntity task = pendingTask(LocalDateTime.of(2026, 10, 29, 1, 0), 0);

        assertThat(claimer.claim(task, now, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("已满 2 次跟进不再领取")
    void doesNotClaimAfterTwoFollowUps() {
        DdlTaskEntity task = pendingTask(NOW.minusHours(1), 2);
        assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("跟进 CAS 失败（并发已被领取）不产生提醒")
    void doesNotDispatchFollowUpWhenCasFails() {
        DdlTaskEntity task = pendingTask(NOW.minusHours(1), 0);
        when(mapper.markFollowUpReminded(anyLong(), anyLong(), any(), anyInt(), any(), any()))
                .thenReturn(0);

        assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
    }

    @Test
    @DisplayName("完成、取消与未知状态一律不产生提醒")
    void neverRemindsCompletedOrCanceledTasks() {
        for (String status : List.of("COMPLETED", "CANCELED", "UNKNOWN")) {
            DdlTaskEntity task = activeTask(NOW.plusHours(1));
            task.setStatus(status);
            assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID))
                    .as(status).isEmpty();
        }
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("ACTIVE 任务缺少 due_at（数据异常）时不写库")
    void doesNotClaimActiveTaskWithoutDueAt() {
        DdlTaskEntity task = activeTask(null);
        assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("通知报文字段来自任务快照，并按用户时区给出展示时间")
    void dispatchCarriesTaskSnapshotAndUserZoneTime() {
        ZoneId newYork = ZoneId.of("America/New_York");
        DdlTaskEntity task = activeTask(LocalDateTime.of(2026, 10, 28, 20, 0));
        task.setCourseId(2001L);
        task.setCourseName("操作系统原理");
        task.setTaskTitle("提交实验二报告");
        task.setDeliverableFormat("学号_姓名.zip");
        task.setSubmissionChannel("学习通");
        task.setRawTimeText("本周五下午5点前");
        task.setTimePrecision("EXACT_DATETIME");
        task.setTimeInferred(0);
        when(mapper.markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(1);

        DdlReminderDispatch dispatch = claimer.claim(
                task, NOW, newYork, DND_START, DND_END, TRACE_ID).get(0);

        assertThat(dispatch.courseId()).isEqualTo(2001L);
        assertThat(dispatch.courseName()).isEqualTo("操作系统原理");
        assertThat(dispatch.taskTitle()).isEqualTo("提交实验二报告");
        assertThat(dispatch.deliverableFormat()).isEqualTo("学号_姓名.zip");
        assertThat(dispatch.submissionChannel()).isEqualTo("学习通");
        assertThat(dispatch.rawTimeText()).isEqualTo("本周五下午5点前");
        assertThat(dispatch.timePrecision()).isEqualTo("EXACT_DATETIME");
        assertThat(dispatch.timeInferred()).isFalse();
        assertThat(dispatch.dueAtUserZone())
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 8, 0));
        assertThat(dispatch.userTimezone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("站内通知写入失败时异常向上抛出，让提醒位一起回滚")
    void notificationFailurePropagatesForRollback() {
        DdlTaskEntity task = activeTask(NOW.plusHours(10));
        when(mapper.markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(1);
        doThrow(new IllegalStateException("notification insert failed"))
                .when(notificationService).createDdlReminderInTx(any());

        assertThatThrownBy(() -> claimer.claim(
                task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification insert failed");
    }

    @Test
    @DisplayName("条件更新失败或未到触发时刻时不写站内通知")
    void noNotificationWhenNothingClaimed() {
        DdlTaskEntity task = activeTask(NOW.plusHours(10));
        when(mapper.markT24Reminded(TASK_ID, USER_ID, task.getDueAt(), NOW)).thenReturn(0);

        assertThat(claimer.claim(task, NOW, SHANGHAI, DND_START, DND_END, TRACE_ID)).isEmpty();
        verifyNoInteractions(notificationService);
    }

    private DdlReminderNotification capturedNotification() {
        ArgumentCaptor<DdlReminderNotification> captor =
                ArgumentCaptor.forClass(DdlReminderNotification.class);
        verify(notificationService).createDdlReminderInTx(captor.capture());
        return captor.getValue();
    }

    private static DdlTaskEntity activeTask(LocalDateTime dueAt) {
        DdlTaskEntity task = baseTask();
        task.setStatus("ACTIVE");
        task.setDueAt(dueAt);
        task.setDueDate(dueAt == null ? null : dueAt.toLocalDate());
        task.setDueTime(dueAt == null ? null : dueAt.toLocalTime());
        task.setTimePrecision("EXACT_DATETIME");
        task.setTimeInferred(0);
        task.setFollowUpAt(null);
        task.setFollowUpCount(0);
        task.setRemindedFlags(0);
        return task;
    }

    private static DdlTaskEntity pendingTask(LocalDateTime followUpAt, int count) {
        DdlTaskEntity task = baseTask();
        task.setStatus("PENDING_TIME");
        task.setTimePrecision("VAGUE_OR_MISSING");
        task.setTimeInferred(0);
        task.setDueAt(null);
        task.setDueDate(null);
        task.setDueTime(null);
        task.setFollowUpAt(followUpAt);
        task.setFollowUpCount(count);
        task.setRemindedFlags(0);
        return task;
    }

    private static DdlTaskEntity baseTask() {
        DdlTaskEntity task = new DdlTaskEntity();
        task.setTaskId(TASK_ID);
        task.setUserId(USER_ID);
        task.setCourseName("操作系统原理");
        task.setTaskTitle("提交实验二报告");
        task.setUserTimezone("Asia/Shanghai");
        task.setStatus("ACTIVE");
        task.setTimeInferred(0);
        task.setFollowUpCount(0);
        task.setRemindedFlags(0);
        return task;
    }
}
