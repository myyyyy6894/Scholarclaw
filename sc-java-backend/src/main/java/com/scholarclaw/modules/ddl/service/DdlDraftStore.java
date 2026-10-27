package com.scholarclaw.modules.ddl.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DDL 提取的 Redis 接入层幂等锁与变更草案存储（TASK-704）。
 *
 * <p>四类键：
 * <ul>
 *   <li>{@code sc:idemp:msg:{platform}:{uid}:{msgId}}：两阶段
 *       {@code PROCESSING:<owner-token>(60s) -> 回执 JSON(24h)}；Redis 不可用时**降级放行**，
 *       由 MySQL {@code uk_ddl_idempotency_key} + 持久化请求指纹兜底；</li>
 *   <li>{@code sc:lock:ddl_content:{uid}:{contentHash}}：语义判定 + 建单 + 回执的串行锁；
 *       Redis 不可用时**失败关闭**，不允许两个不同请求 ID 的同内容请求同时建单；</li>
 *   <li>{@code sc:lock:ddl_draft_decision:{uid}:{taskId}}：同一草案的决策串行锁，
 *       TTL 60s，owner token + Lua 比较释放；</li>
 *   <li>{@code sc:u:{uid}:task_draft:{task_id}}：重复通知变更草案，TTL 24h；
 *       Redis 不可用时**失败关闭**，不返回无法落地的“草案已生成”。</li>
 * </ul>
 *
 * <p>草案不是业务权威数据；MySQL 中的原任务才是权威。决策回执以 MySQL
 * {@code t_ddl_decision_receipt} 为准，Redis 决策回执仅作性能缓存。
 */
@Service
public class DdlDraftStore {

    private static final Logger log = LoggerFactory.getLogger(DdlDraftStore.class);
    private static final String LOCK_PROCESSING = "PROCESSING";
    private static final String LOCK_PROCESSING_PREFIX = "PROCESSING:";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration PROCESSED_TTL = Duration.ofHours(24);
    private static final Duration DRAFT_TTL = Duration.ofHours(24);
    private static final Duration CONTENT_LOCK_TTL = Duration.ofSeconds(15);
    private static final Duration CONTENT_LOCK_WAIT = Duration.ofMillis(1500);
    private static final long CONTENT_LOCK_RETRY_MILLIS = 100L;
    private static final Duration DECISION_LOCK_TTL = Duration.ofSeconds(60);

    /** 仅当锁值仍等于 owner token 时才删除，避免锁过期后误删其他请求的新锁。 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** PROCESSED 回执 → PROCESSING 的 CAS 接管，避免删除别人刚写的新回执/新锁。 */
    private static final DefaultRedisScript<Long> TAKE_OVER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DdlDraftStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取接入层处理锁并返回 owner token。
     *
     * <p>Redis 故障时降级为 {@link RequestLock#acquiredWithoutToken()}：没有锁，也没有 token，
     * 因此后续释放不会误删其他请求的锁；请求级/语义防重仍由 MySQL 唯一键与持久化指纹兜底。
     */
    public RequestLock acquireRequestLock(String sourcePlatform, Long userId,
                                          String clientRequestId) {
        String key = lockKey(sourcePlatform, userId, clientRequestId);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, LOCK_PROCESSING_PREFIX + token, PROCESSING_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return RequestLock.acquired(token);
            }
            String current = redisTemplate.opsForValue().get(key);
            if (isProcessing(current)) {
                return RequestLock.processing();
            }
            // 任何非 PROCESSING 的既有值都视为“已处理”：新版本存 JSON 回执，
            // 旧版本可能是字面量 PROCESSED；回执损坏由 loadReceipt 兜底为过期。
            return RequestLock.processed(current);
        } catch (RuntimeException e) {
            log.warn("DDL 幂等锁 Redis 不可用，降级为数据库唯一键兜底 userId={}", userId);
            return RequestLock.acquiredWithoutToken();
        }
    }

    /**
     * 用 CAS 把失效的 PROCESSED 回执接管为新的 PROCESSING 锁。
     *
     * <p>必须比较读到的原始值：否则可能删除/覆盖其他请求刚刚写入的新回执。
     *
     * @return 成功时返回新 owner token；锁已被其他请求改变时返回 {@code null}
     */
    public String takeOverProcessed(String sourcePlatform, Long userId, String clientRequestId,
                                    String expectedRawValue) {
        if (expectedRawValue == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        try {
            Long result = redisTemplate.execute(TAKE_OVER_SCRIPT,
                    List.of(lockKey(sourcePlatform, userId, clientRequestId)),
                    expectedRawValue, LOCK_PROCESSING_PREFIX + token,
                    String.valueOf(PROCESSING_TTL.toMillis()));
            return result != null && result == 1L ? token : null;
        } catch (RuntimeException e) {
            log.error("DDL 幂等锁接管失败，失败关闭 userId={}", userId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "提取服务暂时不可用，请稍后重试。");
        }
    }

    /** 保存请求终态回执；`PROCESSED` 分支据此原样重放，不再调用 Python。 */
    public void markProcessed(Long userId, String clientRequestId, DdlRequestReceipt receipt) {
        markProcessed(DdlIdempotencyKey.SOURCE_PLATFORM_WEB, userId, clientRequestId, receipt);
    }

    public void markProcessed(String sourcePlatform, Long userId, String clientRequestId,
                              DdlRequestReceipt receipt) {
        markProcessed(sourcePlatform, userId, clientRequestId, receipt, false);
    }

    /**
     * 写入请求终态回执。
     *
     * @param failClosed 对没有 MySQL 任务兜底的终态（语义提示/变更草案）必须为 `true`：
     *                   回执写失败时返回错误，避免“看起来成功、重试却再次调用模型”。
     *                   已建单终态可由 MySQL 唯一键 + 持久化请求指纹兜底，允许降级为告警。
     */
    public void markProcessed(String sourcePlatform, Long userId, String clientRequestId,
                              DdlRequestReceipt receipt, boolean failClosed) {
        try {
            redisTemplate.opsForValue().set(lockKey(sourcePlatform, userId, clientRequestId),
                    objectMapper.writeValueAsString(receipt), PROCESSED_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("DDL 幂等回执写入失败 userId={} failClosed={}", userId, failClosed);
            if (failClosed) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "幂等回执暂时无法保存，请稍后重试。");
            }
        }
    }

    /** 读取请求终态回执；不存在、为 PROCESSING、损坏或版本不识别时返回空。 */
    public Optional<DdlRequestReceipt> loadReceipt(Long userId, String clientRequestId) {
        return loadReceipt(DdlIdempotencyKey.SOURCE_PLATFORM_WEB, userId, clientRequestId);
    }

    public Optional<DdlRequestReceipt> loadReceipt(String sourcePlatform, Long userId,
                                                   String clientRequestId) {
        String key = lockKey(sourcePlatform, userId, clientRequestId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank() || isProcessing(json)) {
                return Optional.empty();
            }
            DdlRequestReceipt receipt = objectMapper.readValue(json, DdlRequestReceipt.class);
            if (!receipt.isCurrentVersion()
                    || receipt.fingerprint() == null || receipt.outcome() == null
                    || !isBusinessValid(receipt.expiresAt())) {
                return Optional.empty();
            }
            return Optional.of(receipt);
        } catch (JsonProcessingException e) {
            // 旧版本字面量 PROCESSED 或损坏值：按无回执处理，由调用方决定重新处理。
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("DDL 幂等回执读取失败 userId={}", userId);
            return Optional.empty();
        }
    }

    /**
     * Redis TTL 只是兜底：业务层必须自己校验 {@code expires_at}，否则 Redis 恢复旧数据
     * 或 TTL 被误改时，过期回执仍会被当作有效结果重放。
     */
    private static boolean isBusinessValid(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            LocalDateTime expires = LocalDateTime.parse(expiresAt);
            return expires.isAfter(ScTimeZone.now().toLocalDateTime());
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 获取“同用户 + content_hash”串行锁，锁住“语义判定 + 建单/草案 + 终态回执”整个临界区。
     *
     * <p>返回 owner token；**Redis 故障时失败关闭**（抛 {@code INTERNAL_ERROR}），
     * 因为 MySQL 唯一键只能拦截同一请求 ID，无法拦截两个不同请求 ID 的相同 {@code content_hash}。
     * 锁忙时最多等待 {@link #CONTENT_LOCK_WAIT}，仍失败返回 `null`，调用方返回
     * `REQUEST_IN_PROGRESS`。
     */
    public String acquireContentLock(Long userId, String contentHash) {
        String key = contentLockKey(userId, contentHash);
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + CONTENT_LOCK_WAIT.toNanos();
        while (true) {
            try {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(key, token, CONTENT_LOCK_TTL);
                if (Boolean.TRUE.equals(acquired)) {
                    return token;
                }
            } catch (RuntimeException e) {
                log.error("DDL 内容哈希锁 Redis 不可用，失败关闭 userId={}", userId);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "提取服务暂时不可用，请稍后重试。");
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(CONTENT_LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public void releaseContentLock(Long userId, String contentHash, String ownerToken) {
        releaseLock(contentLockKey(userId, contentHash), ownerToken);
    }

    /**
     * 决策锁按 {@code user_id + target_task_id} 加锁：同一草案无论用哪个
     * {@code client_request_id} 发起决策，都只允许一个请求进入。
     */
    public String acquireDraftDecisionLock(Long userId, Long targetTaskId) {
        String key = decisionLockKey(userId, targetTaskId);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, token, DECISION_LOCK_TTL);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException e) {
            // 决策没有 MySQL 唯一键兜底：Redis 不可用时失败关闭，避免并发不同 decision 都执行。
            log.error("DDL 决策锁 Redis 不可用，失败关闭 userId={} taskId={}", userId, targetTaskId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "决策服务暂时不可用，请稍后重试。");
        }
    }

    public void releaseDraftDecisionLock(Long userId, Long targetTaskId, String ownerToken) {
        releaseLock(decisionLockKey(userId, targetTaskId), ownerToken);
    }

    /**
     * 决策锁必须在数据库事务 commit/rollback **完成之后**释放。
     *
     * <p>`@Transactional` 代理在方法返回后才提交；若在 `finally` 立即释放，会出现
     * “锁已释放、事务尚未提交”的窗口，第二个不同请求 ID 的决策可重新读到旧草案/旧版本，
     * 最终产生两种业务效果。这里注册 `afterCompletion`，保证提交或回滚完成后才通过
     * owner token + Lua 释放；无事务上下文时（单元测试）立即释放。
     */
    public void releaseDraftDecisionLockAfterCompletion(Long userId, Long targetTaskId,
                                                        String ownerToken) {
        if (ownerToken == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseDraftDecisionLock(userId, targetTaskId, ownerToken);
                }
            });
        } else {
            releaseDraftDecisionLock(userId, targetTaskId, ownerToken);
        }
    }

    private void releaseLock(String key, String ownerToken) {
        if (ownerToken == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(key), ownerToken);
        } catch (RuntimeException e) {
            log.warn("DDL 锁释放失败 key={}", key);
        }
    }

    /** 读取决策终态回执缓存；损坏/版本不识别按不存在处理。 */
    public Optional<DdlDecisionReceipt> loadDecisionReceipt(Long userId, String clientRequestId) {
        String key = decisionKey(userId, clientRequestId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            DdlDecisionReceipt receipt = objectMapper.readValue(json, DdlDecisionReceipt.class);
            if (receipt.version() != DdlDecisionReceipt.CURRENT_VERSION
                    || receipt.fingerprint() == null || receipt.resultTaskId() == null
                    || !isBusinessValid(receipt.expiresAt())) {
                return Optional.empty();
            }
            return Optional.of(receipt);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("DDL 决策回执读取失败 userId={}", userId);
            return Optional.empty();
        }
    }

    /**
     * 写入决策回执缓存；失败只告警。
     *
     * <p>决策回执的权威存储是 MySQL {@code t_ddl_decision_receipt}，Redis 只是性能优化，
     * 因此这里不能失败关闭，也不能因为缓存写失败让已提交的决策看起来失败。
     */
    public void saveDecisionReceipt(Long userId, String clientRequestId,
                                    DdlDecisionReceipt receipt) {
        try {
            redisTemplate.opsForValue().set(decisionKey(userId, clientRequestId),
                    objectMapper.writeValueAsString(receipt), PROCESSED_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("DDL 决策回执缓存写入失败 userId={}", userId);
        }
    }

    /** 释放接入层处理锁；只删除值仍等于本次 owner token 的键。 */
    public void releaseRequest(String sourcePlatform, Long userId, String clientRequestId,
                               String ownerToken) {
        releaseLock(lockKey(sourcePlatform, userId, clientRequestId), ownerToken);
    }

    public void releaseRequest(Long userId, String clientRequestId, String ownerToken) {
        releaseRequest(DdlIdempotencyKey.SOURCE_PLATFORM_WEB, userId, clientRequestId, ownerToken);
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

    /** 读取草案；不存在/损坏/旧版本/已过期返回空并尽量删除，Redis 不可用则失败关闭。 */
    public Optional<DdlTaskDraft> loadDraft(Long userId, Long targetTaskId) {
        String key = draftKey(userId, targetTaskId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            DdlTaskDraft draft = objectMapper.readValue(json, DdlTaskDraft.class);
            if (!isUsable(draft)) {
                log.info("DDL 变更草案已过期或版本过旧，删除 userId={} taskId={}", userId, targetTaskId);
                deleteDraft(userId, targetTaskId);
                return Optional.empty();
            }
            return Optional.of(draft);
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

    /**
     * 草案可用性：必须是当前 schema、携带目标任务版本快照，且未过业务有效期。
     *
     * <p>Redis TTL 只负责最终回收；旧版本草案（如缺少 {@code target_task_updated_at}）
     * 在 TTL 到期前同样必须禁止展示和决策。
     */
    private static boolean isUsable(DdlTaskDraft draft) {
        return draft != null && draft.isCurrentSchema() && draft.hasTargetVersion()
                && !draft.isExpired(ScTimeZone.now().toLocalDateTime());
    }

    /** 尽力删除草案；用于过期/损坏清理，失败不阻断响应。 */
    public void deleteDraft(Long userId, Long targetTaskId) {
        try {
            redisTemplate.delete(draftKey(userId, targetTaskId));
        } catch (RuntimeException e) {
            log.warn("DDL 变更草案删除失败 userId={} taskId={}", userId, targetTaskId);
        }
    }

    /**
     * 严格删除草案：决策成功后必须确认草案已删除，否则 Redis 恢复后旧草案可能被再次执行。
     *
     * <p>失败抛 {@code INTERNAL_ERROR}；调用方应返回错误，重试时通过持久化决策回执重放，
     * 并再次尝试删除草案。
     */
    public void deleteDraftStrict(Long userId, Long targetTaskId) {
        try {
            redisTemplate.delete(draftKey(userId, targetTaskId));
        } catch (RuntimeException e) {
            log.error("DDL 变更草案删除失败（严格模式） userId={} taskId={}", userId, targetTaskId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "变更草案清理失败，请稍后重试。");
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

    /** 严格版本：事务提交后删除失败会让调用方感知（重试可重放已持久化的决策回执）。 */
    public void deleteDraftAfterCommitStrict(Long userId, Long targetTaskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteDraftStrict(userId, targetTaskId);
                }
            });
        } else {
            deleteDraftStrict(userId, targetTaskId);
        }
    }

    /**
     * 列出用户当前**可用**的变更草案（当前 schema、带目标版本、未过期）。
     *
     * <p>过期/旧版本/损坏的键会被立即删除并跳过；Redis 故障时返回空列表（看板不因此失败）。
     */
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
                    DdlTaskDraft draft = objectMapper.readValue(json, DdlTaskDraft.class);
                    if (isUsable(draft)) {
                        drafts.add(draft);
                    } else {
                        log.info("DDL 变更草案已过期或版本过旧，删除 key={}", key);
                        redisTemplate.delete(key);
                    }
                } catch (JsonProcessingException e) {
                    log.warn("DDL 变更草案解析失败，删除并跳过 userId={}", userId);
                    redisTemplate.delete(key);
                }
            }
        } catch (RuntimeException e) {
            log.warn("DDL 变更草案列表读取失败 userId={}", userId);
            return List.of();
        }
        return drafts;
    }

    static String lockKey(Long userId, String clientRequestId) {
        return lockKey(DdlIdempotencyKey.SOURCE_PLATFORM_WEB, userId, clientRequestId);
    }

    static String lockKey(String sourcePlatform, Long userId, String clientRequestId) {
        return "sc:idemp:msg:" + sourcePlatform + ":" + userId + ":" + clientRequestId;
    }

    static String draftKey(Long userId, Long targetTaskId) {
        return "sc:u:" + userId + ":task_draft:" + targetTaskId;
    }

    static String contentLockKey(Long userId, String contentHash) {
        return "sc:lock:ddl_content:" + userId + ":" + contentHash;
    }

    static String decisionKey(Long userId, String clientRequestId) {
        return "sc:idemp:decision:WEB:" + userId + ":" + clientRequestId;
    }

    static String decisionLockKey(Long userId, Long targetTaskId) {
        return "sc:lock:ddl_draft_decision:" + userId + ":" + targetTaskId;
    }

    private static boolean isProcessing(String raw) {
        return raw != null
                && (LOCK_PROCESSING.equals(raw) || raw.startsWith(LOCK_PROCESSING_PREFIX));
    }

    public enum LockState {
        ACQUIRED,
        PROCESSING,
        PROCESSED
    }

    /**
     * 接入锁结果。
     *
     * @param ownerToken  本次请求持有的 token；降级放行时为 {@code null}
     * @param rawValue    读到的原始 Redis 值（仅 PROCESSED 时非空），用于 CAS 接管
     */
    public record RequestLock(LockState state, String ownerToken, String rawValue) {

        public static RequestLock acquired(String ownerToken) {
            return new RequestLock(LockState.ACQUIRED, ownerToken, null);
        }

        public static RequestLock acquiredWithoutToken() {
            return new RequestLock(LockState.ACQUIRED, null, null);
        }

        public static RequestLock processing() {
            return new RequestLock(LockState.PROCESSING, null, null);
        }

        public static RequestLock processed(String rawValue) {
            return new RequestLock(LockState.PROCESSED, null, rawValue);
        }
    }
}
