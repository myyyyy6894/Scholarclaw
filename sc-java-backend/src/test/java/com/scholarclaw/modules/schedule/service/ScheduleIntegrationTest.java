package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.listener.ScheduleLifecycleListener;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "sc.schedule.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false", "sc.schedule.import.worker.enabled=false",
        "sc.ddl.reminder.enabled=false", "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class ScheduleIntegrationTest {
    private static final String TEST_STORAGE = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
            "scholarclaw-schedule-test-" + java.util.UUID.randomUUID()).toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        registry.add("sc.storage.root", () -> TEST_STORAGE);
    }

    @Autowired ScheduleService service;
    @Autowired UserConfigService configs;
    @Autowired ScheduleCleanupService cleanup;
    @Autowired ScheduleLifecycleListener lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean TokenRevocationService revocations;

    @Test
    void httpRequiresJwtUsesStringIdsAndHidesOtherUsersResources() throws Exception {
        var first = configs.createUserAccount("schedule_web_" + System.nanoTime(), "hash", "Asia/Shanghai");
        var second = configs.createUserAccount("schedule_web_" + System.nanoTime(), "hash", "Asia/Shanghai");
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String body = "{\"name\":\"秋季\",\"first_monday\":\"2026-12-28\","
                + "\"week_count\":16,\"timezone\":\"Asia/Shanghai\",\"active\":true}";
        mvc.perform(post("/api/v1/schedule/terms").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String firstToken = jwt.issue(first.getUserId(), first.getUsername(), "STUDENT").token();
        var created = mvc.perform(post("/api/v1/schedule/terms").header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.term_id").isString()).andReturn();
        String termId = mapper.readTree(created.getResponse().getContentAsString()).path("data").path("term_id").asText();
        String secondToken = jwt.issue(second.getUserId(), second.getUsername(), "STUDENT").token();
        mvc.perform(get("/api/v1/schedule/occurrences").header("Authorization", "Bearer " + secondToken)
                        .param("term_id", termId).param("from", "2026-12-28").param("to", "2027-01-03"))
                .andExpect(status().isNotFound());
        var nyTerm = service.createTerm(first.getUserId(), new TermWrite("纽约学期",
                LocalDate.of(2026, 12, 28), 16, "America/New_York", true, null, false));
        service.createRule(first.getUserId(), directRule(nyTerm.termId()));
        mvc.perform(get("/api/v1/schedule/occurrences").header("Authorization", "Bearer " + firstToken)
                        .param("term_id", nyTerm.termId()).param("from", "2026-12-28").param("to", "2026-12-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.occurrences[0].start_at").value("2026-12-28T08:00-05:00"));
    }

    @Test
    void manualScheduleSupportsVersionsPeriodsOverlapAndCrossWeekMove() {
        long user = user();
        long other = user();
        TermView term = service.createTerm(user, term("秋季", true));
        long termId = Long.parseLong(term.termId());
        service.savePeriods(user, termId, new PeriodsWrite(term.version(), periods("08:00", "08:45", "08:55", "09:40"), false));
        RuleView first = service.createRule(user, new RuleWrite(term.termId(), null, "高数", "张老师", "教一301",
                1, null, null, 1, 2, List.of(1, 2, 4), null, false));
        assertThat(first.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(first.endTime()).isEqualTo(LocalTime.of(9, 40));
        RuleWrite overlap = new RuleWrite(term.termId(), null, "英语", null, null, 1,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null, null, List.of(1), null, false);
        assertCode(() -> service.createRule(user, overlap), ErrorCode.STATE_CONFLICT);
        RuleView second = service.createRule(user, new RuleWrite(term.termId(), null, "英语", null, null, 1,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null, null, List.of(1), null, true));
        var firstDay = service.occurrences(user, termId, LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 28));
        assertThat(firstDay.occurrences()).hasSize(2).allMatch(OccurrenceView::conflict);
        assertCode(() -> service.occurrences(other, termId, LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 28)),
                ErrorCode.RESOURCE_NOT_FOUND);

        ExceptionView moved = service.saveException(user, Long.parseLong(first.ruleId()), LocalDate.of(2026, 12, 28),
                new ExceptionWrite("MOVE", LocalDate.of(2027, 1, 6), LocalTime.of(14, 0), LocalTime.of(15, 0), "新教室", null, false));
        assertThat(service.occurrences(user, termId, LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)).occurrences())
                .extracting(OccurrenceView::ruleId).containsExactly(second.ruleId());
        var nextWeek = service.occurrences(user, termId, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10));
        assertThat(nextWeek.occurrences()).hasSize(2);
        assertThat(nextWeek.occurrences().get(1).key()).isEqualTo(first.ruleId() + ":2026-12-28");
        assertThat(nextWeek.occurrences().get(1).room()).isEqualTo("新教室");
        assertCode(() -> service.deleteException(user, Long.parseLong(first.ruleId()), moved.originalDate(), 999L),
                ErrorCode.STATE_CONFLICT);

        TermView latest = service.terms(user).get(0);
        PeriodsWrite changed = new PeriodsWrite(latest.version(), periods("08:10", "08:50", "09:00", "09:45"), false);
        assertThat(service.previewPeriods(user, termId, changed).affected()).hasSize(1);
        assertCode(() -> service.savePeriods(user, termId, changed), ErrorCode.STATE_CONFLICT);
        service.savePeriods(user, termId, new PeriodsWrite(latest.version(), changed.periods(), true));
        assertThat(service.rules(user, termId).get(0).startTime()).isEqualTo(LocalTime.of(8, 10));
        assertCode(() -> service.savePeriods(user, termId, changed), ErrorCode.STATE_CONFLICT);
        assertCode(() -> service.updateRule(user, Long.parseLong(first.ruleId()),
                new RuleWrite(term.termId(), null, "高数", null, null, 2, null, null, 1, 2,
                        List.of(1, 2, 4), first.version() + 1, true)), ErrorCode.STATE_CONFLICT);
    }

    @Test
    void courseUnbindingAndCleanupOnlyAffectTheOwningUser() {
        long firstUser = user();
        long secondUser = user();
        var firstTerm = service.createTerm(firstUser, term("第一", true));
        var secondTerm = service.createTerm(secondUser, term("第二", true));
        var firstRule = service.createRule(firstUser, directRule(firstTerm.termId()));
        var secondRule = service.createRule(secondUser, directRule(secondTerm.termId()));
        jdbc.update("UPDATE t_schedule_rule SET course_id=777 WHERE rule_id IN (?,?)",
                Long.parseLong(firstRule.ruleId()), Long.parseLong(secondRule.ruleId()));
        lifecycle.unbindDeletedCourse(new CourseDeletingEvent(firstUser, 777L));
        assertThat(service.rules(firstUser, Long.parseLong(firstTerm.termId())).get(0).courseId()).isNull();
        assertThat(service.rules(secondUser, Long.parseLong(secondTerm.termId())).get(0).courseId()).isEqualTo("777");
        cleanup.purge(firstUser);
        assertThat(service.terms(firstUser)).isEmpty();
        assertThat(service.terms(secondUser)).hasSize(1);
        assertThat(service.rules(secondUser, Long.parseLong(secondTerm.termId()))).hasSize(1);
    }

    @Test
    void concurrentActivationsLeaveExactlyOneActiveTerm() throws Exception {
        long user = user();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("甲", "乙").stream().map(name -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                return service.createTerm(user, term(name, true));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertThat(service.terms(user)).hasSize(2);
        assertThat(service.terms(user).stream().filter(TermView::active)).hasSize(1);
    }

    @Test
    void databaseRejectsHalfSpecifiedPeriodRange() {
        long user = user();
        TermView term = service.createTerm(user, term("约束", true));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO t_schedule_rule (user_id,term_id,title,weekday,start_time,end_time,start_period,end_period,weeks_json,created_at,updated_at) VALUES (?,?,'测试',1,'08:00:00','09:00:00',1,NULL,'[1]',NOW(3),NOW(3))", user, Long.parseLong(term.termId())))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .rootCause().hasMessageContaining("chk_schedule_rule");
    }

    @Test
    void termCalendarChangeRequiresConfirmationAndPreservesExceptionValidity() {
        long user = user();
        TermView original = service.createTerm(user, term("可调整", true));
        long termId = Long.parseLong(original.termId());
        RuleView rule = service.createRule(user, directRule(original.termId()));
        long currentVersion = service.terms(user).get(0).version();
        var changed = new TermWrite("可调整", LocalDate.of(2027, 1, 4), 16, "Asia/Shanghai",
                true, currentVersion, false);
        assertCode(() -> service.updateTerm(user, termId, changed), ErrorCode.STATE_CONFLICT);
        TermView saved = service.updateTerm(user, termId, new TermWrite(changed.name(), changed.firstMonday(),
                changed.weekCount(), changed.timezone(), changed.active(), currentVersion, true));
        assertThat(service.occurrences(user, termId, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 4)).occurrences())
                .hasSize(1);
        service.saveException(user, Long.parseLong(rule.ruleId()), LocalDate.of(2027, 1, 4),
                new ExceptionWrite("CANCEL", null, null, null, null, null, false));
        long latest = service.terms(user).get(0).version();
        assertCode(() -> service.updateTerm(user, termId, new TermWrite(saved.name(), LocalDate.of(2027, 1, 11),
                16, saved.timezone(), true, latest, true)), ErrorCode.STATE_CONFLICT);
    }

    @Test
    void courseDeletionInvalidatesConcurrentStalePeriodUpdate() throws Exception {
        long user = user();
        TermView term = service.createTerm(user, term("并发", true));
        long termId = Long.parseLong(term.termId());
        RuleView rule = service.createRule(user, directRule(term.termId()));
        jdbc.update("UPDATE t_schedule_rule SET course_id=888 WHERE rule_id=?", Long.parseLong(rule.ruleId()));
        TermView before = service.terms(user).get(0);
        var deletionHasLock = new CountDownLatch(1);
        var finishDeletion = new CountDownLatch(1);
        var updateStarting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                lifecycle.unbindDeletedCourse(new CourseDeletingEvent(user, 888L));
                deletionHasLock.countDown();
                try {
                    if (!finishDeletion.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(deletionHasLock.await(5, TimeUnit.SECONDS)).isTrue();
            var update = pool.submit(() -> {
                updateStarting.countDown();
                try {
                    service.savePeriods(user, termId, new PeriodsWrite(before.version(), List.of(), true));
                    return null;
                } catch (BusinessException e) { return e.getErrorCode(); }
            });
            assertThat(updateStarting.await(5, TimeUnit.SECONDS)).isTrue();
            finishDeletion.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.STATE_CONFLICT);
        }
    }

    private long user() { return configs.createUserAccount("schedule_" + System.nanoTime(), "hash", "Asia/Shanghai").getUserId(); }
    private static TermWrite term(String name, boolean active) {
        return new TermWrite(name, LocalDate.of(2026, 12, 28), 16, "Asia/Shanghai", active, null, false);
    }
    private static List<Period> periods(String s1, String e1, String s2, String e2) {
        return List.of(new Period(1, LocalTime.parse(s1), LocalTime.parse(e1)),
                new Period(2, LocalTime.parse(s2), LocalTime.parse(e2)));
    }
    private static RuleWrite directRule(String termId) {
        return new RuleWrite(termId, null, "课程", null, null, 1, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null, null, List.of(1), null, false);
    }
    private static void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
