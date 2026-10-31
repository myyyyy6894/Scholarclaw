package com.scholarclaw.modules.knowledge.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourseDeletingVectorCleanupListenerTest {

    @Test
    @DisplayName("课程删除后调用统一清理服务（向量 + 目录 + 文档终态）")
    void cleansAllCourseResources() {
        KnowledgeDeletionService cleanup = mock(KnowledgeDeletionService.class);
        when(cleanup.finalizeCourseDeletion(10001L, 2001L, null)).thenReturn(true);

        new CourseDeletingVectorCleanupListener(cleanup).onCourseDeleting(
                new CourseDeletingEvent(10001L, 2001L));

        verify(cleanup).finalizeCourseDeletion(10001L, 2001L, null);
    }
}
