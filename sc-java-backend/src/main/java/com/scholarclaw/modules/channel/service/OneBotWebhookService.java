package com.scholarclaw.modules.channel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.enums.InboundStatus;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.channel.dto.OneBotAck;
import com.scholarclaw.modules.channel.dto.OneBotEvent;
import com.scholarclaw.modules.channel.dto.WebhookDecision;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * OneBot Webhook 同步网关（TASK-803、{@code API-WEBHOOK-001}）。
 *
 * <p>同步阶段按顺序完成：Bearer 令牌 → 事件类型 → 发送者白名单/绑定映射 → Redis 消息级去重
 * → 提交异步处理 → 快速 ACK。**不调用模型、不下载文件、不写业务表**，保证 NFR-PERF-01。
 *
 * <p>白名单 = `t_user_config` 中 `bound_im_platform = QQ_ONEBOT` 且 `bound_im_uid = sender_id`
 * 的 ACTIVE 用户；若配置了 `SC_ONEBOT_ALLOWED_SENDERS`，发送者还必须在该列表内。
 * 未命中任一条件都记录 `AUTH_REJECTED` 并返回 HTTP 200 空响应，绝不消耗 LLM Token。
 */
@Service
public class OneBotWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OneBotWebhookService.class);
    private static final String PLATFORM = "QQ_ONEBOT";

    private final OneBotAuthVerifier authVerifier;
    private final OneBotEventParser eventParser;
    private final UserConfigService userConfigService;
    private final OneBotInboundDedup dedup;
    private final OneBotChannelAdapter channelAdapter;
    private final TaskExecutor executor;
    private final Set<String> allowedSenders;

    public OneBotWebhookService(OneBotAuthVerifier authVerifier,
                                OneBotEventParser eventParser,
                                UserConfigService userConfigService,
                                OneBotInboundDedup dedup,
                                OneBotChannelAdapter channelAdapter,
                                @Qualifier("oneBotExecutor") TaskExecutor executor,
                                @Value("${sc.onebot.allowed-senders:}") String allowedSenders) {
        this.authVerifier = authVerifier;
        this.eventParser = eventParser;
        this.userConfigService = userConfigService;
        this.dedup = dedup;
        this.channelAdapter = channelAdapter;
        this.executor = executor;
        this.allowedSenders = parseAllowedSenders(allowedSenders);
    }

    public WebhookDecision accept(String authorizationHeader, JsonNode payload) {
        if (!authVerifier.isAuthorized(authorizationHeader)) {
            log.warn("OneBot Webhook 令牌校验失败，拒绝请求");
            return WebhookDecision.unauthorized();
        }
        String traceId = TraceContext.currentOrGeneratedTraceId();
        OneBotEvent event;
        try {
            event = eventParser.parse(payload);
        } catch (IllegalArgumentException e) {
            log.warn("OneBot Webhook 报文非法，忽略 error={}", e.getMessage());
            return WebhookDecision.ack(OneBotAck.of(InboundStatus.FAILED, traceId));
        }
        if (event.metaEvent()) {
            return WebhookDecision.ack(OneBotAck.of(InboundStatus.RECEIVED, traceId));
        }
        if (event.ignored()) {
            log.debug("OneBot 事件忽略 reason={} senderId={} messageId={}",
                    event.ignoredReason(), event.senderId(), event.messageId());
            return WebhookDecision.ack(OneBotAck.of(InboundStatus.RECEIVED, traceId));
        }
        if (event.senderId().isBlank() || event.messageId() == null
                || event.messageId().isBlank() || event.messageId().length() > 128) {
            log.warn("OneBot 事件缺少合法 sender_id/message_id，静默丢弃");
            return WebhookDecision.emptyOk();
        }
        if (!allowedSenders.isEmpty() && !allowedSenders.contains(event.senderId())) {
            reject(event.senderId(), event.messageId(), "not_in_env_allowlist");
            return WebhookDecision.emptyOk();
        }
        UserConfigEntity boundUser = userConfigService
                .findActiveByBoundIm(PLATFORM, event.senderId())
                .orElse(null);
        if (boundUser == null) {
            reject(event.senderId(), event.messageId(), "not_bound_active_user");
            return WebhookDecision.emptyOk();
        }

        OneBotInboundDedup.Claim claim = dedup.claim(event.senderId(), event.messageId());
        if (!claim.acquired()) {
            log.info("OneBot 入站重复消息 status={} senderId={} messageId={}",
                    InboundStatus.DUPLICATE_IGNORED, event.senderId(), event.messageId());
            return WebhookDecision.ack(
                    OneBotAck.of(InboundStatus.DUPLICATE_IGNORED, traceId));
        }

        Long userId = boundUser.getUserId();
        try {
            executor.execute(() -> processAsync(event, userId, claim, traceId));
        } catch (RuntimeException e) {
            dedup.release(event.senderId(), event.messageId(), claim);
            log.error("OneBot 异步任务提交失败 messageId={} error={}",
                    event.messageId(), e.getClass().getSimpleName());
            return WebhookDecision.ack(OneBotAck.of(InboundStatus.FAILED, traceId));
        }
        log.info("OneBot 入站已接收 status={} senderId={} messageId={} userId={} postType={}",
                InboundStatus.RECEIVED, event.senderId(), event.messageId(), userId,
                event.postType());
        return WebhookDecision.ack(OneBotAck.of(InboundStatus.RECEIVED, traceId));
    }

    private void processAsync(OneBotEvent event, Long userId,
                              OneBotInboundDedup.Claim claim, String traceId) {
        // 队列饱和时 CallerRunsPolicy 会在调用线程执行；先保存请求线程 MDC，结束后恢复。
        String previousTrace = TraceContext.currentTraceId();
        String previousUser = TraceContext.currentUserId();
        TraceContext.bind(traceId);
        TraceContext.bindUserId(userId);
        log.info("OneBot 入站开始处理 status={} messageId={} userId={}",
                InboundStatus.PROCESSING, event.messageId(), userId);
        try {
            channelAdapter.process(event, userId);
            dedup.markProcessed(event.senderId(), event.messageId(), claim);
            log.info("OneBot 入站处理完成 status={} messageId={} userId={}",
                    InboundStatus.PROCESSED, event.messageId(), userId);
        } catch (RuntimeException e) {
            dedup.release(event.senderId(), event.messageId(), claim);
            log.error("OneBot 入站处理失败 status={} messageId={} userId={} error={}",
                    InboundStatus.FAILED, event.messageId(), userId,
                    e.getClass().getSimpleName(), e);
        } finally {
            TraceContext.clear();
            if (previousTrace != null) {
                TraceContext.bind(previousTrace);
                TraceContext.bindUserId(parseUserId(previousUser));
            }
        }
    }

    private void reject(String senderId, String messageId, String reason) {
        log.info("OneBot 入站鉴权拒绝 status={} senderId={} messageId={} reason={}",
                InboundStatus.AUTH_REJECTED, senderId, messageId, reason);
    }

    private static Set<String> parseAllowedSenders(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Long parseUserId(String raw) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            return null;
        }
        return Long.valueOf(raw);
    }
}
