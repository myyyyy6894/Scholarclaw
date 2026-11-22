package com.scholarclaw.modules.schedule.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.RuleWrite;
import java.util.List;
import java.time.LocalDate;

/** Import suggestions are evidence for review; only the confirm request can create rules. */
public final class ScheduleImportDtos {
    private ScheduleImportDtos() { }
    public record TextImportRequest(String termId, String clientRequestId, String rawText) { }
    public record VersionRequest(Long version) { }
    public record ImageRetryRequest(Long version, String consentToken, Boolean confirmExternalTransfer) { }
    public record ConfirmRequest(Long version, String clientRequestId, List<RuleWrite> rules,
                                 Long reviewedScheduleVersion, String conflictToken) {
        public ConfirmRequest(Long version, String clientRequestId, List<RuleWrite> rules, Long reviewedScheduleVersion) {
            this(version, clientRequestId, rules, reviewedScheduleVersion, null);
        }
        public ConfirmRequest(Long version, String clientRequestId, List<RuleWrite> rules) {
            this(version, clientRequestId, rules, null);
        }
    }
    public record ConflictPreview(long scheduleVersion, List<ImportConflict> conflicts, String conflictToken) { }
    public record ImportConflict(int row, String title, List<LocalDate> dates, boolean duplicate) { }
    public record PreviewRequest(Long version, List<RuleWrite> rules) { }
    public record Receipt(String termId, List<String> ruleIds, long scheduleVersion) { }
    public record ImportView(String importId, String termId, String sourceType, String status, long version, long scheduleVersion,
                             int attempt, String expiresAt, String sourceText, JsonNode draft, String errorMessage,
                             Receipt receipt, String sourceFormat, boolean fileAssistUsed) { }
}
