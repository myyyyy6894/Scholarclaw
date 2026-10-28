package com.scholarclaw.modules.ddl.service;

/**
 * DDL 提醒梯度（{@code 01} {@code FR-DDL-003}、{@code 03} §4.5 {@code reminded_flags}）。
 *
 * <p>正式任务使用 1/2/4 位掩码；待确认跟进提醒不占位掩码，单独以
 * {@code follow_up_at + follow_up_count} 控制。{@link #bizType()} 直接对应
 * {@code t_notification_log.biz_type} 的建议取值，供 TASK-802 落库时使用。
 */
public enum DdlReminderType {

    /** 距离 {@code due_at} 剩余 24 小时。 */
    DDL_T24H(1),

    /** 距离 {@code due_at} 剩余 6 小时（免打扰窗口内提前至窗口开始前 1 小时）。 */
    DDL_T6H(2),

    /** 已超过 {@code due_at}，同时把任务流转为 {@code OVERDUE}。 */
    DDL_OVERDUE(4),

    /** 待确认任务（{@code PENDING_TIME} / {@code PENDING_CONFIRM}）的温和跟进提醒。 */
    DDL_FOLLOW_UP(0);

    private final int flagBit;

    DdlReminderType(int flagBit) {
        this.flagBit = flagBit;
    }

    /** {@code reminded_flags} 位掩码；跟进提醒没有位掩码，返回 0。 */
    public int flagBit() {
        return flagBit;
    }

    /** {@code t_notification_log.biz_type} 取值。 */
    public String bizType() {
        return name();
    }
}
