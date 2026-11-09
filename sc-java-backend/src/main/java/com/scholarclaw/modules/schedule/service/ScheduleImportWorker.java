package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository.ImportRow;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, leased import execution; an old worker cannot overwrite cancel/retry/recovery. */
@Component
public class ScheduleImportWorker {
    private static final Logger log = LoggerFactory.getLogger(ScheduleImportWorker.class);
    private static final Set<String> ROW_KEYS = Set.of("source_text", "title", "teacher", "room", "weekday",
            "start_period", "end_period", "start_time", "end_time", "weeks", "issues");
    private final ScheduleImportRepository imports;
    private final ScheduleRepository schedules;
    private final PythonAiEngineClient python;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    private volatile boolean ready;

    public ScheduleImportWorker(ScheduleImportRepository imports, ScheduleRepository schedules,
                                PythonAiEngineClient python, ObjectMapper mapper,
                                @Value("${sc.schedule.import.worker.enabled:true}") boolean enabled) {
        this.imports = imports; this.schedules = schedules; this.python = python; this.mapper = mapper;
        this.enabled = enabled;
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "schedule-import-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** ApplicationReadyEvent occurs after the migration ApplicationRunner has completed. */
    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { ready = true; }

    @Scheduled(initialDelayString = "${sc.schedule.import.worker.initial-delay-ms:5000}",
            fixedDelayString = "${sc.schedule.import.worker.poll-delay-ms:2000}")
    public void poll() {
        if (!enabled || !ready) return;
        LocalDateTime now = now();
        imports.expireTextTasks(now);
        imports.failExhaustedLeases(now);
        int available = Math.max(0, 2 - executor.getActiveCount());
        if (available == 0) return;
        for (long id : imports.candidates(now, available)) {
            String token = UUID.randomUUID().toString();
            if (!imports.claim(id, token, now, now.plusSeconds(90))) continue;
            try {
                executor.execute(() -> processClaim(id, token));
            } catch (RejectedExecutionException e) {
                imports.unclaim(id, token, now());
            }
        }
    }

    void processClaim(long id, String token) {
        try {
            ImportRow task = imports.claimed(id, token).orElse(null);
            if (task == null) return;
            var term = schedules.term(task.userId(), task.termId()).orElseThrow();
            JsonNode response = python.postJson(PythonAiEngineClient.SCHEDULE_TEXT_PATH,
                    Map.of("user_id", Long.toString(task.userId()), "raw_text", task.sourceText(),
                            "week_count", term.weekCount()), task.userId(), Duration.ofSeconds(60));
            String draft = validateSuggestions(response, task.sourceText(), term.weekCount());
            imports.finish(id, token, draft, now());
        } catch (RuntimeException e) {
            // Never log text, model responses, credentials, or exception messages.
            log.warn("课表导入建议失败 importId={} type={}", id, e.getClass().getSimpleName());
            imports.fail(id, token, "文字识别暂不可用，请重试或改用手动维护。", now());
        }
    }

    String validateSuggestions(JsonNode data, String rawText, int weekCount) {
        if (data == null || !data.isObject() || data.size() != 1 || !data.path("rows").isArray()) throw invalidDraft();
        JsonNode rows = data.path("rows");
        List<String> lines = rawText == null ? null : rawLines(rawText);
        if (rows.size() > 200 || (lines != null && rows.size() != lines.size())) throw invalidDraft();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            if (!row.isObject() || row.size() != ROW_KEYS.size()) throw invalidDraft();
            var names = row.fieldNames();
            while (names.hasNext()) if (!ROW_KEYS.contains(names.next())) throw invalidDraft();
            if (!row.path("source_text").isTextual()
                    || (lines == null && row.path("source_text").textValue().length() > 2000)
                    || (lines != null && !row.path("source_text").textValue().equals(lines.get(i)))) {
                throw invalidDraft();
            }
            textOrNull(row, "title", 200); textOrNull(row, "teacher", 100); textOrNull(row, "room", 200);
            intOrNull(row, "weekday", 1, 7);
            int startPeriod = intOrNull(row, "start_period", 1, 30);
            int endPeriod = intOrNull(row, "end_period", 1, 30);
            if ((startPeriod == -1) != (endPeriod == -1) || (startPeriod != -1 && endPeriod < startPeriod)) {
                throw invalidDraft();
            }
            LocalTime start = timeOrNull(row, "start_time");
            LocalTime end = timeOrNull(row, "end_time");
            if ((start == null) != (end == null) || (start != null && !start.isBefore(end))) throw invalidDraft();
            JsonNode weeks = row.path("weeks");
            if (!weeks.isArray() || weeks.size() > weekCount) throw invalidDraft();
            int previous = 0;
            for (JsonNode week : weeks) {
                if (!week.isInt() || week.intValue() <= previous || week.intValue() > weekCount) throw invalidDraft();
                previous = week.intValue();
            }
            JsonNode issues = row.path("issues");
            if (!issues.isArray() || issues.size() > 32) throw invalidDraft();
            for (JsonNode issue : issues) if (!issue.isTextual() || issue.textValue().length() > 255) throw invalidDraft();
        }
        try { return mapper.writeValueAsString(data); }
        catch (JsonProcessingException e) { throw invalidDraft(); }
    }
    private static List<String> rawLines(String text) {
        if (text == null) throw invalidDraft();
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r\\n|[\\n\\r\\u000B\\u000C\\u001C-\\u001E\\u0085\\u2028\\u2029]", -1)) {
            if (!line.isBlank()) lines.add(line);
        }
        return lines;
    }
    private static void textOrNull(JsonNode row, String name, int max) {
        JsonNode value = row.get(name);
        if (value == null || !(value.isNull() || value.isTextual() && value.textValue().length() <= max)) throw invalidDraft();
    }
    private static int intOrNull(JsonNode row, String name, int min, int max) {
        JsonNode value = row.get(name);
        if (value == null) throw invalidDraft();
        if (value.isNull()) return -1;
        if (!value.isInt() || value.intValue() < min || value.intValue() > max) throw invalidDraft();
        return value.intValue();
    }
    private static LocalTime timeOrNull(JsonNode row, String name) {
        JsonNode value = row.get(name);
        if (value == null) throw invalidDraft();
        if (value.isNull()) return null;
        if (!value.isTextual() || !value.textValue().matches("\\d{2}:\\d{2}:\\d{2}")) throw invalidDraft();
        try { return LocalTime.parse(value.textValue()); }
        catch (RuntimeException e) { throw invalidDraft(); }
    }
    private static IllegalArgumentException invalidDraft() { return new IllegalArgumentException("无效的课表建议"); }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
