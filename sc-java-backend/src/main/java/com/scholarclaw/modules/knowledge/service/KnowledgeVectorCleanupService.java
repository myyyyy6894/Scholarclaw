package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
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

    public KnowledgeVectorCleanupService(PythonAiEngineClient pythonClient,
                                         VectorCompensationPublisher compensationPublisher) {
        this.pythonClient = pythonClient;
        this.compensationPublisher = compensationPublisher;
    }

    public boolean cleanupDocument(Long userId, Long courseId, Long docId,
                                   Integer staleParseVersion) {
        Map<String, Object> body = base("DOCUMENT", userId);
        body.put("course_id", String.valueOf(courseId));
        body.put("doc_id", String.valueOf(docId));
        if (staleParseVersion != null) {
            body.put("stale_parse_version", staleParseVersion);
        }
        return cleanup(body, userId);
    }

    public boolean cleanupCourse(Long userId, Long courseId, Integer courseIndexVersion) {
        Map<String, Object> body = base("COURSE_COLLECTION", userId);
        body.put("course_id", String.valueOf(courseId));
        if (courseIndexVersion != null) {
            body.put("course_index_version", courseIndexVersion);
        }
        return cleanup(body, userId);
    }

    public boolean cleanupUser(Long userId) {
        return cleanup(base("USER_ALL", userId), userId);
    }

    /** Python 要求 `X-User-Id` 与请求体 `user_id` 一致，缺失会被判 400；必须透传真实身份。 */
    private boolean cleanup(Map<String, Object> body, Long userId) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                pythonClient.deleteJson(PythonAiEngineClient.VECTORS_PATH, body, userId,
                        CLEANUP_TIMEOUT);
                return true;
            } catch (BusinessException e) {
                if (!isTransient(e.getErrorCode()) || attempt == ATTEMPTS) {
                    reportFailure(body, userId, e.getErrorCode().code(), attempt);
                    return false;
                }
                log.warn("向量清理瞬态失败，重试一次 scope={} code={}",
                        body.get("scope"), e.getErrorCode().code());
            } catch (RuntimeException e) {
                if (attempt == ATTEMPTS) {
                    reportFailure(body, userId, e.getClass().getSimpleName(), attempt);
                    return false;
                }
                log.warn("向量清理异常，重试一次 scope={} error={}",
                        body.get("scope"), e.getClass().getSimpleName());
            }
        }
        return false;
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

    private void reportFailure(Map<String, Object> body, Long userId, String reason, int attempts) {
        Object scope = body.get("scope");
        boolean registered = false;
        try {
            registered = compensationPublisher.publish(String.valueOf(scope), userId,
                    asLong(body.get("course_id")), asLong(body.get("doc_id")),
                    asInteger(body.get("course_index_version")),
                    asInteger(body.get("stale_parse_version")));
        } catch (RuntimeException e) {
            log.error("向量清理补偿登记异常 scope={} error={}", scope,
                    e.getClass().getSimpleName());
        }
        if (registered) {
            log.error("向量清理最终失败，补偿已登记 scope={} userId={} reason={} attempts={}",
                    scope, userId, reason, attempts);
        } else {
            log.error("向量清理最终失败且补偿登记失败，需人工介入 scope={} userId={} "
                            + "reason={} attempts={}", scope, userId, reason, attempts);
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
