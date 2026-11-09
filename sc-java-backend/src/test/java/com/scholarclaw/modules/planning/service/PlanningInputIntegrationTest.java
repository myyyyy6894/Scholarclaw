package com.scholarclaw.modules.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.*;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.ExceptionWrite;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.RuleWrite;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.TermWrite;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfSystemProperty(named = "sc.planning.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value", "sc.schedule.import.worker.enabled=false",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false",
        "sc.ddl.reminder.enabled=false", "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class PlanningInputIntegrationTest {
    private static final String TEST_STORAGE = Path.of(System.getProperty("java.io.tmpdir"),
            "scholarclaw-planning-test-" + UUID.randomUUID()).toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        registry.add("sc.storage.root", () -> TEST_STORAGE);
    }
    @Autowired PlanningInputService planning;
    @Autowired PlanningCleanupService cleanup;
    @Autowired UserConfigService users;
    @Autowired DdlTaskService ddl;
    @Autowired ScheduleService schedule;
    @Autowired PlanningSnapshotService snapshots;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean TokenRevocationService revocations;

    @Test
    void preferenceMustBeExplicitAndVersionsProtectEdits() {
        long user = user();
        assertThat(planning.preference(user)).isNull();
        var invalid = new PreferenceWrite("Asia/Shanghai", List.of(new Window(1, 480, 600), new Window(1, 599, 720)),
                50, 180, 10, null);
        assertCode(() -> planning.savePreference(user, invalid), ErrorCode.VALIDATION_ERROR);
        var created = planning.savePreference(user, preference(null));
        assertThat(created.windows()).containsExactly(new Window(1, 480, 600), new Window(1, 600, 720));
        assertCode(() -> planning.savePreference(user, preference(null)), ErrorCode.STATE_CONFLICT);
        var updated = planning.savePreference(user, preference(created.version()));
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertCode(() -> planning.savePreference(user, preference(created.version())), ErrorCode.STATE_CONFLICT);
        assertCode(() -> planning.savePreference(user, new PreferenceWrite("+08:00", List.of(new Window(1, 0, 1440)),
                50, 180, 0, updated.version())), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void eventOwnerVersionAndCleanupAreEnforced() {
        long owner = user(), other = user();
        var created = planning.createEvent(owner, new EventWrite("睡眠", "Asia/Shanghai", LocalDate.of(2026, 11, 9),
                null, 1, 0, 420, true, null));
        long id = Long.parseLong(created.eventId());
        assertCode(() -> planning.updateEvent(other, id, event(created.version())), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> planning.deleteEvent(owner, id, created.version() + 1), ErrorCode.STATE_CONFLICT);
        var updated = planning.updateEvent(owner, id, event(created.version()));
        assertThat(updated.endDate()).isEqualTo(updated.startDate());
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertCode(() -> planning.createEvent(owner, new EventWrite("跨日", "Asia/Shanghai", LocalDate.of(2026, 11, 9),
                null, null, 1320, 420, true, null)), ErrorCode.VALIDATION_ERROR);
        planning.savePreference(owner, preference(null));
        cleanup.purge(owner);
        assertThat(planning.events(owner)).isEmpty();
        assertThat(planning.preference(owner)).isNull();
    }

    @Test
    void profileIsOwnedAndStoresAbsoluteTimeWithoutChangingDeadline() throws Exception {
        long owner = user(), other = user();
        var task = task(owner);
        long taskId = task.taskId();
        var input = new ProfileWrite(180, 120, 2, OffsetDateTime.parse("2026-11-09T09:00:00-05:00"), true, 30, null);
        assertCode(() -> planning.saveProfile(other, taskId, input), ErrorCode.RESOURCE_NOT_FOUND);
        var saved = planning.saveProfile(owner, taskId, input);
        assertThat(saved.taskId()).isEqualTo(Long.toString(taskId));
        assertThat(saved.earliestStartAt()).isEqualTo("2026-11-09T14:00Z");
        assertThat(ddl.getTask(owner, taskId).dueAt()).isEqualTo(task.dueAt());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> updateProfile(owner, taskId, saved.version(), start));
            var second = pool.submit(() -> updateProfile(owner, taskId, saved.version(), start));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "conflict");
        }
        assertThat(ddl.getTask(owner, taskId).dueAt()).isEqualTo(task.dueAt());
        cleanup.purge(owner);
        assertThat(planning.profiles(owner)).isEmpty();
        assertThat(ddl.getTask(owner, taskId)).isNotNull();
    }

    @Test
    void endpointsRequireJwtAndReturnStringIds() throws Exception {
        var account = users.createUserAccount("plan_web_" + UUID.randomUUID().toString().replace("-", ""), "hash", "Asia/Shanghai");
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String token = jwt.issue(account.getUserId(), account.getUsername(), "STUDENT").token();
        mvc.perform(get("/api/v1/planning/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/planning/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"固定日程","timezone":"Asia/Shanghai","start_date":"2026-11-09",
                                 "start_minute":480,"end_minute":540,"hard":true}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.event_id").isString());
    }

    @Test
    void snapshotCombinesOwnedCalendarEventsAndTasksAndChangesWhenInputsChange() {
        long owner = user(), other = user();
        LocalDate today = LocalDate.now(ScTimeZone.zoneId());
        int weekday = today.getDayOfWeek().getValue();
        planning.savePreference(owner, new PreferenceWrite("Asia/Shanghai", List.of(new Window(weekday, 540, 1020)),
                50, 180, 10, null));
        var hard = planning.createEvent(owner, new EventWrite("会议", "Asia/Shanghai", today, null, null, 720, 780, true, null));
        planning.createEvent(owner, new EventWrite("可调整", "Asia/Shanghai", today, null, null, 840, 900, false, null));
        var term = schedule.createTerm(owner, new TermWrite("规划测试", today.minusDays(weekday - 1L), 8,
                "Asia/Shanghai", true, null, false));
        var course = schedule.createRule(owner, new RuleWrite(term.termId(), null, "测试课程", null, null, weekday,
                LocalTime.of(10, 0), LocalTime.of(11, 0), null, null, List.of(1), null, false));
        var task = task(owner);
        task(owner); // 未配置工时的任务必须显式报告，而不是猜测。
        planning.saveProfile(owner, task.taskId(), new ProfileWrite(120, 90, 1, null, true, 30, null));
        planning.savePreference(other, new PreferenceWrite("Asia/Shanghai", List.of(new Window(weekday, 540, 1020)),
                50, 180, 0, null));

        var snapshot = snapshots.read(owner, today, today.plusDays(30)); // 课表请求跨31天需安全分片。
        assertThat(snapshot.hasActiveTerm()).isTrue();
        assertThat(snapshot.termId()).isEqualTo(term.termId());
        assertThat(snapshot.occupied()).contains(
                new PlanningEngine.Interval(today.atTime(9, 50).atZone(ScTimeZone.zoneId()).toInstant(),
                        today.atTime(11, 10).atZone(ScTimeZone.zoneId()).toInstant()),
                new PlanningEngine.Interval(today.atTime(11, 50).atZone(ScTimeZone.zoneId()).toInstant(),
                        today.atTime(13, 10).atZone(ScTimeZone.zoneId()).toInstant()));
        assertThat(snapshot.softEvents()).singleElement().satisfies(event -> assertThat(event.title()).isEqualTo("可调整"));
        assertThat(snapshot.tasks()).singleElement().satisfies(value -> assertThat(value.id()).isEqualTo(task.taskId().toString()));
        assertThat(snapshot.unavailableTasks()).singleElement().satisfies(value -> assertThat(value.reason()).contains("工时"));
        assertThat(snapshots.read(owner, today, today.plusDays(30)).fingerprint()).isEqualTo(snapshot.fingerprint());
        var otherSnapshot = snapshots.read(other, today, today.plusDays(30));
        assertThat(otherSnapshot.tasks()).isEmpty();
        assertThat(otherSnapshot.occupied()).isEmpty();
        assertThat(otherSnapshot.hasActiveTerm()).isFalse();

        schedule.saveException(owner, Long.parseLong(course.ruleId()), today, new ExceptionWrite("CANCEL", null, null, null, null, null, false));
        var afterCancel = snapshots.read(owner, today, today.plusDays(30));
        assertThat(afterCancel.fingerprint()).isNotEqualTo(snapshot.fingerprint());
        assertThat(afterCancel.occupied()).hasSize(1);
        planning.updateEvent(owner, Long.parseLong(hard.eventId()), new EventWrite("会议修改", "Asia/Shanghai", today,
                null, null, 720, 780, true, hard.version()));
        assertThat(snapshots.read(owner, today, today.plusDays(30)).fingerprint()).isNotEqualTo(afterCancel.fingerprint());
    }

    private String updateProfile(long user, long task, long version, CountDownLatch start) throws InterruptedException {
        start.await();
        try { planning.saveProfile(user, task, new ProfileWrite(180, 90, 2, null, true, 30, version)); return "ok"; }
        catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.STATE_CONFLICT) return "conflict";
            throw e;
        }
    }
    private com.scholarclaw.modules.ddl.dto.DdlTaskVO task(long user) {
        return ddl.createManualTask(user, new CreateDdlTaskRequest("plan_" + UUID.randomUUID(), null, "测试课程", "测试任务",
                "DATE_ONLY", LocalDate.now(ScTimeZone.zoneId()).plusDays(14).toString(), null, null, null)).task();
    }
    private long user() { return users.createUserAccount("plan_" + UUID.randomUUID().toString().replace("-", ""), "hash", "Asia/Shanghai").getUserId(); }
    private static PreferenceWrite preference(Long version) {
        return new PreferenceWrite("Asia/Shanghai", List.of(new Window(1, 600, 720), new Window(1, 480, 600)), 50, 180, 10, version);
    }
    private static EventWrite event(Long version) {
        return new EventWrite("临时会议", "Asia/Shanghai", LocalDate.of(2026, 11, 10), null, null, 600, 660, true, version);
    }
    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
