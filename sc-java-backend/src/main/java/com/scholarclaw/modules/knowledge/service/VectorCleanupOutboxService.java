package com.scholarclaw.modules.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.knowledge.entity.VectorCleanupOutboxEntity;
import com.scholarclaw.modules.knowledge.mapper.VectorCleanupOutboxMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 向量清理持久化补偿 outbox（{@code 03} §4.10、P9 复核第 2 项）。
 *
 * <p><b>为什么需要它：</b>RabbitMQ 补偿消息与 Python/Chroma 同时不可用时，仅靠 MQ 会把
 * “向量尚未清理”的事实留在日志里；业务行一旦进入 `DELETED` / 物理删除，GC 就再也找不到
 * 这次清理。本服务把补偿意图写进与业务行同库的 MySQL 表，调用方据此决定是否允许进入终态。
 *
 * <p><b>幂等：</b>`dedup_key` 唯一索引 + `INSERT ... ON DUPLICATE KEY UPDATE`，同一清理意图
 * 只保留一行；已经 `DONE` 的记录不会被重复登记重置。
 */
@Service
public class VectorCleanupOutboxService {

    private static final Logger log = LoggerFactory.getLogger(VectorCleanupOutboxService.class);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_MANUAL = "MANUAL";

    private final VectorCleanupOutboxMapper mapper;

    public VectorCleanupOutboxService(VectorCleanupOutboxMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 幂等登记一次清理意图。
     *
     * @return true 表示记录已持久化（无论新建、已存在 PENDING 还是已 DONE）
     */
    public boolean enqueue(String scope, Long userId, Long courseId, Long docId,
                           Integer courseIndexVersion, Integer staleParseVersion,
                           String traceId) {
        if (scope == null || userId == null) {
            return false;
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        String dedupKey = dedupKey(scope, userId, courseId, docId, courseIndexVersion,
                staleParseVersion);
        VectorCleanupOutboxEntity row = new VectorCleanupOutboxEntity();
        row.setDedupKey(dedupKey);
        row.setScope(scope);
        row.setUserId(userId);
        row.setCourseId(courseId);
        row.setDocId(docId);
        row.setCourseIndexVersion(courseIndexVersion);
        row.setStaleParseVersion(staleParseVersion);
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        row.setNextRetryAt(now);
        row.setTraceId(traceId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            mapper.insert(row);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 已存在：DONE 保持不动；PENDING/MANUAL 重新排到当前时刻，允许再次重放。
            int updated = mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                    .eq(VectorCleanupOutboxEntity::getDedupKey, dedupKey)
                    .ne(VectorCleanupOutboxEntity::getStatus, STATUS_DONE)
                    .set(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING)
                    .set(VectorCleanupOutboxEntity::getNextRetryAt, now)
                    .set(VectorCleanupOutboxEntity::getRetryCount, 0)
                    .set(VectorCleanupOutboxEntity::getLastError, null)
                    .set(VectorCleanupOutboxEntity::getUpdatedAt, now));
            if (updated == 0) {
                // 已 DONE：清理意图已满足。
                return true;
            }
            log.info("向量清理补偿已重新激活 scope={} userId={} courseId={} docId={}",
                    scope, userId, courseId, docId);
            return true;
        } catch (RuntimeException e) {
            log.error("向量清理补偿登记失败 scope={} userId={} error={}",
                    scope, userId, e.getClass().getSimpleName());
            return false;
        }
    }

    /** 扫描到期补偿（`PENDING` / 租约过期的 `PROCESSING`）。 */
    public List<VectorCleanupOutboxEntity> findDue(LocalDateTime now, int limit) {
        return mapper.selectList(Wrappers.<VectorCleanupOutboxEntity>lambdaQuery()
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .le(VectorCleanupOutboxEntity::getNextRetryAt, now)
                .orderByAsc(VectorCleanupOutboxEntity::getNextRetryAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 原子领取一条补偿；返回 false 表示已被其他实例领取或已完成。 */
    public boolean claim(Long cleanupId, LocalDateTime now, Duration lease) {
        return mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                .eq(VectorCleanupOutboxEntity::getCleanupId, cleanupId)
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .le(VectorCleanupOutboxEntity::getNextRetryAt, now)
                .set(VectorCleanupOutboxEntity::getStatus, STATUS_PROCESSING)
                .set(VectorCleanupOutboxEntity::getNextRetryAt, now.plus(lease))
                .set(VectorCleanupOutboxEntity::getUpdatedAt, now)) == 1;
    }

    public boolean markDone(Long cleanupId) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                .eq(VectorCleanupOutboxEntity::getCleanupId, cleanupId)
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING,
                        STATUS_MANUAL)
                .set(VectorCleanupOutboxEntity::getStatus, STATUS_DONE)
                .set(VectorCleanupOutboxEntity::getLastError, null)
                .set(VectorCleanupOutboxEntity::getUpdatedAt, now)) == 1;
    }

    public boolean markRetry(Long cleanupId, int retryCount, String error,
                             LocalDateTime nextRetryAt) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                .eq(VectorCleanupOutboxEntity::getCleanupId, cleanupId)
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .set(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING)
                .set(VectorCleanupOutboxEntity::getRetryCount, retryCount)
                .set(VectorCleanupOutboxEntity::getLastError, truncate(error))
                .set(VectorCleanupOutboxEntity::getNextRetryAt, nextRetryAt)
                .set(VectorCleanupOutboxEntity::getUpdatedAt, now)) == 1;
    }

    public boolean markManual(Long cleanupId, String error) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                .eq(VectorCleanupOutboxEntity::getCleanupId, cleanupId)
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .set(VectorCleanupOutboxEntity::getStatus, STATUS_MANUAL)
                .set(VectorCleanupOutboxEntity::getLastError, truncate(error))
                .set(VectorCleanupOutboxEntity::getUpdatedAt, now)) == 1;
    }

    /**
     * 把超过 24 小时仍未完成的记录标记为 `MANUAL`，并返回数量供 GC 输出人工介入告警。
     */
    public int markStaleManual(LocalDateTime createdBefore) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return mapper.update(null, Wrappers.<VectorCleanupOutboxEntity>lambdaUpdate()
                .in(VectorCleanupOutboxEntity::getStatus, STATUS_PENDING, STATUS_PROCESSING)
                .lt(VectorCleanupOutboxEntity::getCreatedAt, createdBefore)
                .set(VectorCleanupOutboxEntity::getStatus, STATUS_MANUAL)
                .set(VectorCleanupOutboxEntity::getLastError,
                        truncate("超过 24 小时未完成，需人工介入"))
                .set(VectorCleanupOutboxEntity::getUpdatedAt, now));
    }

    /** 待重放缓存的清理任务是否存在（含已完成），用于调用方判断可否进入终态。 */
    public boolean existsFor(String scope, Long userId, Long courseId, Long docId,
                             Integer courseIndexVersion, Integer staleParseVersion) {
        if (scope == null || userId == null) {
            return false;
        }
        Long count = mapper.selectCount(Wrappers.<VectorCleanupOutboxEntity>lambdaQuery()
                .eq(VectorCleanupOutboxEntity::getDedupKey, dedupKey(scope, userId, courseId,
                        docId, courseIndexVersion, staleParseVersion)));
        return count != null && count > 0;
    }

    static String dedupKey(String scope, Long userId, Long courseId, Long docId,
                           Integer courseIndexVersion, Integer staleParseVersion) {
        return scope + "|" + userId + "|" + dash(courseId) + "|" + dash(docId) + "|"
                + dash(courseIndexVersion) + "|" + dash(staleParseVersion);
    }

    private static String dash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
