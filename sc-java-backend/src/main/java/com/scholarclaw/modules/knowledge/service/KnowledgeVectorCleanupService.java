package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调用 Python `API-INT-AI-004` 幂等清理 ChromaDB 衍生向量（TASK-505）。
 *
 * <p>清理失败不得回滚已完成的业务状态（文档删除、DLQ 失败、课程切换），
 * 只记录 WARN 并交给后续 GC 兜底；这与 `02` §11.2 的最终一致性边界一致。
 */
@Service
public class KnowledgeVectorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorCleanupService.class);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    private final PythonAiEngineClient pythonClient;

    public KnowledgeVectorCleanupService(PythonAiEngineClient pythonClient) {
        this.pythonClient = pythonClient;
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
        try {
            pythonClient.deleteJson(PythonAiEngineClient.VECTORS_PATH, body, userId,
                    CLEANUP_TIMEOUT);
            return true;
        } catch (BusinessException e) {
            log.warn("向量清理失败，已交由后续 GC 兜底 scope={} code={}",
                    body.get("scope"), e.getErrorCode().code());
            return false;
        } catch (RuntimeException e) {
            log.warn("向量清理异常，已交由后续 GC 兜底 scope={} error={}",
                    body.get("scope"), e.getClass().getSimpleName());
            return false;
        }
    }

    private static Map<String, Object> base(String scope, Long userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("user_id", String.valueOf(userId));
        return body;
    }
}
