package com.scholarclaw.common.enums;

/**
 * IM 入站消息处理状态（{@code 02} §9；对应 {@code FR-IM-001} 的入站幂等与白名单校验）。
 *
 * <p>{@code AUTH_REJECTED} 必须静默丢弃且不消耗任何 LLM Token；
 * 重复投递命中幂等键时记 {@code DUPLICATE_IGNORED} 而非 {@code FAILED}。
 */
public enum InboundStatus {
    RECEIVED,
    DUPLICATE_IGNORED,
    AUTH_REJECTED,
    PROCESSING,
    PROCESSED,
    FAILED
}
