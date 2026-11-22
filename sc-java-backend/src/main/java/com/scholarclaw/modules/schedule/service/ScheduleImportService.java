package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.RuleWrite;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.*;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository.ImportRow;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository.Term;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative task ownership, optimistic versions, and atomic confirmation live in Java. */
@Service
public class ScheduleImportService {
    private static final int MAX_ACTIVE_TASKS = 20;
    private final ScheduleImportRepository imports;
    private final ScheduleRepository schedules;
    private final ScheduleService scheduleService;
    private final UserConfigService users;
    private final ObjectMapper mapper;
    private final AesGcmCryptoService crypto;
    private final int retentionHours;

    public ScheduleImportService(ScheduleImportRepository imports, ScheduleRepository schedules,
                                 ScheduleService scheduleService, UserConfigService users, ObjectMapper mapper, AesGcmCryptoService crypto,
                                 @Value("${sc.schedule.import.retention-hours:24}") int retentionHours) {
        this.imports = imports;
        this.schedules = schedules;
        this.scheduleService = scheduleService;
        this.users = users;
        this.mapper = mapper;
        this.crypto = crypto;
        if (retentionHours < 1 || retentionHours > 168) {
            throw new IllegalArgumentException("课表导入任务有效期须为1至168小时");
        }
        this.retentionHours = retentionHours;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportView createText(long userId, TextImportRequest request) {
        lockUser(userId);
        if (request == null) throw invalid("请输入导入内容。");
        long termId = ScheduleService.parseId(request.termId());
        String requestId = requireRequestId(request.clientRequestId());
        String rawText = request.rawText();
        if (rawText == null || rawText.isBlank() || rawText.length() > 20_000
                || rawText.lines().filter(line -> !line.isBlank()).count() > 200) {
            throw invalid("文字须为1至20000字符且最多200个非空行。");
        }
        String fingerprint = fingerprint(new TextFingerprint(Long.toString(termId), rawText));
        var existing = imports.byRequest(userId, requestId);
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) throw idempotencyConflict();
            return view(existing.get());
        }
        Term term = schedules.termForUpdate(userId, termId).orElseThrow(ScheduleImportService::notFound);
        if (imports.activeCount(userId) >= MAX_ACTIVE_TASKS) {
            throw conflict("未完成的导入任务过多，请先确认、取消或等待过期。");
        }
        LocalDateTime now = now();
        long id = imports.insertText(userId, termId, requestId, fingerprint, rawText,
                term.scheduleVersion(), now.plusHours(retentionHours), now);
        return view(require(userId, id));
    }

    @Transactional(readOnly = true)
    public List<ImportView> recent(long userId, long termId) {
        schedules.term(userId, termId).orElseThrow(ScheduleImportService::notFound);
        return imports.recent(userId, termId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ImportView get(long userId, long id) { return view(require(userId, id)); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportView retry(long userId, long id, VersionRequest request) {
        lockUser(userId);
        ImportRow task = requireForUpdate(userId, id);
        ensureLive(task);
        if (request == null || request.version() == null || request.version() != task.version()
                || imports.retry(userId, id, task.version(), now()) != 1) {
            throw conflict("导入任务已变化或不能再重试，请刷新后检查。");
        }
        return view(require(userId, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportView cancel(long userId, long id, VersionRequest request) {
        lockUser(userId);
        ImportRow task = requireForUpdate(userId, id);
        if ("CANCELED".equals(task.status())) return view(task);
        ensureLive(task);
        if (request == null || request.version() == null || request.version() != task.version()
                || imports.cancel(userId, id, task.version(), now()) != 1) {
            throw conflict("导入任务已变化或不能取消，请刷新后检查。");
        }
        return view(require(userId, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConflictPreview preview(long userId, long id, PreviewRequest request) {
        lockUser(userId);
        ImportRow task = requireForUpdate(userId, id);
        ensureLive(task);
        if (request == null || request.version() == null || request.version() != task.version()
                || !"REVIEW_REQUIRED".equals(task.status()) || !"FILE".equals(task.sourceType())) {
            throw conflict("导入草稿已变化，请刷新后重新校对。");
        }
        validateRules(task, request.rules());
        selectedSources(task, request.rules().size(), request.sourceSegments());
        Term term = schedules.termForUpdate(userId, task.termId()).orElseThrow(ScheduleImportService::notFound);
        var batch = scheduleService.analyzeImport(userId, term, request.rules());
        String token = crypto.encrypt(previewFingerprint(task, term, request.rules(), request.sourceSegments()));
        return new ConflictPreview(term.scheduleVersion(), batch.conflicts(), token);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt confirm(long userId, long id, ConfirmRequest request) {
        lockUser(userId);
        if (request == null) throw invalid("请选择并校对需要保存的课程。");
        String requestId = requireRequestId(request.clientRequestId());
        if (request.version() == null || request.rules() == null || request.rules().isEmpty()
                || request.rules().size() > 200 || request.rules().stream().anyMatch(rule -> rule == null)) {
            throw invalid("请选择1至200条课程并提供任务版本。");
        }
        String fingerprint = request.conflictToken() != null
                ? fingerprint(new FileConfirmFingerprint(Long.toString(id), request.version(), request.rules(),
                        request.reviewedScheduleVersion(), request.sourceSegments()))
                : request.reviewedScheduleVersion() == null
                ? fingerprint(new ConfirmFingerprint(Long.toString(id), request.version(), request.rules()))
                : fingerprint(new ReviewedConfirmFingerprint(Long.toString(id), request.version(), request.rules(),
                        request.reviewedScheduleVersion()));
        var prior = imports.byConfirmRequest(userId, requestId);
        if (prior.isPresent()) {
            if (!fingerprint.equals(prior.get().confirmFingerprint())) throw idempotencyConflict();
            return parseReceipt(prior.get().receiptJson());
        }
        ImportRow task = requireForUpdate(userId, id);
        ensureLive(task);
        if (!"REVIEW_REQUIRED".equals(task.status()) || request.version() != task.version()) {
            throw conflict("导入任务状态或版本已变化，请刷新后重试。");
        }
        Term term = schedules.termForUpdate(userId, task.termId()).orElseThrow(ScheduleImportService::notFound);
        if ((term.scheduleVersion() != task.scheduleVersion() || request.reviewedScheduleVersion() != null)
                && !Long.valueOf(term.scheduleVersion()).equals(request.reviewedScheduleVersion())) {
            throw conflict("课表或节次已变化，请刷新后在当前草稿重新校对。");
        }
        validateRules(task, request.rules());
        List<String> ruleIds;
        List<RuleOrigin> origins = new ArrayList<>();
        if ("FILE".equals(task.sourceType())) {
            if (!Long.valueOf(term.scheduleVersion()).equals(request.reviewedScheduleVersion())
                    || !matchesPreview(request.conflictToken(), previewFingerprint(task, term, request.rules(), request.sourceSegments()))) {
                throw conflict("请按当前课表预览并确认所选安排。");
            }
            var selected = selectedSources(task, request.rules().size(), request.sourceSegments());
            var batch = scheduleService.analyzeImport(userId, term, request.rules());
            ruleIds = scheduleService.insertImport(userId, term, batch);
            for (int i = 0; i < ruleIds.size(); i++) {
                JsonNode row = selected.get(i);
                origins.add(new RuleOrigin(ruleIds.get(i), row == null ? null : row.path("segment_id").textValue(),
                        row == null ? null : row.path("source_ref").textValue(),
                        row == null ? null : row.path("section_code").textValue()));
            }
        } else {
            ruleIds = new ArrayList<>();
            for (RuleWrite rule : request.rules()) ruleIds.add(scheduleService.createRule(userId, rule).ruleId());
        }
        long finalVersion = schedules.term(userId, task.termId()).orElseThrow(ScheduleImportService::notFound)
                .scheduleVersion();
        Receipt receipt = new Receipt(Long.toString(task.termId()), List.copyOf(ruleIds), finalVersion, origins);
        if (imports.confirm(userId, id, task.version(), requestId, fingerprint, write(receipt), now()) != 1) {
            throw conflict("导入任务已变化，请刷新后重试。");
        }
        return receipt;
    }

    private static void validateRules(ImportRow task, List<RuleWrite> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 200) throw invalid("请选择1至200条课程。");
        for (RuleWrite rule : rules) {
            if (rule == null || !Long.toString(task.termId()).equals(rule.termId()) || rule.version() != null
                    || (rule.courseId() != null && !rule.courseId().isBlank())) {
                throw invalid("导入课程须属于该学期，确认时不能关联知识库课程。");
            }
        }
    }
    private String previewFingerprint(ImportRow task, Term term, List<RuleWrite> rules, List<String> segments) {
        return fingerprint(new FileConfirmFingerprint(Long.toString(task.id()), task.version(), rules,
                term.scheduleVersion(), segments));
    }
    private boolean matchesPreview(String token, String expected) {
        if (token == null || token.length() > 256) return false;
        try {
            String actual = crypto.decrypt(token);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
        } catch (BusinessException e) { return false; }
    }
    private List<JsonNode> selectedSources(ImportRow task, int ruleCount, List<String> segments) {
        if (segments == null || segments.size() != ruleCount) throw invalid("请一并提交所选课程的来源标识。");
        var draft = parseJson(task.draftJson());
        var sources = new HashMap<String, JsonNode>();
        for (JsonNode row : draft.path("rows")) sources.put(row.path("segment_id").textValue(), row);
        var used = new HashSet<String>();
        List<JsonNode> resolved = new ArrayList<>(ruleCount);
        for (String id : segments) {
            if (id == null) {
                resolved.add(null); // A manually added row has no original file fragment.
            } else {
                JsonNode source = sources.get(id);
                if (source == null || !used.add(id)) throw invalid("课程来源已变化、重复或不属于当前草稿。");
                resolved.add(source);
            }
        }
        return resolved;
    }
    private ImportView view(ImportRow row) {
        boolean expired = isExpirable(row.status()) && !row.expiresAt().isAfter(now());
        return new ImportView(Long.toString(row.id()), Long.toString(row.termId()), row.sourceType(),
                expired ? "EXPIRED" : row.status(), row.version(), row.scheduleVersion(), row.attempt(),
                ScTimeZone.toOffsetDateTime(row.expiresAt()).toString(), expired ? null : row.sourceText(),
                expired ? null : parseJson(row.draftJson()), expired ? null : row.errorMessage(),
                row.receiptJson() == null ? null : parseReceipt(row.receiptJson()), row.sourceFormat(), row.fileAssistUsed());
    }
    private void ensureLive(ImportRow row) {
        if (isExpirable(row.status()) && !row.expiresAt().isAfter(now())) {
            throw new BusinessException(ErrorCode.SCHEDULE_IMPORT_EXPIRED, "导入任务已过期，请重新导入。");
        }
    }
    private static boolean isExpirable(String status) {
        return List.of("QUEUED", "RUNNING", "REVIEW_REQUIRED", "FAILED").contains(status);
    }
    private ImportRow require(long userId, long id) { return imports.find(userId, id).orElseThrow(ScheduleImportService::notFound); }
    private ImportRow requireForUpdate(long userId, long id) {
        return imports.findForUpdate(userId, id).orElseThrow(ScheduleImportService::notFound);
    }
    private void lockUser(long userId) {
        if (!"ACTIVE".equals(users.lockForUpdate(userId).getAccountStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
    }
    static String requireRequestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "请求标识须为1至64位字母、数字、下划线或连字符。");
        }
        return value;
    }
    private JsonNode parseJson(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
    }
    private Receipt parseReceipt(String value) {
        try { return mapper.readValue(value, Receipt.class); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
    }
    private String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private record FileConfirmFingerprint(String importId, Long version, List<RuleWrite> rules,
                                          Long reviewedScheduleVersion, List<String> sourceSegments) { }
    private record TextFingerprint(String termId, String rawText) { }
    private record ConfirmFingerprint(String importId, Long version, List<RuleWrite> rules) { }
    private record ReviewedConfirmFingerprint(String importId, Long version, List<RuleWrite> rules,
                                              Long reviewedScheduleVersion) { }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.STATE_CONFLICT, message); }
    private static BusinessException idempotencyConflict() { return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
