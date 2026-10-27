package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.service.DdlDraftStore.LockState;
import com.scholarclaw.modules.ddl.service.DdlDraftStore.RequestLock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** TASK-704 Redis 幂等锁、决策锁与变更草案存储。 */
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
    @DisplayName("SET NX 成功 → ACQUIRED，值带 owner token，TTL 60s")
    void acquiresLockWithOwnerToken() {
        when(valueOperations.setIfAbsent(eq(LOCK_KEY), anyString(), eq(PROCESSING_TTL)))
                .thenReturn(true);

        RequestLock lock = store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID);

        assertThat(lock.state()).isEqualTo(LockState.ACQUIRED);
        assertThat(lock.ownerToken()).isNotBlank();
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq(LOCK_KEY), value.capture(), eq(PROCESSING_TTL));
        assertThat(value.getValue()).startsWith("PROCESSING:").contains(lock.ownerToken());
    }

    @Test
    @DisplayName("已存在 PROCESSING → PROCESSING；回执 JSON → PROCESSED 并返回原始值")
    void reportsExistingLockState() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSING");
        assertThat(store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID).state())
                .isEqualTo(LockState.PROCESSING);

        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSING:token-a");
        assertThat(store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID).state())
                .isEqualTo(LockState.PROCESSING);

        when(valueOperations.get(LOCK_KEY)).thenReturn("{\"outcome\":\"CREATED\"}");
        RequestLock processed = store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID);
        assertThat(processed.state()).isEqualTo(LockState.PROCESSED);
        assertThat(processed.rawValue()).isEqualTo("{\"outcome\":\"CREATED\"}");

        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSED");
        assertThat(store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID).state())
                .isEqualTo(LockState.PROCESSED);
    }

    @Test
    @DisplayName("Redis 异常时接入锁降级放行，但不返回 owner token（不会误删别人的锁）")
    void requestLockFailsOpenWithoutToken() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        RequestLock lock = store.acquireRequestLock("WEB", USER_ID, CLIENT_REQUEST_ID);

        assertThat(lock.state()).isEqualTo(LockState.ACQUIRED);
        assertThat(lock.ownerToken()).isNull();
        store.releaseRequest("WEB", USER_ID, CLIENT_REQUEST_ID, lock.ownerToken());
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(), any());
    }

    @Test
    @DisplayName("释放接入锁只删除值仍等于 owner token 的键（Lua 比较）")
    void releasesRequestLockWithLuaCompare() {
        store.releaseRequest("WEB", USER_ID, CLIENT_REQUEST_ID, "owner-token");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), eq(List.of(LOCK_KEY)), eq("owner-token"));
        assertThat(script.getValue().getScriptAsString()).contains("redis.call('get', KEYS[1])");
    }

    @Test
    @DisplayName("PROCESSED 回执 CAS 接管：只有原始值未变才成功")
    @SuppressWarnings("unchecked")
    void takeOverProcessedUsesCompareAndSet() {
        org.mockito.Mockito.doReturn(1L).when(redisTemplate)
                .execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("old"),
                        anyString(), anyString());

        String token = store.takeOverProcessed("WEB", USER_ID, CLIENT_REQUEST_ID, "old");

        assertThat(token).isNotBlank();
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("old"),
                anyString(), anyString());

        org.mockito.Mockito.doReturn(0L).when(redisTemplate)
                .execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("old"),
                        anyString(), anyString());
        assertThat(store.takeOverProcessed("WEB", USER_ID, CLIENT_REQUEST_ID, "old")).isNull();
    }

    @Test
    @DisplayName("markProcessed 写终态回执 + 24h；ID 为字符串")
    void marksProcessed() {
        DdlRequestReceipt receipt = DdlRequestReceipt.of("fp_1",
                "SEMANTIC_DUPLICATE_HINT", TASK_ID, null, "2099-12-31T22:00:00");

        store.markProcessed(USER_ID, CLIENT_REQUEST_ID, receipt);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(LOCK_KEY), json.capture(), eq(PROCESSED_TTL));
        assertThat(json.getValue())
                .contains("\"fingerprint\":\"fp_1\"")
                .contains("\"outcome\":\"SEMANTIC_DUPLICATE_HINT\"")
                .contains("\"task_id\":\"103\"");
    }

    @Test
    @DisplayName("loadReceipt：回执可读；PROCESSING/旧字面量/损坏值返回空")
    void loadsReceipt() throws Exception {
        DdlRequestReceipt receipt = DdlRequestReceipt.of("fp_1", "CREATED", TASK_ID, null,
                "2099-12-31T22:00:00");
        when(valueOperations.get(LOCK_KEY))
                .thenReturn(objectMapper.writeValueAsString(receipt));
        Optional<DdlRequestReceipt> loaded = store.loadReceipt(USER_ID, CLIENT_REQUEST_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().outcome()).isEqualTo("CREATED");
        assertThat(loaded.get().taskId()).isEqualTo("103");

        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSING:token");
        assertThat(store.loadReceipt(USER_ID, CLIENT_REQUEST_ID)).isEmpty();
        when(valueOperations.get(LOCK_KEY)).thenReturn("PROCESSED");
        assertThat(store.loadReceipt(USER_ID, CLIENT_REQUEST_ID)).isEmpty();
        when(valueOperations.get(LOCK_KEY)).thenReturn("not-json");
        assertThat(store.loadReceipt(USER_ID, CLIENT_REQUEST_ID)).isEmpty();
    }

    @Test
    @DisplayName("content_hash 串行锁：返回 owner token；Redis 异常失败关闭；Lua 比较后释放")
    @SuppressWarnings("unchecked")
    void contentLock() {
        when(valueOperations.setIfAbsent(eq("sc:lock:ddl_content:10001:hash_1"),
                anyString(), eq(Duration.ofSeconds(15)))).thenReturn(true);
        String token = store.acquireContentLock(USER_ID, "hash_1");
        assertThat(token).isNotBlank();
        store.releaseContentLock(USER_ID, "hash_1", token);
        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of("sc:lock:ddl_content:10001:hash_1")),
                eq(token));

        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThatThrownBy(() -> store.acquireContentLock(USER_ID, "hash_2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("决策锁按 user_id + target_task_id 加锁；忙返回空；Redis 故障失败关闭")
    void decisionLockScopedByTask() {
        String key = "sc:lock:ddl_draft_decision:10001:103";
        when(valueOperations.setIfAbsent(eq(key), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(true);
        String token = store.acquireDraftDecisionLock(USER_ID, TASK_ID);
        assertThat(token).isNotBlank();
        store.releaseDraftDecisionLock(USER_ID, TASK_ID, token);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)), eq(token));

        when(valueOperations.setIfAbsent(eq(key), anyString(), any(Duration.class)))
                .thenReturn(false);
        assertThat(store.acquireDraftDecisionLock(USER_ID, TASK_ID)).isNull();

        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThatThrownBy(() -> store.acquireDraftDecisionLock(USER_ID, TASK_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("决策锁延迟到事务 afterCompletion 释放，方法返回时仍持有锁")
    void decisionLockIsReleasedOnlyAfterTransactionCompletion() {
        String key = "sc:lock:ddl_draft_decision:10001:103";
        org.springframework.transaction.support.TransactionSynchronizationManager
                .initSynchronization();
        try {
            store.releaseDraftDecisionLockAfterCompletion(USER_ID, TASK_ID, "owner");
            long before = org.mockito.Mockito.mockingDetails(redisTemplate).getInvocations()
                    .stream().filter(i -> i.getMethod().getName().equals("execute")).count();
            assertThat(before).isZero();

            for (org.springframework.transaction.support.TransactionSynchronization sync :
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .getSynchronizations()) {
                sync.afterCompletion(
                        org.springframework.transaction.support.TransactionSynchronization
                                .STATUS_COMMITTED);
            }
            long after = org.mockito.Mockito.mockingDetails(redisTemplate).getInvocations()
                    .stream().filter(i -> i.getMethod().getName().equals("execute")).count();
            assertThat(after).isEqualTo(1);
            verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(key)), eq("owner"));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .clearSynchronization();
        }
    }

    @Test
    @DisplayName("决策回执缓存：写入与读取；写入失败不抛错（MySQL 才是权威）")
    void decisionReceiptCache() throws Exception {
        DdlDecisionReceipt receipt = DdlDecisionReceipt.of("CONFIRM_UPDATE:103",
                "CONFIRM_UPDATE", TASK_ID, TASK_ID, "2099-12-31T22:00:00");
        store.saveDecisionReceipt(USER_ID, CLIENT_REQUEST_ID, receipt);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("sc:idemp:decision:WEB:10001:req_extract_001"),
                json.capture(), eq(PROCESSED_TTL));
        assertThat(json.getValue())
                .contains("\"decision\":\"CONFIRM_UPDATE\"")
                .contains("\"result_task_id\":\"103\"");

        when(valueOperations.get("sc:idemp:decision:WEB:10001:req_extract_001"))
                .thenReturn(objectMapper.writeValueAsString(receipt));
        Optional<DdlDecisionReceipt> loaded =
                store.loadDecisionReceipt(USER_ID, CLIENT_REQUEST_ID);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().fingerprint()).isEqualTo("CONFIRM_UPDATE:103");

        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        store.saveDecisionReceipt(USER_ID, CLIENT_REQUEST_ID, receipt);
    }

    @Test
    @DisplayName("回执写入失败：failClosed=true 抛 INTERNAL_ERROR；false 仅告警")
    void markProcessedFailurePolicy() {
        DdlRequestReceipt receipt = DdlRequestReceipt.of("fp", "SEMANTIC_DUPLICATE_HINT",
                TASK_ID, null, "2099-12-31T22:00:00");
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        store.markProcessed(DdlIdempotencyKey.SOURCE_PLATFORM_WEB, USER_ID,
                CLIENT_REQUEST_ID, receipt, false);

        assertThatThrownBy(() -> store.markProcessed(
                DdlIdempotencyKey.SOURCE_PLATFORM_WEB, USER_ID, CLIENT_REQUEST_ID,
                receipt, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("草案写入使用 24h TTL，JSON 含目标版本快照与用户时区")
    void savesDraftWithTtl() {
        store.saveDraft(USER_ID, draft());

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(DRAFT_KEY), json.capture(), eq(Duration.ofHours(24)));
        assertThat(json.getValue()).contains("\"target_task_id\":\"103\"")
                .contains("\"schema_version\":" + DdlTaskDraft.CURRENT_VERSION)
                .contains("\"target_task_updated_at\":\"2026-10-15T11:40\"")
                .contains("\"course_id\":\"2001\"")
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
        assertThat(loaded.get().targetTaskUpdatedAtValue()).isNotNull();

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
    @DisplayName("草案写入异常：失败关闭为 INTERNAL_ERROR")
    void saveFailsClosedOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.saveDraft(USER_ID, draft()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("严格删除草案：Redis 异常必须抛错，不能静默成功")
    void strictDraftDeletionFailsClosed() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete(DRAFT_KEY);

        assertThatThrownBy(() -> store.deleteDraftStrict(USER_ID, TASK_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));

        // 非严格删除只用于过期清理，允许仅告警。
        store.deleteDraft(USER_ID, TASK_ID);
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
    @DisplayName("过期草案：读取为空并立即删除，不依赖 Redis TTL")
    void expiredDraftIsDeleted() throws Exception {
        DdlTaskDraft expired = draftWith("2026-01-01T00:00:00", DdlTaskDraft.CURRENT_VERSION);
        when(valueOperations.get(DRAFT_KEY)).thenReturn(objectMapper.writeValueAsString(expired));

        assertThat(store.loadDraft(USER_ID, TASK_ID)).isEmpty();
        verify(redisTemplate).delete(DRAFT_KEY);
    }

    @Test
    @DisplayName("缺少 schema_version 的旧草案：读取为空并删除")
    void legacySchemaDraftIsDeleted() throws Exception {
        String legacyJson = objectMapper.writeValueAsString(draft()).replace(
                ",\"schema_version\":" + DdlTaskDraft.CURRENT_VERSION, "");
        when(valueOperations.get(DRAFT_KEY)).thenReturn(legacyJson);

        assertThat(store.loadDraft(USER_ID, TASK_ID)).isEmpty();
        verify(redisTemplate).delete(DRAFT_KEY);
    }

    @Test
    @DisplayName("缺少 target_task_updated_at 的草案：读取为空并删除")
    void draftWithoutTargetVersionIsDeleted() throws Exception {
        DdlTaskDraft noVersion = new DdlTaskDraft(
                TASK_ID, null, 2001L, "操作系统原理", "提交实验二报告",
                "EXACT_DATETIME", "2099-12-31", "22:00:00", "2099-12-31T22:00", false,
                null, "ACTIVE", null, "zip", "学习通", "原文", "12月31日 22:00",
                "Asia/Shanghai", new BigDecimal("0.960"), false, "hash",
                "2099-01-01T00:00:00", DdlTaskDraft.CURRENT_VERSION);
        when(valueOperations.get(DRAFT_KEY)).thenReturn(objectMapper.writeValueAsString(noVersion));

        assertThat(store.loadDraft(USER_ID, TASK_ID)).isEmpty();
        verify(redisTemplate).delete(DRAFT_KEY);
    }

    @Test
    @DisplayName("草案列表跳过并删除过期/旧版本草案")
    @SuppressWarnings("unchecked")
    void listDraftsSkipsInvalidEntries() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, true, false);
        when(cursor.next()).thenReturn(DRAFT_KEY, DRAFT_KEY + ":old", DRAFT_KEY + ":expired");
        when(valueOperations.get(DRAFT_KEY))
                .thenReturn(objectMapper.writeValueAsString(draft()));
        when(valueOperations.get(DRAFT_KEY + ":old"))
                .thenReturn(objectMapper.writeValueAsString(draft()).replace(
                        ",\"schema_version\":" + DdlTaskDraft.CURRENT_VERSION, ""));
        when(valueOperations.get(DRAFT_KEY + ":expired"))
                .thenReturn(objectMapper.writeValueAsString(
                        draftWith("2026-01-01T00:00:00", DdlTaskDraft.CURRENT_VERSION)));

        assertThat(store.listDrafts(USER_ID)).hasSize(1);
        verify(redisTemplate).delete(DRAFT_KEY + ":old");
        verify(redisTemplate).delete(DRAFT_KEY + ":expired");
    }

    @Test
    @DisplayName("Redis 请求回执过期后不再重放")
    void expiredRequestReceiptIsIgnored() throws Exception {
        DdlRequestReceipt expired = DdlRequestReceipt.of(
                "fp", "CREATED", 103L, null, "2026-01-01T00:00:00");
        when(valueOperations.get(anyString())).thenReturn(
                objectMapper.writeValueAsString(expired));

        assertThat(store.loadReceipt(USER_ID, "req_1")).isEmpty();
    }

    @Test
    @DisplayName("Redis 决策回执过期后不再重放")
    void expiredDecisionReceiptIsIgnored() throws Exception {
        DdlDecisionReceipt expired = DdlDecisionReceipt.of(
                "fp", "CONFIRM_UPDATE", 103L, 103L, "2026-01-01T00:00:00");
        when(valueOperations.get(anyString())).thenReturn(
                objectMapper.writeValueAsString(expired));

        assertThat(store.loadDecisionReceipt(USER_ID, "req_1")).isEmpty();
    }

    private static DdlTaskDraft draftWith(String expiresAt, int schemaVersion) {
        return new DdlTaskDraft(
                TASK_ID, "2026-10-15T11:40", 2001L, "操作系统原理", "提交实验二报告",
                "EXACT_DATETIME", "2099-12-31", "22:00:00", "2099-12-31T22:00", false,
                null, "ACTIVE", null, "zip", "学习通", "原文", "12月31日 22:00",
                "Asia/Shanghai", new BigDecimal("0.960"), false, "hash",
                expiresAt, schemaVersion);
    }

    @Test
    @DisplayName("无事务时 deleteDraftAfterCommit 立即删除；严格版本失败抛错")
    void deleteAfterCommitWithoutTransactionDeletesNow() {
        store.deleteDraftAfterCommit(USER_ID, TASK_ID);
        verify(redisTemplate).delete(DRAFT_KEY);

        store.deleteDraftAfterCommitStrict(USER_ID, TASK_ID);
        verify(redisTemplate, org.mockito.Mockito.times(2)).delete(DRAFT_KEY);
    }

    private static DdlTaskDraft draft() {
        return new DdlTaskDraft(
                TASK_ID, "2026-10-15T11:40", 2001L, "操作系统原理", "提交实验二报告",
                "EXACT_DATETIME", "2099-12-31", "22:00:00", "2099-12-31T22:00", false,
                null, "ACTIVE", null, "zip", "学习通", "原文", "12月31日 22:00",
                "Asia/Shanghai", new BigDecimal("0.960"), false, "hash",
                "2099-01-01T00:00:00", DdlTaskDraft.CURRENT_VERSION);
    }
}
