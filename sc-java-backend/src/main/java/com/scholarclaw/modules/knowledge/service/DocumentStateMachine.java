package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文档状态机（{@code 02} §9.1）。
 *
 * <p>矩阵之外的跳转一律拒绝；真正落库时仍使用带 {@code parse_version} 与状态条件的
 * 原子 UPDATE，状态机校验不是并发控制的替代品。
 *
 * <p>重复投递不建模为状态迁移：`PARSING` 文档再次入队只递增 {@code parse_version}
 * （见 {@link KnowledgeDocService#retryFailed} 等版本更新路径），不允许 `PARSING -> PARSING`。
 */
public final class DocumentStateMachine {

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED = new EnumMap<>(DocumentStatus.class);

    static {
        ALLOWED.put(DocumentStatus.UPLOADED, EnumSet.of(
                DocumentStatus.PARSING, DocumentStatus.FAILED, DocumentStatus.DELETING));
        ALLOWED.put(DocumentStatus.PARSING, EnumSet.of(
                DocumentStatus.READY, DocumentStatus.FAILED, DocumentStatus.DELETING));
        ALLOWED.put(DocumentStatus.READY, EnumSet.of(
                DocumentStatus.PARSING, DocumentStatus.DELETING));
        ALLOWED.put(DocumentStatus.FAILED, EnumSet.of(
                DocumentStatus.PARSING, DocumentStatus.DELETING));
        ALLOWED.put(DocumentStatus.DELETING, EnumSet.of(DocumentStatus.DELETED));
        ALLOWED.put(DocumentStatus.DELETED, EnumSet.noneOf(DocumentStatus.class));
    }

    private DocumentStateMachine() {
    }

    public static boolean canTransition(DocumentStatus from, DocumentStatus to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** 非法跳转抛 {@code 409 STATE_CONFLICT}；消息不暴露内部状态机细节以外的数据。 */
    public static void requireTransition(String fromValue, DocumentStatus to) {
        DocumentStatus from;
        try {
            from = DocumentStatus.valueOf(fromValue);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "文档当前状态未知，无法执行该操作。");
        }
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "文档当前状态不允许执行该操作，请刷新后重试。");
        }
    }
}
