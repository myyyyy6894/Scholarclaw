package com.scholarclaw.modules.notification.service;

import java.time.Instant;

/** Schedule implements this port. Outbound delivery cannot outlive the current occurrence group. */
public interface ScheduleReminderOutboundGuard {
    boolean isCurrent(long userId, long reminderId, long notificationId, Instant now);
}
