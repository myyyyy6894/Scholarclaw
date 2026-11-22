package com.scholarclaw.modules.notification.service;

import com.scholarclaw.common.im.OfficialBotSender;
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
 *   <li>成功 → `SENT_IM`；可重试失败 → `PENDING_IM` 且 30s 后重试，最多 2 次；</li>
 *   <li>重试耗尽/不可恢复/未配置 → `FALLBACK_WEB_ONLY`，站内信永不删除。</li>
 * </ol>
 *
 * <p><b>投递语义：</b>IM 侧是“至少一次”。若进程在“已发送、未写 SENT_IM”之间崩溃，
 * 或 {@code markSent} 写入失败，租约到期后会重试并可能产生重复；站内信与业务状态不受影响。
 */
@Component
public class NotificationOutboundScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboundScheduler.class);
    private static final int MAX_RETRY_COUNT = 2;
    private static final int MAX_ERROR_LENGTH = 512;
    private static final String FALLBACK_UNKNOWN_BIZ_TYPE =
            "通知类型无法识别，仅站内信";

    private final NotificationLogMapper mapper;
    private final UserConfigService userConfigService;
    private final NotificationOutboundPolicy outboundPolicy;
    private final OfficialBotSender officialBotSender;
    private final Clock clock;
    private final boolean enabled;
    private final boolean imPushEnabled;
    private final int batchSize;
    private final long leaseSeconds;
    private final long retryDelaySeconds;

    public NotificationOutboundScheduler(NotificationLogMapper mapper,
                                         UserConfigService userConfigService,
                                         NotificationOutboundPolicy outboundPolicy,
                                         OfficialBotSender officialBotSender,
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
        this.officialBotSender = officialBotSender;
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

        if (candidate.getTargetImPlatform() != null) {
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    "原提醒渠道已停用，仅站内信", now);
            return Outcome.FALLBACK;
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
            mapper.markFallback(candidate.getNotificationId(), leaseUntil, decision.imErrorMsg(), now);
            return Outcome.FALLBACK;
        }
        if (decision.status() == OutboundStatus.RATE_LIMITED_DELAYED) {
            mapper.markRateLimited(candidate.getNotificationId(), leaseUntil,
                    decision.scheduledPushAt(), decision.imErrorMsg(), now);
            return Outcome.DELAYED;
        }
        return dispatchViaOfficialBot(candidate, leaseUntil, now);
    }

    /**
     * 通过用户自带的官方机器人投递。
     *
     * <p>发送与状态写入分开捕获：markSent 抛异常时消息已经送达，不能再当失败重发。
     */
    private Outcome dispatchViaOfficialBot(NotificationLogEntity candidate,
                                           LocalDateTime leaseUntil, LocalDateTime now) {
        boolean delivered;
        try {
            delivered = officialBotSender.sendProactive(candidate.getUserId(), candidate.getContent());
        } catch (RuntimeException e) {
            return handleSendFailure(candidate, leaseUntil, now, e);
        }
        if (!delivered) {
            // 用户还没私聊过机器人，拿不到 openid —— 只能站内信
            mapper.markFallback(candidate.getNotificationId(), leaseUntil,
                    "用户尚未与官方机器人建立会话，仅站内信", now);
            return Outcome.FALLBACK;
        }
        int updated = mapper.markSent(candidate.getNotificationId(), leaseUntil, now);
        if (updated != 1) {
            log.warn("官方机器人已发送但状态更新未生效，租约到期后可能重复 notificationId={}",
                    candidate.getNotificationId());
        }
        return Outcome.SENT;
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
