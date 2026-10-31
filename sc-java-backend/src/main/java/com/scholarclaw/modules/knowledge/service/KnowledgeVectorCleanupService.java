package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.knowledge.mq.VectorCompensationPublisher;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调用 Python `API-INT-AI-004` 幂等清理 ChromaDB 衍生向量（TASK-505）。
 *
 * <p>清理失败不得回滚已完成的业务状态（文档删除、DLQ 失败、课程切换）。
 * 瞬态错误（连接失败/超时/5xx）统一重试 1 次；仍失败时写入持久化补偿队列并记录 ERROR，
 * 由 TASK-902 GC 重放，**不能把失败视为已解决**。
 */
@Service
public class KnowledgeVectorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorCleanupService.class);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final int ATTEMPTS = 2;

    private final PythonAiEngineClient pythonClient;
    private final VectorCompensationPublisher compensationPublisher;
    private final VectorCleanupOutboxService outboxService;

    public KnowledgeVectorCleanupService(PythonAiEngineClient pythonClient,
                                         VectorCompensationPublisher compensationPublisher,
                                         VectorCleanupOutboxService outboxService) {
        this.pythonClient = pythonClient;
        this.compensationPublisher = compensationPublisher;
        this.outboxService = outboxService;
    }

    public VectorCleanupResult cleanupDocument(Long userId, Long courseId, Long docId,
                                               Integer staleParseVersion) {
        Map<String, Object> body = base("DOCUMENT", userId);
        body.put("course_id", String.valueOf(courseId));
        body.put("doc_id", String.valueOf(docId));
        if (staleParseVersion != null) {
            body.put("stale_parse_version", staleParseVersion);
        }
        return cleanup(body, userId);
    }

    public VectorCleanupResult cleanupCourse(Long userId, Long courseId,
                                              Integer courseIndexVersion) {
        Map<String, Object> body = base("COURSE_COLLECTION", userId);
        body.put("course_id", String.valueOf(courseId));
        if (courseIndexVersion != null) {
            body.put("course_index_version", courseIndexVersion);
        }
        return cleanup(body, userId);
    }

    public VectorCleanupResult cleanupUser(Long userId) {
        return cleanup(base("USER_ALL", userId), userId);
    }

    /** Python 要求 `X-User-Id` 与请求体 `user_id` 一致，缺失会被判 400；必须透传真实身份。 */
    private VectorCleanupResult cleanup(Map<String, Object> body, Long userId) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                pythonClient.deleteJson(PythonAiEngineClient.VECTORS_PATH, body, userId,
                        CLEANUP_TIMEOUT);
                return VectorCleanupResult.succeeded();
            } catch (BusinessException e) {
                if (!isTransient(e.getErrorCode()) || attempt == ATTEMPTS) {
                    boolean persisted = reportFailure(body, userId, e.getErrorCode().code(),
                            attempt);
                    return VectorCleanupResult.failed(persisted);
                }
                log.warn("向量清理瞬态失败，重试一次 scope={} code={}",
                        body.get("scope"), e.getErrorCode().code());
            } catch (RuntimeException e) {
                if (attempt == ATTEMPTS) {
                    boolean persisted = reportFailure(body, userId,
                            e.getClass().getSimpleName(), attempt);
                    return VectorCleanupResult.failed(persisted);
                }
                log.warn("向量清理异常，重试一次 scope={} error={}",
                        body.get("scope"), e.getClass().getSimpleName());
            }
        }
        return VectorCleanupResult.failed(false);
    }

    /**
     * GC 重放：单次直接调用 Python 清理，不重试、不重复写补偿队列。
     *
     * <p>调用方是 `rag.ingest.vector-compensation` 消费者：失败由消费者把原消息停放到
     * parking 队列，避免“清理失败 → 再写补偿 → 再消费”的无限循环。
     */
    public boolean cleanupDirect(String scope, Long userId, Long courseId, Long docId,
                                 Integer courseIndexVersion, Integer staleParseVersion) {
        Map<String, Object> body = base(scope, userId);
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
        try {
            pythonClient.deleteJson(PythonAiEngineClient.VECTORS_PATH, body, userId,
                    CLEANUP_TIMEOUT);
            return true;
        } catch (RuntimeException e) {
            log.warn("GC 向量清理失败 scope={} userId={} error={}", scope, userId,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isTransient(ErrorCode code) {
        return code == ErrorCode.UPSTREAM_MODEL_ERROR
                || code == ErrorCode.UPSTREAM_TIMEOUT
                // Python 侧 ChromaDB 5xx/连接失败会映射为 INTERNAL_ERROR，同样属于可重试瞬态
                || code == ErrorCode.INTERNAL_ERROR;
    }

    /**
     * 最终失败：先写 MySQL outbox（终态门禁的权威凭据），再尽力发布 MQ 补偿消息。
     *
     * @return outbox 是否成功持久化；只有它为 true 时调用方才允许把业务行置为终态
     */
    private boolean reportFailure(Map<String, Object> body, Long userId, String reason,
                                  int attempts) {
        Object scope = body.get("scope");
        Long courseId = asLong(body.get("course_id"));
        Long docId = asLong(body.get("doc_id"));
        Integer courseIndexVersion = asInteger(body.get("course_index_version"));
        Integer staleParseVersion = asInteger(body.get("stale_parse_version"));
        boolean persisted;
        try {
            persisted = outboxService.enqueue(String.valueOf(scope), userId, courseId, docId,
                    courseIndexVersion, staleParseVersion, TraceContext.currentTraceId());
        } catch (RuntimeException e) {
            log.error("向量清理补偿 outbox 登记异常 scope={} error={}", scope,
                    e.getClass().getSimpleName());
            persisted = false;
        }
        // MQ 只用于加速重试；即使发布失败，outbox 仍会在下一轮 GC 被重放。
        boolean published = false;
        try {
            published = compensationPublisher.publish(String.valueOf(scope), userId,
                    courseId, docId, courseIndexVersion, staleParseVersion);
        } catch (RuntimeException e) {
            log.error("向量清理 MQ 补偿登记异常 scope={} error={}", scope,
                    e.getClass().getSimpleName());
        }
        if (persisted) {
            log.error("向量清理最终失败，已写入持久化 outbox scope={} userId={} reason={} "
                            + "attempts={} mqPublished={}", scope, userId, reason, attempts,
                    published);
        } else {
            log.error("向量清理最终失败且 outbox 登记失败，业务行将保持删除中并等待重试 "
                            + "scope={} userId={} reason={} attempts={}", scope, userId, reason,
                    attempts);
        }
        return persisted;
    }

    /**
     * 向量清理结果。
     *
     * @param confirmed             Python/Chroma 已确认清理
     * @param compensationPersisted outbox 已持久化（后续由 GC 重放）
     */
    public record VectorCleanupResult(boolean confirmed, boolean compensationPersisted) {

        public static VectorCleanupResult succeeded() {
            return new VectorCleanupResult(true, false);
        }

        public static VectorCleanupResult failed(boolean compensationPersisted) {
            return new VectorCleanupResult(false, compensationPersisted);
        }

        /** 清理是否已“有交代”：确认完成或补偿已落库。 */
        public boolean handled() {
            return confirmed || compensationPersisted;
        }
    }

    private static Long asLong(Object value) {
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private static Integer asInteger(Object value) {
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private static Map<String, Object> base(String scope, Long userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("user_id", String.valueOf(userId));
        return body;
    }
}
