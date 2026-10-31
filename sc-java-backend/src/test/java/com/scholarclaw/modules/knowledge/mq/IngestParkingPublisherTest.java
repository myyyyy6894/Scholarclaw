package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class IngestParkingPublisherTest {

    @Test
    @DisplayName("只有 broker ACK 且未被 return 才视为停放成功")
    void requiresBrokerAck() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ok"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        IngestParkingPublisher publisher = new IngestParkingPublisher(
                template, "rag.ingest.exchange", "rag.ingest.parking",
                "rag.ingest.parking.manual", 100);

        assertThat(publisher.publish("{}".getBytes(StandardCharsets.UTF_8),
                "tr_cron_0123456789ab", "job_1")).isTrue();
        verify(template).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("确认超时返回 false")
    void timesOut() {
        IngestParkingPublisher publisher = new IngestParkingPublisher(
                mock(RabbitTemplate.class), "rag.ingest.exchange", "rag.ingest.parking",
                "rag.ingest.parking.manual", 50);

        assertThat(publisher.publish("{}".getBytes(StandardCharsets.UTF_8), null, "job_1"))
                .isFalse();
    }
}
