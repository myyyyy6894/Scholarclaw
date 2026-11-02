package com.scholarclaw.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.client.OneBotApiClient;
import com.scholarclaw.common.im.OfficialBotSender;
import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** TASK-804 出站调度：租约领取、令牌桶、抖动、重试与 FALLBACK_WEB_ONLY。 */
class NotificationOutboundSchedulerTest {

    private static final long USER_ID = 10001L;
    private static final long NOTIFICATION_ID = 70001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
    private static final LocalDateTime LEASE = NOW.plusSeconds(60);

    private NotificationLogMapper mapper;
    private UserConfigService userConfigService;
    private NotificationOutboundPolicy policy;
    private ImRateLimiter rateLimiter;
    private ImPushJitter jitter;
    private OneBotApiClient oneBotApiClient;
    private NotificationOutboundScheduler scheduler;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationLogMapper.class);
        userConfigService = mock(UserConfigService.class);
        policy = mock(NotificationOutboundPolicy.class);
        rateLimiter = mock(ImRateLimiter.class);
        jitter = mock(ImPushJitter.class);
        oneBotApiClient = mock(OneBotApiClient.class);
        scheduler = new NotificationOutboundScheduler(mapper, userConfigService, policy,
                rateLimiter, jitter, oneBotApiClient, mock(OfficialBotSender.class),
                CLOCK, true, true, 10, 60, 30);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(new UserConfigEntity()));
        when(mapper.selectOutboundCandidates(NOW, 10))
                .thenReturn(List.of(candidate(NOTIFICATION_ID, "DDL_T24H", 0)));
        when(mapper.claimOutbound(NOTIFICATION_ID, NOW, LEASE, NOW)).thenReturn(1);
        when(policy.resolve(anyBoolean(), any(), any(), any())).thenReturn(pending());
        when(rateLimiter.tryAcquire())
                .thenReturn(new ImRateLimiter.AcquireResult(true, 0));
        when(oneBotApiClient.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("推送成功：SENT_IM，清空计划时间")
    void marksSentOnSuccess() {
        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.sent()).isEqualTo(1);
        verify(oneBotApiClient).sendPrivateMsg(24681012L, "提醒正文");
        verify(mapper).markSent(NOTIFICATION_ID, LEASE, NOW);
    }

    @Test
    @DisplayName("租约未拿到（其他实例已领取）：不发送")
    void skipsWhenClaimLoses() {
        when(mapper.claimOutbound(NOTIFICATION_ID, NOW, LEASE, NOW)).thenReturn(0);

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.sent()).isZero();
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("重新校验为 FALLBACK：只改状态，不发送")
    void fallsBackWhenPolicySaysSo() {
        when(policy.resolve(anyBoolean(), any(), any(), any())).thenReturn(
                new NotificationOutboundPolicy.Decision(OutboundStatus.FALLBACK_WEB_ONLY,
                        null, "未绑定 IM 账号，仅站内信", null, null));

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.fallback()).isEqualTo(1);
        verify(mapper).markFallback(NOTIFICATION_ID, LEASE, "未绑定 IM 账号，仅站内信", NOW);
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("重新校验为免打扰顺延：写 RATE_LIMITED_DELAYED，不发送")
    void delaysWhenPolicySaysSo() {
        LocalDateTime resume = NOW.plusHours(8);
        when(policy.resolve(anyBoolean(), any(), any(), any())).thenReturn(
                new NotificationOutboundPolicy.Decision(OutboundStatus.RATE_LIMITED_DELAYED,
                        resume, null, "QQ_ONEBOT", "24681012"));

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.delayed()).isEqualTo(1);
        verify(mapper).markRateLimited(NOTIFICATION_ID, LEASE, resume, null, NOW);
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("令牌桶拒绝：顺延到等待时间 + 5~15s 抖动")
    void delaysWhenRateLimited() {
        when(rateLimiter.tryAcquire())
                .thenReturn(new ImRateLimiter.AcquireResult(false, 6000));
        when(jitter.randomRateDelayMillis()).thenReturn(5000L);

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.delayed()).isEqualTo(1);
        verify(mapper).markRateLimited(eq(NOTIFICATION_ID), eq(LEASE),
                eq(NOW.plusSeconds(11)), anyString(), eq(NOW));
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("OneBot 未配置：直接降级，不消耗令牌、不抖动")
    void fallsBackWhenOneBotUnconfigured() {
        when(oneBotApiClient.isConfigured()).thenReturn(false);

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.fallback()).isEqualTo(1);
        verify(rateLimiter, never()).tryAcquire();
        verify(mapper).markFallback(eq(NOTIFICATION_ID), eq(LEASE), anyString(), eq(NOW));
    }

    @Test
    @DisplayName("不支持的 IM 平台：FALLBACK_WEB_ONLY")
    void fallsBackForUnsupportedPlatform() {
        when(policy.resolve(anyBoolean(), any(), any(), any())).thenReturn(
                new NotificationOutboundPolicy.Decision(OutboundStatus.PENDING_IM, NOW,
                        null, "WECOM", "123456"));

        scheduler.dispatchOnce();

        verify(mapper).markFallback(eq(NOTIFICATION_ID), eq(LEASE), anyString(), eq(NOW));
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("目标 QQ 格式非法：FALLBACK_WEB_ONLY")
    void fallsBackForInvalidTargetUid() {
        when(policy.resolve(anyBoolean(), any(), any(), any())).thenReturn(
                new NotificationOutboundPolicy.Decision(OutboundStatus.PENDING_IM, NOW,
                        null, "QQ_ONEBOT", "not-a-number"));

        scheduler.dispatchOnce();

        verify(mapper).markFallback(eq(NOTIFICATION_ID), eq(LEASE), anyString(), eq(NOW));
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("首次发送失败：PENDING_IM 重试，计数 +1，30s 后重试")
    void retriesFirstFailure() {
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR,
                "OneBot 回复发送失败，请稍后在 Web 工作台操作。"))
                .when(oneBotApiClient).sendPrivateMsg(anyLong(), anyString());

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.failed()).isEqualTo(1);
        verify(mapper).markRetry(eq(NOTIFICATION_ID), eq(LEASE),
                eq(NOW.plusSeconds(30)), eq(0), anyString(), eq(NOW));
        verify(mapper, never()).markSent(anyLong(), any(), any());
    }

    @Test
    @DisplayName("第 2 次重试仍失败：FALLBACK_WEB_ONLY")
    void fallsBackAfterRetryExhausted() {
        when(mapper.selectOutboundCandidates(NOW, 10))
                .thenReturn(List.of(candidate(NOTIFICATION_ID, "DDL_T24H", 2)));
        when(mapper.claimOutbound(NOTIFICATION_ID, NOW, LEASE, NOW)).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "发送失败"))
                .when(oneBotApiClient).sendPrivateMsg(anyLong(), anyString());

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.fallback()).isEqualTo(1);
        verify(mapper).markFallback(eq(NOTIFICATION_ID), eq(LEASE), anyString(), eq(NOW));
        verify(mapper, never()).markRetry(anyLong(), any(), any(), anyInt(),
                anyString(), any());
    }

    @Test
    @DisplayName("失败错误信息脱敏：不含令牌/请求体")
    void sanitizesFailureMessage() {
        doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR,
                "OneBot 回复发送失败，请稍后在 Web 工作台操作。"))
                .when(oneBotApiClient).sendPrivateMsg(anyLong(), anyString());

        scheduler.dispatchOnce();

        verify(mapper).markRetry(eq(NOTIFICATION_ID), eq(LEASE), any(), eq(0),
                org.mockito.ArgumentMatchers.argThat(msg ->
                        msg.contains("INTERNAL_ERROR") && !msg.contains("Bearer")
                                && !msg.contains("<HTML")),
                eq(NOW));
    }

    @Test
    @DisplayName("单条异常隔离：后续候选继续处理")
    void isolatesPerCandidateFailure() {
        NotificationLogEntity first = candidate(70001L, "DDL_T24H", 0);
        NotificationLogEntity second = candidate(70002L, "DOC_INGEST_READY", 0);
        when(mapper.selectOutboundCandidates(NOW, 10)).thenReturn(List.of(first, second));
        when(mapper.claimOutbound(anyLong(), eq(NOW), eq(LEASE), eq(NOW))).thenReturn(1);
        when(policy.resolve(anyBoolean(), any(), eq(NotificationBizType.DDL_T24H), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(policy.resolve(anyBoolean(), any(), eq(NotificationBizType.DOC_INGEST_READY), any()))
                .thenReturn(pending());

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.sent()).isEqualTo(1);
    }

    @Test
    @DisplayName("纳秒时钟：租约截断到毫秒，避免 DATETIME(3) 等值条件匹配失败")
    void truncatesClockNanosToMillisForLease() {
        LocalDateTime nanosNow = NOW.plusNanos(123_456_789);
        LocalDateTime now = nanosNow.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        LocalDateTime lease = now.plusSeconds(60);
        NotificationLogEntity candidate = candidate(NOTIFICATION_ID, "DDL_T24H", 0);
        candidate.setScheduledPushAt(now);
        when(mapper.selectOutboundCandidates(now, 10)).thenReturn(List.of(candidate));
        when(mapper.claimOutbound(NOTIFICATION_ID, now, lease, now)).thenReturn(1);
        NotificationOutboundScheduler nanosScheduler = new NotificationOutboundScheduler(
                mapper, userConfigService, policy, rateLimiter, jitter, oneBotApiClient,
                mock(OfficialBotSender.class),
                Clock.fixed(nanosNow.atZone(ZONE).toInstant(), ZONE),
                true, true, 10, 60, 30);

        nanosScheduler.dispatchOnce();

        verify(mapper).claimOutbound(NOTIFICATION_ID, now, lease, now);
        verify(mapper).markSent(NOTIFICATION_ID, lease, now);
        verify(mapper, never()).markSent(eq(NOTIFICATION_ID), any(),
                eq(nanosNow));
    }

    @Test
    @DisplayName("发送成功后 markSent 未生效：本轮仍算已发送，不能误判为发送失败重试")
    void doesNotTreatMarkSentFailureAsSendFailure() {
        when(mapper.markSent(NOTIFICATION_ID, LEASE, NOW)).thenReturn(0);

        NotificationOutboundScheduler.DispatchSummary summary = scheduler.dispatchOnce();

        assertThat(summary.sent()).isEqualTo(1);
        verify(oneBotApiClient).sendPrivateMsg(24681012L, "提醒正文");
        verify(mapper, never()).markRetry(anyLong(), any(), any(), anyInt(), anyString(), any());
        verify(mapper, never()).markFallback(anyLong(), any(), anyString(), any());
    }

    @Test
    @DisplayName("故障注入：发送成功后 markSent 抛异常，租约到期重试会重复发送（至少一次契约）")
    void documentsAtLeastOnceWhenMarkSentThrows() {
        when(mapper.markSent(NOTIFICATION_ID, LEASE, NOW))
                .thenThrow(new IllegalStateException("db write failed"));

        NotificationOutboundScheduler.DispatchSummary first = scheduler.dispatchOnce();
        NotificationOutboundScheduler.DispatchSummary second = scheduler.dispatchOnce();

        assertThat(first.failed()).isEqualTo(1);
        assertThat(second.failed()).isEqualTo(1);
        // 消息已实际送达两次：系统行为与“正常路径至少一次投递 + 本地去重”的契约一致，
        // 不能声明严格恰好一次。
        verify(oneBotApiClient, org.mockito.Mockito.times(2))
                .sendPrivateMsg(24681012L, "提醒正文");
        verify(mapper, never()).markRetry(anyLong(), any(), any(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("调度注解显式声明时区（ENV-01）")
    void scheduledAnnotationDeclaresZone() throws Exception {
        Scheduled scheduled = NotificationOutboundScheduler.class
                .getMethod("scheduledDispatch")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.zone()).isEqualTo("${sc.timezone:Asia/Shanghai}");
        assertThat(scheduled.fixedDelayString()).contains("sc.notification.outbound.interval-ms");
    }

    private static NotificationOutboundPolicy.Decision pending() {
        return new NotificationOutboundPolicy.Decision(OutboundStatus.PENDING_IM, NOW,
                null, "QQ_ONEBOT", "24681012");
    }

    private static NotificationLogEntity candidate(long id, String bizType, int retryCount) {
        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setNotificationId(id);
        entity.setUserId(USER_ID);
        entity.setBizType(bizType);
        entity.setContent("提醒正文");
        entity.setReadStatus("UNREAD");
        entity.setOutboundStatus("PENDING_IM");
        entity.setScheduledPushAt(NOW);
        entity.setImRetryCount(retryCount);
        return entity;
    }
}
