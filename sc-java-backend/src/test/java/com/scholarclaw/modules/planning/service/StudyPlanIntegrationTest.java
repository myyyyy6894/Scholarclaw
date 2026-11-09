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
import com.scholarclaw.modules.ddl.service.DdlDraftStore;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.*;
import com.scholarclaw.modules.planning.dto.StudyPlanDtos.*;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.nio.file.Path;
import java.time.LocalDate;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "sc.planning.integration", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value", "sc.schedule.import.worker.enabled=false",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false", "sc.ddl.reminder.enabled=false",
        "sc.deletion-gc.enabled=false", "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class StudyPlanIntegrationTest {
    private static final String TEST_STORAGE = Path.of(System.getProperty("java.io.tmpdir"),
            "scholarclaw-study-plan-test-" + UUID.randomUUID()).toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        registry.add("sc.storage.root", () -> TEST_STORAGE);
    }
    @Autowired StudyPlanService plans;
    @Autowired PlanningInputService input;
    @Autowired PlanningCleanupService cleanup;
    @Autowired DdlTaskService ddl;
    @Autowired UserConfigService users;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean TokenRevocationService revocations;
    @MockBean DdlDraftStore draftStore;

    @Test
    void creationAndAcceptanceAreIdempotentAndNeverChangeDeadline() {
        long owner = user(), other = user();
        var task = setup(owner);
        LocalDate day = tomorrow();
        var request = new CreatePlan(day, day, "plan-create-1", true);
        var draft = plans.create(owner, request);
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThat(draft.warnings()).anyMatch(warning -> warning.contains("未检查课表"));
        assertThat(draft.blocks()).hasSize(2);
        assertThat(plans.create(owner, request).planId()).isEqualTo(draft.planId());
        assertCode(() -> plans.create(owner, new CreatePlan(day, day.plusDays(1), "plan-create-1", true)), ErrorCode.IDEMPOTENCY_CONFLICT);
        assertCode(() -> plans.get(other, Long.parseLong(draft.planId())), ErrorCode.RESOURCE_NOT_FOUND);
        var selected = List.of(draft.blocks().getFirst().blockId());
        var acceptance = new AcceptPlan(draft.revision(), "plan-accept-1", selected);
        var accepted = plans.accept(owner, Long.parseLong(draft.planId()), acceptance);
        assertThat(accepted.state()).isEqualTo("ACCEPTED");
        assertThat(accepted.blocks()).extracting(BlockView::state).containsExactly("PLANNED", "CANCELED");
        assertThat(plans.accept(owner, Long.parseLong(draft.planId()), acceptance).planId()).isEqualTo(draft.planId());
        assertCode(() -> plans.accept(owner, Long.parseLong(draft.planId()), new AcceptPlan(draft.revision(),
                "plan-accept-1", List.of(draft.blocks().getLast().blockId()))), ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(ddl.getTask(owner, task.taskId()).dueAt()).isEqualTo(task.dueAt());

        var next = plans.create(owner, new CreatePlan(day, day, "plan-create-2", true));
        assertThat(next.blocks()).singleElement().satisfies(block -> {
            assertThat(block.startAt()).isEqualTo(accepted.blocks().getFirst().endAt());
            assertThat(block.endAt()).isEqualTo(draft.blocks().getLast().endAt());
        });
        cleanup.purge(owner);
        assertCode(() -> plans.get(owner, Long.parseLong(draft.planId())), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ddl.getTask(owner, task.taskId())).isNotNull();
    }

    @Test
    void twoDraftsCannotAcceptOverlappingBlocksConcurrently() throws Exception {
        long owner = user(); setup(owner);
        LocalDate day = tomorrow();
        var first = plans.create(owner, new CreatePlan(day, day, "parallel-create-1", true));
        var second = plans.create(owner, new CreatePlan(day, day, "parallel-create-2", true));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> acceptConcurrent(owner, first, "parallel-accept-1", start));
            var b = pool.submit(() -> acceptConcurrent(owner, second, "parallel-accept-2", start));
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "conflict");
        }
    }

    @Test
    void changingAnInputMakesTheDraftStaleAndRejectionPreservesIt() {
        long owner = user(); setup(owner);
        LocalDate day = tomorrow();
        assertCode(() -> plans.create(owner, new CreatePlan(day, day, "no-term", false)), ErrorCode.VALIDATION_ERROR);
        var draft = plans.create(owner, new CreatePlan(day, day, "stale-create", true));
        input.createEvent(owner, new EventWrite("新日程", "Asia/Shanghai", day, null, null, 600, 660, true, null));
        assertThat(plans.get(owner, Long.parseLong(draft.planId())).stale()).isTrue();
        assertCode(() -> plans.accept(owner, Long.parseLong(draft.planId()), new AcceptPlan(draft.revision(), "stale-accept",
                List.of(draft.blocks().getFirst().blockId()))), ErrorCode.STATE_CONFLICT);
        assertThat(plans.get(owner, Long.parseLong(draft.planId())).blocks()).allMatch(block -> block.state().equals("PROPOSED"));
        plans.cancelDraft(owner, Long.parseLong(draft.planId()), draft.revision());
        assertThat(plans.get(owner, Long.parseLong(draft.planId())).state()).isEqualTo("CANCELED");
    }

    @Test
    void apiRequiresJwtAndReturnsStringPlanAndBlockIds() throws Exception {
        var account = users.createUserAccount("plan_http_" + UUID.randomUUID().toString().replace("-", ""), "hash", "Asia/Shanghai");
        setup(account.getUserId());
        when(revocations.check(anyLong(), any())).thenReturn(new TokenRevocationService.TokenStatus(false, false));
        String token = jwt.issue(account.getUserId(), account.getUsername(), "STUDENT").token();
        String body = "{\"from_date\":\"" + tomorrow() + "\",\"to_date\":\"" + tomorrow()
                + "\",\"client_request_id\":\"http-plan\",\"confirm_no_active_term\":true}";
        mvc.perform(post("/api/v1/planning/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/planning/plans").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_id").isString())
                .andExpect(jsonPath("$.data.blocks[0].block_id").isString());
    }

    @Test
    void profileCannotCommitAfterConcurrentTaskCancellation() throws Exception {
        long owner = user();
        var task = setup(owner);
        var profile = input.profiles(owner).getFirst();
        CountDownLatch canceledInTransaction = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancel = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                ddl.cancelTask(owner, task.taskId());
                canceledInTransaction.countDown();
                try { if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("超时"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return null;
            }));
            assertThat(canceledInTransaction.await(10, TimeUnit.SECONDS)).isTrue();
            var save = pool.submit(() -> {
                try {
                    input.saveProfile(owner, task.taskId(), new ProfileWrite(120, 80, 2, null, true, 30, profile.version()));
                    return "saved";
                } catch (BusinessException e) { return e.getErrorCode().name(); }
            });
            allowCommit.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            assertThat(save.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
        }
        assertThat(input.profiles(owner).getFirst().remainingMinutes()).isEqualTo(120);
    }

    private String acceptConcurrent(long user, PlanView plan, String request, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            plans.accept(user, Long.parseLong(plan.planId()), new AcceptPlan(plan.revision(), request,
                    plan.blocks().stream().map(BlockView::blockId).toList()));
            return "ok";
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.STATE_CONFLICT) return "conflict";
            throw e;
        }
    }
    private com.scholarclaw.modules.ddl.dto.DdlTaskVO setup(long user) {
        LocalDate day = tomorrow();
        input.savePreference(user, new PreferenceWrite("Asia/Shanghai", List.of(new Window(day.getDayOfWeek().getValue(), 540, 720)),
                60, 180, 0, null));
        var task = ddl.createManualTask(user, new CreateDdlTaskRequest("plan-task-" + UUID.randomUUID(), null, "测试课程", "测试任务",
                "DATE_ONLY", day.plusDays(4).toString(), null, null, null)).task();
        input.saveProfile(user, task.taskId(), new ProfileWrite(120, 120, 2, null, true, 30, null));
        return task;
    }
    private long user() { return users.createUserAccount("study_" + UUID.randomUUID().toString().replace("-", ""), "hash", "Asia/Shanghai").getUserId(); }
    private static LocalDate tomorrow() { return LocalDate.now(ScTimeZone.zoneId()).plusDays(1); }
    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
