package com.scholarclaw.common.enums;

/**
 * DDL 四级时间精度（{@code 01} §FR-DDL-001；{@code t_ddl_task.time_precision}）。
 *
 * <p>硬性不变量：
 * <ul>
 *   <li>{@code VAGUE_OR_MISSING}（如"尽快交""考完再说"）绝不生成 {@code due_at}；</li>
 *   <li>{@code DATE_ONLY} 按 PO-02 默认方案取当日 {@code 23:59:59}，且必须
 *       {@code time_inferred = 1} 并在卡片显式标注；</li>
 *   <li>{@code INVALID_OR_PAST} 进入 {@code PENDING_TIME} 等待用户补充。</li>
 * </ul>
 */
public enum TimePrecision {
    EXACT_DATETIME,
    DATE_ONLY,
    VAGUE_OR_MISSING,
    INVALID_OR_PAST
}
