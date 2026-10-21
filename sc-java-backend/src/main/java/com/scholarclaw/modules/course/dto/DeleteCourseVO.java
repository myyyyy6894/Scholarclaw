package com.scholarclaw.modules.course.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 删除课程响应（{@code 04} `API-COURSE-004` 响应 `data`）。 */
public record DeleteCourseVO(@JsonSerialize(using = ToStringSerializer.class) Long deletedCourseId) {
}
