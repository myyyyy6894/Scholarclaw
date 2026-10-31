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

    /** parking 消息头：原始消费队列，供自动重放选择投递目标。 */
    public static final String SOURCE_HEADER = "x-sc-parking-source";
    /** parking 消息头：已自动重放次数。 */
    public static final String ATTEMPT_HEADER = "x-sc-parking-attempt";

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String parkingQueue;
    private final String manualQueue;
    private final long confirmTimeoutMillis;

    public IngestParkingPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange,
            @Value("${sc.mq.ingest.parking:rag.ingest.parking}") String parkingQueue,
            @Value("${sc.mq.ingest.parking-manual:rag.ingest.parking.manual}") String manualQueue,
            @Value("${sc.mq.ingest.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.parkingQueue = parkingQueue;
        this.manualQueue = manualQueue;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public boolean publish(byte[] rawBody, String traceId, String messageId) {
        return publish(rawBody, traceId, messageId, null, 0);
    }

    /**
     * 停放到 parking，并记录来源队列与已重放次数。
     *
     * @param source 原始消费队列；为空时自动重放会转人工队列，避免盲目投递
     */
    public boolean publish(byte[] rawBody, String traceId, String messageId, String source,
                           int attempt) {
        return send(exchange, parkingQueue, rawBody, traceId, messageId, source, attempt);
    }

    /** 自动重放：把 parking 消息重新投递回原队列（或任一指定 routing key）。 */
    public boolean republish(byte[] rawBody, String routingKey, String traceId,
                             String source, int attempt) {
        return send(exchange, routingKey, rawBody, traceId, null, source, attempt);
    }

    /** 超过自动重放上限：转人工队列（默认交换机按队列名直投）。 */
    public boolean moveToManual(byte[] rawBody, String traceId, String source, int attempt) {
        return send("", manualQueue, rawBody, traceId, null, source, attempt);
    }

    private boolean send(String targetExchange, String routingKey, byte[] rawBody,
                         String traceId, String messageId, String source, int attempt) {
        try {
            String payload = new String(rawBody, StandardCharsets.UTF_8);
            CorrelationData correlation = new CorrelationData(messageId);
            rabbitTemplate.convertAndSend(targetExchange, routingKey, payload, raw -> {
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
                if (source != null && !source.isBlank()) {
                    properties.setHeader(SOURCE_HEADER, source);
                }
                properties.setHeader(ATTEMPT_HEADER, attempt);
                return raw;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                log.error("停放/重放未获 broker 确认 queue={} messageId={}", routingKey, messageId);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("停放/重放被中断 queue={}", routingKey);
        } catch (TimeoutException | ExecutionException e) {
            log.error("停放/重放确认失败 queue={} error={}", routingKey,
                    e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("停放/重放发布失败 queue={} error={}", routingKey,
                    e.getClass().getSimpleName());
        }
        return false;
    }

    public String parkingQueue() {
        return parkingQueue;
    }

    public String manualQueue() {
        return manualQueue;
    }
}
