package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class IngestQueueBacklogMonitorTest {

    private static final String PARKING = "rag.ingest.parking";
    private static final String PARKING_MANUAL = "rag.ingest.parking.manual";
    private static final String COMPENSATION = "rag.ingest.vector-compensation";

    private static IngestQueueBacklogMonitor monitor(RabbitAdmin admin) {
        return new IngestQueueBacklogMonitor(admin, PARKING, PARKING_MANUAL, COMPENSATION, 1);
    }

    @Test
    @DisplayName("停放/补偿队列积压被巡检读取")
    void reportsBothQueues() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueInfo(PARKING))
                .thenReturn(new QueueInformation(PARKING, 1, 0));
        when(admin.getQueueInfo(COMPENSATION))
                .thenReturn(new QueueInformation(COMPENSATION, 0, 0));

        IngestQueueBacklogMonitor monitor = monitor(admin);

        assertThat(monitor.checkQueue(PARKING, "死信停放")).isEqualTo(1);
        assertThat(monitor.checkQueue(COMPENSATION, "向量清理补偿")).isZero();
    }

    @Test
    @DisplayName("broker 不可用时返回 -1 且不抛异常")
    void brokerFailureDoesNotBreakBusiness() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueInfo(PARKING)).thenThrow(new IllegalStateException("broker down"));

        assertThat(monitor(admin).checkQueue(PARKING, "死信停放")).isEqualTo(-1);
    }

    @Test
    @DisplayName("队列尚未声明时返回 -1")
    void missingQueueReturnsUnknown() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueInfo(PARKING)).thenReturn(null);

        assertThat(monitor(admin).checkQueue(PARKING, "死信停放")).isEqualTo(-1);
    }
}
