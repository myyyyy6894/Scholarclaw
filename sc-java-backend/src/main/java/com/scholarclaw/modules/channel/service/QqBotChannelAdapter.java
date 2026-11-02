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
 * <p>与 OneBot 通道的区别：
 * <ul>
 *   <li><b>没有 sender→账号的全局映射</b>：连接本身就属于某个用户，消息天然归属该用户；</li>
 *   <li>首次私聊时把 openid 记到该用户名下，之后只接受同一 openid（防止他人给这个机器人发消息
 *       被当成账号主人的输入）；</li>
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

    public QqBotChannelAdapter(AgentOrchestratorService orchestrator,
                               UserConfigService userConfigService,
                               QqBotSender sender) {
        this.orchestrator = orchestrator;
        this.userConfigService = userConfigService;
        this.sender = sender;
    }

    /** 处理一条已连接机器人的入站消息。 */
    public void handle(QqBotConnection.InboundMessage message) {
        Long userId = message.userId();
        String openid = message.openid();
        if (openid == null || openid.isBlank() || message.messageId() == null) {
            return;
        }
        if (!acceptOpenid(userId, openid)) {
            log.warn("QQ 机器人收到非绑定 openid 的消息，已忽略 userId={}", userId);
            return;
        }
        String text = message.content() == null ? "" : message.content().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            safeReply(userId, openid, message.messageId(),
                    "⚠️ 消息文本过长，请分段发送，或在 PC Web 工作台粘贴完整通知。");
            return;
        }
        try {
            AgentReply reply = orchestrator.handle(new AgentInboundMessage(
                    userId, "QQ_OFFICIAL", message.messageId(), text, referenceTime(userId), null, false));
            if (reply != null && reply.replyText() != null && !reply.replyText().isBlank()) {
                safeReply(userId, openid, message.messageId(), reply.replyText());
            }
        } catch (BusinessException e) {
            log.warn("QQ 机器人文本处理失败 userId={} code={}", userId, e.getErrorCode().code());
            safeReply(userId, openid, message.messageId(), userMessage(e));
        } catch (RuntimeException e) {
            log.error("QQ 机器人文本处理未预期失败 userId={} error={}",
                    userId, e.getClass().getSimpleName(), e);
            safeReply(userId, openid, message.messageId(), GENERIC_FAILURE);
        }
    }

    /**
     * 首次私聊自动绑定 openid；之后必须一致。
     *
     * <p>绑定后该机器人只服务账号主人——这也是「用户自带 Bot」模式的安全性来源：
     * 别人给他的机器人发消息会被忽略。
     */
    private boolean acceptOpenid(Long userId, String openid) {
        UserConfigEntity config = userConfigService.findById(userId).orElse(null);
        if (config == null) {
            return false;
        }
        String bound = config.getQqbotOpenid();
        if (bound == null || bound.isBlank()) {
            userConfigService.bindQqBotOpenid(userId, openid);
            log.info("QQ 机器人已绑定首个 openid userId={}", userId);
            return true;
        }
        return bound.equals(openid);
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

    private void safeReply(Long userId, String openid, String messageId, String content) {
        try {
            sender.reply(userId, openid, messageId, 1, content);
        } catch (RuntimeException e) {
            log.warn("QQ 机器人回复失败 userId={} error={}", userId, e.getClass().getSimpleName());
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
