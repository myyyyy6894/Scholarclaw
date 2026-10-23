package com.scholarclaw.modules.knowledge.mq;

import com.scholarclaw.common.filter.TraceContext;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 死信二次失败的持久化停放（TASK-506 复核修复）。
 *
 * <p>DLQ 处理遇到数据库/网络瞬态异常时不能直接 ACK 丢弃任务；有界重试后把原始消息
 * 停放到持久化 parking 队列，等待人工或后续补偿任务恢复。parking 队列不设置消费者，
 * 避免形成新的无限重投环路。
 */
@Component
public class IngestParkingPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestParkingPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String parkingQueue;
    private final long confirmTimeoutMillis;

    public IngestParkingPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange,
            @Value("${sc.mq.ingest.parking:rag.ingest.parking}") String parkingQueue,
            @Value("${sc.mq.ingest.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.parkingQueue = parkingQueue;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public boolean publish(byte[] rawBody, String traceId, String messageId) {
        try {
            String payload = new String(rawBody, StandardCharsets.UTF_8);
            CorrelationData correlation = new CorrelationData(messageId);
            rabbitTemplate.convertAndSend(exchange, parkingQueue, payload, raw -> {
                var properties = raw.getMessageProperties();
                properties.setContentType("application/json");
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                if (messageId != null) {
                    properties.setMessageId(messageId);
                }
                if (traceId != null) {
                    properties.setHeader(TraceContext.HEADER_NAME, traceId);
                }
                return raw;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                log.error("死信停放未获 broker 确认 queue={} messageId={}", parkingQueue, messageId);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("死信停放被中断 queue={}", parkingQueue);
        } catch (TimeoutException | ExecutionException e) {
            log.error("死信停放确认失败 queue={} error={}", parkingQueue,
                    e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("死信停放发布失败 queue={} error={}", parkingQueue,
                    e.getClass().getSimpleName());
        }
        return false;
    }

    public String parkingQueue() {
        return parkingQueue;
    }
}
