package com.scholarclaw.modules.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建课程请求（{@code 04} `API-COURSE-002`）。 */
public record CreateCourseRequest(
        @NotBlank(message = "course_name 不能为空")
        @Size(max = 128, message = "course_name 长度不能超过 128")
        String courseName,

        @Size(max = 500, message = "description 长度不能超过 500")
        String description) {
}
