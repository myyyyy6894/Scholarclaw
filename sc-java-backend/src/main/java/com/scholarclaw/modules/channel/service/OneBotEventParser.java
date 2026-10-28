package com.scholarclaw.modules.channel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.modules.channel.dto.OneBotEvent;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * OneBot 11 事件解析（TASK-803）。
 *
 * <p>支持：
 * <ul>
 *   <li>{@code message/private} 文本与单文件；</li>
 *   <li>{@code message/group} 仅在消息段包含 {@code at.qq == self_id} 时处理；</li>
 *   <li>{@code forward} 消息段（只传 {@code id}，由 {@code OneBotApiClient} 拉取单层文本）；</li>
 *   <li>{@code notice/group_upload} 群文件通知；</li>
 *   <li>{@code meta_event} 心跳直接忽略。</li>
 * </ul>
 *
 * <p>刻意不实现 v1.1 的多层嵌套转发解析：嵌套由 {@code OneBotApiClient} 明确报
 * {@code E-IM-03}，并引导用户复制文本或使用 Web。数组消息段优先；字符串消息只做最小 CQ 码处理。
 */
@Component
public class OneBotEventParser {

    private static final Pattern CQ_CODE = Pattern.compile("\\[CQ:[^\\]]+]");
    private static final Pattern FORWARD_CQ = Pattern.compile("\\[CQ:forward,[^\\]]*id=([^,\\]]+)");
    private static final Pattern FILE_ID_CQ =
            Pattern.compile("\\[CQ:file,[^\\]]*file_id=([^,\\]]+)");
    private static final Pattern FILE_NAME_CQ =
            Pattern.compile("\\[CQ:file,[^\\]]*name=([^,\\]]+)");

    public OneBotEvent parse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("OneBot payload 不是 JSON 对象");
        }
        String postType = text(payload, "post_type").toLowerCase(Locale.ROOT);
        if (postType.isBlank()) {
            throw new IllegalArgumentException("缺少 post_type");
        }
        String selfId = text(payload, "self_id");
        String senderId = text(payload, "user_id");
        long time = number(payload.path("time"), 0L);

        if ("meta_event".equals(postType)) {
            return new OneBotEvent(postType, null, null, null, senderId, selfId, time,
                    null, null, null, null, -1L, true, false, true, "meta_event");
        }
        if ("notice".equals(postType)) {
            return parseNotice(payload, senderId, selfId, time);
        }
        if (!"message".equals(postType)) {
            return ignored(postType, null, null, senderId, selfId, time, "unsupported_post_type");
        }

        String messageType = text(payload, "message_type").toLowerCase(Locale.ROOT);
        if (!"private".equals(messageType) && !"group".equals(messageType)) {
            return ignored("message", messageType, null, senderId, selfId, time,
                    "unsupported_message_type");
        }
        String messageId = text(payload, "message_id");
        if (messageId.isBlank() || senderId.isBlank()) {
            throw new IllegalArgumentException("消息缺少 message_id/user_id");
        }
        if (senderId.equals(selfId)) {
            return new OneBotEvent(postType, messageType, null, messageId, senderId, selfId, time,
                    null, null, null, null, -1L, false, "group".equals(messageType),
                    true, "self_message");
        }

        MessageParts parts = new MessageParts();
        JsonNode message = payload.path("message");
        if (message.isArray()) {
            parseArray(message, selfId, parts);
        } else {
            parseString(message.isTextual() ? message.asText() : text(payload, "raw_message"),
                    selfId, parts);
        }

        boolean group = "group".equals(messageType);
        if (group && !parts.atSelf) {
            return new OneBotEvent(postType, messageType, null, messageId, senderId, selfId, time,
                    null, null, null, null, -1L, false, true, true, "group_not_at_bot");
        }
        String text = parts.text.toString();
        if (parts.fileReference == null && parts.forwardId == null && text.isBlank()) {
            return new OneBotEvent(postType, messageType, null, messageId, senderId, selfId, time,
                    null, null, null, null, -1L, false, group, true, "empty_message");
        }
        return new OneBotEvent(postType, messageType, null, messageId, senderId, selfId, time,
                text.isBlank() ? null : text.trim(),
                parts.forwardId, parts.fileReference, parts.fileName, parts.fileSize,
                false, group, false, null);
    }

    private OneBotEvent parseNotice(JsonNode payload, String senderId, String selfId, long time) {
        String noticeType = text(payload, "notice_type").toLowerCase(Locale.ROOT);
        if (!"group_upload".equals(noticeType)) {
            return ignored("notice", null, noticeType, senderId, selfId, time,
                    "unsupported_notice_type");
        }
        JsonNode file = payload.path("file");
        String fileReference = firstNonBlank(
                text(file, "id"), text(file, "file_id"), text(file, "file"));
        String fileName = firstNonBlank(text(file, "name"), text(file, "file_name"));
        long fileSize = number(file.path("size"), -1L);
        if (fileReference.isBlank()) {
            return ignored("notice", null, noticeType, senderId, selfId, time, "file_missing_id");
        }
        // 群文件通知没有 message_id，使用 file.id 作为接入层幂等键。
        return new OneBotEvent("notice", null, noticeType, fileReference, senderId, selfId, time,
                null, null, fileReference, fileName, fileSize,
                false, true, false, null);
    }

    private void parseArray(JsonNode message, String selfId, MessageParts parts) {
        for (JsonNode segment : message) {
            String type = text(segment, "type").toLowerCase(Locale.ROOT);
            JsonNode data = segment.path("data");
            switch (type) {
                case "text" -> parts.text.append(text(data, "text"));
                case "at" -> {
                    if (!selfId.isBlank() && selfId.equals(text(data, "qq"))) {
                        parts.atSelf = true;
                    }
                }
                case "forward" -> parts.forwardId = firstNonBlank(
                        text(data, "id"), text(data, "message_id"));
                case "file" -> {
                    parts.fileReference = firstNonBlank(
                            text(data, "file_id"), text(data, "file"), text(data, "url"));
                    parts.fileName = firstNonBlank(
                            text(data, "name"), text(data, "file_name"));
                    parts.fileSize = number(data.path("file_size"), -1L);
                }
                default -> {
                    // reply/image/video 等不参与文本拼接；不提前实现富媒体解析。
                }
            }
        }
    }

    private void parseString(String raw, String selfId, MessageParts parts) {
        String value = raw == null ? "" : raw;
        if (!selfId.isBlank() && value.contains("[CQ:at,qq=" + selfId + "]")) {
            parts.atSelf = true;
        }
        Matcher forward = FORWARD_CQ.matcher(value);
        if (forward.find()) {
            parts.forwardId = forward.group(1);
        }
        Matcher fileId = FILE_ID_CQ.matcher(value);
        if (fileId.find()) {
            parts.fileReference = fileId.group(1);
        }
        Matcher fileName = FILE_NAME_CQ.matcher(value);
        if (fileName.find()) {
            parts.fileName = fileName.group(1);
        }
        parts.text.append(CQ_CODE.matcher(value).replaceAll("").trim());
    }

    private static OneBotEvent ignored(String postType, String messageType, String noticeType,
                                       String senderId, String selfId, long time, String reason) {
        return new OneBotEvent(postType, messageType, noticeType, null, senderId, selfId, time,
                null, null, null, null, -1L, false, "group".equals(messageType), true, reason);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static long number(JsonNode node, long fallback) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && node.asText().matches("-?\\d{1,19}")) {
            return Long.parseLong(node.asText());
        }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static final class MessageParts {
        private final StringBuilder text = new StringBuilder();
        private String forwardId;
        private String fileReference;
        private String fileName;
        private long fileSize = -1L;
        private boolean atSelf;
    }
}
