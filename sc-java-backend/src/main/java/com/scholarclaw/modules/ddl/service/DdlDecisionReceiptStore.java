package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.entity.DdlDecisionReceiptEntity;
import com.scholarclaw.modules.ddl.mapper.DdlDecisionReceiptMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * `API-DDL-005` 决策回执的 MySQL 持久化存储（TASK-704 审阅修复）。
 *
 * <p>Redis 回执只用于快速重放；真正保证“同一请求不会第二次执行”的是本表
 * {@code uk_ddl_decision_receipt (user_id, client_request_id)}。回执写入与数据库变更
 * 位于同一本地事务（由 {@link DdlExtractionService#decideDraft} 的事务边界保证）：
 * 写入失败时业务更新一并回滚，不会出现“动作已提交、回执却丢失”。
 */
@Service
public class DdlDecisionReceiptStore {

    private static final int RECEIPT_TTL_HOURS = 24;

    private final DdlDecisionReceiptMapper mapper;

    public DdlDecisionReceiptStore(DdlDecisionReceiptMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取未过期的决策回执；过期或版本不识别按不存在处理。 */
    public Optional<DdlDecisionReceipt> find(Long userId, String clientRequestId) {
        DdlDecisionReceiptEntity entity =
                mapper.selectByRequest(userId, clientRequestId);
        if (entity == null) {
            return Optional.empty();
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        if (entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        if (entity.getRequestFingerprint() == null || entity.getDecision() == null) {
            return Optional.empty();
        }
        return Optional.of(DdlDecisionReceipt.of(
                entity.getRequestFingerprint(),
                entity.getDecision(),
                entity.getTargetTaskId(),
                entity.getResultTaskId(),
                entity.getExpiresAt().toString()));
    }

    /**
     * 持久化决策回执；唯一键冲突时回查并校验指纹。
     *
     * <p>不同 {@code decision}/{@code target_task_id} 复用同一请求 ID 属于幂等冲突；
     * 相同指纹的并发重试视为成功（由唯一键收敛）。
     */
    public void saveStrict(Long userId, String clientRequestId, DdlDecisionReceipt receipt) {
        DdlDecisionReceiptEntity entity = new DdlDecisionReceiptEntity();
        entity.setUserId(userId);
        entity.setClientRequestId(clientRequestId);
        entity.setRequestFingerprint(receipt.fingerprint());
        entity.setDecision(receipt.decision());
        entity.setTargetTaskId(parseId(receipt.targetTaskId()));
        entity.setResultTaskId(parseId(receipt.resultTaskId()));
        entity.setExpiresAt(ScTimeZone.now().toLocalDateTime()
                .plusHours(RECEIPT_TTL_HOURS));
        entity.setCreatedAt(ScTimeZone.now().toLocalDateTime());
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            DdlDecisionReceiptEntity existing =
                    mapper.selectByRequest(userId, clientRequestId);
            if (existing == null
                    || !Objects.equals(existing.getRequestFingerprint(), receipt.fingerprint())
                    || !Objects.equals(existing.getDecision(), receipt.decision())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "决策回执暂时无法保存，请稍后重试。");
        }
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "决策回执中的任务 ID 不是十进制字符串。");
        }
    }
}
