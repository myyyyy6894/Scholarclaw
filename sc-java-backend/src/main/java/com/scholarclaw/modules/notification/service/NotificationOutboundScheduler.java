package com.scholarclaw.modules.notification.service;

import com.scholarclaw.common.client.OneBotApiClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 外部 IM 出站调度器（TASK-804、{@code FR-IM-003}、{@code NFR-SEC-01}）。
 *
 * <p>每 10s 扫描 `PENDING_IM` / `RATE_LIMITED_DELAYED` 且 `scheduled_push_at <= NOW(3)` 的站内信：
 * <ol>
 *   <li>用 `scheduled_push_at` 作为 DB 租约原子领取，多实例下同一行只有一个调度器能发送；</li>
 *   <li>重新校验全局开关、用户绑定、免打扰与通知类型（创建后用户可能解绑/停用）；</li>
 *   <li>Redis 全局令牌桶（10~15 条/分）+ 1.5~3.5s 随机抖动；</li>
 *   <li>成功 → `SENT_IM`；可重试失败 → `PENDING_IM` 且 30s 后重试，最多 2 次；</li>
 *   <li>重试耗尽/不可恢复/未配置 → `FALLBACK_WEB_ONLY`，站内信永不删除。</li>
 * </ol>
 *
 * <p><b>投递语义：</b>IM 侧是“至少一次”。若进程在“已发送、未写 SENT_IM”之间崩溃，
 * 租约到期后会重试并可能产生重复；站内信与业务状态不受影响。绝不先写 SENT_IM 再发送，
 * 否则会永久丢消息。
 */
@Component
public class NotificationOutboundScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboundScheduler.class);
    private static final int MAX_RETRY_COUNT = 2;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String SUPPORTED_PLATFORM = "QQ_ONEBOT";
    private static final String FALLBACK_UNCONFIGURED =
            "OneBot Action API 未配置，仅站内信";
    private static final String FALLBACK_UNSUPPORTED_PLATFORM =
            "暂不支持的 IM 平台，仅站内信";
    private static final String FALLBACK_UNKNOWN_BIZ_TYPE =
            "通知类型无法识别，仅站内信";

    private final NotificationLogMapper mapper;
    private final UserConfigService userConfigService;
    private final NotificationOutboundPolicy outboundPolicy;
    private final ImRateLimiter rateLimiter;
    private final ImPushJitter jitter;
    private final OneBotApiClient oneBotApiClient;
    private final Clock clock;
    private final boolean enabled;
    private final boolean imPushEnabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retryDelaySeconds;

    public NotificationOutboundScheduler(NotificationLogMapper mapper,
                                         UserConfigService userConfigService,
                                         NotificationOutboundPolicy outboundPolicy,
                                         ImRateLimiter rateLimiter,
                                         ImPushJitter jitter,
                                         OneBotApiClient oneBotApiClient,
                                         Clock clock,
                                         @Value("${sc.notification.outbound.enabled:true}")
                                         boolean enabled,
                                         @Value("${sc.im.push-enabled:false}")
                                         boolean imPushEnabled,
                                         @Value("${sc.notification.outbound.batch-size:5}")
                                         int batchSize,
                                         @Value("${sc.notification.outbound.lease-seconds:60}")
                                         long leaseSeconds,
                                         @Value("${sc.notification.outbound.retry-delay-seconds:30}")
                                         long retryDelaySeconds) {
        this.mapper = mapper;
        this.userConfigService = userConfigService;
        this.outboundPolicy = outboundPolicy;
        this.rateLimiter = rateLimiter;
        this.jitter = jitter;
        this.oneBotApiClient = oneBotApiClient;
        this.clock = clock;
        this.enabled = enabled;
        this.imPushEnabled = imPushEnabled;
        this.batchSize = Math.max(1, batchSize);
        this.leaseSeconds = Math.max(5, leaseSeconds);
        this.retryDelaySeconds = Math.max(1, retryDelaySeconds);
    }

    @Scheduled(initialDelayString = "${sc.notification.outbound.initial-delay-ms:30000}",
            fixedDelayString = "${sc.notification.outbound.interval-ms:10000}",
            zone = "${sc.timezone:Asia/Shanghai}")
    public void scheduledDispatch() {
        if (!enabled) {
            return;
        }
        TraceContext.bind(TraceContext.generateCronTraceId());
        try {
            DispatchSummary summary = dispatchOnce();
            if (summary.sent() > 0 || summary.failed() > 0
                    || summary.delayed() > 0 || summary.fallback() > 0) {
                log.info("IM 出站扫描完成 candidates={} sent={} retried={} delayed={} fallback={}",
                        summary.candidates(), summary.sent(), summary.failed(),
                        summary.delayed(), summary.fallback());
            } else {
                log.debug("IM 出站扫描完成 candidates={} 无待发送", summary.candidates());
            }
        } catch (RuntimeException e) {
            log.error("IM 出站扫描失败 error={}", e.getClass().getSimpleName(), e);
        } finally {
            TraceContext.clear();
        }
    }

    /** 执行一次扫描；公开方法供测试直接驱动，不依赖 @Scheduled。 */
    public DispatchSummary dispatchOnce() {
        // 必须截断到毫秒：t_notification_log 的时间列是 DATETIME(3)，而 LocalDateTime.now()
        // 带纳秒；若用纳秒值做租约等值条件，MySQL 存的是毫秒，markSent/markRetry 会匹配不到。
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        List<NotificationLogEntity> candidates =
                mapper.selectOutboundCandidates(now, batchSize);
        int sent = 0;
        int failed = 0;
        int delayed = 0;
        int fallback = 0;
        for (NotificationLogEntity candidate : candidates) {
            Outcome outcome;
            try {
                outcome = dispatchOne(candidate, now);
            } catch (RuntimeException e) {
                log.error("IM 出站处理异常 notificationId={} error={}",
                        candidate.getNotificationId(), e.getClass().getSimpleName(), e);
                outcome = Outcome.FAILED;
            }
            switch (outcome) {
                case SENT -> sent++;
                case RETRIED, FAILED -> failed++;
                case DELAYED -> delayed++;
                case FALLBACK -> fallback++;
                case SKIPPED -> {
                    // 已被其他实例领取/状态变化，不计入本轮处理数。
                }
            }
        }
        return new DispatchSummary(candidates.size(), sent, failed, delayed, fallback);
    }

    private Outcome dispatchOne(NotificationLogEntity candidate, LocalDateTime now) {
        if (candidate.getNotificationId() == null
                || candidate.getScheduledPushAt() == null
                || candidate.getUserId() == null) {
            return Outcome.SKIPPED;
        }
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        int claimed = mapper.claimOutbound(candidate.getNotificationId(),
                candidate.getScheduledPushAt(), leaseUntil, now);
        if (claimed != 1) {
            return Outcome.SKIPPED;
        }

        NotificationBizType bizType;
        try {
            bizType = NotificationBizType.fromCode(candidate.getBizType());
        } catch (IllegalArgumentException e) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    FALLBACK_UNKNOWN_BIZ_TYPE, now);
            return Outcome.FALLBACK;
        }

        UserConfigEntity config = userConfigService.findById(candidate.getUserId()).orElse(null);
        NotificationOutboundPolicy.Decision decision = outboundPolicy.resolve(
                imPushEnabled, config, bizType, now);
        if (decision.status() == OutboundStatus.FALLBACK_WEB_ONLY) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    decision.imErrorMsg(), now);
            return Outcome.FALLBACK;
        }
        if (decision.status() == OutboundStatus.RATE_LIMITED_DELAYED) {
            mapper.markRateLimited(candidate.getNotificationId(), leaseUntil,
                    decision.scheduledPushAt(), decision.imErrorMsg(), now);
            return Outcome.DELAYED;
        }
        if (!SUPPORTED_PLATFORM.equals(decision.targetImPlatform())) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    FALLBACK_UNSUPPORTED_PLATFORM, now);
            return Outcome.FALLBACK;
        }
        if (decision.targetImUid() == null || decision.targetImUid().isBlank()) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    "目标 IM 账号为空，仅站内信", now);
            return Outcome.FALLBACK;
        }
        Long targetUid = parseUidOrNull(decision.targetImUid());
        if (targetUid == null) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    "目标 IM 账号格式非法，仅站内信", now);
            return Outcome.FALLBACK;
        }
        if (!oneBotApiClient.isConfigured()) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    FALLBACK_UNCONFIGURED, now);
            return Outcome.FALLBACK;
        }

        ImRateLimiter.AcquireResult token = rateLimiter.tryAcquire();
        if (!token.allowed()) {
            LocalDateTime next = now.plusNanos(token.waitMillis() * 1_000_000L)
                    .plusNanos(jitter.randomRateDelayMillis() * 1_000_000L);
            mapper.markRateLimited(candidate.getNotificationId(), leaseUntil, next,
                    "IM 发送频率受限，已顺延", now);
            return Outcome.DELAYED;
        }

        try {
            jitter.sleepBeforeSend();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("IM 出站发送前抖动被中断 notificationId={}，本轮中止",
                    candidate.getNotificationId());
            return Outcome.SKIPPED;
        }

        try {
            oneBotApiClient.sendPrivateMsg(targetUid, candidate.getContent());
            int updated = mapper.markSent(candidate.getNotificationId(), leaseUntil, now);
            if (updated != 1) {
                log.warn("IM 已发送但状态更新失败，租约到期后可能重复 notificationId={}",
                        candidate.getNotificationId());
            }
            return Outcome.SENT;
        } catch (RuntimeException e) {
            return handleSendFailure(candidate, leaseUntil, now, e);
        }
    }

    private Outcome handleSendFailure(NotificationLogEntity candidate, LocalDateTime leaseUntil,
                                      LocalDateTime now, RuntimeException error) {
        String errorMsg = sanitizeError(error);
        int retryCount = candidate.getImRetryCount() == null ? 0 : candidate.getImRetryCount();
        log.warn("IM 推送失败 notificationId={} retryCount={} error={}",
                candidate.getNotificationId(), retryCount, error.getClass().getSimpleName());
        if (retryCount >= MAX_RETRY_COUNT) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil, errorMsg, now);
            return Outcome.FALLBACK;
        }
        LocalDateTime next = now.plusSeconds(retryDelaySeconds);
        int updated = mapper.markRetry(candidate.getNotificationId(), leaseUntil, next,
                retryCount, errorMsg, now);
        if (updated != 1) {
            log.warn("IM 重试状态写入失败 notificationId={}（可能已被其他实例接管）",
                    candidate.getNotificationId());
        }
        return Outcome.RETRIED;
    }

    private static Long parseUidOrNull(String raw) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            return null;
        }
        long value = Long.parseLong(raw);
        return value > 0 ? value : null;
    }

    private static String sanitizeError(RuntimeException error) {
        String message;
        if (error instanceof BusinessException businessException) {
            message = businessException.getErrorCode().code() + " "
                    + (businessException.getMessage() == null
                    ? businessException.getErrorCode().defaultMessage()
                    : businessException.getMessage());
        } else {
            message = "IM 发送失败（" + error.getClass().getSimpleName() + "）";
        }
        // 绝不记录请求体、响应体或令牌；异常消息由 OneBotApiClient 已脱敏。
        return message.length() <= MAX_ERROR_LENGTH
                ? message : message.substring(0, MAX_ERROR_LENGTH - 1) + "…";
    }

    /** 一轮扫描汇总。 */
    public record DispatchSummary(int candidates, int sent, int failed,
                                  int delayed, int fallback) {
    }

    private enum Outcome {
        SENT,
        RETRIED,
        FAILED,
        DELAYED,
        FALLBACK,
        SKIPPED
    }
}
