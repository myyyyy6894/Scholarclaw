package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.knowledge.mq.VectorCompensationPublisher;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService.VectorCleanupResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeVectorCleanupServiceTest {

    private PythonAiEngineClient client;
    private VectorCompensationPublisher compensation;
    private VectorCleanupOutboxService outbox;
    private KnowledgeVectorCleanupService service;

    @BeforeEach
    void setUp() {
        client = mock(PythonAiEngineClient.class);
        compensation = mock(VectorCompensationPublisher.class);
        outbox = mock(VectorCleanupOutboxService.class);
        when(outbox.enqueue(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        service = new KnowledgeVectorCleanupService(client, compensation, outbox);
    }

    @Test
    @DisplayName("DOCUMENT 清理发送 scope/user/course/doc/stale_version")
    @SuppressWarnings("unchecked")
    void sendsDocumentCleanup() {
        when(client.deleteJson(any(), any(), any(), any())).thenReturn(new ObjectMapper().nullNode());

        VectorCleanupResult result = service.cleanupDocument(10001L, 2001L, 50001L, 3);

        assertThat(result.confirmed()).isTrue();
        assertThat(result.handled()).isTrue();
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).deleteJson(eq(PythonAiEngineClient.VECTORS_PATH), body.capture(),
                eq(10001L), any(Duration.class));
        assertThat((Map<String, Object>) body.getValue())
                .containsEntry("scope", "DOCUMENT")
                .containsEntry("user_id", "10001")
                .containsEntry("course_id", "2001")
                .containsEntry("doc_id", "50001")
                .containsEntry("stale_parse_version", 3);
        verify(compensation, never()).publish(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("瞬态失败重试一次，第二次成功后不再登记补偿")
    void retriesTransientFailureOnce() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR))
                .thenReturn(new ObjectMapper().nullNode());

        assertThat(service.cleanupCourse(10001L, 2001L, 1).confirmed()).isTrue();

        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(compensation, never()).publish(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).enqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("连续瞬态失败：写入 MySQL outbox 并尽力发布 MQ 补偿")
    void recordsCompensationAfterContinuousFailure() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));
        when(compensation.publish(any(), any(), any(), any(), any(), any())).thenReturn(true);

        VectorCleanupResult result = service.cleanupCourse(10001L, 2001L, 1);

        assertThat(result.confirmed()).isFalse();
        assertThat(result.compensationPersisted()).isTrue();
        assertThat(result.handled()).isTrue();
        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(outbox).enqueue("COURSE_COLLECTION", 10001L, 2001L, null, 1, null, null);
        verify(compensation).publish("COURSE_COLLECTION", 10001L, 2001L, null, 1, null);
    }

    @Test
    @DisplayName("Python 侧 INTERNAL_ERROR（ChromaDB 5xx）同样重试一次")
    void retriesInternalError() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .thenReturn(new ObjectMapper().nullNode());

        assertThat(service.cleanupDocument(10001L, 2001L, 50001L, 1).confirmed()).isTrue();

        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(compensation, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("MQ 发布失败但 outbox 已落库：仍视为有交代，日志明确 mqPublished=false")
    void outboxAloneCountsAsHandledWhenMqFails() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR));
        when(compensation.publish(any(), any(), any(), any(), any(), any())).thenReturn(false);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(KnowledgeVectorCleanupService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        VectorCleanupResult result;
        try {
            result = service.cleanupCourse(10001L, 2001L, 1);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(result.handled()).isTrue();
        String logs = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("已写入持久化 outbox").contains("mqPublished=false")
                .doesNotContain("outbox 登记失败");
    }

    @Test
    @DisplayName("outbox 登记失败：业务行不得进入终态（handled=false）")
    void outboxFailureBlocksTerminalState() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR));
        when(outbox.enqueue(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(compensation.publish(any(), any(), any(), any(), any(), any())).thenReturn(true);

        VectorCleanupResult result = service.cleanupCourse(10001L, 2001L, 1);

        assertThat(result.confirmed()).isFalse();
        assertThat(result.compensationPersisted()).isFalse();
        assertThat(result.handled()).isFalse();
    }

    @Test
    @DisplayName("确定性 4xx 错误不重试，直接登记补偿")
    void doesNotRetryDeterministicError() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

        VectorCleanupResult result = service.cleanupDocument(10001L, 2001L, 50001L, null);

        assertThat(result.handled()).isTrue();
        verify(client, times(1)).deleteJson(any(), any(), any(), any());
        verify(outbox).enqueue("DOCUMENT", 10001L, 2001L, 50001L, null, null, null);
        verify(compensation).publish("DOCUMENT", 10001L, 2001L, 50001L, null, null);
    }
}
