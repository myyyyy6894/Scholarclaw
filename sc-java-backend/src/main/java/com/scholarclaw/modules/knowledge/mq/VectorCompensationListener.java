package com.scholarclaw.modules.knowledge.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * `rag.ingest.vector-compensation` 补偿队列消费者（{@code 06} TASK-902）。
 *
 * <p>`KnowledgeVectorCleanupService` 在瞬态失败重试后仍失败时写入该队列；本消费者重放
 * Python `DELETE /internal/ai/v1/rag/vectors`，且**不再写补偿队列**（避免自循环）。
 * 重放失败时把原始消息停放到持久化 parking 队列，交由人工/后续 GC 处理；
 * 停放也失败时抛出异常，让容器重新投递，宁可重复也不静默丢失。
 *
 * <p>消息天然幂等：Python 删除不存在的 Collection/向量视为成功。
 */
@Component
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true",
        matchIfMissing = true)
public class VectorCompensationListener {

    private static final Logger log = LoggerFactory.getLogger(VectorCompensationListener.class);
    private static final Set<String> SCOPES =
            Set.of("DOCUMENT", "COURSE_COLLECTION", "USER_ALL");

    private final ObjectMapper objectMapper;
    private final KnowledgeVectorCleanupService cleanupService;
    private final IngestParkingPublisher parkingPublisher;

    public VectorCompensationListener(ObjectMapper objectMapper,
                                      KnowledgeVectorCleanupService cleanupService,
                                      IngestParkingPublisher parkingPublisher) {
        this.objectMapper = objectMapper;
        this.cleanupService = cleanupService;
        this.parkingPublisher = parkingPublisher;
    }

    @RabbitListener(queues = "${sc.mq.ingest.vector-compensation:rag.ingest.vector-compensation}")
    public void onMessage(Message message) {
        handle(message.getBody());
    }

    /** 供单测直接驱动。 */
    void handle(byte[] rawBody) {
        JsonNode payload = parse(rawBody);
        if (payload == null) {
            return;
        }
        String traceId = text(payload, "trace_id");
        if (traceId != null) {
            TraceContext.bind(traceId);
        }
        try {
            String scope = text(payload, "scope");
            Long userId = longOrNull(payload, "user_id");
            Long courseId = longOrNull(payload, "course_id");
            Long docId = longOrNull(payload, "doc_id");
            Integer courseIndexVersion = intOrNull(payload, "course_index_version");
            Integer staleParseVersion = intOrNull(payload, "stale_parse_version");
            if (!"VECTOR_CLEANUP".equals(text(payload, "kind"))
                    || scope == null || !SCOPES.contains(scope) || userId == null) {
                log.warn("向量补偿消息字段非法，忽略 scope={} userId={}", scope, userId);
                return;
            }
            boolean cleaned = cleanupService.cleanupDirect(scope, userId, courseId, docId,
                    courseIndexVersion, staleParseVersion);
            if (cleaned) {
                log.info("向量补偿重放成功 scope={} userId={} courseId={} docId={}",
                        scope, userId, courseId, docId);
                return;
            }
            boolean parked = parkingPublisher.publish(rawBody, traceId, null);
            if (!parked) {
                // 停放失败不能 ACK 丢弃；抛出交由容器重新投递。
                throw new IllegalStateException("向量补偿重放失败且停放失败");
            }
            log.error("向量补偿重放失败，已停放到 parking 队列 scope={} userId={}",
                    scope, userId);
        } finally {
            TraceContext.clear();
        }
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode node = objectMapper.readTree(new String(rawBody, StandardCharsets.UTF_8));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.warn("向量补偿消息不是合法 JSON，忽略 error={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
