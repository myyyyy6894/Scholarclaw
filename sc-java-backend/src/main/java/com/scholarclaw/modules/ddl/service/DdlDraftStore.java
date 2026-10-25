package com.scholarclaw.modules.ddl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DDL 提取的 Redis 接入层幂等锁与变更草案存储（TASK-704）。
 *
 * <p>两类键：
 * <ul>
 *   <li>{@code sc:idemp:msg:WEB:{uid}:{client_request_id}}：两阶段
 *       {@code PROCESSING(60s) -> PROCESSED(24h)}，拦截并发重试；Redis 不可用时**降级放行**，
 *       由 MySQL {@code uk_ddl_idempotency_key} 兜底；</li>
 *   <li>{@code sc:u:{uid}:task_draft:{task_id}}：重复通知变更草案，TTL 24h；
 *       Redis 不可用时**失败关闭**，不返回无法落地的“草案已生成”。</li>
 * </ul>
 *
 * <p>草案不是业务权威数据；MySQL 中的原任务才是权威。
 */
@Service
public class DdlDraftStore {

    private static final Logger log = LoggerFactory.getLogger(DdlDraftStore.class);
    private static final String LOCK_PROCESSING = "PROCESSING";
    private static final String LOCK_PROCESSED = "PROCESSED";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration PROCESSED_TTL = Duration.ofHours(24);
    private static final Duration DRAFT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DdlDraftStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 尝试获取接入层处理锁。Redis 故障时返回 {@link LockState#ACQUIRED}（降级放行）。 */
    public LockState acquireWebRequest(Long userId, String clientRequestId) {
        String key = lockKey(userId, clientRequestId);
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, LOCK_PROCESSING, PROCESSING_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return LockState.ACQUIRED;
            }
            String current = redisTemplate.opsForValue().get(key);
            if (LOCK_PROCESSING.equals(current)) {
                return LockState.PROCESSING;
            }
            if (LOCK_PROCESSED.equals(current)) {
                return LockState.PROCESSED;
            }
            // 未知值/脏值：视为过期，删除后重试一次。
            redisTemplate.delete(key);
            Boolean retried = redisTemplate.opsForValue()
                    .setIfAbsent(key, LOCK_PROCESSING, PROCESSING_TTL);
            return Boolean.TRUE.equals(retried) ? LockState.ACQUIRED : LockState.PROCESSING;
        } catch (RuntimeException e) {
            log.warn("DDL 幂等锁 Redis 不可用，降级为数据库唯一键兜底 userId={}", userId);
            return LockState.ACQUIRED;
        }
    }

    public void markProcessed(Long userId, String clientRequestId) {
        try {
            redisTemplate.opsForValue()
                    .set(lockKey(userId, clientRequestId), LOCK_PROCESSED, PROCESSED_TTL);
        } catch (RuntimeException e) {
            log.warn("DDL 幂等锁标记 PROCESSED 失败 userId={}", userId);
        }
    }

    public void release(Long userId, String clientRequestId) {
        try {
            redisTemplate.delete(lockKey(userId, clientRequestId));
        } catch (RuntimeException e) {
            log.warn("DDL 幂等锁释放失败 userId={}", userId);
        }
    }

    /** 保存变更草案；Redis 不可用时失败关闭，避免返回无法落地的草案。 */
    public void saveDraft(Long userId, DdlTaskDraft draft) {
        try {
            redisTemplate.opsForValue().set(draftKey(userId, draft.targetTaskId()),
                    objectMapper.writeValueAsString(draft), DRAFT_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.error("DDL 变更草案写入失败 userId={} taskId={}", userId, draft.targetTaskId());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "变更草案暂时无法保存，请稍后重试。");
        }
    }

    /** 读取草案；不存在/损坏返回空，Redis 不可用则失败关闭。 */
    public Optional<DdlTaskDraft> loadDraft(Long userId, Long targetTaskId) {
        String key = draftKey(userId, targetTaskId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, DdlTaskDraft.class));
        } catch (JsonProcessingException e) {
            log.warn("DDL 变更草案解析失败，按过期处理 userId={} taskId={}", userId, targetTaskId);
            deleteDraft(userId, targetTaskId);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.error("DDL 变更草案读取失败 userId={} taskId={}", userId, targetTaskId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "变更草案暂时不可用，请稍后重试。");
        }
    }

    public void deleteDraft(Long userId, Long targetTaskId) {
        try {
            redisTemplate.delete(draftKey(userId, targetTaskId));
        } catch (RuntimeException e) {
            log.warn("DDL 变更草案删除失败 userId={} taskId={}", userId, targetTaskId);
        }
    }

    /**
     * 在数据库事务提交后删除草案；无事务时立即删除。
     *
     * <p>用于任务完成/取消/编辑后清理已失效的草案，避免用户看到已无意义的变更确认卡。
     */
    public void deleteDraftAfterCommit(Long userId, Long targetTaskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteDraft(userId, targetTaskId);
                }
            });
        } else {
            deleteDraft(userId, targetTaskId);
        }
    }

    /** 列出用户当前未过期的全部变更草案；Redis 故障时返回空列表（看板不因此失败）。 */
    public List<DdlTaskDraft> listDrafts(Long userId) {
        String pattern = "sc:u:" + userId + ":task_draft:*";
        List<DdlTaskDraft> drafts = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String json = redisTemplate.opsForValue().get(key);
                if (json == null || json.isBlank()) {
                    continue;
                }
                try {
                    drafts.add(objectMapper.readValue(json, DdlTaskDraft.class));
                } catch (JsonProcessingException e) {
                    log.warn("DDL 变更草案解析失败，跳过 userId={}", userId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("DDL 变更草案列表读取失败 userId={}", userId);
            return List.of();
        }
        return drafts;
    }

    static String lockKey(Long userId, String clientRequestId) {
        return "sc:idemp:msg:WEB:" + userId + ":" + clientRequestId;
    }

    static String draftKey(Long userId, Long targetTaskId) {
        return "sc:u:" + userId + ":task_draft:" + targetTaskId;
    }

    public enum LockState {
        ACQUIRED,
        PROCESSING,
        PROCESSED
    }
}
