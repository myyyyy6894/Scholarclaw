package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.client.OneBotApiClient;
import com.scholarclaw.common.im.OfficialBotSender;
import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import com.scholarclaw.modules.notification.service.ImPushJitter;
import com.scholarclaw.modules.notification.service.ImRateLimiter;
import com.scholarclaw.modules.notification.service.NotificationOutboundPolicy;
import com.scholarclaw.modules.notification.service.NotificationOutboundScheduler;
import com.scholarclaw.modules.notification.service.NotificationService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 跨模块集成：TASK-801 免打扰内的待确认跟进必须立即落站内信，
 * TASK-802 只把外部 IM 顺延到窗口结束，TASK-804 在窗口结束前不得尝试发送。
 */
class DdlFollowUpDndIntegrationTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final long NOTIFICATION_ID = 70001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 10, 29, 2, 0);
    private static final LocalDateTime DND_END = LocalDateTime.of(2026, 10, 29, 7, 30);
    private static final String TRACE_ID = "tr_cron_0123456789ab";

    private DdlTaskMapper ddlMapper;
    private NotificationLogMapper notificationMapper;
    private UserConfigService userConfigService;
    private NotificationService notificationService;
    private DdlReminderClaimer claimer;
    private NotificationOutboundScheduler outboundScheduler;
    private ImRateLimiter rateLimiter;
    private OneBotApiClient oneBotApiClient;
    private NotificationLogEntity stored;

    @BeforeEach
    void setUp() {
        ddlMapper = mock(DdlTaskMapper.class);
        notificationMapper = mock(NotificationLogMapper.class);
        userConfigService = mock(UserConfigService.class);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));
        doAnswer(invocation -> {
            NotificationLogEntity entity = invocation.getArgument(0);
            entity.setNotificationId(NOTIFICATION_ID);
            stored = entity;
            return 1;
        }).when(notificationMapper).insert(any(NotificationLogEntity.class));

        notificationService = new NotificationService(notificationMapper, userConfigService,
                new NotificationOutboundPolicy(), Clock.fixed(NIGHT.atZone(ZONE).toInstant(), ZONE),
                true);
        claimer = new DdlReminderClaimer(ddlMapper, new DdlReminderPolicy(), notificationService);

        rateLimiter = mock(ImRateLimiter.class);
        oneBotApiClient = mock(OneBotApiClient.class);
        when(rateLimiter.tryAcquire()).thenReturn(new ImRateLimiter.AcquireResult(true, 0));
        when(oneBotApiClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("DND 内跟进：站内信立即可读，IM 顺延到 07:30 之后才发送")
    void followUpIsVisibleImmediatelyButImWaitsForDndEnd() {
        DdlTaskEntity task = pendingTask(NIGHT.minusHours(1));
        when(ddlMapper.markFollowUpReminded(TASK_ID, USER_ID, task.getFollowUpAt(), 0,
                NIGHT.plusHours(24), NIGHT)).thenReturn(1);

        List<DdlReminderDispatch> dispatches = claimer.claim(
                task, NIGHT, ZONE, LocalTime.of(23, 30), LocalTime.of(7, 30), TRACE_ID);

        assertThat(dispatches).hasSize(1);
        ArgumentCaptor<NotificationLogEntity> captor =
                ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getReadStatus()).isEqualTo("UNREAD");
        assertThat(captor.getValue().getOutboundStatus())
                .isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED.name());
        assertThat(captor.getValue().getScheduledPushAt()).isEqualTo(DND_END);

        when(notificationMapper.selectOutboundCandidates(any(), anyInt()))
                .thenAnswer(invocation -> {
                    LocalDateTime now = invocation.getArgument(0);
                    return stored.getScheduledPushAt() != null
                            && !stored.getScheduledPushAt().isAfter(now)
                            ? List.of(stored) : List.of();
                });
        when(notificationMapper.claimOutbound(anyLong(), any(), any(), any())).thenReturn(1);
        when(notificationMapper.markSent(anyLong(), any(), any())).thenReturn(1);

        NotificationOutboundScheduler beforeWindow = schedulerAt(DND_END.minusMinutes(1));
        NotificationOutboundScheduler atWindow = schedulerAt(DND_END);

        assertThat(beforeWindow.dispatchOnce().candidates()).isZero();
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), any());

        atWindow.dispatchOnce();

        verify(oneBotApiClient).sendPrivateMsg(eq(123456L), any());
        verify(notificationMapper).markSent(eq(NOTIFICATION_ID), any(), eq(DND_END));
    }

    private NotificationOutboundScheduler schedulerAt(LocalDateTime now) {
        return new NotificationOutboundScheduler(notificationMapper, userConfigService,
                new NotificationOutboundPolicy(), rateLimiter, mock(ImPushJitter.class),
                oneBotApiClient, mock(OfficialBotSender.class),
                Clock.fixed(now.atZone(ZONE).toInstant(), ZONE),
                true, true, 10, 60, 30);
    }

    private static UserConfigEntity config() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setTimezone("Asia/Shanghai");
        config.setDndStartTime(LocalTime.of(23, 30));
        config.setDndEndTime(LocalTime.of(7, 30));
        config.setBoundImPlatform("QQ_ONEBOT");
        config.setBoundImUid("123456");
        return config;
    }

    private static DdlTaskEntity pendingTask(LocalDateTime followUpAt) {
        DdlTaskEntity task = new DdlTaskEntity();
        task.setTaskId(TASK_ID);
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
}
