package com.scholarclaw.modules.agent.dto;

/** 低置信度时需要暂存的原始消息（Redis `sc:u:{uid}:pending_intent`，TTL 30 分钟）。 */
public record PendingIntent(
        String rawText,
        String referenceTime,
        String platform,
        String sourceMsgId,
        String courseId,
        String createdAt) {
}
