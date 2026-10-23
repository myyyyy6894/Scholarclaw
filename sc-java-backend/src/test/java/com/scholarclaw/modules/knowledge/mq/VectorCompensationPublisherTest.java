package com.scholarclaw.modules.knowledge.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class VectorCompensationPublisherTest {

    private static VectorCompensationPublisher publisher(RabbitTemplate template, long timeout) {
        return new VectorCompensationPublisher(template, new ObjectMapper(),
                "rag.ingest.exchange", "rag.ingest.vector-compensation", timeout);
    }

    @Test
    @DisplayName("broker ACK 且未 return 才算补偿登记成功")
    void requiresAckAndNoReturn() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ok"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        assertThat(publisher(template, 100).publish("DOCUMENT", 10001L, 2001L,
                50001L, null, 3)).isTrue();
    }

    @Test
    @DisplayName("nack 或 mandatory return 不得当作登记成功")
    void nackOrReturnIsFailure() {
        RabbitTemplate nack = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack"));
            return null;
        }).when(nack).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(publisher(nack, 100).publish("DOCUMENT", 10001L, 2001L, 50001L, null, 3))
                .isFalse();

        RabbitTemplate returned = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312,
                    "NO_ROUTE", "rag.ingest.exchange", "rag.ingest.vector-compensation"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, "ack"));
            return null;
        }).when(returned).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThat(publisher(returned, 100).publish("DOCUMENT", 10001L, 2001L, 50001L, null, 3))
                .isFalse();
    }

    @Test
    @DisplayName("确认超时返回 false")
    void timeoutIsFailure() {
        assertThat(publisher(mock(RabbitTemplate.class), 50)
                .publish("COURSE_COLLECTION", 10001L, 2001L, null, 1, null)).isFalse();
    }
}
