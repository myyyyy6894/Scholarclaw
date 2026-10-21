package com.scholarclaw.modules.course.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 切换课程 Embedding 模型响应（{@code 04} `API-COURSE-003` 响应 `data`）。 */
public record ChangeEmbeddingModelVO(
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        int previousIndexVersion,
        int currentIndexVersion,
        String indexStatus,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDim,
        int reindexingDocCount) {
}
