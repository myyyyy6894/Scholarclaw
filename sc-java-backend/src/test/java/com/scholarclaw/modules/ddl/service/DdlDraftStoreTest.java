package com.scholarclaw.modules.ddl.service;

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
import com.scholarclaw.modules.ddl.service.DdlDraftStore.LockState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** TASK-704 Redis 幂等锁与变更草案存储。 */
class DdlDraftStoreTest {

    private static final long USER_ID = 10001L;
    private static final long TASK_ID = 103L;
    private static final String CLIENT_REQUEST_ID = "req_extract_001";
    private static final String LOCK_KEY = "sc:idemp:msg:WEB:10001:req_extract_001";
    private static final String DRAFT_KEY = "sc:u:10001:task_draft:103";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration PROCESSED_TTL = Duration.ofHours(24);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private DdlDraftStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(),
                ZoneId.of("Asia/Shanghai")).build();
        store = new DdlDraftStore(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("SET NX 成功 → ACQUIRED，键格式与 PROCESSING TTL 正确")
    void acquiresLock() {
        when(valueOperations.setIfAbsent(LOCK_KEY, "PROCESSING", PROCESSING_TTL))
                .thenReturn(true);

        assertThat(store.acquireWebRequest(USER_ID, CLIENT_REQUEST_ID))
                .isEqualTo(LockState.ACQUIRED);
    }

    @Test
    @DisplayName("已存在 PROCESSING → PROCESSING；PROCESSED → PROCESSED")
    void reportsExistingLockState() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSING");
        assertThat(store.acquireWebRequest(USER_ID, CLIENT_REQUEST_ID))
                .isEqualTo(LockState.PROCESSING);

        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSED");
        assertThat(store.acquireWebRequest(USER_ID, CLIENT_REQUEST_ID))
                .isEqualTo(LockState.PROCESSED);
    }

    @Test
    @DisplayName("未知锁值：删除后重试一次")
    void retriesUnknownLockValue() {
        when(valueOperations.setIfAbsent(LOCK_KEY, "PROCESSING", PROCESSING_TTL))
                .thenReturn(false, true);
        when(valueOperations.get(LOCK_KEY)).thenReturn("UNKNOWN");

        assertThat(store.acquireWebRequest(USER_ID, CLIENT_REQUEST_ID))
                .isEqualTo(LockState.ACQUIRED);
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("Redis 异常时降级放行（数据库唯一键兜底）")
    void failsOpenOnRedisError() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(store.acquireWebRequest(USER_ID, CLIENT_REQUEST_ID))
                .isEqualTo(LockState.ACQUIRED);
    }

    @Test
    @DisplayName("markProcessed 写 PROCESSED + 24h；release 删除键")
    void marksProcessedAndReleases() {
        store.markProcessed(USER_ID, CLIENT_REQUEST_ID);
        verify(valueOperations).set(LOCK_KEY, "PROCESSED", PROCESSED_TTL);

        store.release(USER_ID, CLIENT_REQUEST_ID);
        verify(redisTemplate).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("草案写入使用 24h TTL，JSON 为 snake_case")
    void savesDraftWithTtl() {
        store.saveDraft(USER_ID, draft());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(DRAFT_KEY), json.capture(), eq(Duration.ofHours(24)));
        assertThat(json.getValue()).contains("\"target_task_id\":103")
                .contains("\"due_at\":\"2099-12-31T22:00\"")
                .contains("\"user_timezone\":\"Asia/Shanghai\"");
    }

    @Test
    @DisplayName("草案读取反序列化；不存在返回空")
    void loadsDraft() throws Exception {
        when(valueOperations.get(DRAFT_KEY))
                .thenReturn(objectMapper.writeValueAsString(draft()));

        Optional<DdlTaskDraft> loaded = store.loadDraft(USER_ID, TASK_ID);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().targetTaskId()).isEqualTo(TASK_ID);
        assertThat(loaded.get().courseName()).isEqualTo("操作系统原理");

        when(valueOperations.get(DRAFT_KEY)).thenReturn(null);
        assertThat(store.loadDraft(USER_ID, TASK_ID)).isEmpty();
    }

    @Test
    @DisplayName("草案 JSON 损坏：删除并按过期处理")
    void corruptDraftIsDeleted() {
        when(valueOperations.get(DRAFT_KEY)).thenReturn("not-json");

        assertThat(store.loadDraft(USER_ID, TASK_ID)).isEmpty();
        verify(redisTemplate).delete(DRAFT_KEY);
    }

    @Test
    @DisplayName("Redis 读取异常：失败关闭为 INTERNAL_ERROR")
    void loadFailsClosedOnRedisError() {
        when(valueOperations.get(DRAFT_KEY)).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> store.loadDraft(USER_ID, TASK_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("Redis 写入异常：失败关闭为 INTERNAL_ERROR")
    void saveFailsClosedOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.saveDraft(USER_ID, draft()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("列出用户草案：SCAN 命中并反序列化；Redis 故障返回空列表")
    @SuppressWarnings("unchecked")
    void listsDrafts() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(DRAFT_KEY);
        when(valueOperations.get(DRAFT_KEY))
                .thenReturn(objectMapper.writeValueAsString(draft()));

        assertThat(store.listDrafts(USER_ID)).hasSize(1);

        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThat(store.listDrafts(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("无事务时 deleteDraftAfterCommit 立即删除")
    void deleteAfterCommitWithoutTransactionDeletesNow() {
        store.deleteDraftAfterCommit(USER_ID, TASK_ID);

        verify(redisTemplate).delete(DRAFT_KEY);
    }

    private static DdlTaskDraft draft() {
        return new DdlTaskDraft(
                TASK_ID, 2001L, "操作系统原理", "提交实验二报告", "EXACT_DATETIME",
                "2099-12-31", "22:00:00", "2099-12-31T22:00", false, "ACTIVE", null,
                "zip", "学习通", "原文", "12月31日 22:00", "Asia/Shanghai",
                new BigDecimal("0.960"), false, "hash", "2026-10-16T11:40:00");
    }
}
