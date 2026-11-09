package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.ImageRetryRequest;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.ImportView;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository.ImportRow;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Image task creation, consent and preview; formal schedules remain owned by ScheduleImportService. */
@Service
public class ScheduleImageImportService {
    private static final Logger log = LoggerFactory.getLogger(ScheduleImageImportService.class);
    private final ScheduleImageStorage storage;
    private final ScheduleImageModelResolver models;
    private final ScheduleImportRepository imports;
    private final ScheduleRepository schedules;
    private final ScheduleImportService importService;
    private final UserConfigService users;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final int retentionHours;

    public ScheduleImageImportService(ScheduleImageStorage storage, ScheduleImageModelResolver models,
                                      ScheduleImportRepository imports, ScheduleRepository schedules,
                                      ScheduleImportService importService, UserConfigService users, ObjectMapper mapper,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${sc.schedule.import.retention-hours:24}") int retentionHours) {
        this.storage = storage; this.models = models; this.imports = imports; this.schedules = schedules;
        this.importService = importService; this.users = users; this.mapper = mapper;
        if (retentionHours < 1 || retentionHours > 168) throw new IllegalArgumentException("非法课表导入有效期");
        this.retentionHours = retentionHours;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }

    public ScheduleImageModelResolver.ModelSelection model(long userId) { return models.selection(userId); }

    public ImportView create(long userId, String termIdText, String requestIdText, String consentToken,
                             boolean confirmed, MultipartFile file) {
        long termId = ScheduleService.parseId(termIdText);
        String requestId = ScheduleImportService.requireRequestId(requestIdText);
        if (!confirmed || consentToken == null || !consentToken.matches("[0-9a-f]{64}")) throw confirmationRequired();
        // Validate outside the transaction and keep a bounded memory permit until the file is saved.
        try (var image = storage.validate(file)) {
            String fingerprint = fingerprint(new ImageFingerprint(Long.toString(termId), image.originalHash(), consentToken));
            return transaction.execute(status -> {
                var user = users.lockForUpdate(userId);
                if (!"ACTIVE".equals(user.getAccountStatus())) throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
                var previous = imports.byRequest(userId, requestId);
                if (previous.isPresent()) {
                    if (!fingerprint.equals(previous.get().fingerprint())) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    return importService.get(userId, previous.get().id());
                }
                models.verifyConsent(user, consentToken);
                var term = schedules.termForUpdate(userId, termId).orElseThrow(ScheduleImageImportService::notFound);
                if (imports.activeCount(userId) >= 20) {
                    throw new BusinessException(ErrorCode.STATE_CONFLICT, "未完成的导入任务过多，请先处理已有任务。");
                }
                String name = storage.store(userId, image);
                String path = "raw/schedule-import/" + userId + "/" + name;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completionStatus) {
                        if (completionStatus == STATUS_COMMITTED) return;
                        try { storage.delete(userId, name); }
                        catch (RuntimeException e) {
                            // An orphan sweep must later recover failures or a process crash before commit.
                            log.warn("回滚图片暂存清理失败 userId={} type={}", userId, e.getClass().getSimpleName());
                        }
                    }
                });
                LocalDateTime now = now();
                long id = imports.insertImage(userId, termId, requestId, fingerprint, path, image.mediaType(),
                        consentToken, term.scheduleVersion(), now.plusHours(retentionHours), now);
                return importService.get(userId, id);
            });
        }
    }

    public ImportView retry(long userId, long id, ImageRetryRequest request) {
        if (request == null || request.version() == null || !Boolean.TRUE.equals(request.confirmExternalTransfer())
                || request.consentToken() == null || !request.consentToken().matches("[0-9a-f]{64}")) {
            throw confirmationRequired();
        }
        return transaction.execute(status -> {
            var user = users.lockForUpdate(userId);
            models.verifyConsent(user, request.consentToken());
            ImportRow task = imports.findForUpdate(userId, id).orElseThrow(ScheduleImageImportService::notFound);
            ensureLiveImage(task);
            var term = schedules.termForUpdate(userId, task.termId()).orElseThrow(ScheduleImageImportService::notFound);
            if (term.scheduleVersion() != task.scheduleVersion()) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "课表或节次已变化，请重新导入并校对。");
            }
            if (imports.retryImage(userId, id, request.version(), request.consentToken(), now()) != 1) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT, "导入任务已变化或不能再识别，请刷新后检查。");
            }
            return importService.get(userId, id);
        });
    }

    public ImagePreview preview(long userId, long id) {
        ImportRow task = imports.find(userId, id).orElseThrow(ScheduleImageImportService::notFound);
        ensureLiveImage(task);
        String name = sourceName(task);
        if (!("image/png".equals(task.sourceMediaType()) && name.endsWith(".png"))
                && !("image/jpeg".equals(task.sourceMediaType()) && name.endsWith(".jpg"))) throw notFound();
        return new ImagePreview(task.sourceMediaType(), storage.open(userId, name));
    }

    static String sourceName(ImportRow task) {
        String prefix = "raw/schedule-import/" + task.userId() + "/";
        String path = task.sourcePath();
        if (path == null || !path.startsWith(prefix)) throw notFound();
        String name = path.substring(prefix.length());
        if (!name.matches("[0-9a-f]{32}\\.(png|jpg)")) throw notFound();
        return name;
    }
    private static void ensureLiveImage(ImportRow task) {
        if (!"IMAGE".equals(task.sourceType()) || task.sourcePath() == null
                || !java.util.Set.of("QUEUED", "RUNNING", "REVIEW_REQUIRED", "FAILED").contains(task.status())) {
            throw notFound();
        }
        if (!task.expiresAt().isAfter(now())) throw new BusinessException(ErrorCode.SCHEDULE_IMPORT_EXPIRED);
    }
    private String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static BusinessException confirmationRequired() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "请先确认图片发送对象及可能产生的费用。");
    }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private record ImageFingerprint(String termId, String originalHash, String consentToken) { }
    public record ImagePreview(String mediaType, InputStream stream) { }
}
