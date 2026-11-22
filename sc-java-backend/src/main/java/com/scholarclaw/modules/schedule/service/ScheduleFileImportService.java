package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.ImageRetryRequest;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.ImportView;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository.ImportRow;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Owner-scoped file drafts. Only extracted evidence is retained; the uploaded bytes are request-scoped. */
@Service
public class ScheduleFileImportService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("XLSX", "CSV", "DOCX");
    private static final Set<String> FIELDS = Set.of("segment_id", "title", "section_code", "teacher", "room",
            "weekday", "start_period", "end_period", "start_time", "end_time", "weeks");
    private final ScheduleImportRepository repository;
    private final ScheduleRepository schedules;
    private final ScheduleImportService imports;
    private final ScheduleImportWorker validator;
    private final ScheduleImageModelResolver models;
    private final UserConfigService users;
    private final PythonAiEngineClient python;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final int retentionHours;

    public ScheduleFileImportService(ScheduleImportRepository repository, ScheduleRepository schedules,
            ScheduleImportService imports, ScheduleImportWorker validator, ScheduleImageModelResolver models,
            UserConfigService users, PythonAiEngineClient python, ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            @Value("${sc.schedule.import.retention-hours:24}") int retentionHours) {
        this.repository = repository;
        this.schedules = schedules;
        this.imports = imports;
        this.validator = validator;
        this.models = models;
        this.users = users;
        this.python = python;
        this.mapper = mapper;
        this.retentionHours = retentionHours;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Layout(Integer tableIndex, Integer headerRow, Map<String, Integer> mapping) { }

    public ImportView create(long userId, String termValue, String requestId, MultipartFile file) {
        long termId = ScheduleService.parseId(termValue);
        String key = ScheduleImportService.requireRequestId(requestId);
        Upload upload = read(file, termId);
        var existing = repository.byRequest(userId, key);
        if (existing.isPresent()) return existingView(userId, existing.get(), upload);
        schedules.term(userId, termId).orElseThrow(ScheduleFileImportService::notFound);
        String draft = extract(userId, upload, null);
        Long id = transactions.execute(status -> {
            lockUser(userId);
            var raced = repository.byRequest(userId, key);
            if (raced.isPresent()) return ScheduleService.parseId(existingView(userId, raced.get(), upload).importId());
            var term = schedules.termForUpdate(userId, termId).orElseThrow(ScheduleFileImportService::notFound);
            if (repository.activeCount(userId) >= 20) throw conflict("未完成的导入任务过多。");
            LocalDateTime now = now();
            return repository.insertFile(userId, termId, key, upload.fingerprint(), upload.format(), draft,
                    term.scheduleVersion(), now.plusHours(retentionHours), now);
        });
        return imports.get(userId, id);
    }

    public ImportView remap(long userId, long id, long version, Layout layout, MultipartFile file) {
        ImportRow task = requireDraft(userId, id, version);
        Upload upload = read(file, task.termId());
        if (!task.fingerprint().equals(upload.fingerprint())) throw invalid("请选择创建该草稿的原文件。");
        if (layout == null || layout.tableIndex() == null) throw invalid("请选择要使用的工作表或表格。");
        String result = extract(userId, upload, layout);
        if (repository.replaceFileLayout(userId, id, version, result, now()) != 1) throw changed();
        return imports.get(userId, id);
    }

    public ScheduleImageModelResolver.ModelSelection model(long userId) { return models.selection(userId); }

    public ImportView assist(long userId, long id, ImageRetryRequest request) {
        if (request == null || request.version() == null || !Boolean.TRUE.equals(request.confirmExternalTransfer())) {
            throw invalid("请确认对话模型发送对象与费用。");
        }
        ImportRow task = requireDraft(userId, id, request.version());
        JsonNode original;
        try { original = mapper.readTree(task.draftJson()); }
        catch (IOException e) { throw invalid("草稿不可读取。"); }

        var fragments = mapper.createArrayNode();
        for (JsonNode row : original.path("rows")) {
            if (!"COURSE".equals(row.path("state").textValue())) continue;
            ObjectNode fragment = mapper.createObjectNode();
            FIELDS.forEach(field -> fragment.set(field, row.get(field)));
            fragments.add(fragment);
        }
        if (fragments.isEmpty() || fragments.toString().length() > 20_000) {
            throw invalid("没有可发送的课程字段，或整份草稿超出单次模型容量。");
        }
        ModelEndpoint endpoint = transactions.execute(status -> {
            var user = lockUser(userId);
            var selected = models.endpoint(user, request.consentToken());
            if (repository.consumeFileAssist(userId, id, task.version(), now()) != 1) {
                throw conflict("草稿已变化或已使用模型整理。");
            }
            return selected;
        });
        String draft = null;
        String error = null;
        try {
            var response = python.postJson(PythonAiEngineClient.SCHEDULE_FILE_ASSIST_PATH,
                    Map.of("user_id", Long.toString(userId), "rows", fragments, "chat_config", endpoint),
                    userId, Duration.ofSeconds(60));
            draft = mergeSuggestions(original, response);
        } catch (RuntimeException e) {
            error = "模型整理未完成，已保留本地草稿。";
        }
        repository.finishFileAssist(userId, id, task.version() + 1, draft, error, now());
        return imports.get(userId, id);
    }

    private String mergeSuggestions(JsonNode original, JsonNode reply) {
        var suggestions = reply.path("rows");
        if (!suggestions.isArray() || reply.size() != 1) throw invalid("模型结果无效。");
        var rows = new HashMap<String, ObjectNode>();
        original.path("rows").forEach(row -> {
            if ("COURSE".equals(row.path("state").textValue())) rows.put(row.path("segment_id").asText(), (ObjectNode) row);
        });
        if (suggestions.size() != rows.size()) throw invalid("模型结果遗漏来源。");
        for (JsonNode suggestion : suggestions) {
            ObjectNode row = rows.remove(suggestion.path("segment_id").textValue());
            if (row == null || suggestion.size() != FIELDS.size()) throw invalid("模型结果来源无效。");
            boolean updated = false;
            for (String field : FIELDS) {
                JsonNode value = suggestion.get(field);
                if (value == null) throw invalid("模型结果字段无效。");
                // Only fill absent descriptive fields. Calendar information is supplied by the source or user.
                if (Set.of("title", "teacher", "room", "section_code").contains(field)
                        && row.path(field).isNull() && value.isTextual() && !value.asText().isBlank()) {
                    row.set(field, value);
                    updated = true;
                }
            }
            if (updated) row.withArray("issues").add("模型补充字段，请核对原文后保存");
        }
        return validateDraft(original);
    }

    private String extract(long userId, Upload upload, Layout layout) {
        var body = new HashMap<String, Object>();
        body.put("user_id", Long.toString(userId));
        body.put("source_format", upload.format());
        body.put("content", Base64.getEncoder().encodeToString(upload.bytes()));
        if (layout != null) {
            body.put("table_index", layout.tableIndex());
            body.put("header_row", layout.headerRow());
            body.put("mapping", layout.mapping());
        }
        try {
            return validateDraft(python.postJson(PythonAiEngineClient.SCHEDULE_FILE_PATH, body, userId, Duration.ofSeconds(12)));
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.VALIDATION_ERROR) throw e;
            throw invalid("文件内容、行列范围或字段映射无效，请核对后重试。");
        }
    }

    private String validateDraft(JsonNode draft) {
        if (draft == null || !draft.isObject() || draft.size() != 3 || !draft.path("rows").isArray()
                || !draft.path("tables").isArray() || draft.path("tables").size() > 20
                || !draft.path("minimum_weeks").isInt()) throw invalid("文件草稿格式无效。");
        Set<Integer> tables = new HashSet<>();
        for (var table : draft.path("tables")) {
            if (table.size() != 3 || !table.path("index").isInt() || table.path("index").asInt() < 1
                    || table.path("index").asInt() > 20 || !tables.add(table.path("index").asInt())
                    || !boundedText(table.path("label"), 64)
                    || !Set.of("GRID", "DETAIL", "UNKNOWN").contains(table.path("layout").asText())) {
                throw invalid("文件表格坐标无效。");
            }
        }
        Set<String> segments = new HashSet<>();
        var legacy = mapper.createObjectNode();
        var legacyRows = legacy.putArray("rows");
        int highest = 0;
        for (JsonNode row : draft.path("rows")) {
            var ref = row.path("source_ref");
            var segment = row.path("segment_id");
            var section = row.path("section_code");
            if (!row.isObject() || !boundedText(ref, 80) || !boundedText(segment, 100)
                    || !segment.asText().startsWith(ref.asText() + "#") || !segments.add(segment.asText())
                    || !(section.isNull() || boundedText(section, 64))
                    || !Set.of("COURSE", "UNPARSED", "UNSCHEDULED").contains(row.path("state").asText())) {
                throw invalid("文件草稿来源无效。");
            }
            ObjectNode projected = ((ObjectNode) row).deepCopy();
            projected.remove(Set.of("source_ref", "segment_id", "section_code", "state"));
            legacyRows.add(projected);
            for (JsonNode week : row.path("weeks")) highest = Math.max(highest, week.asInt());
        }
        validator.validateSuggestions(legacy, null, 53);
        if (draft.path("minimum_weeks").intValue() != highest) throw invalid("文件草稿周次无效。");
        return draft.toString();
    }

    private static boolean boundedText(JsonNode node, int max) {
        return node.isTextual() && !node.asText().isBlank() && node.asText().length() <= max;
    }
    private ImportRow requireDraft(long userId, long id, long version) {
        ImportRow task = repository.find(userId, id).filter(row -> "FILE".equals(row.sourceType()))
                .orElseThrow(ScheduleFileImportService::notFound);
        if (version != task.version() || !task.expiresAt().isAfter(now()) || !"REVIEW_REQUIRED".equals(task.status())) {
            throw changed();
        }
        return task;
    }
    private ImportView existingView(long userId, ImportRow existing, Upload upload) {
        if (!existing.fingerprint().equals(upload.fingerprint())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return imports.get(userId, existing.id());
    }
    private com.scholarclaw.modules.userconfig.entity.UserConfigEntity lockUser(long userId) {
        var user = users.lockForUpdate(userId);
        if (!"ACTIVE".equals(user.getAccountStatus())) throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        return user;
    }
    private static Upload read(MultipartFile file, long termId) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid("请选择不超过 2 MiB 的课表文件。");
        String name = file.getOriginalFilename();
        String format = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(format)) throw invalid("请选择 XLSX、CSV 或 DOCX 文件。");
        try {
            byte[] bytes = file.getBytes();
            boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
            if (bytes.length > MAX_BYTES || zip == "CSV".equals(format)) throw invalid("文件内容与扩展名不一致。");
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(("FILE:" + termId + ":" + format + ":").getBytes(StandardCharsets.US_ASCII));
            return new Upload(format, bytes, HexFormat.of().formatHex(digest.digest(bytes)));
        } catch (IOException e) {
            throw invalid("无法读取上传文件。");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private record Upload(String format, byte[] bytes, String fingerprint) { }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static BusinessException changed() { return conflict("导入草稿已变化或过期，请刷新后重试。"); }
    private static BusinessException conflict(String text) { return new BusinessException(ErrorCode.STATE_CONFLICT, text); }
    private static BusinessException invalid(String text) { return new BusinessException(ErrorCode.VALIDATION_ERROR, text); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
