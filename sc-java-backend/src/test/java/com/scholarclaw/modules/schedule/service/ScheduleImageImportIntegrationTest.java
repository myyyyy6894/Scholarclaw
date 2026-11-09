package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.*;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Real isolated MySQL and synthetic files; the only model dependency is a fake. */
@EnabledIfSystemProperty(named = "sc.schedule.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value", "sc.schedule.import.worker.enabled=false",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false",
        "sc.ddl.reminder.enabled=false", "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class ScheduleImageImportIntegrationTest {
    private static final Path TEST_STORAGE = Path.of(System.getProperty("java.io.tmpdir"),
            "scholarclaw-image-import-test-" + UUID.randomUUID());
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        registry.add("sc.storage.root", TEST_STORAGE::toString);
    }
    @Autowired ScheduleImageImportService images;
    @Autowired ScheduleImageImportWorker worker;
    @Autowired ScheduleImportService imports;
    @Autowired ScheduleService schedules;
    @Autowired ScheduleCleanupService accountCleanup;
    @Autowired UserConfigService users;
    @Autowired AesGcmCryptoService crypto;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @SpyBean ScheduleImageStorage storage;
    @SpyBean ScheduleImportRepository repository;
    @MockBean PythonAiEngineClient python;
    @MockBean TokenRevocationService revocations;

    @Test
    void httpRequiresOwnerAndShowsSanitizedModelAndImage() throws Exception {
        UserConfigEntity owner = user(); UserConfigEntity other = user();
        TermView term = term(owner.getUserId());
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String token = jwt.issue(owner.getUserId(), owner.getUsername(), "STUDENT").token();
        String otherToken = jwt.issue(other.getUserId(), other.getUsername(), "STUDENT").token();
        var selection = mvc.perform(get("/api/v1/schedule/imports/image/model").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.origin").value("https://vision.example.com"))
                .andReturn();
        String body = selection.getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-secret", "private-query");
        String consent = mapper.readTree(body).path("data").path("confirmation_token").asText();
        var result = mvc.perform(multipart("/api/v1/schedule/imports/image").file(image())
                        .param("term_id", term.termId()).param("client_request_id", "image-http")
                        .param("consent_token", consent).param("confirm_external_transfer", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.source_type").value("IMAGE"))
                .andReturn();
        String id = mapper.readTree(result.getResponse().getContentAsString()).path("data").path("import_id").asText();
        mvc.perform(get("/api/v1/schedule/imports/{id}/image", id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/schedule/imports/{id}/image", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/schedule/imports/{id}/image", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void concurrentCreateAndConfirmAreIdempotentAndCleanupIsDurable() throws Exception {
        long user = user().getUserId(); TermView term = term(user); String consent = images.model(user).confirmationToken();
        CountDownLatch start = new CountDownLatch(1);
        ImportView created;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return images.create(user, term.termId(), "same", consent, true, image()); });
            var second = pool.submit(() -> { start.await(); return images.create(user, term.termId(), "same", consent, true, image()); });
            start.countDown();
            created = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS).importId()).isEqualTo(created.importId());
        }
        long id = Long.parseLong(created.importId());
        var row = repository.find(user, id).orElseThrow();
        Path file = TEST_STORAGE.resolve(row.sourcePath());
        try (var files = Files.list(file.getParent())) { assertThat(files.count()).isEqualTo(1); }
        String lease = claim(id);
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class)))
                .thenReturn(suggestion());
        worker.processClaim(id, lease);
        ImportView review = imports.get(user, id);
        assertThat(review.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(review.draft().path("rows")).hasSize(1);
        verify(python, times(1)).postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class));
        var request = new ConfirmRequest(review.version(), "save-image", List.of(rule(term.termId())));
        assertThat(imports.confirm(user, id, request)).isEqualTo(imports.confirm(user, id, request));
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).hasSize(1);
        String name = ScheduleImageImportService.sourceName(row);
        doThrow(new IllegalStateException("simulated IO failure")).when(storage).delete(user, name);
        worker.cleanupTerminalImages(now());
        assertThat(repository.find(user, id).orElseThrow().sourcePath()).isNotNull();
        assertThat(Files.exists(file)).isTrue();
        doCallRealMethod().when(storage).delete(user, name);
        worker.cleanupTerminalImages(now().plusMinutes(2));
        assertThat(repository.find(user, id).orElseThrow().sourcePath()).isNull();
        assertThat(Files.exists(file)).isFalse();
        assertCode(() -> images.preview(user, id), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void configChangesOrInterruptedAttemptNeverAutoSendAndManualSaveStillWorks() throws Exception {
        long user = user().getUserId(); TermView term = term(user); String consent = images.model(user).confirmationToken();
        ImportView created = images.create(user, term.termId(), "changed", consent, true, image());
        long id = Long.parseLong(created.importId());
        configure(user, "vision-model-b");
        String lease = claim(id);
        worker.processClaim(id, lease);
        verifyNoInteractions(python);
        ImportView review = imports.get(user, id);
        assertThat(review.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(review.errorMessage()).contains("配置已变化");
        assertCode(() -> images.retry(user, id, new ImageRetryRequest(review.version(), consent, true)), ErrorCode.STATE_CONFLICT);
        String newConsent = images.model(user).confirmationToken();
        assertCode(() -> images.retry(user, id, new ImageRetryRequest(review.version(), newConsent, false)), ErrorCode.VALIDATION_ERROR);
        ImportView retried = images.retry(user, id, new ImageRetryRequest(review.version(), newConsent, true));
        assertThat(retried.status()).isEqualTo("QUEUED");
        String secondLease = claim(id);
        jdbc.update("UPDATE t_schedule_import SET lease_until=? WHERE import_id=?", now().minusSeconds(1), id);
        repository.recoverImageLeases(now());
        worker.processClaim(id, secondLease);
        verifyNoInteractions(python);
        assertThat(repository.imageCandidates(now(), 100)).doesNotContain(id);
        ImportView manual = imports.get(user, id);
        assertThat(manual.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(manual.draft().path("rows")).isEmpty();
        imports.confirm(user, id, new ConfirmRequest(manual.version(), "manual", List.of(rule(term.termId()))));
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).hasSize(1);
    }

    @Test
    void failedModelCallRequiresFreshConsentAndAllowsManualSave() throws Exception {
        long user = user().getUserId(); TermView term = term(user);
        long id = Long.parseLong(create(user, term, "model-failure").importId());
        String lease = claim(id);
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));
        worker.processClaim(id, lease);
        ImportView review = imports.get(user, id);
        assertThat(review.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(review.errorMessage()).contains("可能已产生费用");
        assertThat(review.draft().path("rows")).isEmpty();
        assertThat(repository.imageCandidates(now(), 100)).doesNotContain(id);
        try (var preview = images.preview(user, id).stream()) { assertThat(preview).isNotNull(); }
        imports.confirm(user, id, new ConfirmRequest(review.version(), "manual-after-failure", List.of(rule(term.termId()))));
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).hasSize(1);
        verify(python, times(1)).postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class));
    }

    @Test
    void lateModelResponseCannotRestoreCanceledTask() throws Exception {
        long user = user().getUserId(); TermView term = term(user);
        long id = Long.parseLong(create(user, term, "late-response").importId());
        String lease = claim(id);
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class)))
                .thenAnswer(call -> {
                    ImportView running = imports.get(user, id);
                    imports.cancel(user, id, new VersionRequest(running.version()));
                    return suggestion();
                });
        worker.processClaim(id, lease);
        ImportView canceled = imports.get(user, id);
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.draft()).isNull();
        worker.cleanupTerminalImages(now());
        assertThat(repository.find(user, id).orElseThrow().sourcePath()).isNull();
        verify(python, times(1)).postJson(eq(PythonAiEngineClient.SCHEDULE_IMAGE_PATH), any(), eq(user), any(Duration.class));
    }

    @Test
    void expirationRollbackOrphansAndAccountCleanupDoNotLoseTrackedFiles() throws Exception {
        long user = user().getUserId(); long other = user().getUserId();
        TermView term = term(user), otherTerm = term(other);
        ImportView task = create(user, term, "expire");
        ImportView otherTask = create(other, otherTerm, "other");
        long id = Long.parseLong(task.importId());
        var row = repository.find(user, id).orElseThrow(); Path file = TEST_STORAGE.resolve(row.sourcePath());
        jdbc.update("UPDATE t_schedule_import SET expires_at=? WHERE import_id=?", now().minusSeconds(1), id);
        assertCode(() -> images.preview(user, id), ErrorCode.SCHEDULE_IMPORT_EXPIRED);
        repository.expireImageTasks(now()); worker.cleanupTerminalImages(now());
        assertThat(Files.exists(file)).isFalse();
        assertThat(repository.find(user, id).orElseThrow().sourcePath()).isNull();

        String orphan;
        try (var validated = storage.validate(image())) { orphan = storage.store(user, validated); }
        Path orphanPath = TEST_STORAGE.resolve("raw/schedule-import/" + user + "/" + orphan);
        Files.setLastModifiedTime(orphanPath, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        var otherRow = repository.find(other, Long.parseLong(otherTask.importId())).orElseThrow();
        Path otherFile = TEST_STORAGE.resolve(otherRow.sourcePath());
        Files.setLastModifiedTime(otherFile, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        worker.sweepOrphansOnce();
        assertThat(Files.exists(orphanPath)).isFalse();
        assertThat(Files.exists(otherFile)).isTrue();

        doThrow(new IllegalStateException("simulated IO failure")).when(storage).deleteUserImages(other);
        assertThatThrownBy(() -> accountCleanup.purge(other)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(other, Long.parseLong(otherTask.importId()))).isPresent();
        assertThat(Files.exists(otherFile)).isTrue();
        doCallRealMethod().when(storage).deleteUserImages(other);
        accountCleanup.purge(other);
        assertThat(repository.find(other, Long.parseLong(otherTask.importId()))).isEmpty();
        assertThat(Files.exists(otherFile)).isFalse();
    }

    @Test
    void transactionRollbackRemovesStagedImage() throws Exception {
        long user = user().getUserId(); TermView term = term(user); String consent = images.model(user).confirmationToken();
        doThrow(new IllegalStateException("synthetic DB failure")).when(repository).insertImage(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyLong(), any(), any());
        assertThatThrownBy(() -> images.create(user, term.termId(), "rollback", consent, true, image()))
                .isInstanceOf(IllegalStateException.class);
        Path directory = TEST_STORAGE.resolve("raw/schedule-import/" + user);
        try (var files = Files.list(directory)) { assertThat(files.count()).isZero(); }
    }

    private ImportView create(long user, TermView term, String request) throws Exception {
        return images.create(user, term.termId(), request, images.model(user).confirmationToken(), true, image());
    }
    private String claim(long id) {
        String lease = UUID.randomUUID().toString();
        assertThat(repository.claimImage(id, lease, now(), now().plusSeconds(120))).isTrue();
        return lease;
    }
    private UserConfigEntity user() {
        var user = users.createUserAccount("img_" + System.nanoTime(), "hash", "Asia/Shanghai");
        configure(user.getUserId(), "vision-model-a");
        return user;
    }
    private void configure(long user, String model) {
        users.updateByokModelConfig(user, new UserConfigService.ByokModelUpdate("DEEPSEEK",
                "https://vision.example.com/v1?private-query=secret", model, crypto.encrypt("synthetic-secret"),
                null, null, null, null, null));
    }
    private TermView term(long user) {
        return schedules.createTerm(user, new TermWrite("测试学期", LocalDate.of(2026, 12, 28), 16,
                "Asia/Shanghai", true, null, false));
    }
    private static RuleWrite rule(String term) {
        return new RuleWrite(term, null, "已校对课程", null, null, 1, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null, null, List.of(1, 2), null, false);
    }
    private JsonNode suggestion() throws Exception {
        return mapper.readTree("""
                {"rows":[{"source_text":"合成图片文字","title":"数学","teacher":null,"room":null,
                "weekday":1,"start_period":null,"end_period":null,"start_time":"08:00:00","end_time":"09:00:00",
                "weeks":[1,2],"issues":[]}]}
                """);
    }
    private static MockMultipartFile image() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("image", "test.png", "image/png", out.toByteArray());
    }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
