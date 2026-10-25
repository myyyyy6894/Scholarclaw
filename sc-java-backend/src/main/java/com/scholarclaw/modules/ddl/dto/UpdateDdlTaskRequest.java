package com.scholarclaw.modules.ddl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 编辑 DDL 任务或确认激活待定任务请求（{@code 04} `API-DDL-004`）。 */
public record UpdateDdlTaskRequest(
        String courseId,

        @NotBlank(message = "course_name 不能为空")
        @Size(max = 128, message = "course_name 长度不能超过 128")
        String courseName,

        @NotBlank(message = "task_title 不能为空")
        @Size(max = 255, message = "task_title 长度不能超过 255")
        String taskTitle,

        @NotBlank(message = "time_precision 不能为空")
        String timePrecision,

        String dueDate,
        String dueTime,

        @Size(max = 255, message = "deliverable_format 长度不能超过 255")
        String deliverableFormat,

        @Size(max = 255, message = "submission_channel 长度不能超过 255")
        String submissionChannel) {
}
