package com.scholarclaw.modules.planning.listener;

import static org.mockito.Mockito.*;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.planning.service.PlanningCleanupService;
import com.scholarclaw.modules.planning.repository.StudyPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class PlanningLifecycleListenerTest {
    @Test
    void reportsCompletionOnlyAfterPlanningCleanupSucceeds() {
        var cleanup = mock(PlanningCleanupService.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var listener = new PlanningLifecycleListener(cleanup, publisher, mock(StudyPlanRepository.class));
        doThrow(new IllegalStateException("synthetic failure")).doNothing().when(cleanup).purge(2L);
        listener.purgeUser(new UserDeactivatingEvent(2L));
        verifyNoInteractions(publisher);
        listener.purgeUser(new UserDeactivatingEvent(2L));
        verify(publisher).publishEvent(new UserCleanupCompletedEvent(2L, UserCleanupCompletedEvent.Module.PLANNING));
    }
}
