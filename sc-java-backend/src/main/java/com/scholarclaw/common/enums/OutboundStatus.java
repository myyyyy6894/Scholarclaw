package com.scholarclaw.common.enums;

/**
 * 出站通知推送状态（{@code 02} §9；对应 {@code FR-IM-003}）。
 *
 * <p>{@code FALLBACK_WEB_ONLY} 是站内信保底状态：IM 发送失败、被限流或推送总开关关闭时，
 * 站内通知记录必须保留并落到该状态，不得因 IM 失败而丢失通知。
 */
public enum OutboundStatus {
    PENDING_IM,
    RATE_LIMITED_DELAYED,
    SENT_IM,
    FALLBACK_WEB_ONLY
}
