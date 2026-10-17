package com.scholarclaw.common.enums;

/**
 * 文档异步任务类型（{@code 02} §5/§7；{@code MQ-INGEST-001} 消息载荷字段）。
 *
 * <p>RabbitMQ 仅承载这三类文档任务，普通对话、DDL 提取与通知推送禁止经 MQ。
 */
public enum JobType {
    INITIAL_INGEST,
    RETRY_FAILED,
    REINDEX
}
