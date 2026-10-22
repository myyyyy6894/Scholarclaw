package com.scholarclaw.modules.knowledge.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RagIngestDlqListenerTest {

    private static final String BODY = """
            {"ingest_job_id":"job_1","job_type":"INITIAL_INGEST","user_id":"10001",
             "course_id":"2001","course_index_version":2,"doc_id":"50001","parse_version":3,
             "retry_count":3}
            """;

    private KnowledgeDocMapper docMapper;
    private KnowledgeDocService docService;
    private CourseService courseService;
    private KnowledgeVectorCleanupService cleanupService;
    private RagIngestDlqListener listener;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        docService = mock(KnowledgeDocService.class);
        courseService = mock(CourseService.class);
        cleanupService = mock(KnowledgeVectorCleanupService.class);
        listener = new RagIngestDlqListener(new ObjectMapper(), docMapper, docService,
                courseService, cleanupService);
    }

    @Test
    @DisplayName("当前 PARSING 版本进入死信：置 FAILED 并清理向量")
    void marksFailedAndCleansVectors() {
        when(docMapper.selectOne(any())).thenReturn(doc("PARSING", 3, "job_1"));
        when(docService.markFailed(50001L, 3, "E-RAG-02",
                "课件解析多次重试后仍失败，请稍后手动重试或更换文件。")).thenReturn(true);

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(docService).markFailed(eq(50001L), eq(3), eq("E-RAG-02"), any(String.class));
        verify(courseService).refreshIndexStatus(10001L, 2001L, 2);
        verify(cleanupService).cleanupDocument(10001L, 2001L, 50001L, 3);
    }

    @Test
    @DisplayName("旧版本或已终结文档的死信直接忽略")
    void ignoresStaleDeadLetter() {
        when(docMapper.selectOne(any())).thenReturn(doc("READY", 4, "job_1"));

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(docService, never()).markFailed(any(), any(Integer.class), any(), any());
        verify(cleanupService, never()).cleanupDocument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("死信任务 ID 与当前任务不一致时忽略")
    void ignoresDifferentJob() {
        when(docMapper.selectOne(any())).thenReturn(doc("PARSING", 3, "job_other"));

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(docService, never()).markFailed(any(), any(Integer.class), any(), any());
    }

    @Test
    @DisplayName("非法 JSON 不抛异常")
    void ignoresInvalidJson() {
        listener.handle("not-json".getBytes(StandardCharsets.UTF_8));
        assertThat(true).isTrue();
    }

    private static KnowledgeDocEntity doc(String status, int version, String jobId) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(50001L);
        doc.setUserId(10001L);
        doc.setCourseId(2001L);
        doc.setStatus(status);
        doc.setParseVersion(version);
        doc.setCourseIndexVersion(2);
        doc.setLastIngestJobId(jobId);
        return doc;
    }
}
