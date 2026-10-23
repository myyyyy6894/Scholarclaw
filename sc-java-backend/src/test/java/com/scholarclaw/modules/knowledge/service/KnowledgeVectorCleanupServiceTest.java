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
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeVectorCleanupServiceTest {

    private PythonAiEngineClient client;
    private VectorCompensationPublisher compensation;
    private KnowledgeVectorCleanupService service;

    @BeforeEach
    void setUp() {
        client = mock(PythonAiEngineClient.class);
        compensation = mock(VectorCompensationPublisher.class);
        service = new KnowledgeVectorCleanupService(client, compensation);
    }

    @Test
    @DisplayName("DOCUMENT 清理发送 scope/user/course/doc/stale_version")
    void sendsDocumentCleanup() {
        when(client.deleteJson(any(), any(), any(), any())).thenReturn(new ObjectMapper().nullNode());

        assertThat(service.cleanupDocument(10001L, 2001L, 50001L, 3)).isTrue();

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
    }

    @Test
    @DisplayName("瞬态失败重试一次，第二次成功后不再登记补偿")
    void retriesTransientFailureOnce() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR))
                .thenReturn(new ObjectMapper().nullNode());

        assertThat(service.cleanupCourse(10001L, 2001L, 1)).isTrue();

        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(compensation, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("连续瞬态失败：返回 false 并登记持久补偿")
    void recordsCompensationAfterContinuousFailure() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));
        when(compensation.publish(any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(service.cleanupCourse(10001L, 2001L, 1)).isFalse();

        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(compensation).publish("COURSE_COLLECTION", 10001L, 2001L, null, 1, null);
    }

    @Test
    @DisplayName("Python 侧 INTERNAL_ERROR（ChromaDB 5xx）同样重试一次")
    void retriesInternalError() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .thenReturn(new ObjectMapper().nullNode());

        assertThat(service.cleanupDocument(10001L, 2001L, 50001L, 1)).isTrue();

        verify(client, times(2)).deleteJson(any(), any(), any(), any());
        verify(compensation, never()).publish(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("补偿发布返回 false 时日志必须报告登记失败")
    void doesNotClaimCompensationWhenPublishFails() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR));
        when(compensation.publish(any(), any(), any(), any(), any(), any())).thenReturn(false);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(KnowledgeVectorCleanupService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(service.cleanupCourse(10001L, 2001L, 1)).isFalse();
        } finally {
            logger.detachAppender(appender);
        }
        String logs = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(logs).contains("补偿登记失败").doesNotContain("补偿已登记");
    }

    @Test
    @DisplayName("确定性 4xx 错误不重试，直接登记补偿")
    void doesNotRetryDeterministicError() {
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

        assertThat(service.cleanupDocument(10001L, 2001L, 50001L, null)).isFalse();

        verify(client, times(1)).deleteJson(any(), any(), any(), any());
        verify(compensation).publish("DOCUMENT", 10001L, 2001L, 50001L, null, null);
    }
}
