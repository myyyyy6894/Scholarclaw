package com.scholarclaw.modules.notification.service;

/**
 * 创建一条站内通知的内部命令（TASK-802）。
 *
 * <p>调用方（`ddl` / `knowledge`）只负责提供业务语义；{@code NotificationService}
 * 负责初始 {@code outbound_status}、IM 目标快照、免打扰顺延与落库。
 * {@code traceId} 为空时取当前 MDC 或生成 {@code tr_cron_*}。
 */
public record NotificationCreateCommand(
        Long userId,
        NotificationBizType bizType,
        Long bizId,
        String title,
        String content,
        String traceId) {
}
