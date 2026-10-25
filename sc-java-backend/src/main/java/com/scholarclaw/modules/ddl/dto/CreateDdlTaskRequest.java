package com.scholarclaw.modules.ddl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web 手动新建 DDL 任务请求（{@code 04} `API-DDL-002`）。
 *
 * <p>{@code client_request_id} 的“缺失”必须返回 {@code IDEMPOTENCY_KEY_REQUIRED} 而不是
 * 通用 {@code VALIDATION_ERROR}，因此这里不加 {@code @NotBlank}，由 Service 精确区分。
 */
public record CreateDdlTaskRequest(
        @Size(max = 128, message = "client_request_id 长度不能超过 128")
        String clientRequestId,

        String courseId,

        @NotBlank(message = "course_name 不能为空")
        @Size(max = 128, message = "course_name 长度不能超过 128")
        String courseName,

        @NotBlank(message = "task_title 不能为空")
        @Size(max = 255, message = "task_title 长度不能超过 255")
        String taskTitle,

        String timePrecision,
        String dueDate,
        String dueTime,

        @Size(max = 255, message = "deliverable_format 长度不能超过 255")
        String deliverableFormat,

        @Size(max = 255, message = "submission_channel 长度不能超过 255")
        String submissionChannel) {
}
