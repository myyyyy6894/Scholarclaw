package com.scholarclaw.modules.channel.dto;

/**
 * 统一后的 OneBot 入站事件（TASK-803）。
 *
 * <p>只保留渠道适配需要的字段：事件类型、发送者/消息 ID、文本、单层转发 ID 与单个文件引用。
 * {@code ignored=true} 表示消息合法但不需要业务处理（心跳、群聊未 @ 机器人、非群文件通知等），
 * 网关应快速 ACK 而不是返回错误。
 */
public record OneBotEvent(
        String postType,
        String messageType,
        String noticeType,
        String messageId,
        String senderId,
        String selfId,
        long eventTime,
        String text,
        String forwardId,
        String fileReference,
        String fileName,
        long fileSize,
        boolean metaEvent,
        boolean groupMessage,
        boolean ignored,
        String ignoredReason) {

    public boolean hasFile() {
        return fileReference != null && !fileReference.isBlank();
    }

    public boolean hasForward() {
        return forwardId != null && !forwardId.isBlank();
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
