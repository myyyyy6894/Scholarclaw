package com.scholarclaw.modules.knowledge.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourseDeletingVectorCleanupListenerTest {

    @Test
    @DisplayName("课程删除后调用统一清理服务")
    void cleansAllCourseCollections() {
        KnowledgeVectorCleanupService cleanup = mock(KnowledgeVectorCleanupService.class);
        when(cleanup.cleanupCourse(10001L, 2001L, null)).thenReturn(true);

        new CourseDeletingVectorCleanupListener(cleanup).onCourseDeleting(
                new CourseDeletingEvent(10001L, 2001L));

        verify(cleanup).cleanupCourse(10001L, 2001L, null);
    }
}
