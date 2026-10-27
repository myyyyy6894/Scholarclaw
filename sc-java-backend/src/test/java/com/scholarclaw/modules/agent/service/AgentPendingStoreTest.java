package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.PendingAction;
import com.scholarclaw.modules.agent.dto.PendingIntent;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** TASK-706 待澄清意图 / 待确认动作的 Redis 存储。 */
class AgentPendingStoreTest {

    private static final long USER_ID = 168L;
    private static final String INTENT_KEY = "sc:u:168:pending_intent";
    private static final String ACTION_KEY = "sc:u:168:pending_action";

    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private PendingIntentStore intentStore;
    private PendingActionStore actionStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(),
                ZoneId.of("Asia/Shanghai")).build();
        intentStore = new PendingIntentStore(redisTemplate, objectMapper);
        actionStore = new PendingActionStore(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("pending_intent：键名、30 分钟 TTL、JSON 含原文")
    void savesPendingIntent() {
        intentStore.save(USER_ID, new PendingIntent("帮我看看这个", "2026-10-25T12:00:00+08:00",
                "QQ_ONEBOT", "msg_1", "2001", "2026-10-25T12:00:00+08:00"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(INTENT_KEY), json.capture(), eq(Duration.ofMinutes(30)));
        assertThat(json.getValue())
                .contains("\"raw_text\":\"帮我看看这个\"")
                .contains("\"platform\":\"QQ_ONEBOT\"")
                .contains("\"source_msg_id\":\"msg_1\"");
    }

    @Test
    @DisplayName("pending_intent：可读；缺失/损坏返回空；Redis 故障读取为空")
    void loadsPendingIntent() throws Exception {
        PendingIntent intent = new PendingIntent("原文", "2026-10-25T12:00:00+08:00",
                "WEB", "req_1", null, "now");
        when(valueOperations.get(INTENT_KEY))
                .thenReturn(objectMapper.writeValueAsString(intent));
        Optional<PendingIntent> loaded = intentStore.load(USER_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().rawText()).isEqualTo("原文");

        when(valueOperations.get(INTENT_KEY)).thenReturn(null);
        assertThat(intentStore.load(USER_ID)).isEmpty();
        when(valueOperations.get(INTENT_KEY)).thenReturn("not-json");
        assertThat(intentStore.load(USER_ID)).isEmpty();
        when(valueOperations.get(INTENT_KEY)).thenThrow(new RuntimeException("redis down"));
        assertThat(intentStore.load(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("pending_intent 写入失败：失败关闭为 INTERNAL_ERROR")
    void pendingIntentSaveFailsClosed() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> intentStore.save(USER_ID, new PendingIntent(
                "原文", "2026-10-25T12:00:00+08:00", "WEB", "req_1", null, "now")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("pending_action：键名、10 分钟 TTL、动作与参数可读")
    void savesAndLoadsPendingAction() throws Exception {
        PendingAction action = PendingAction.update(102L, LocalDate.of(2026, 10, 25),
                LocalTime.of(22, 0), "改到 2026-10-25 22:00", "now");
        actionStore.save(USER_ID, action);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(ACTION_KEY), json.capture(), eq(Duration.ofMinutes(10)));
        assertThat(json.getValue())
                .contains("\"action\":\"TASK_UPDATE\"")
                .contains("\"task_id\":\"102\"")
                .contains("\"due_date\":\"2026-10-25\"");

        when(valueOperations.get(ACTION_KEY))
                .thenReturn(objectMapper.writeValueAsString(action));
        Optional<PendingAction> loaded = actionStore.load(USER_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().taskId()).isEqualTo(102L);
        assertThat(loaded.get().action()).isEqualTo(AgentToolAction.TASK_UPDATE);

        actionStore.clear(USER_ID);
        verify(valueOperations).get(ACTION_KEY);
    }
}
