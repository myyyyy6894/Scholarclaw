package com.scholarclaw.modules.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import java.util.List;

/** `API-CHAT-001` 单条消息。 */
public record ChatMessageVO(
        @JsonSerialize(using = ToStringSerializer.class) Long messageId,
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        String platform,
        String role,
        String intentType,
        String content,
        List<CitationItemVO> citations,
        boolean isRejected,
        String traceId,
        Integer retrievalCostMs,
        Integer llmFirstTokenMs,
        OffsetDateTime createdAt) {
}
