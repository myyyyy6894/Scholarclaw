package com.scholarclaw.modules.knowledge.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mq.IngestParkingPublisher;
import com.scholarclaw.modules.knowledge.service.KnowledgeFailureService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class RagIngestDlqListenerTest {

    private static final String BODY = """
            {"ingest_job_id":"job_1","job_type":"INITIAL_INGEST","trace_id":"tr_cron_0123456789ab",
             "user_id":"10001","course_id":"2001","course_index_version":2,"doc_id":"50001",
             "parse_version":3,"retry_count":3}
            """;

    private KnowledgeDocMapper docMapper;
    private KnowledgeFailureService failureService;
    private IngestParkingPublisher parkingPublisher;
    private RagIngestDlqListener listener;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        failureService = mock(KnowledgeFailureService.class);
        parkingPublisher = mock(IngestParkingPublisher.class);
        listener = new RagIngestDlqListener(new ObjectMapper(), docMapper,
                failureService, parkingPublisher);
    }

    @Test
    @DisplayName("当前 PARSING 版本进入死信：统一失败收敛（父块/向量/课程）")
    void marksFailedWithUnifiedConvergence() {
        when(docMapper.selectOne(any())).thenReturn(doc("PARSING", 3, "job_1"));
        when(failureService.fail(10001L, 2001L, 50001L, 3, 2, "E-RAG-02",
                "课件解析多次重试后仍失败，请稍后手动重试或更换文件。"))
                .thenReturn(new KnowledgeFailureService.FailureOutcome(true, false, "FAILED"));

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(failureService).fail(eq(10001L), eq(2001L), eq(50001L), eq(3), eq(2),
                eq("E-RAG-02"), any(String.class));
        verify(parkingPublisher, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("旧版本或已终结文档的死信直接忽略")
    void ignoresStaleDeadLetter() {
        when(docMapper.selectOne(any())).thenReturn(doc("READY", 4, "job_1"));

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(failureService, never()).fail(any(), any(), any(), any(Integer.class),
                any(Integer.class), any(), any());
        verify(parkingPublisher, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("死信任务 ID 与当前任务不一致时忽略")
    void ignoresDifferentJob() {
        when(docMapper.selectOne(any())).thenReturn(doc("PARSING", 3, "job_other"));

        listener.handle(BODY.getBytes(StandardCharsets.UTF_8));

        verify(failureService, never()).fail(any(), any(), any(), any(Integer.class),
                any(Integer.class), any(), any());
    }

    @Test
    @DisplayName("非法 JSON 不抛异常")
    void ignoresInvalidJson() {
        listener.handle("not-json".getBytes(StandardCharsets.UTF_8));
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("数据库瞬态异常：不丢消息，停放至 parking 队列并保留 trace")
    void parksMessageOnTransientFailure() {
        when(docMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        when(parkingPublisher.publish(any(), any(), any())).thenReturn(true);
        when(parkingPublisher.parkingQueue()).thenReturn("rag.ingest.parking");

        listener.onDeadLetter(message());

        verify(parkingPublisher).publish(any(), org.mockito.ArgumentMatchers.eq(
                "tr_cron_0123456789ab"), any());
        assertThat(com.scholarclaw.common.filter.TraceContext.currentTraceId()).isNull();
        assertThat(com.scholarclaw.common.filter.TraceContext.currentUserId()).isNull();
    }

    @Test
    @DisplayName("parking 也失败时必须抛出，交给容器重投而不是静默 ACK")
    void rethrowsWhenParkingFails() {
        when(docMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));
        when(parkingPublisher.publish(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> listener.onDeadLetter(message()))
                .isInstanceOf(RuntimeException.class);
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("job_1");
        return new Message(BODY.getBytes(StandardCharsets.UTF_8), properties);
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
