package com.scholarclaw.modules.planning.listener;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.planning.service.PlanningCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PlanningLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(PlanningLifecycleListener.class);
    private final PlanningCleanupService cleanup;
    private final ApplicationEventPublisher publisher;
    public PlanningLifecycleListener(PlanningCleanupService cleanup, ApplicationEventPublisher publisher) {
        this.cleanup = cleanup; this.publisher = publisher;
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeUser(UserDeactivatingEvent event) {
        try { cleanup.purge(event.userId()); }
        catch (RuntimeException e) {
            log.error("账号注销：计划清理失败 userId={} error={}", event.userId(), e.getClass().getSimpleName());
            return;
        }
        publisher.publishEvent(new UserCleanupCompletedEvent(event.userId(), UserCleanupCompletedEvent.Module.PLANNING));
    }
}
