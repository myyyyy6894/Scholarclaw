package com.scholarclaw.modules.knowledge.mq;

/** `MQ-INGEST-001` 允许的三种任务类型（{@code 04} §4.9.9）。 */
public enum IngestJobType {
    INITIAL_INGEST,
    RETRY_FAILED,
    REINDEX
}
