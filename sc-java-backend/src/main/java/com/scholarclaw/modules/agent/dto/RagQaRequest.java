package com.scholarclaw.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** `API-RAG-001` 请求体。 */
public record RagQaRequest(
        @NotBlank @Pattern(regexp = "\\d{1,19}") String courseId,
        @NotBlank @Size(min = 1, max = 2000) String query,
        @Size(max = 128) String clientMsgId) {
}
