package com.scholarclaw.modules.knowledge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** `API-INT-JAVA-002` 请求体（TASK-602）。 */
public record ParentChunkBatchRequest(
        @NotBlank @Pattern(regexp = "\\d{1,19}") String userId,
        @NotBlank @Pattern(regexp = "\\d{1,19}") String courseId,
        @NotNull @Min(1) Integer expectedCourseIndexVersion,
        @NotNull @Size(min = 1, max = 4)
        List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,64}") String> parentChunkIds) {
}
