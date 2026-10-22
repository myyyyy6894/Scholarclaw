package com.scholarclaw.modules.knowledge.mq;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * `MQ-INGEST-001` 标准消息体（{@code 04} §4.9.9）。
 *
 * <p>BIGINT ID 一律十进制字符串；**绝不包含 `api_key`**。字段名显式声明为 snake_case，
 * 不依赖全局 Jackson 命名策略，避免消息契约随 Web 序列化配置漂移。
 */
public record IngestMessage(
        @JsonProperty("ingest_job_id") String ingestJobId,
        @JsonProperty("job_type") String jobType,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("course_id") String courseId,
        @JsonProperty("course_index_version") int courseIndexVersion,
        @JsonProperty("doc_id") String docId,
        @JsonProperty("parse_version") int parseVersion,
        @JsonProperty("doc_name") String docName,
        @JsonProperty("storage_rel_path") String storageRelPath,
        @JsonProperty("file_sha256") String fileSha256,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("enqueued_at") String enqueuedAt) {
}
