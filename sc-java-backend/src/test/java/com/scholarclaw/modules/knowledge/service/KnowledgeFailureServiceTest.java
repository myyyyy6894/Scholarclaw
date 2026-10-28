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
import com.scholarclaw.modules.notification.service.NotificationBizType;
import com.scholarclaw.modules.notification.service.NotificationCreateCommand;
import com.scholarclaw.modules.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class KnowledgeFailureServiceTest {

    private KnowledgeDocService docService;
    private KnowledgeParentChunkMapper parentChunkMapper;
    private CourseService courseService;
    private ApplicationEventPublisher eventPublisher;
    private NotificationService notificationService;
    private KnowledgeFailureService service;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        parentChunkMapper = mock(KnowledgeParentChunkMapper.class);
        courseService = mock(CourseService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        notificationService = mock(NotificationService.class);
        service = new KnowledgeFailureService(docService, parentChunkMapper,
                courseService, eventPublisher, notificationService);
    }

    @Test
    @DisplayName("失败收敛：置 FAILED、清父块、登记向量清理、刷新课程并写站内通知")
    void convergesFailureAtomically() {
        when(docService.markFailed(50001L, 3, "E-RAG-02", "failed")).thenReturn(true);
        KnowledgeDocEntity failedDoc = new KnowledgeDocEntity();
        failedDoc.setDocId(50001L);
        failedDoc.setDocName("第5章_事务处理.pdf");
        when(docService.findOwnedDoc(10001L, 50001L)).thenReturn(failedDoc);

        KnowledgeFailureService.FailureOutcome outcome =
                service.fail(10001L, 2001L, 50001L, 3, 2, "E-RAG-02", "failed");

        assertThat(outcome.applied()).isTrue();
        verify(parentChunkMapper).deleteByDoc(10001L, 50001L);
        verify(eventPublisher).publishEvent(any(DocumentVectorCleanupEvent.class));
        verify(courseService).refreshIndexStatus(10001L, 2001L, 2);
        ArgumentCaptor<NotificationCreateCommand> notification =
                ArgumentCaptor.forClass(NotificationCreateCommand.class);
        verify(notificationService).createInTx(notification.capture());
        assertThat(notification.getValue().bizType()).isEqualTo(NotificationBizType.DOC_INGEST_FAILED);
        assertThat(notification.getValue().bizId()).isEqualTo(50001L);
        assertThat(notification.getValue().title()).contains("第5章_事务处理.pdf");
        assertThat(notification.getValue().content()).contains("E-RAG-02");
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
        verify(notificationService, never()).createInTx(any());
    }
}
