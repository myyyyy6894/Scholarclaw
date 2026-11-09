package com.scholarclaw.modules.schedule.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.RuleWrite;
import java.util.List;

/** Import suggestions are evidence for review; only the confirm request can create rules. */
public final class ScheduleImportDtos {
    private ScheduleImportDtos() { }
    public record TextImportRequest(String termId, String clientRequestId, String rawText) { }
    public record VersionRequest(Long version) { }
    public record ConfirmRequest(Long version, String clientRequestId, List<RuleWrite> rules) { }
    public record Receipt(String termId, List<String> ruleIds, long scheduleVersion) { }
    public record ImportView(String importId, String termId, String sourceType, String status, long version,
                             int attempt, String expiresAt, String sourceText, JsonNode draft, String errorMessage,
                             Receipt receipt) { }
}
