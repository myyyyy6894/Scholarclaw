package com.scholarclaw.modules.course.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 课程空间视图（{@code 04} `API-COURSE-001` 响应 `data` 单项）。 */
public record CourseNamespaceVO(
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        String courseName,
        String description,
        boolean isDefault,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDim,
        int indexVersion,
        String indexStatus,
        int readyDocCount,
        int parsingDocCount,
        int failedDocCount,
        String createdAt,
        String updatedAt) {
}
