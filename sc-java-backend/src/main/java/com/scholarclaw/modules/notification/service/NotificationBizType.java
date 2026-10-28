package com.scholarclaw.modules.notification.service;

/**
 * 站内通知业务类型（{@code 03} §4.7 / {@code 04} §4.7.1 的 {@code biz_type}）。
 *
 * <p>{@code urgent} 只影响外部 IM 的首次投递判定：T-6h 是 PO-03 唯一明确的紧急提醒，
 * 其余类型在夜间免打扰窗口内一律进入 {@code RATE_LIMITED_DELAYED} 并顺延到窗口结束。
 * 站内信本身无论状态如何都会立即落库、可在 Web 查看。
 */
public enum NotificationBizType {

    DDL_T24H(false),
    DDL_T6H(true),
    DDL_OVERDUE(false),
    DDL_FOLLOW_UP(false),
    DOC_INGEST_READY(false),
    DOC_INGEST_FAILED(false),
    COURSE_REBUILD_DONE(false);

    private final boolean urgent;

    NotificationBizType(boolean urgent) {
        this.urgent = urgent;
    }

    public boolean urgent() {
        return urgent;
    }

    public static NotificationBizType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("biz_type 不能为空");
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的 biz_type: " + code);
        }
    }
}
