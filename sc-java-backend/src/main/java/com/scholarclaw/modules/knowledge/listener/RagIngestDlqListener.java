package com.scholarclaw.modules.knowledge.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * `rag.ingest.dlq` 死信处理（TASK-505）。
 *
 * <p>Python 消费者重试耗尽后 `nack(requeue=false)`，消息经 DLX 进入死信队列。
 * Java 在此把仍处于当前 `PARSING` 版本的文档置为 `FAILED: E-RAG-02`，并尽力清理残余向量；
 * 重复投递、旧版本或文档已终结时直接忽略。向量清理失败只记录 WARN，不阻塞死信 ACK。
 */
@Component
@ConditionalOnProperty(name = "sc.mq.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class RagIngestDlqListener {

    private static final Logger log = LoggerFactory.getLogger(RagIngestDlqListener.class);

    private final ObjectMapper objectMapper;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocService docService;
    private final CourseService courseService;
    private final KnowledgeVectorCleanupService vectorCleanupService;

    public RagIngestDlqListener(ObjectMapper objectMapper,
                                KnowledgeDocMapper docMapper,
                                KnowledgeDocService docService,
                                CourseService courseService,
                                KnowledgeVectorCleanupService vectorCleanupService) {
        this.objectMapper = objectMapper;
        this.docMapper = docMapper;
        this.docService = docService;
        this.courseService = courseService;
        this.vectorCleanupService = vectorCleanupService;
    }

    @RabbitListener(queues = "${sc.mq.ingest.dlq:rag.ingest.dlq}")
    public void onDeadLetter(Message message) {
        try {
            handle(message.getBody());
        } catch (RuntimeException e) {
            // 死信不能被无限重投：记录后可追踪信息并 ACK
            log.error("死信处理异常，已 ACK 避免阻塞 error={}", e.getClass().getSimpleName());
        }
    }

    void handle(byte[] rawBody) {
        JsonNode payload = parse(rawBody);
        if (payload == null) {
            return;
        }
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
        boolean applied = docService.markFailed(docId, parseVersion, "E-RAG-02",
                "课件解析多次重试后仍失败，请稍后手动重试或更换文件。");
        if (!applied) {
            return;
        }
        try {
            courseService.refreshIndexStatus(userId, doc.getCourseId(),
                    doc.getCourseIndexVersion());
        } catch (RuntimeException e) {
            log.warn("死信后课程状态收敛失败 courseId={} error={}",
                    doc.getCourseId(), e.getClass().getSimpleName());
        }
        vectorCleanupService.cleanupDocument(userId, doc.getCourseId(), docId, parseVersion);
        log.warn("解析任务进入死信并置 FAILED docId={} version={}", docId, parseVersion);
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(new String(rawBody, StandardCharsets.UTF_8));
            return root.isObject() ? root : null;
        } catch (Exception e) {
            log.warn("死信消息不是合法 JSON，忽略");
            return null;
        }
    }
}
