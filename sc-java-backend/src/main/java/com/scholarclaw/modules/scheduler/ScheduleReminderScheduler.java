package com.scholarclaw.modules.scheduler;

import com.scholarclaw.modules.schedule.service.ScheduleReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduleReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleReminderScheduler.class);
    private final ScheduleReminderService reminders;
    private final boolean enabled;
    private volatile boolean ready;

    public ScheduleReminderScheduler(ScheduleReminderService reminders,
                                     @Value("${sc.schedule.reminder.scan-enabled:true}") boolean enabled) {
        this.reminders = reminders;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() { ready = true; }

    @Scheduled(initialDelayString = "${sc.schedule.reminder.initial-delay-ms:20000}",
            fixedDelayString = "${sc.schedule.reminder.scan-delay-ms:15000}")
    public void scheduledScan() {
        if (enabled && ready) runOnce();
    }

    public int runOnce() {
        int created = 0;
        long after = 0;
        while (true) {
            var batch = reminders.enabledOwners(after, 100);
            if (batch.isEmpty()) break;
            for (long userId : batch) {
                try { created += reminders.reconcile(userId); }
                catch (RuntimeException e) {
                    log.warn("课前提醒扫描失败 userId={} type={}", userId, e.getClass().getSimpleName());
                }
            }
            after = batch.getLast();
        }
        reminders.prune();
        return created;
    }
}
