package com.scholarclaw.modules.knowledge.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** `API-INT-JAVA-003` 请求体。 */
public record IngestResultRequest(
        @NotBlank String ingestJobId,
        @NotBlank String userId,
        @NotBlank String courseId,
        @NotBlank String docId,
        @NotNull @Min(1) Integer parseVersion,
        @NotNull @Min(1) Integer courseIndexVersion,
        @NotBlank String status,
        String indexedEmbeddingProvider,
        String indexedEmbeddingModel,
        Integer indexedEmbeddingDim,
        @NotNull @Min(0) Integer pageCount,
        @NotNull @Min(0) Integer chunkCount,
        List<@Valid ParentChunkItem> parentChunks,
        String errorCode,
        String errorMsg) {

    public record ParentChunkItem(
            @NotBlank String parentChunkId,
            @NotNull @Min(1) Integer chunkSeq,
            @NotNull @Min(1) Integer pageStart,
            @NotNull @Min(1) Integer pageEnd,
            String sectionTitle,
            @NotBlank String content,
            @NotNull @Min(0) Integer charLength) {
    }
}
