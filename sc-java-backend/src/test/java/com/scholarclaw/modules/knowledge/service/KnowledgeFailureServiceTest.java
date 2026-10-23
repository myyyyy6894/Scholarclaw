package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.DocumentVectorCleanupEvent;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class KnowledgeFailureServiceTest {

    private KnowledgeDocService docService;
    private KnowledgeParentChunkMapper parentChunkMapper;
    private CourseService courseService;
    private ApplicationEventPublisher eventPublisher;
    private KnowledgeFailureService service;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        parentChunkMapper = mock(KnowledgeParentChunkMapper.class);
        courseService = mock(CourseService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new KnowledgeFailureService(docService, parentChunkMapper,
                courseService, eventPublisher);
    }

    @Test
    @DisplayName("失败收敛：置 FAILED、清父块、登记向量清理、刷新课程")
    void convergesFailureAtomically() {
        when(docService.markFailed(50001L, 3, "E-RAG-02", "failed")).thenReturn(true);

        KnowledgeFailureService.FailureOutcome outcome =
                service.fail(10001L, 2001L, 50001L, 3, 2, "E-RAG-02", "failed");

        assertThat(outcome.applied()).isTrue();
        verify(parentChunkMapper).deleteByDoc(10001L, 50001L);
        verify(eventPublisher).publishEvent(any(DocumentVectorCleanupEvent.class));
        verify(courseService).refreshIndexStatus(10001L, 2001L, 2);
    }

    @Test
    @DisplayName("版本已被接管：用 FOR UPDATE 当前读识别 v2，并清理迟到 v3 向量")
    void inspectsCurrentVersionWhenNotApplied() {
        KnowledgeDocEntity current = new KnowledgeDocEntity();
        current.setDocId(50001L);
        current.setCourseId(2001L);
        current.setParseVersion(4);
        current.setCourseIndexVersion(2);
        current.setStatus("PARSING");
        when(docService.markFailed(50001L, 3, "E-RAG-02", "failed")).thenReturn(false);
        when(docService.findOwnedDocForUpdate(10001L, 50001L)).thenReturn(current);

        KnowledgeFailureService.FailureOutcome outcome =
                service.fail(10001L, 2001L, 50001L, 3, 2, "E-RAG-02", "failed");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.staleCleanupTriggered()).isTrue();
        assertThat(outcome.currentStatus()).isEqualTo("PARSING");
        verify(docService).findOwnedDocForUpdate(10001L, 50001L);
        verify(eventPublisher).publishEvent(any(DocumentVectorCleanupEvent.class));
        verify(parentChunkMapper, never()).deleteByDoc(any(), any());
    }
}
