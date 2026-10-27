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
        String submissionChannel,

        /**
         * 用户显式确认当前时间并请求重新判定状态（Web/Agent 的“确认时间并激活”）。
         *
         * <p>缺省或 `false` 时，若提交的日期/时刻与任务原值一致，后端保留原精度、原状态与
         * 原确认原因，绝不因“只改标题”而隐式激活或改写截止时间。
         */
        Boolean reconfirmTime) {

    /** 兼容未携带 `reconfirm_time` 的调用方。 */
    public UpdateDdlTaskRequest(String courseId, String courseName, String taskTitle,
                               String timePrecision, String dueDate, String dueTime,
                               String deliverableFormat, String submissionChannel) {
        this(courseId, courseName, taskTitle, timePrecision, dueDate, dueTime,
                deliverableFormat, submissionChannel, null);
    }
}
