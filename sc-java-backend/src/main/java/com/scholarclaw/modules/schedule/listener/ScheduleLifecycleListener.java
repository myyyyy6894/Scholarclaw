package com.scholarclaw.modules.schedule.listener;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.schedule.service.ScheduleCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ScheduleLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(ScheduleLifecycleListener.class);
    private final ScheduleRepository repository;
    private final ScheduleCleanupService cleanup;
    private final ApplicationEventPublisher publisher;

    public ScheduleLifecycleListener(ScheduleRepository repository, ScheduleCleanupService cleanup,
                                     ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.cleanup = cleanup;
        this.publisher = publisher;
    }

    @EventListener
    public void unbindDeletedCourse(CourseDeletingEvent event) {
        repository.unbindCourse(event.userId(), event.courseId(), ScTimeZone.now().toLocalDateTime());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeUser(UserDeactivatingEvent event) {
        try {
            cleanup.purge(event.userId());
        } catch (RuntimeException e) {
            log.error("账号注销：课表清理失败 userId={} error={}", event.userId(), e.getClass().getSimpleName());
            return;
        }
        publisher.publishEvent(new UserCleanupCompletedEvent(event.userId(), UserCleanupCompletedEvent.Module.SCHEDULE));
    }
}
