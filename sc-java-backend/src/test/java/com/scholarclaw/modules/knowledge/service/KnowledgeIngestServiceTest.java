package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mq.IngestJobPublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeIngestServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeDocService docService;
    private IngestJobPublisher publisher;
    private KnowledgeIngestService service;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        publisher = mock(IngestJobPublisher.class);
        when(publisher.isEnabled()).thenReturn(true);
        service = new KnowledgeIngestService(docService, publisher);
    }

    @Test
    @DisplayName("INITIAL_INGEST 发布成功后才推进 PARSING")
    void marksParsingOnlyAfterPublish() {
        when(docService.requireOwnedDoc(USER_ID, DOC_ID)).thenReturn(doc("UPLOADED"));
        when(publisher.publish(any())).thenReturn(true);

        assertThat(service.enqueueInitial(USER_ID, DOC_ID)).isTrue();

        verify(docService).markParsing(org.mockito.ArgumentMatchers.eq(DOC_ID),
                org.mockito.ArgumentMatchers.eq(1), any(String.class));
    }

    @Test
    @DisplayName("INITIAL_INGEST 发布失败时保留 UPLOADED，等待巡检补投")
    void keepsUploadedWhenPublishFails() {
        when(docService.requireOwnedDoc(USER_ID, DOC_ID)).thenReturn(doc("UPLOADED"));
        when(publisher.publish(any())).thenReturn(false);

        assertThat(service.enqueueInitial(USER_ID, DOC_ID)).isFalse();

        verify(docService, never()).markParsing(any(), anyInt(), any());
    }

    @Test
    @DisplayName("重试/重建发布失败必须报错，不伪装成功")
    void retryFailsWhenPublishFails() {
        when(docService.retryFailed(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(DOC_ID), any(String.class)))
                .thenReturn(doc("PARSING"));
        when(publisher.publish(any())).thenReturn(false);

        assertThatThrownBy(() -> service.retry(USER_ID, DOC_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("课程切换后为每份 PARSING 文档发布 REINDEX 并回填任务 ID")
    void publishesReindexForCourse() {
        KnowledgeDocEntity first = doc("PARSING");
        KnowledgeDocEntity second = doc("PARSING");
        second.setDocId(50002L);
        when(docService.findParsingForCourse(USER_ID, COURSE_ID, 2))
                .thenReturn(List.of(first, second));
        when(publisher.publish(any())).thenReturn(true);

        assertThat(service.publishReindexForCourse(USER_ID, COURSE_ID, 2)).isEqualTo(2);

        verify(docService).recordEnqueued(org.mockito.ArgumentMatchers.eq(DOC_ID),
                org.mockito.ArgumentMatchers.eq(1), any(String.class));
        verify(docService).recordEnqueued(org.mockito.ArgumentMatchers.eq(50002L),
                org.mockito.ArgumentMatchers.eq(1), any(String.class));
    }

    private static KnowledgeDocEntity doc(String status) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setDocName("a.pdf");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        doc.setFileSha256("e3b0c442");
        doc.setStatus(status);
        doc.setParseVersion(1);
        doc.setCourseIndexVersion(2);
        doc.setRetryCount(0);
        return doc;
    }

}
