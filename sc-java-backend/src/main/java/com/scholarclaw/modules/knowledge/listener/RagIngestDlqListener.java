package com.scholarclaw.modules.knowledge.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mq.IngestParkingPublisher;
import com.scholarclaw.modules.knowledge.service.KnowledgeFailureService;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * `rag.ingest.dlq` 死信处理（TASK-505，TASK-506 复核修复）。
 *
 * <p>Python 消费者重试耗尽后 `nack(requeue=false)`，消息经 DLX 进入死信队列。
 * Java 在此把仍处于当前 `PARSING` 版本的文档置为 `FAILED: E-RAG-02`，并统一清空父块与向量。
 *
 * <p><b>不能丢消息：</b>只有确定永久无效的消息（非法 JSON、字段非法、文档已不存在/冻结、
 * 旧版本、任务 ID 不匹配）才 ACK 丢弃。数据库/网络等瞬态异常先在本进程内重试，
 * 仍失败则把原始消息停放到持久化 parking 队列；parking 也失败时抛出让容器重新投递，
 * 宁可重复也不能静默丢失。
 */
@Component
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class RagIngestDlqListener {

    private static final Logger log = LoggerFactory.getLogger(RagIngestDlqListener.class);
    private static final int TRANSIENT_ATTEMPTS = 3;
    private static final long TRANSIENT_BACKOFF_MILLIS = 300L;

    private final ObjectMapper objectMapper;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeFailureService failureService;
    private final IngestParkingPublisher parkingPublisher;

    public RagIngestDlqListener(ObjectMapper objectMapper,
                                KnowledgeDocMapper docMapper,
                                KnowledgeFailureService failureService,
                                IngestParkingPublisher parkingPublisher) {
        this.objectMapper = objectMapper;
        this.docMapper = docMapper;
        this.failureService = failureService;
        this.parkingPublisher = parkingPublisher;
    }

    @RabbitListener(queues = "${sc.mq.ingest.dlq:rag.ingest.dlq}")
    public void onDeadLetter(Message message) {
        JsonNode payload = parse(message.getBody());
        if (payload == null) {
            // 非法 JSON 不得静默 ACK：停放到 parking，交给自动重放/人工队列。
            String source = message.getMessageProperties().getConsumerQueue();
            String messageId = message.getMessageProperties().getMessageId();
            String traceId = TraceContext.currentTraceId();
            if (parkingPublisher.publish(message.getBody(), traceId, messageId, source, 0)) {
                log.error("死信消息不是合法 JSON，已停放到 parking messageId={}", messageId);
                return;
            }
            throw new IllegalStateException("死信消息不是合法 JSON 且停放失败");
        }
        bindContext(payload);
        try {
            handlePayload(payload);
        } catch (RuntimeException e) {
            // 上下文尚未清理：parking 消息头与日志都能拿到原始 trace/user
            parkOrRequeue(message, e);
        } finally {
            TraceContext.clear();
        }
    }

    /** 供单测直接驱动：独立完成上下文绑定与清理。 */
    void handle(byte[] rawBody) {
        JsonNode payload = parse(rawBody);
        if (payload == null) {
            log.warn("死信消息不是合法 JSON，忽略");
            return;
        }
        bindContext(payload);
        try {
            handlePayload(payload);
        } finally {
            TraceContext.clear();
        }
    }

    private void bindContext(JsonNode payload) {
        String traceId = payload.path("trace_id").asText(null);
        if (!TraceContext.isAcceptableInboundTraceId(traceId)) {
            traceId = TraceContext.generateCronTraceId();
        }
        TraceContext.bind(traceId);
        TraceContext.bindUserId(parseUserIdOrNull(payload.path("user_id").asText(null)));
    }

    private static Long parseUserIdOrNull(String raw) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            return null;
        }
        return Long.valueOf(raw);
    }

    private void handlePayload(JsonNode payload) {
        long docId;
        long userId;
        int parseVersion;
        try {
            docId = Long.parseLong(payload.path("doc_id").asText());
            userId = Long.parseLong(payload.path("user_id").asText());
            parseVersion = payload.path("parse_version").asInt();
        } catch (NumberFormatException e) {
            log.warn("死信字段非法，忽略");
            return;
        }
        persistFailure(payload, docId, userId, parseVersion);
    }

    private void persistFailure(JsonNode payload, long docId, long userId, int parseVersion) {
        KnowledgeDocEntity doc = docMapper.selectOne(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId));
        if (doc == null || !"PARSING".equals(doc.getStatus())
                || !doc.getParseVersion().equals(parseVersion)) {
            log.info("死信已过期或文档已终结 docId={} version={}", docId, parseVersion);
            return;
        }
        String jobId = payload.path("ingest_job_id").asText(null);
        if (jobId != null && doc.getLastIngestJobId() != null
                && !jobId.equals(doc.getLastIngestJobId())) {
            log.info("死信任务不是当前任务 docId={} jobId={}", docId, jobId);
            return;
        }
        KnowledgeFailureService.FailureOutcome outcome = failureService.fail(userId,
                doc.getCourseId(), docId, parseVersion,
                doc.getCourseIndexVersion(), "E-RAG-02",
                "课件解析多次重试后仍失败，请稍后手动重试或更换文件。");
        if (outcome.applied()) {
            log.warn("解析任务进入死信并置 FAILED docId={} version={}", docId, parseVersion);
        }
    }

    private void parkOrRequeue(Message message, RuntimeException cause) {
        String messageId = message.getMessageProperties().getMessageId();
        String traceId = TraceContext.currentTraceId();
        String source = message.getMessageProperties().getConsumerQueue();
        int attempt = intHeader(message, IngestParkingPublisher.ATTEMPT_HEADER);
        log.error("死信处理遇瞬态异常，尝试停放 messageId={} error={}",
                messageId, cause.getClass().getSimpleName());
        for (int retry = 1; retry <= TRANSIENT_ATTEMPTS; retry++) {
            if (parkingPublisher.publish(message.getBody(), traceId, messageId, source,
                    attempt)) {
                log.error("死信已停放至 {} messageId={}",
                        parkingPublisher.parkingQueue(), messageId);
                return;
            }
            sleepBackoff(retry);
        }
        // 停放也失败：抛出触发容器重投，宁可重复处理也不能丢消息
        log.error("死信停放失败，交给容器重投 messageId={}", messageId);
        throw cause;
    }

    private static int intHeader(Message message, String name) {
        Object value = message.getMessageProperties().getHeaders().get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(TRANSIENT_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(new String(rawBody, StandardCharsets.UTF_8));
            return root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }
}
