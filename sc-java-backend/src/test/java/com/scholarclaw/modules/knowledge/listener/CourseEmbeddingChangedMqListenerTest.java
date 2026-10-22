package com.scholarclaw.modules.knowledge.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourseEmbeddingChangedMqListenerTest {

    @Test
    @DisplayName("课程切换提交后批量发布 REINDEX")
    void publishesAfterCourseEmbeddingChange() {
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        KnowledgeVectorCleanupService cleanupService = mock(KnowledgeVectorCleanupService.class);
        when(ingestService.publishReindexForCourse(10001L, 2001L, 2)).thenReturn(3);

        new CourseEmbeddingChangedMqListener(ingestService, cleanupService).onCourseEmbeddingChanged(
                new CourseEmbeddingChangedEvent(10001L, 2001L, 1, 2,
                        "OPENAI_COMPATIBLE", "embed-v2", 1024));

        verify(ingestService).publishReindexForCourse(10001L, 2001L, 2);
        verify(cleanupService).cleanupCourse(10001L, 2001L, 1);
    }

    @Test
    @DisplayName("僵尸巡检只补投成功入队的文档")
    void scannerCountsOnlyPublishedDocs() {
        KnowledgeDocService docService = mock(KnowledgeDocService.class);
        KnowledgeIngestService ingestService = mock(KnowledgeIngestService.class);
        KnowledgeDocEntity first = doc(50001L);
        KnowledgeDocEntity second = doc(50002L);
        when(docService.findStaleUploaded(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(first, second));
        when(ingestService.enqueueInitial(10001L, 50001L)).thenReturn(true);
        when(ingestService.enqueueInitial(10001L, 50002L)).thenReturn(false);

        int published = new ZombieIngestScanner(docService, ingestService, true, 5, 50).scan();

        assertThat(published).isEqualTo(1);
    }

    private static KnowledgeDocEntity doc(Long docId) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(docId);
        doc.setUserId(10001L);
        doc.setCourseId(2001L);
        doc.setStatus("UPLOADED");
        return doc;
    }
}
