package com.scholarclaw.modules.channel.dto;

/**
 * Webhook 同步阶段决策：HTTP 状态 + 可选 ACK。
 *
 * <p>鉴权失败返回 401；白名单拒绝/非法报文返回 HTTP 200 空响应（OneBot 不应重试）；
 * 正常/重复/meta 事件返回 200 + {@link OneBotAck}。
 */
public record WebhookDecision(int httpStatus, OneBotAck ack) {

    public static WebhookDecision unauthorized() {
        return new WebhookDecision(401, null);
    }

    public static WebhookDecision emptyOk() {
        return new WebhookDecision(200, null);
    }

    public static WebhookDecision ack(OneBotAck ack) {
        return new WebhookDecision(200, ack);
    }
}
