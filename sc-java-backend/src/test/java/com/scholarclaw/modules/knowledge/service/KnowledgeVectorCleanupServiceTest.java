package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeVectorCleanupServiceTest {

    @Test
    @DisplayName("DOCUMENT 清理发送 scope/user/course/doc/stale_version")
    void sendsDocumentCleanup() {
        PythonAiEngineClient client = mock(PythonAiEngineClient.class);
        KnowledgeVectorCleanupService service = new KnowledgeVectorCleanupService(client);

        assertThat(service.cleanupDocument(10001L, 2001L, 50001L, 3)).isTrue();

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(client).deleteJson(
                eq(PythonAiEngineClient.VECTORS_PATH), body.capture(),
                eq(10001L), any(Duration.class));
        assertThat((Map<String, Object>) body.getValue())
                .containsEntry("scope", "DOCUMENT")
                .containsEntry("user_id", "10001")
                .containsEntry("course_id", "2001")
                .containsEntry("doc_id", "50001")
                .containsEntry("stale_parse_version", 3);
    }

    @Test
    @DisplayName("清理失败只返回 false，不向业务抛异常")
    void swallowsCleanupFailure() {
        PythonAiEngineClient client = mock(PythonAiEngineClient.class);
        when(client.deleteJson(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR));
        KnowledgeVectorCleanupService service = new KnowledgeVectorCleanupService(client);

        assertThat(service.cleanupCourse(10001L, 2001L, 1)).isFalse();
    }
}
