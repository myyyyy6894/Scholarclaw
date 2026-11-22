package com.scholarclaw.modules.channel.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.service.AgentOrchestratorService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.common.config.ScTimeZone;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * QQ 官方机器人入站适配器（用户自带 Bot 模式）。
 *
 * <p>官方机器人的接入约束：
 * <ul>
 *   <li><b>没有 sender→账号的全局映射</b>：连接本身就属于某个用户，消息天然归属该用户；</li>
 *   <li>只接受经 Web 一次性挑战确认的私聊 openid；群身份与私聊身份不能直接等同；</li>
 *   <li>回复走被动回复（带 msg_id），不走主动推送。</li>
 * </ul>
 */
@Service
public class QqBotChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(QqBotChannelAdapter.class);
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final String GENERIC_FAILURE = "⚠️ 消息处理失败，请稍后在 PC Web 工作台重试。";

    private final AgentOrchestratorService orchestrator;
    private final UserConfigService userConfigService;
    private final QqBotSender sender;
    private final QqBotBindingService bindingService;

    public QqBotChannelAdapter(AgentOrchestratorService orchestrator,
                               UserConfigService userConfigService,
                               QqBotSender sender, QqBotBindingService bindingService) {
        this.orchestrator = orchestrator;
        this.userConfigService = userConfigService;
        this.sender = sender;
        this.bindingService = bindingService;
    }

    /** 处理一条已连接机器人的入站消息。 */
    public void handle(QqBotConnection.InboundMessage message) {
        Long userId = message.userId();
        String openid = message.openid();
        if (openid == null || openid.isBlank() || message.messageId() == null || message.group()) {
            return;
        }
        String text = message.content() == null ? "" : message.content().trim();
        if (text.startsWith("/绑定")) {
            String[] parts = text.split("\\s+", 2);
            if (parts.length == 2 && bindingService.consume(message, parts[1])) {
                safeReply(message, "✅ QQ 身份已绑定，可继续私聊使用。");
            }
            return; // 绑定码绝不进入 Agent、模型或日志。
        }
        if (!acceptOpenid(message)) {
            log.warn("QQ 机器人收到非绑定 openid 的消息，已忽略 userId={}", userId);
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            safeReply(message,
                    "⚠️ 消息文本过长，请分段发送，或在 PC Web 工作台粘贴完整通知。");
            return;
        }
        try {
            AgentReply reply = orchestrator.handle(new AgentInboundMessage(
                    userId, "QQ_OFFICIAL", message.messageId(), text, referenceTime(userId), null, false));
            if (reply != null && reply.replyText() != null && !reply.replyText().isBlank()) {
                safeReply(message, reply.replyText());
            }
        } catch (BusinessException e) {
            log.warn("QQ 机器人文本处理失败 userId={} code={}", userId, e.getErrorCode().code());
            safeReply(message, userMessage(e));
        } catch (RuntimeException e) {
            log.error("QQ 机器人文本处理未预期失败 userId={} error={}",
                    userId, e.getClass().getSimpleName(), e);
            safeReply(message, GENERIC_FAILURE);
        }
    }

    private boolean acceptOpenid(QqBotConnection.InboundMessage message) {
        UserConfigEntity config = userConfigService.findById(message.userId()).orElse(null);
        return config != null && "ACTIVE".equals(config.getAccountStatus())
                && java.util.Objects.equals(config.getQqbotAppId(), message.appId())
                && java.util.Objects.equals(config.getQqbotBindingVersion(), message.connectionVersion())
                && Integer.valueOf(1).equals(config.getQqbotBindingVerified())
                && message.openid().equals(config.getQqbotOpenid());
    }

    /** 参考时间：按用户时区成 ISO 偏移时间（DDL 相对时间解析依赖它）。 */
    private String referenceTime(Long userId) {
        ZoneId zone = userConfigService.findById(userId)
                .map(UserConfigEntity::getTimezone)
                .map(zoneName -> {
                    try {
                        return ZoneId.of(zoneName);
                    } catch (RuntimeException e) {
                        return ScTimeZone.zoneId();
                    }
                })
                .orElseGet(ScTimeZone::zoneId);
        return java.time.ZonedDateTime.now(zone).toOffsetDateTime().toString();
    }

    private void safeReply(QqBotConnection.InboundMessage message, String content) {
        try {
            sender.reply(message.userId(), message.appId(), message.connectionVersion(),
                    message.openid(), message.messageId(), 1, content);
        } catch (RuntimeException e) {
            log.warn("QQ 机器人回复失败 userId={} error={}", message.userId(), e.getClass().getSimpleName());
        }
    }

    private static String userMessage(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        if (code == ErrorCode.E_SYS_01) {
            return "⚠️ " + code.defaultMessage() + "\n请先在 PC Web 工作台的【个人设置 → 模型配置】完成配置。";
        }
        return "⚠️ " + code.defaultMessage();
    }
}
