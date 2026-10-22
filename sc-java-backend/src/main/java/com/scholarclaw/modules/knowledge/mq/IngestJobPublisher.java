package com.scholarclaw.modules.knowledge.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.filter.TraceContext;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * `MQ-INGEST-001` 生产端（TASK-501）。
 *
 * <p>只发持久化 JSON 到默认交换机（routing key = 队列名），不发送明文 `api_key`。
 * 发布失败只记录 job/doc 标识并返回 {@code false}，由调用方决定是否保留 `UPLOADED`
 * 等待 `ZombieIngestScanner` 补投；绝不把失败伪装成已入队。
 */
@Component
public class IngestJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestJobPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String exchange;
    private final String queue;

    public IngestJobPublisher(RabbitTemplate rabbitTemplate,
                              ObjectMapper objectMapper,
                              @Value("${sc.mq.ingest.enabled:true}") boolean enabled,
                              @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange,
                              @Value("${sc.mq.ingest.queue:rag.ingest.queue}") String queue) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.exchange = exchange;
        this.queue = queue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return true 表示消息已交给 broker；false 表示未启用或发布失败 */
    public boolean publish(IngestMessage message) {
        if (!enabled) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(exchange, queue, json, raw -> {
                var properties = raw.getMessageProperties();
                properties.setContentType("application/json");
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(message.ingestJobId());
                properties.setHeader(TraceContext.HEADER_NAME, message.traceId());
                return raw;
            });
            return true;
        } catch (JsonProcessingException e) {
            log.error("解析任务消息序列化失败 jobId={} docId={}",
                    message.ingestJobId(), message.docId(), e);
        } catch (RuntimeException e) {
            log.error("解析任务发布失败 jobId={} docId={} queue={} error={}",
                    message.ingestJobId(), message.docId(), queue, e.getClass().getSimpleName());
        }
        return false;
    }
}
