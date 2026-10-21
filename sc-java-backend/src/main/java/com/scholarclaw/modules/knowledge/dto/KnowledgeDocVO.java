package com.scholarclaw.modules.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 课件文档视图（{@code 04} `API-DOC-001` 响应 `data` 单项）。 */
public record KnowledgeDocVO(
        @JsonSerialize(using = ToStringSerializer.class) Long docId,
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        String docName,
        String fileExt,
        long fileSizeBytes,
        String status,
        int parseVersion,
        int courseIndexVersion,
        String indexedEmbeddingModel,
        Integer indexedEmbeddingDim,
        int pageCount,
        int chunkCount,
        int retryCount,
        String errorCode,
        String errorMsg,
        String enqueuedAt,
        String readyAt,
        String createdAt,
        String updatedAt) {
}
