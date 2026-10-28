package com.scholarclaw.modules.notification.dto;

import java.util.List;

/** `API-NOTIF-001` 分页响应（{@code 04} §4.7.1）。 */
public record NotificationListVO(
        long unreadCount,
        int page,
        int size,
        long total,
        int totalPages,
        List<NotificationVO> items) {
}
