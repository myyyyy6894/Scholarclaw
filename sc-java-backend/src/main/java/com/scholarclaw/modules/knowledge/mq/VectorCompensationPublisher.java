package com.scholarclaw.modules.knowledge.mq;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.filter.TraceContext;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 向量清理最终失败时的持久化补偿记录（TASK-506 复核修复）。
 *
 * <p>ChromaDB 是派生索引，清理失败不能回滚业务；但也不能只打日志就当作已解决。
 * 这里把待清理范围写入持久化队列，供 TASK-902 GC 消费/重放。
 */
@Component
public class VectorCompensationPublisher {

    private static final Logger log = LoggerFactory.getLogger(VectorCompensationPublisher.class);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String queue;
    private final long confirmTimeoutMillis;

    public VectorCompensationPublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${sc.mq.ingest.exchange:rag.ingest.exchange}") String exchange,
            @Value("${sc.mq.ingest.vector-compensation:rag.ingest.vector-compensation}") String queue,
            @Value("${sc.mq.ingest.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.queue = queue;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public boolean publish(String scope, Long userId, Long courseId, Long docId,
                           Integer courseIndexVersion, Integer staleParseVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "VECTOR_CLEANUP");
        body.put("scope", scope);
        body.put("user_id", String.valueOf(userId));
        if (courseId != null) {
            body.put("course_id", String.valueOf(courseId));
        }
        if (docId != null) {
            body.put("doc_id", String.valueOf(docId));
        }
        if (courseIndexVersion != null) {
            body.put("course_index_version", courseIndexVersion);
        }
        if (staleParseVersion != null) {
            body.put("stale_parse_version", staleParseVersion);
        }
        body.put("recorded_at", ScTimeZone.now().format(TIMESTAMP));
        body.put("trace_id", TraceContext.currentOrGeneratedTraceId());
        try {
            String payload = objectMapper.writeValueAsString(body);
            CorrelationData correlation = new CorrelationData();
            rabbitTemplate.convertAndSend(exchange, queue, payload, raw -> {
                var properties = raw.getMessageProperties();
                properties.setContentType("application/json");
                properties.setContentEncoding(StandardCharsets.UTF_8.name());
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return raw;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                log.error("向量清理补偿记录未获 broker 确认 queue={} scope={}", queue, scope);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("向量清理补偿记录被中断 queue={}", queue);
        } catch (TimeoutException | ExecutionException e) {
            log.error("向量清理补偿记录确认失败 queue={} error={}", queue,
                    e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("向量清理补偿记录写入失败 queue={} error={}", queue,
                    e.getClass().getSimpleName());
        }
        return false;
    }
}
