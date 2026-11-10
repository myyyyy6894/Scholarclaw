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
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.dto.ScheduleImportDtos.*;
import com.scholarclaw.modules.schedule.repository.ScheduleImportRepository;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
class ScheduleImportIntegrationTest {
    private static final String TEXT = "数学 周一 1-2节 1-16周";
    private static final String TEST_STORAGE = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
            "scholarclaw-schedule-import-test-" + UUID.randomUUID()).toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        registry.add("sc.storage.root", () -> TEST_STORAGE);
    }
    @Autowired ScheduleImportService imports;
    @Autowired ScheduleImportWorker worker;
    @Autowired ScheduleImportRepository repository;
    @Autowired ScheduleService schedules;
    @Autowired ScheduleCleanupService cleanup;
    @Autowired UserConfigService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean PythonAiEngineClient python;
    @MockBean TokenRevocationService revocations;

    @Test
    void taskCreationIsIdempotentAndOwnedAndHttpDoesNotCacheSource() throws Exception {
        var first = users.createUserAccount("import_" + System.nanoTime(), "hash", "Asia/Shanghai");
        var other = users.createUserAccount("import_" + System.nanoTime(), "hash", "Asia/Shanghai");
        TermView term = term(first.getUserId());
        ImportView created = imports.createText(first.getUserId(), new TextImportRequest(term.termId(), "new-1", TEXT));
        assertThat(imports.createText(first.getUserId(), new TextImportRequest(term.termId(), "new-1", TEXT)).importId())
                .isEqualTo(created.importId());
        assertCode(() -> imports.createText(first.getUserId(), new TextImportRequest(term.termId(), "new-1", "不同原文")),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        assertCode(() -> imports.get(other.getUserId(), Long.parseLong(created.importId())), ErrorCode.RESOURCE_NOT_FOUND);
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String ownerToken = jwt.issue(first.getUserId(), first.getUsername(), "STUDENT").token();
        String otherToken = jwt.issue(other.getUserId(), other.getUsername(), "STUDENT").token();
        mvc.perform(get("/api/v1/schedule/imports/{id}", created.importId()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/schedule/imports/{id}", created.importId()).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/schedule/imports/{id}", created.importId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.import_id").isString())
                .andExpect(jsonPath("$.data.source_text").value(TEXT));
    }

    @Test
    void concurrentConfirmIsIdempotentAndRollsBackInvalidBatch() throws Exception {
        long user = user();
        TermView term = term(user);
        ImportView draft = ready(user, term);
        long id = Long.parseLong(draft.importId());
        RuleWrite good = rule(term.termId(), "数学", 8);
        RuleWrite bad = rule(term.termId(), "", 10);
        assertCode(() -> imports.confirm(user, id, new ConfirmRequest(draft.version(), "bad", List.of(good, bad))),
                ErrorCode.VALIDATION_ERROR);
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).isEmpty();
        assertThat(imports.get(user, id).status()).isEqualTo("REVIEW_REQUIRED");

        ConfirmRequest request = new ConfirmRequest(draft.version(), "confirm-1", List.of(good));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return imports.confirm(user, id, request); });
            var b = pool.submit(() -> { start.await(); return imports.confirm(user, id, request); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).hasSize(1);
        assertThat(imports.get(user, id).sourceText()).isNull();
        assertThat(imports.get(user, id).draft()).isNull();
        assertCode(() -> imports.confirm(user, id,
                new ConfirmRequest(draft.version(), "confirm-1", List.of(rule(term.termId(), "其他", 8)))),
                ErrorCode.IDEMPOTENCY_CONFLICT);
        assertCode(() -> imports.confirm(user, id, new ConfirmRequest(draft.version(), "confirm-2", List.of(good))),
                ErrorCode.STATE_CONFLICT);
    }

    @Test
    void scheduleChangesPreventStaleConfirmation() throws Exception {
        long user = user();
        TermView term = term(user);
        ImportView draft = ready(user, term);
        schedules.createRule(user, rule(term.termId(), "新增", 10));
        assertCode(() -> imports.confirm(user, Long.parseLong(draft.importId()),
                new ConfirmRequest(draft.version(), "confirm-stale", List.of(rule(term.termId(), "数学", 8)))),
                ErrorCode.STATE_CONFLICT);
        assertThat(schedules.rules(user, Long.parseLong(term.termId()))).hasSize(1);
    }

    @Test
    void periodChangesCanBeReviewedAndSavedWithoutRepeatingRecognition() throws Exception {
        long user = user(); TermView term = term(user);
        ImportView draft = ready(user, term);
        long id = Long.parseLong(draft.importId());
        schedules.savePeriods(user, Long.parseLong(term.termId()), new PeriodsWrite(term.version(), List.of(
                new Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
                new Period(2, LocalTime.of(8, 55), LocalTime.of(9, 40))), false));
        TermView latest = schedules.terms(user).getFirst();
        RuleWrite byPeriod = new RuleWrite(term.termId(), null, "数学", null, null, 1,
                null, null, 1, 2, List.of(1, 2), null, false);
        assertCode(() -> imports.confirm(user, id, new ConfirmRequest(draft.version(), "stale", List.of(byPeriod))),
                ErrorCode.STATE_CONFLICT);
        assertCode(() -> imports.confirm(user, id, new ConfirmRequest(draft.version(), "wrong-version",
                List.of(byPeriod), latest.scheduleVersion() + 1)), ErrorCode.STATE_CONFLICT);
        ConfirmRequest approved = new ConfirmRequest(draft.version(), "reviewed", List.of(byPeriod), latest.scheduleVersion());
        Receipt receipt = imports.confirm(user, id, approved);
        assertThat(imports.confirm(user, id, approved)).isEqualTo(receipt);
        RuleView saved = schedules.rules(user, Long.parseLong(term.termId())).getFirst();
        assertThat(saved.startPeriod()).isEqualTo(1);
        assertThat(saved.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(saved.endTime()).isEqualTo(LocalTime.of(9, 40));
        assertCode(() -> imports.confirm(user, id, new ConfirmRequest(draft.version(), "reviewed",
                List.of(byPeriod), latest.scheduleVersion() + 1)), ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void leaseRecoveryPreventsLateResultAndCancelRemovesSource() throws Exception {
        long user = user();
        TermView term = term(user);
        ImportView task = imports.createText(user, new TextImportRequest(term.termId(), "recovery", TEXT));
        long id = Long.parseLong(task.importId());
        LocalDateTime now = now();
        assertThat(repository.claim(id, "first-token", now, now.plusSeconds(1))).isTrue();
        jdbc.update("UPDATE t_schedule_import SET lease_until=? WHERE import_id=?", now.minusSeconds(1), id);
        assertThat(repository.claim(id, "second-token", now, now.plusSeconds(90))).isTrue();
        assertThat(repository.finish(id, "first-token", "{}", now)).isFalse();
        ImportView running = imports.get(user, id);
        imports.cancel(user, id, new VersionRequest(running.version()));
        assertThat(repository.finish(id, "second-token", "{}", now)).isFalse();
        assertThat(imports.get(user, id).status()).isEqualTo("CANCELED");
        assertThat(imports.get(user, id).sourceText()).isNull();
        worker.processClaim(id, "second-token");
        verifyNoInteractions(python);
    }

    @Test
    void expiryHidesAndErasesSourceAndOldWorkerCannotSave() {
        long user = user();
        TermView term = term(user);
        ImportView task = imports.createText(user, new TextImportRequest(term.termId(), "expires", TEXT));
        long id = Long.parseLong(task.importId());
        assertThat(repository.claim(id, "late-token", now(), now().plusSeconds(90))).isTrue();
        jdbc.update("UPDATE t_schedule_import SET expires_at=? WHERE import_id=?", now().minusSeconds(1), id);
        assertThat(imports.get(user, id).status()).isEqualTo("EXPIRED");
        assertThat(imports.get(user, id).sourceText()).isNull();
        assertCode(() -> imports.cancel(user, id, new VersionRequest(task.version())), ErrorCode.SCHEDULE_IMPORT_EXPIRED);
        repository.expireTextTasks(now());
        assertThat(repository.find(user, id).orElseThrow().sourceText()).isNull();
        assertThat(repository.finish(id, "late-token", "{}", now())).isFalse();
    }

    @Test
    void failedAttemptsCanRetryAndAccountCleanupRemovesOnlyOwnedTasks() throws Exception {
        long user = user();
        long other = user();
        TermView term = term(user);
        TermView otherTerm = term(other);
        ImportView task = imports.createText(user, new TextImportRequest(term.termId(), "failure", TEXT));
        ImportView otherTask = imports.createText(other, new TextImportRequest(otherTerm.termId(), "other", TEXT));
        long id = Long.parseLong(task.importId());
        assertThat(repository.claim(id, "attempt-one", now(), now().plusSeconds(90))).isTrue();
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_TEXT_PATH), any(), eq(user), any(Duration.class)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));
        worker.processClaim(id, "attempt-one");
        ImportView failed = imports.get(user, id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.sourceText()).isEqualTo(TEXT);
        ImportView retried = imports.retry(user, id, new VersionRequest(failed.version()));
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(repository.claim(id, "attempt-two", now(), now().plusSeconds(90))).isTrue();
        jdbc.update("UPDATE t_schedule_import SET attempt=3,lease_until=? WHERE import_id=?", now().minusSeconds(1), id);
        repository.failExhaustedLeases(now());
        ImportView exhausted = imports.get(user, id);
        assertThat(exhausted.status()).isEqualTo("FAILED");
        assertCode(() -> imports.retry(user, id, new VersionRequest(exhausted.version())), ErrorCode.STATE_CONFLICT);
        cleanup.purge(user);
        assertCode(() -> imports.get(user, id), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(imports.get(other, Long.parseLong(otherTask.importId())).sourceText()).isEqualTo(TEXT);
    }

    @Test
    void httpCreateAndConfirmUseAuthenticatedOwnerAndReceipt() throws Exception {
        var account = users.createUserAccount("import_http_" + System.nanoTime(), "hash", "Asia/Shanghai");
        TermView term = term(account.getUserId());
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String token = jwt.issue(account.getUserId(), account.getUsername(), "STUDENT").token();
        String body = mapper.writeValueAsString(new TextImportRequest(term.termId(), "http-create", TEXT));
        var response = mvc.perform(post("/api/v1/schedule/imports/text").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.import_id").isString()).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("import_id").asText();
        String lease = UUID.randomUUID().toString();
        assertThat(repository.claim(Long.parseLong(id), lease, now(), now().plusSeconds(90))).isTrue();
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_TEXT_PATH), any(), eq(account.getUserId()), any(Duration.class)))
                .thenReturn(suggestion(TEXT));
        worker.processClaim(Long.parseLong(id), lease);
        ImportView draft = imports.get(account.getUserId(), Long.parseLong(id));
        String confirm = mapper.writeValueAsString(new ConfirmRequest(draft.version(), "http-confirm",
                List.of(rule(term.termId(), "数学", 8))));
        mvc.perform(post("/api/v1/schedule/imports/{id}/confirm", id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rule_ids[0]").isString());
        mvc.perform(get("/api/v1/schedule/imports/term/{id}", term.termId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"));
    }

    @Test
    void malformedOrUnrelatedPythonSuggestionsAreRejected() throws Exception {
        assertThatThrownBy(() -> worker.validateSuggestions(mapper.readTree("{\"rows\":[]}"), TEXT, 16))
                .isInstanceOf(IllegalArgumentException.class);
        JsonNode forged = suggestion("其他用户的内容");
        assertThatThrownBy(() -> worker.validateSuggestions(forged, TEXT, 16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(worker.validateSuggestions(suggestion(TEXT), TEXT, 16)).contains("source_text");
    }

    private ImportView ready(long user, TermView term) throws Exception {
        ImportView created = imports.createText(user, new TextImportRequest(term.termId(), UUID.randomUUID().toString(), TEXT));
        long id = Long.parseLong(created.importId());
        String token = UUID.randomUUID().toString();
        assertThat(repository.claim(id, token, now(), now().plusSeconds(90))).isTrue();
        when(python.postJson(eq(PythonAiEngineClient.SCHEDULE_TEXT_PATH), any(), eq(user), any(Duration.class)))
                .thenReturn(suggestion(TEXT));
        worker.processClaim(id, token);
        ImportView result = imports.get(user, id);
        assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
        return result;
    }
    private JsonNode suggestion(String evidence) {
        var row = mapper.createObjectNode();
        row.put("source_text", evidence); row.put("title", "数学"); row.putNull("teacher"); row.putNull("room");
        row.put("weekday", 1); row.put("start_period", 1); row.put("end_period", 2);
        row.putNull("start_time"); row.putNull("end_time");
        row.putArray("weeks").add(1).add(2); row.putArray("issues");
        var result = mapper.createObjectNode(); result.putArray("rows").add(row); return result;
    }
    private long user() { return users.createUserAccount("import_" + System.nanoTime(), "hash", "Asia/Shanghai").getUserId(); }
    private TermView term(long user) {
        return schedules.createTerm(user, new TermWrite("秋季", LocalDate.of(2026, 12, 28), 16,
                "Asia/Shanghai", true, null, false));
    }
    private static RuleWrite rule(String term, String title, int hour) {
        return new RuleWrite(term, null, title, null, null, 1, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0),
                null, null, List.of(1, 2), null, false);
    }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
