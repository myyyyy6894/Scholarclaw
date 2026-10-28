package com.scholarclaw.modules.notification.dto;

/** `API-NOTIF-002` 响应（{@code 04} §4.7.2）。 */
public record MarkReadResultVO(int updatedCount, long remainingUnreadCount) {
}
