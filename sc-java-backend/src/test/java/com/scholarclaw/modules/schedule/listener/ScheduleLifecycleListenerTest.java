package com.scholarclaw.modules.schedule.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.schedule.service.ScheduleCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ScheduleLifecycleListenerTest {
    @Test
    void onlyReportsCompletionAfterSuccessfulCleanup() {
        var repository = mock(ScheduleRepository.class);
        var cleanup = mock(ScheduleCleanupService.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var listener = new ScheduleLifecycleListener(repository, cleanup, publisher);
        doThrow(new IllegalStateException("test")).when(cleanup).purge(1L);
        listener.purgeUser(new UserDeactivatingEvent(1L));
        verify(publisher, never()).publishEvent(any(Object.class));
        listener.purgeUser(new UserDeactivatingEvent(2L));
        verify(publisher).publishEvent(new UserCleanupCompletedEvent(2L, UserCleanupCompletedEvent.Module.SCHEDULE));
    }
}
