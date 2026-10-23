package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class IngestJobPublisherTest {

    private static final IngestMessage MESSAGE = new IngestMessage(
            "job_20261022_120000123_abcdef", "INITIAL_INGEST", "tr_cron_0123456789ab",
            "10001", "2001", 1, "50001", 1, "a.pdf",
            "raw/10001/2001/50001.pdf", "e3b0c442", 0,
            "2026-10-22T12:00:00.000+08:00");

    private static RabbitTemplate confirmingTemplate(boolean ack) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, "test"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        return template;
    }

    private static IngestJobPublisher publisher(RabbitTemplate template) {
        return new IngestJobPublisher(template, new ObjectMapper(), true,
                "rag.ingest.exchange", "rag.ingest.queue", 1000);
    }

    @Test
    @DisplayName("broker 确认后发布持久化 JSON，消息 ID 与追踪头正确")
    void publishesPersistentJsonAfterConfirm() {
        RabbitTemplate template = confirmingTemplate(true);

        boolean published = publisher(template).publish(MESSAGE);

        assertThat(published).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> processor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq("rag.ingest.exchange"), eq("rag.ingest.queue"),
                payload.capture(), processor.capture(), any(CorrelationData.class));

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
    @DisplayName("broker 未确认（nack）时不得当作入队成功")
    void nackIsNotSuccess() {
        assertThat(publisher(confirmingTemplate(false)).publish(MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("确认超时不得当作入队成功")
    void confirmTimeoutIsNotSuccess() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        IngestJobPublisher publisher = new IngestJobPublisher(
                template, new ObjectMapper(), true, "rag.ingest.exchange",
                "rag.ingest.queue", 50);

        assertThat(publisher.publish(MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("broker 不可达（发送即抛异常）时返回 false")
    void unreachableBrokerIsNotSuccess() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpConnectException(
                        new java.net.ConnectException("refused")))
                .when(template).convertAndSend(anyString(), anyString(), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThat(publisher(template).publish(MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("mandatory return（未路由到任何队列）时不得当作入队成功")
    void unroutableIsNotSuccess() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312,
                    "NO_ROUTE", "rag.ingest.exchange", "rag.ingest.queue"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThat(publisher(template).publish(MESSAGE)).isFalse();
    }

    @Test
    @DisplayName("关闭生产端时不发送任何消息")
    void disabledDoesNotPublish() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        IngestJobPublisher publisher = new IngestJobPublisher(
                template, new ObjectMapper(), false, "rag.ingest.exchange",
                "rag.ingest.queue", 1000);

        assertThat(publisher.publish(MESSAGE)).isFalse();
        verify(template, never()).convertAndSend(any(String.class), any(String.class),
                any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }
}
