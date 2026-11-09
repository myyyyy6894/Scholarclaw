package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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

/** Image attempts are never auto-replayed after a lease expires or a model call fails. */
@Component
public class ScheduleImageImportWorker {
    private static final Logger log = LoggerFactory.getLogger(ScheduleImageImportWorker.class);
    private final ScheduleImportRepository imports;
    private final ScheduleRepository schedules;
    private final ScheduleImageModelResolver models;
    private final ScheduleImageStorage storage;
    private final ScheduleImportWorker validator;
    private final PythonAiEngineClient python;
    private final ThreadPoolExecutor executor;
    private final boolean enabled;
    private volatile boolean ready;
    private String orphanCursor = "";

    public ScheduleImageImportWorker(ScheduleImportRepository imports, ScheduleRepository schedules,
                                     ScheduleImageModelResolver models, ScheduleImageStorage storage,
                                     ScheduleImportWorker validator, PythonAiEngineClient python,
                                     @Value("${sc.schedule.import.worker.enabled:true}") boolean enabled) {
        this.imports = imports; this.schedules = schedules; this.models = models; this.storage = storage;
        this.validator = validator; this.python = python; this.enabled = enabled;
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "schedule-image-import-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() { ready = true; }

    @Scheduled(initialDelayString = "${sc.schedule.import.worker.initial-delay-ms:5000}",
            fixedDelayString = "${sc.schedule.import.worker.poll-delay-ms:2000}")
    public void poll() {
        if (!enabled || !ready) return;
        LocalDateTime now = now();
        imports.expireImageTasks(now);
        imports.recoverImageLeases(now);
        cleanupTerminalImages(now);
        int available = Math.max(0, 2 - executor.getActiveCount());
        if (available == 0) return;
        for (long id : imports.imageCandidates(now, available)) {
            String lease = UUID.randomUUID().toString();
            if (!imports.claimImage(id, lease, now, now.plusSeconds(120))) continue;
            try { executor.execute(() -> processClaim(id, lease)); }
            catch (RejectedExecutionException e) { imports.unclaim(id, lease, now()); }
        }
    }

    void processClaim(long id, String lease) {
        try {
            var task = imports.claimed(id, lease).orElse(null);
            if (task == null || !"IMAGE".equals(task.sourceType())) return;
            var term = schedules.term(task.userId(), task.termId()).orElseThrow();
            if (term.scheduleVersion() != task.scheduleVersion()) {
                imports.imageNeedsManualReview(id, lease, "课表或节次已变化，请重新导入。", now());
                return;
            }
            String name = ScheduleImageImportService.sourceName(task);
            ModelEndpoint endpoint = models.endpoint(task.userId(), task.modelFingerprint());
            Map<String, Object> chat = new LinkedHashMap<>();
            chat.put("provider", endpoint.provider()); chat.put("base_url", endpoint.baseUrl());
            chat.put("api_key", endpoint.apiKey()); chat.put("model_name", endpoint.modelName());
            JsonNode response = python.postJson(PythonAiEngineClient.SCHEDULE_IMAGE_PATH,
                    Map.of("user_id", Long.toString(task.userId()), "source_name", name,
                            "week_count", term.weekCount(), "chat_config", chat), task.userId(), Duration.ofSeconds(90));
            String draft = validator.validateSuggestions(response, null, term.weekCount());
            imports.finish(id, lease, draft, now());
        } catch (RuntimeException e) {
            // Never log image bytes, extracted text, model responses, endpoint or exception messages.
            log.warn("课表图片建议失败 importId={} type={}", id, e.getClass().getSimpleName());
            String message = e instanceof BusinessException business && business.getErrorCode() == ErrorCode.STATE_CONFLICT
                    ? "模型配置已变化，图片未按旧确认发送；可重新确认或手动填写。"
                    : "图片识别未完成，可能已产生费用；可对照原图手动填写，或重新确认后再次识别。";
            imports.imageNeedsManualReview(id, lease, message, now());
        }
    }

    void cleanupTerminalImages(LocalDateTime now) {
        for (var task : imports.imageCleanupCandidates(now.minusMinutes(1), 100)) {
            try {
                String name = ScheduleImageImportService.sourceName(task);
                storage.delete(task.userId(), name);
                imports.clearImagePath(task.id(), task.sourcePath(), now);
            } catch (RuntimeException e) {
                log.warn("课表图片清理失败 importId={} type={}", task.id(), e.getClass().getSimpleName());
                imports.recordImageCleanupFailure(task.id(), task.sourcePath(), now);
            }
        }
    }

    /** Compensate a crash between writing a file and committing its task; never delete referenced images. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweepOrphans() {
        if (!enabled || !ready) return;
        sweepOrphansOnce();
    }

    void sweepOrphansOnce() {
        // Upload transactions time out after 30 seconds; 10 minutes gives an ample commit/rollback margin.
        var candidates = storage.orphanCandidates(Instant.now().minus(Duration.ofMinutes(10)), orphanCursor, 100);
        if (candidates.isEmpty()) { orphanCursor = ""; return; }
        for (var candidate : candidates) {
            String path = "raw/schedule-import/" + candidate.userId() + "/" + candidate.name();
            try {
                if (!imports.imagePathReferenced(candidate.userId(), path)) {
                    storage.delete(candidate.userId(), candidate.name());
                }
            } catch (RuntimeException e) {
                log.warn("课表图片孤儿检查失败 userId={} type={}", candidate.userId(), e.getClass().getSimpleName());
            }
        }
        orphanCursor = candidates.getLast().key();
    }

    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
