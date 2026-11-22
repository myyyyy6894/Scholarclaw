package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage.QqSession;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.channel.service.QqBotChannelAdapter;
import com.scholarclaw.modules.channel.service.QqBotConnection.InboundMessage;
import com.scholarclaw.modules.channel.service.QqBotSender;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.RuleWrite;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.TermWrite;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@EnabledIfSystemProperty(named = "sc.qq.natural.integration", matches = "true")
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false",
        "sc.schedule.reminder.scan-enabled=false", "sc.schedule.import.worker.enabled=false",
        "sc.ddl.reminder.enabled=false", "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class QqNaturalLanguageIntegrationTest {
    private static final String REFERENCE = "2026-11-23T19:00:00+08:00";
    private static final Pattern TOKEN = Pattern.compile("确认 ([A-F0-9]{6})");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
        if (!"16386".equals(System.getProperty("sc.test.redis.port"))) {
            throw new IllegalStateException("Explicit isolated Redis port 16386 required");
        }
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 16386);
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired AgentOrchestratorService agent;
    @Autowired QqBotChannelAdapter adapter;
    @Autowired ScheduleReminderService reminders;
    @Autowired ScheduleService schedules;
    @Autowired DdlTaskService tasks;
    @Autowired UserConfigService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @MockitoBean PythonAiEngineClient python;
    @MockitoBean QqBotSender sender;
    @MockitoSpyBean DdlUserConfigResolver config;

    @BeforeEach
    void stubRoutesFromSameCorpus() throws Exception {
        var corpus = mapper.readTree(Path.of("..", "sc-python-ai", "tests", "fixtures", "qq_natural_corpus.json").toFile());
        doReturn(new ModelEndpoint("OLLAMA", "http://localhost:11434", null, "fixture"))
                .when(config).resolveChatEndpoint(any());
        when(python.postJson(eq(PythonAiEngineClient.AGENT_INTERPRET_PATH), any(), anyLong(), any(Duration.class)))
                .thenAnswer(call -> {
                    String text = call.getArgument(1, Map.class).get("raw_text").toString();
                    JsonNode item = example(corpus, text);
                    if (item == null) throw new BusinessException(com.scholarclaw.common.exception.ErrorCode.UPSTREAM_TIMEOUT);
                    var output = mapper.createObjectNode().put("action", item.path("action").asText()).put("confidence", 0.93);
                    output.set("arguments", item.path("arguments"));
                    output.set("missing", item.path("missing"));
                    output.putArray("evidence").add(text);
                    return output;
                });
        when(python.postJson(eq(PythonAiEngineClient.ROUTE_AND_EXTRACT_PATH), any(), anyLong(), any(Duration.class)))
                .thenAnswer(call -> {
                    String text = call.getArgument(1, Map.class).get("raw_text").toString();
                    JsonNode item = example(corpus, text);
                    return mapper.createObjectNode().put("intent", item == null ? "CHITCHAT_HELP" : item.path("legacy").asText())
                            .put("intent_confidence", 0.93);
                });
    }

    @Test
    void realTimetableAndLegacyComparisonWithVerifiedChannel() {
        var user = owner();
        var term = schedules.createTerm(user.id(), new TermWrite("测试学期", MONDAY, 16, "Asia/Shanghai", true, null, false));
        schedules.createRule(user.id(), new RuleWrite(term.termId(), null, "数据结构", null, "A201",
                2, LocalTime.of(8, 0), LocalTime.of(9, 0), null, null, List.of(1), null, false));
        schedules.createRule(user.id(), new RuleWrite(term.termId(), null, "离散数学", null, "B305",
                2, LocalTime.of(8, 0), LocalTime.of(8, 45), null, null, List.of(1), null, true));
        String query = "明早第一节在哪";
        var old = agent.handle(new AgentInboundMessage(user.id(), "QQ_OFFICIAL", "old-message", query, REFERENCE, null, false));
        assertThat(old.action()).isEqualTo(AgentToolAction.HELP);
        var current = agent.handle(message(user, query));
        assertThat(current.action()).isEqualTo(AgentToolAction.SCHEDULE_QUERY);
        assertThat(current.replyText()).contains("08:00", "数据结构", "A201", "离散数学", "B305", "撞课");
        assertThat(current.requiresConfirmation()).isFalse();

        // An explicit time-qualified query reaches the real channel's reply adapter without a real send.
        adapter.handle(new InboundMessage(user.id(), user.session().appId(), user.session().bindingVersion(),
                user.session().openid(), "2026-11-24第一节在哪", "adapter-smoke", false, null));
        verify(sender).reply(eq(user.id()), eq(user.session().appId()), eq(user.session().bindingVersion()),
                eq(user.session().openid()), eq("adapter-smoke"), eq(1), contains("A201"));
    }

    @Test
    void reminderRequiresOneTimeCodeAndChecksNegationAndPreferenceVersion() {
        var user = owner();
        String text = "以后上课提前二十分钟提醒我";
        var draft = agent.handle(message(user, text));
        assertThat(draft.action()).isEqualTo(AgentToolAction.REMINDER_SET);
        assertThat(draft.requiresConfirmation()).isTrue();
        assertThat(reminders.preference(user.id()).enabled()).isFalse();
        assertThat(agent.handle(message(user, "确认")).replyText()).contains("本轮回显");
        var confirmation = message(user, confirmation(draft));
        assertThat(agent.handle(confirmation).action()).isEqualTo(AgentToolAction.REMINDER_SET);
        assertThat(reminders.preference(user.id()).enabled()).isTrue();
        assertThat(agent.handle(confirmation).replyText()).contains("已处理");
        assertThat(agent.handle(message(user, "别关上课提醒，我只是不想收到作业通知")).action()).isEqualTo(AgentToolAction.IGNORE);
        assertThat(reminders.preference(user.id()).version()).isOne();

        var disable = agent.handle(message(user, "关闭课前提醒"));
        assertThat(disable.requiresConfirmation()).isTrue();
        reminders.setPreference(user.id(), new ScheduleReminderService.PreferenceWrite(false, 1L));
        reminders.setPreference(user.id(), new ScheduleReminderService.PreferenceWrite(true, 2L));
        assertThatThrownBy(() -> agent.handle(message(user, confirmation(disable))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("偏好已变化");
        assertThat(reminders.preference(user.id()).enabled()).isTrue();
    }

    @Test
    void rescheduleClarifiesCandidateAndExactTimeAndSupportsReference() {
        var user = owner();
        var first = task(user, "数据库作业甲");
        var second = task(user, "数据库作业乙");
        String text = "把数据库作业挪到周五晚上";
        var old = agent.handle(new AgentInboundMessage(user.id(), "QQ_OFFICIAL", "old-update", text, REFERENCE, null, false));
        assertThat(old.action()).isEqualTo(AgentToolAction.CLARIFY);
        var select = agent.handle(message(user, text));
        assertThat(select.replyText()).contains(first.taskTitle(), second.taskTitle(), "回复序号");
        var question = agent.handle(message(user, "刚才第二个，只这一次"));
        assertThat(question.replyText()).contains(second.taskTitle(), "几点截止");
        assertThat(tasks.getTask(user.id(), second.taskId()).dueDate()).isEqualTo(second.dueDate());
        var draft = agent.handle(message(user, "晚上8点半"));
        assertThat(draft.replyText()).contains("2026-11-27 20:30", second.taskTitle(), "Asia/Shanghai");
        assertThat(draft.requiresConfirmation()).isTrue();
        assertThat(tasks.getTask(user.id(), second.taskId()).dueDate()).isEqualTo(second.dueDate());
        assertThat(agent.handle(message(user, confirmation(draft))).action()).isEqualTo(AgentToolAction.TASK_UPDATE);
        assertThat(tasks.getTask(user.id(), second.taskId()).dueDate()).isEqualTo("2026-11-27");
        assertThat(tasks.getTask(user.id(), first.taskId()).dueDate()).isEqualTo(first.dueDate());
        var done = agent.handle(message(user, "把刚才那个标完成"));
        assertThat(done.requiresConfirmation()).isTrue();
        assertThat(done.task().taskId()).isEqualTo(second.taskId());
        agent.handle(message(user, confirmation(done)));
        assertThat(tasks.getTask(user.id(), second.taskId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void staleTaskOtherAccountOtherBindingAndExpiredContextCannotWrite() {
        var user = owner();
        var other = owner();
        var own = task(user, "数据库作业");
        var draft = prepareDate(user);
        var foreign = agent.handle(message(other, confirmation(draft)));
        assertThat(foreign.replyText()).contains("过期");
        jdbc.update("UPDATE t_ddl_task SET updated_at=DATE_ADD(updated_at,INTERVAL 1 SECOND) WHERE task_id=?", own.taskId());
        var staleConfirmation = message(user, confirmation(draft));
        assertThatThrownBy(() -> agent.handle(staleConfirmation))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务在校对后已变化");
        assertThat(tasks.getTask(user.id(), own.taskId()).dueDate()).isEqualTo(own.dueDate());

        draft = prepareDate(user);
        var previous = message(user, confirmation(draft));
        jdbc.update("UPDATE t_user_config SET qqbot_binding_version=8 WHERE user_id=?", user.id());
        var changed = new Owner(user.id(), new QqSession(user.session().appId(), 8L, user.session().openid()));
        assertThat(agent.handle(message(changed, confirmation(draft))).replyText()).contains("过期");
        assertThatThrownBy(() -> agent.handle(previous)).isInstanceOf(BusinessException.class).hasMessageContaining("绑定已变化");
        assertThat(tasks.getTask(user.id(), own.taskId()).dueDate()).isEqualTo(own.dueDate());

        var expired = prepareDate(changed);
        String key = QqConversationStore.scope(message(changed, "test")) + ":turn";
        assertThat(redis.getExpire(key)).isBetween(1L, 300L);
        redis.expire(key, Duration.ZERO);
        assertThat(agent.handle(message(changed, confirmation(expired))).replyText()).contains("过期");
        assertThat(tasks.getTask(user.id(), own.taskId()).dueDate()).isEqualTo(own.dueDate());
    }

    @Test
    void slashCommandsRemainAvailableAndBatchConfirmationIsScoped() {
        var user = owner();
        var first = task(user, "说明一");
        var second = task(user, "说明二");
        assertThat(agent.handle(message(user, "/list")).action()).isEqualTo(AgentToolAction.TASK_LIST);
        var draft = agent.handle(message(user, "/done T" + first.taskId() + " T" + second.taskId()));
        assertThat(draft.requiresConfirmation()).isTrue();
        assertThat(agent.handle(message(user, "确认")).replyText()).contains("全部标记完成");
        assertThat(tasks.getTask(user.id(), first.taskId()).status()).isEqualTo("COMPLETED");
        assertThat(tasks.getTask(user.id(), second.taskId()).status()).isEqualTo("COMPLETED");
        verifyNoInteractions(python);
    }

    private AgentReply prepareDate(Owner owner) {
        assertThat(agent.handle(message(owner, "把数据库作业挪到周五晚上")).replyText()).contains("几点截止");
        return agent.handle(message(owner, "20:00"));
    }

    private Owner owner() {
        long id = users.createUserAccount("qq_intent_" + System.nanoTime(), "hash", "Asia/Shanghai").getUserId();
        String app = Long.toString(8_000_000 + id);
        String openid = "assistant-fixture-" + id;
        jdbc.update("""
                UPDATE t_user_config SET qqbot_app_id=?,qqbot_openid=?,qqbot_binding_version=7,
                  qqbot_binding_verified=1,qqbot_secret_cipher='fixture' WHERE user_id=?
                """, app, openid, id);
        return new Owner(id, new QqSession(app, 7L, openid));
    }

    private DdlTaskVO task(Owner user, String title) {
        return tasks.createManualTask(user.id(), new CreateDdlTaskRequest(UUID.randomUUID().toString(), null,
                "测试课程", title, "EXACT_DATETIME", "2026-12-04", "21:00:00", null, null)).task();
    }

    private AgentInboundMessage message(Owner user, String text) {
        return new AgentInboundMessage(user.id(), "QQ_OFFICIAL", UUID.randomUUID().toString(), text,
                REFERENCE, null, false, user.session());
    }

    private static String confirmation(AgentReply draft) {
        var match = TOKEN.matcher(draft.replyText());
        if (!match.find()) throw new AssertionError("Expected confirmation token");
        return match.group();
    }

    private static JsonNode example(JsonNode corpus, String text) {
        for (JsonNode item : corpus) if (item.path("text").asText().equals(text)) return item;
        return null;
    }

    private record Owner(long id, QqSession session) { }
}
