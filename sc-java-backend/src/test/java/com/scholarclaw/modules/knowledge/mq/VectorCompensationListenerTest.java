package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorCompensationListenerTest {

    private KnowledgeVectorCleanupService cleanupService;
    private IngestParkingPublisher parkingPublisher;
    private VectorCompensationListener listener;

    @BeforeEach
    void setUp() {
        cleanupService = mock(KnowledgeVectorCleanupService.class);
        parkingPublisher = mock(IngestParkingPublisher.class);
        listener = new VectorCompensationListener(new ObjectMapper(), cleanupService,
                parkingPublisher);
    }

    @Test
    @DisplayName("重放成功：调用一次清理，不停放")
    void replaysSuccessfully() {
        when(cleanupService.cleanupDirect("DOCUMENT", 11L, 21L, 31L, null, 2))
                .thenReturn(true);

        listener.handle(body("DOCUMENT", "11", "21", "31", null, "2"));

        verify(cleanupService).cleanupDirect("DOCUMENT", 11L, 21L, 31L, null, 2);
        verify(parkingPublisher, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("重放失败：停放到 parking 队列")
    void parksWhenReplayFails() {
        when(cleanupService.cleanupDirect(eq("USER_ALL"), eq(11L), isNull(), isNull(),
                isNull(), isNull())).thenReturn(false);
        when(parkingPublisher.publish(any(), eq("tr_test"), isNull())).thenReturn(true);

        listener.handle(body("USER_ALL", "11", null, null, null, null));

        verify(parkingPublisher).publish(any(), eq("tr_test"), isNull());
    }

    @Test
    @DisplayName("重放与停放都失败：抛异常让容器重投")
    void throwsWhenParkingFails() {
        when(cleanupService.cleanupDirect(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(parkingPublisher.publish(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> listener.handle(
                body("COURSE_COLLECTION", "11", "21", null, "3", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("非法 JSON / 未知 scope：忽略且不清理")
    void ignoresInvalidMessages() {
        listener.handle("not-json".getBytes(StandardCharsets.UTF_8));
        listener.handle(body("UNKNOWN", "11", null, null, null, null));

        verify(cleanupService, never()).cleanupDirect(any(), any(), any(), any(), any(), any());
        verify(parkingPublisher, never()).publish(any(), any(), any());
    }

    private static byte[] body(String scope, String userId, String courseId, String docId,
                               String courseIndexVersion, String staleParseVersion) {
        StringBuilder json = new StringBuilder("{\"kind\":\"VECTOR_CLEANUP\",")
                .append("\"scope\":\"").append(scope).append("\",")
                .append("\"user_id\":\"").append(userId).append("\",")
                .append("\"trace_id\":\"tr_test\"");
        if (courseId != null) {
            json.append(",\"course_id\":\"").append(courseId).append("\"");
        }
        if (docId != null) {
            json.append(",\"doc_id\":\"").append(docId).append("\"");
        }
        if (courseIndexVersion != null) {
            json.append(",\"course_index_version\":").append(courseIndexVersion);
        }
        if (staleParseVersion != null) {
            json.append(",\"stale_parse_version\":").append(staleParseVersion);
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
