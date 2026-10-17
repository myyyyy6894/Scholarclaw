package com.scholarclaw.common.enums;

/**
 * DDL 任务状态（{@code 02} §9.3 状态机；取值与 {@code 03} 的 CHECK 约束一致）。
 *
 * <p>提醒规则与状态强绑定：{@code PENDING_TIME} / {@code PENDING_CONFIRM} 绝不触发
 * T-24h、T-6h 与逾期提醒（{@code FR-DDL-003}）。
 */
public enum TaskStatus {
    PENDING_TIME,
    PENDING_CONFIRM,
    ACTIVE,
    OVERDUE,
    COMPLETED,
    CANCELED
}
