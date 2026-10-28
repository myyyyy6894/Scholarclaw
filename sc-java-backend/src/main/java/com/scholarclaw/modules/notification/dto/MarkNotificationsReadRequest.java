package com.scholarclaw.modules.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * `API-NOTIF-002` 请求（{@code 04} §4.7.2）。
 *
 * <p>{@code mark_all=true} 时忽略 {@code notification_ids}；为 false 时必须提供
 * 1~100 个正十进制字符串 ID。ID 全部按字符串处理，前端不得转 JS Number。
 */
public record MarkNotificationsReadRequest(
        @NotNull(message = "mark_all 不能为空")
        Boolean markAll,
        @Size(max = 100, message = "notification_ids 单次最多 100 个")
        List<String> notificationIds) {
}
