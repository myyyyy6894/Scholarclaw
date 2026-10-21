package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 文档状态机（{@code 02} §9.1）：非法跳转必须被拒绝。 */
class DocumentStateMachineTest {

    @Test
    @DisplayName("允许矩阵内的跳转")
    void allowsDocumentedTransitions() {
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.UPLOADED, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.PARSING, DocumentStatus.READY)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.PARSING, DocumentStatus.FAILED)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.READY, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.FAILED, DocumentStatus.PARSING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.UPLOADED, DocumentStatus.DELETING)).isTrue();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.DELETING, DocumentStatus.DELETED)).isTrue();
    }

    @Test
    @DisplayName("拒绝矩阵外跳转（例如 READY 不能直接回 UPLOADED、DELETED 不能复活）")
    void rejectsUndocumentedTransitions() {
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.READY, DocumentStatus.UPLOADED)).isFalse();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.DELETED, DocumentStatus.PARSING)).isFalse();
        assertThat(DocumentStateMachine.canTransition(
                DocumentStatus.UPLOADED, DocumentStatus.READY)).isFalse();

        assertThatThrownBy(() -> DocumentStateMachine.requireTransition(
                DocumentStatus.DELETED.name(), DocumentStatus.PARSING))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
    }

    @Test
    @DisplayName("未知状态值一律按状态冲突拒绝")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> DocumentStateMachine.requireTransition(
                "NOT_A_STATUS", DocumentStatus.PARSING))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
    }
}
