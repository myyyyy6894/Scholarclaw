package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class IngestJobPublisherTest {

    private static final IngestMessage MESSAGE = new IngestMessage(
            "job_20261022_120000123_abcdef", "INITIAL_INGEST", "tr_cron_0123456789ab",
            "10001", "2001", 1, "50001", 1, "a.pdf",
            "raw/10001/2001/50001.pdf", "e3b0c442", 0,
            "2026-10-22T12:00:00.000+08:00");

    @Test
    @DisplayName("发布持久化 JSON 到业务队列，消息 ID 与追踪头正确")
    void publishesPersistentJson() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        IngestJobPublisher publisher = new IngestJobPublisher(
                template, new ObjectMapper(), true, "rag.ingest.exchange", "rag.ingest.queue");

        boolean published = publisher.publish(MESSAGE);

        assertThat(published).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> processor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("rag.ingest.exchange"), eq("rag.ingest.queue"),
                payload.capture(), processor.capture());

        assertThat((String) payload.getValue()).contains("\"job_type\":\"INITIAL_INGEST\"")
                .doesNotContain("api_key");

        Message raw = new Message(new byte[0]);
        Message processed = processor.getValue().postProcessMessage(raw);
        assertThat(processed.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(processed.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(processed.getMessageProperties().getMessageId())
                .isEqualTo(MESSAGE.ingestJobId());
        assertThat((Object) processed.getMessageProperties().getHeader("X-Trace-Id"))
                .isEqualTo(MESSAGE.traceId());
    }

    @Test
    @DisplayName("关闭生产端时不发送任何消息")
    void disabledDoesNotPublish() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        IngestJobPublisher publisher = new IngestJobPublisher(
                template, new ObjectMapper(), false, "rag.ingest.exchange", "rag.ingest.queue");

        assertThat(publisher.publish(MESSAGE)).isFalse();
        verify(template, never()).convertAndSend(any(String.class), any(String.class),
                any(), any(MessagePostProcessor.class));
    }
}
