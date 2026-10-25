package com.scholarclaw.modules.ddl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web 端粘贴通知文本提取请求（{@code 04} `API-DDL-001`）。
 *
 * <p>{@code client_request_id} 的“缺失”必须返回 {@code IDEMPOTENCY_KEY_REQUIRED}，
 * 因此由 Service 精确区分，不在此处加 {@code @NotBlank}。
 */
public record ExtractDdlTaskRequest(
        @NotBlank(message = "raw_text 不能为空")
        @Size(max = 4000, message = "raw_text 长度不能超过 4000")
        String rawText,

        @Size(max = 128, message = "client_request_id 长度不能超过 128")
        String clientRequestId,

        Boolean forceCreate,

        String courseId,

        String referenceTime) {
}
