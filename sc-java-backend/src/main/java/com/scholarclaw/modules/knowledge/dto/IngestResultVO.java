package com.scholarclaw.modules.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** `API-INT-JAVA-003` 响应数据。 */
public record IngestResultVO(
        @JsonSerialize(using = ToStringSerializer.class) Long docId,
        boolean applied,
        String currentStatus,
        boolean staleVectorCleanupTriggered) {
}
