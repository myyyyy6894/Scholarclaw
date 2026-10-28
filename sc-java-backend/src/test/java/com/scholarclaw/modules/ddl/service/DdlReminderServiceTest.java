package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-801 扫描候选、用户偏好隔离、单任务失败隔离与汇总。 */
class DdlReminderServiceTest {

    private static final long USER_ID = 10001L;
    private static final long ACTIVE_TASK_ID = 103L;
    private static final long PENDING_TASK_ID = 104L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final LocalDateTime HORIZON = NOW.plusHours(24);
    private static final int BATCH_SIZE = 50;
    private static final String TRACE_ID = "tr_cron_0123456789ab";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private DdlTaskMapper mapper;
    private DdlReminderClaimer claimer;
    private UserConfigService userConfigService;
    private DdlReminderService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DdlTaskMapper.class);
        claimer = mock(DdlReminderClaimer.class);
        userConfigService = mock(UserConfigService.class);
        service = new DdlReminderService(mapper, claimer, new DdlReminderPolicy(),
                userConfigService, new DdlTimeRuleValidator(), BATCH_SIZE);
    }

    @Test
    @DisplayName("同时扫描正式梯度与待确认跟进，并按用户配置的时区/免打扰调用领取")
    void scansBothCandidateGroupsWithUserPreference() {
        DdlTaskEntity active = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        DdlTaskEntity pending = pendingTask(PENDING_TASK_ID, NOW.minusHours(1));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(active));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of(pending));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                "Asia/Shanghai", LocalTime.of(23, 30), LocalTime.of(7, 30))));
        DdlReminderDispatch activeDispatch = dispatch(ACTIVE_TASK_ID,
                DdlReminderType.DDL_T24H, 0);
        DdlReminderDispatch followUpDispatch = dispatch(PENDING_TASK_ID,
                DdlReminderType.DDL_FOLLOW_UP, 1);
        when(claimer.claim(eq(active), eq(NOW), eq(SHANGHAI),
                eq(LocalTime.of(23, 30)), eq(LocalTime.of(7, 30)), eq(TRACE_ID)))
                .thenReturn(List.of(activeDispatch));
        when(claimer.claim(eq(pending), eq(NOW), eq(SHANGHAI),
                eq(LocalTime.of(23, 30)), eq(LocalTime.of(7, 30)), eq(TRACE_ID)))
                .thenReturn(List.of(followUpDispatch));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.candidates()).isEqualTo(2);
        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.unclaimed()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.dispatches()).containsExactly(activeDispatch, followUpDispatch);
    }

    @Test
    @DisplayName("用户配置缺失：跳过提醒且不写任务表")
    void skipsWhenUserConfigMissing() {
        DdlTaskEntity active = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(active));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.empty());

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
        assertThat(result.failed()).isZero();
        verify(claimer, never()).claim(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("注销中的用户不再收到提醒")
    void skipsDeactivatingUser() {
        DdlTaskEntity active = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(active));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        UserConfigEntity config = config("Asia/Shanghai", null, null);
        config.setAccountStatus("DEACTIVATING");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.claimed()).isZero();
        verify(claimer, never()).claim(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("单任务领取失败不影响后续任务，失败数如实上报")
    void isolatesPerTaskFailure() {
        DdlTaskEntity failing = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        DdlTaskEntity healthy = activeTask(ACTIVE_TASK_ID + 1, NOW.plusHours(11));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(failing, healthy));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                "Asia/Shanghai", null, null)));
        when(claimer.claim(eq(failing), any(), any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("boom"));
        when(claimer.claim(eq(healthy), any(), any(), any(), any(), anyString()))
                .thenReturn(List.of(dispatch(healthy.getTaskId(), DdlReminderType.DDL_T24H, 0)));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.dispatches()).singleElement()
                .extracting(DdlReminderDispatch::taskId).isEqualTo(healthy.getTaskId());
    }

    @Test
    @DisplayName("读取用户配置异常：计入失败且继续扫描其他用户")
    void isolatesConfigReadFailure() {
        DdlTaskEntity task = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(task));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenThrow(new IllegalStateException("db down"));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
    }

    @Test
    @DisplayName("领取返回空（并发已领取/任务已变化）计入 unclaimed，不算失败")
    void countsUnclaimedWhenClaimerReturnsEmpty() {
        DdlTaskEntity task = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(task));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                "Asia/Shanghai", null, null)));
        when(claimer.claim(eq(task), any(), any(), any(), any(), anyString()))
                .thenReturn(List.of());

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.unclaimed()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("用户配置时区为空时回退任务创建时的时区快照")
    void fallsBackToTaskTimezoneSnapshot() {
        DdlTaskEntity task = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        task.setUserTimezone("America/New_York");
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(task));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                null, null, null)));
        when(claimer.claim(eq(task), any(), eq(ZoneId.of("America/New_York")),
                eq(LocalTime.of(23, 30)), eq(LocalTime.of(7, 30)), eq(TRACE_ID)))
                .thenReturn(List.of(dispatch(ACTIVE_TASK_ID, DdlReminderType.DDL_T24H, 0)));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.claimed()).isEqualTo(1);
        verify(claimer).claim(eq(task), eq(NOW), eq(ZoneId.of("America/New_York")),
                eq(LocalTime.of(23, 30)), eq(LocalTime.of(7, 30)), eq(TRACE_ID));
    }

    @Test
    @DisplayName("用户时区非法：跳过该任务并计入失败，不调用领取")
    void skipsInvalidTimezone() {
        DdlTaskEntity task = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(task));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                "Mars/Olympus", null, null)));

        DdlReminderService.ReminderScanResult result = service.scan(NOW, TRACE_ID);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
        verify(claimer, never()).claim(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("同一用户一轮只读取一次配置")
    void cachesUserConfigPerScan() {
        DdlTaskEntity first = activeTask(ACTIVE_TASK_ID, NOW.plusHours(10));
        DdlTaskEntity second = activeTask(ACTIVE_TASK_ID + 1, NOW.plusHours(11));
        when(mapper.selectActiveReminderCandidates(NOW, HORIZON, BATCH_SIZE))
                .thenReturn(List.of(first, second));
        when(mapper.selectFollowUpCandidates(NOW, BATCH_SIZE)).thenReturn(List.of());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config(
                "Asia/Shanghai", null, null)));
        when(claimer.claim(any(), any(), any(), any(), any(), anyString())).thenReturn(List.of());

        service.scan(NOW, TRACE_ID);

        verify(userConfigService).findById(USER_ID);
    }

    private static UserConfigEntity config(String timezone, LocalTime dndStart, LocalTime dndEnd) {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setTimezone(timezone);
        config.setDndStartTime(dndStart);
        config.setDndEndTime(dndEnd);
        return config;
    }

    private static DdlTaskEntity activeTask(long taskId, LocalDateTime dueAt) {
        DdlTaskEntity task = new DdlTaskEntity();
        task.setTaskId(taskId);
        task.setUserId(USER_ID);
        task.setStatus("ACTIVE");
        task.setDueAt(dueAt);
        task.setDueDate(dueAt.toLocalDate());
        task.setDueTime(dueAt.toLocalTime());
        task.setTimePrecision("EXACT_DATETIME");
        task.setTimeInferred(0);
        task.setUserTimezone("Asia/Shanghai");
        task.setFollowUpCount(0);
        task.setRemindedFlags(0);
        return task;
    }

    private static DdlTaskEntity pendingTask(long taskId, LocalDateTime followUpAt) {
        DdlTaskEntity task = new DdlTaskEntity();
        task.setTaskId(taskId);
        task.setUserId(USER_ID);
        task.setStatus("PENDING_TIME");
        task.setTimePrecision("VAGUE_OR_MISSING");
        task.setTimeInferred(0);
        task.setUserTimezone("Asia/Shanghai");
        task.setFollowUpAt(followUpAt);
        task.setFollowUpCount(0);
        task.setRemindedFlags(0);
        return task;
    }

    private static DdlReminderDispatch dispatch(long taskId, DdlReminderType type,
                                                int followUpCount) {
        return new DdlReminderDispatch(USER_ID, taskId, null, type,
                "操作系统原理", "提交实验二报告", null, null, null, "EXACT_DATETIME",
                false, NOW.plusHours(10), NOW.plusHours(10), "Asia/Shanghai",
                followUpCount, NOW, TRACE_ID);
    }
}
