package com.scholarclaw.modules.knowledge.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * `MQ-INGEST-001` 生产端（TASK-501）。
 *
 * <p>只发持久化 JSON 到命名交换机（routing key = 队列名），不发送明文 `api_key`。
 * 必须收到 broker 的 publisher confirm（ACK）且没有 mandatory return 才算成功；
 * 发布失败只记录 job/doc 标识并返回 {@code false}，由调用方保留可恢复的 `PARSING`
 * 状态等待 `ZombieIngestScanner` 补投；绝不把失败伪装成已入队。
 */
@Component
public class IngestJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestJobPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String exchange;
    private final String queue;
    private final long confirmTimeoutMillis;

    public IngestJobPublisher(RabbitTemplate rabbitTemplate,
                              ObjectMapper objectMapper,
                              @Value("${sc.mq.ingest.enabled:true}") boolean enabled,
                              @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange,
                              @Value("${sc.mq.ingest.queue:rag.ingest.queue}") String queue,
                              @Value("${sc.mq.ingest.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.exchange = exchange;
        this.queue = queue;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return true 表示 broker 已确认（ACK）且消息未被 mandatory return；false 表示未启用或发布失败
     */
    public boolean publish(IngestMessage message) {
        if (!enabled) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            CorrelationData correlation = new CorrelationData(message.ingestJobId());
            rabbitTemplate.convertAndSend(exchange, queue, json, raw -> {
                var properties = raw.getMessageProperties();
                properties.setContentType("application/json");
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(message.ingestJobId());
                properties.setHeader(TraceContext.HEADER_NAME, message.traceId());
                return raw;
            }, correlation);
            return awaitConfirm(message, correlation);
        } catch (JsonProcessingException e) {
            log.error("解析任务消息序列化失败 jobId={} docId={}",
                    message.ingestJobId(), message.docId(), e);
        } catch (RuntimeException e) {
            log.error("解析任务发布失败 jobId={} docId={} queue={} error={}",
                    message.ingestJobId(), message.docId(), queue, e.getClass().getSimpleName());
        }
        return false;
    }

    private boolean awaitConfirm(IngestMessage message, CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.error("broker 未确认解析任务 jobId={} docId={} reason={}",
                        message.ingestJobId(), message.docId(),
                        confirm == null ? "no-confirm" : confirm.getReason());
                return false;
            }
            if (correlation.getReturned() != null) {
                log.error("解析任务未被任何队列接收 jobId={} docId={} reply={}",
                        message.ingestJobId(), message.docId(),
                        correlation.getReturned().getReplyText());
                return false;
            }
            return true;
        } catch (TimeoutException e) {
            log.error("解析任务发布确认超时 jobId={} docId={} timeoutMs={}",
                    message.ingestJobId(), message.docId(), confirmTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("解析任务发布确认被中断 jobId={}", message.ingestJobId());
        } catch (ExecutionException e) {
            log.error("解析任务发布确认失败 jobId={} error={}",
                    message.ingestJobId(), e.getCause() == null
                            ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
        }
        return false;
    }
}
