package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.client.PythonAiEngineClient.SseFrame;
import com.scholarclaw.common.client.PythonAiEngineClient.SseStreamHandle;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.agent.dto.CitationItemVO;
import com.scholarclaw.modules.agent.dto.RagQaInternalRequest;
import com.scholarclaw.modules.agent.dto.RagQaRequest;
import com.scholarclaw.modules.agent.service.RagModelConfigService.RagModelConfig;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * `API-RAG-001` SSE 流式问答编排（TASK-604，`04` §4.5.1）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>建流前同步完成课程归属、READY 文档集与模型配置校验（失败返回标准 JSON 错误）；</li>
 *   <li>建流后转发 Python 的 `citation` / `rejection_notice` / `token` 帧，并补全 Java 域字段；</li>
 *   <li>收到 `done` 后短事务落库 `ASSISTANT` 消息，再发送带消息 ID 的 `done` 帧；</li>
 *   <li>错误、首包超时、整流超时与客户端断流均明确终止，**不把不完整回答写成成功**。</li>
 * </ul>
 */
@Service
public class RagQaService {

    private static final Logger log = LoggerFactory.getLogger(RagQaService.class);
    private static final long EMITTER_TIMEOUT_MS = 120_000L;
    private static final long FIRST_PACKET_TIMEOUT_MS = 15_000L;
    private static final Duration UPSTREAM_READ_TIMEOUT = Duration.ofSeconds(120);

    private final CourseService courseService;
    private final KnowledgeDocService knowledgeDocService;
    private final RagModelConfigService modelConfigService;
    private final ChatMessageService chatMessageService;
    private final ChatWindowService chatWindowService;
    private final PythonAiEngineClient pythonAiEngineClient;
    private final ObjectMapper objectMapper;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    public RagQaService(CourseService courseService,
                        KnowledgeDocService knowledgeDocService,
                        RagModelConfigService modelConfigService,
                        ChatMessageService chatMessageService,
                        ChatWindowService chatWindowService,
                        PythonAiEngineClient pythonAiEngineClient,
                        ObjectMapper objectMapper,
                        @Qualifier("ragStreamExecutor") ExecutorService streamExecutor,
                        @Qualifier("ragStreamScheduler") ScheduledExecutorService timeoutScheduler) {
        this.courseService = courseService;
        this.knowledgeDocService = knowledgeDocService;
        this.modelConfigService = modelConfigService;
        this.chatMessageService = chatMessageService;
        this.chatWindowService = chatWindowService;
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.objectMapper = objectMapper;
        this.streamExecutor = streamExecutor;
        this.timeoutScheduler = timeoutScheduler;
    }

    public SseEmitter stream(Long userId, RagQaRequest request) {
        Long courseId = parsePositiveId(request.courseId(), "course_id");
        CourseNamespaceEntity course = courseService.getActiveCourse(userId, courseId);
        List<Long> validDocIds = knowledgeDocService.validDocIds(
                userId, courseId, course.getIndexVersion());
        if (validDocIds.isEmpty()) {
            if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
                throw new BusinessException(ErrorCode.COURSE_INDEX_REBUILDING);
            }
            throw new BusinessException(ErrorCode.COURSE_NO_READY_DOCS);
        }
        RagModelConfig config = modelConfigService.resolve(userId, course);

        String query = request.query().trim();
        String traceId = TraceContext.currentOrGeneratedTraceId();
        Long userMessageId = chatMessageService.appendUser(
                userId, courseId, query, normalizeClientMsgId(request.clientMsgId()), traceId);
        List<ChatMessageService.RagQaInternalHistory> history =
                chatMessageService.recentHistory(userId, courseId, userMessageId);
        chatWindowService.append(userId, courseId, ChatMessageService.ROLE_USER, query);

        RagQaInternalRequest body = buildInternalRequest(
                userId, course, validDocIds, query, history, config);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        StreamContext context = new StreamContext(userId, courseId, course, body,
                traceId, userMessageId, emitter);

        emitter.onCompletion(() -> cancel(context));
        emitter.onError(error -> cancel(context));
        emitter.onTimeout(() -> finishWithError(context, "UPSTREAM_TIMEOUT",
                "响应超时，请稍后重试。"));

        context.firstPacketTimer = timeoutScheduler.schedule(
                () -> {
                    if (!context.firstFrame.get()) {
                        finishWithError(context, "UPSTREAM_TIMEOUT", "响应超时，请稍后重试。");
                    }
                }, FIRST_PACKET_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        streamExecutor.execute(() -> runStream(context));
        return emitter;
    }

    private void runStream(StreamContext context) {
        TraceContext.bind(context.traceId);
        TraceContext.bindUserId(context.userId);
        try {
            SseStreamHandle handle;
            try {
                handle = pythonAiEngineClient.openSseStream(
                        PythonAiEngineClient.RAG_QA_STREAM_PATH, context.body,
                        context.userId, UPSTREAM_READ_TIMEOUT);
            } catch (BusinessException error) {
                finishWithError(context, error.getErrorCode().code(), error.getMessage());
                return;
            }
            context.handle.set(handle);
            if (context.terminal.get()) {
                closeHandle(context);
                return;
            }
            try {
                while (!context.terminal.get()) {
                    SseFrame frame = handle.next();
                    if (frame == null) {
                        break;
                    }
                    context.firstFrame.set(true);
                    if ("done".equals(frame.event())) {
                        handleDone(context, frame.data());
                        return;
                    }
                    if ("error".equals(frame.event())) {
                        handleErrorFrame(context, frame.data());
                        return;
                    }
                    forward(context, frame);
                }
                finishWithError(context, "UPSTREAM_MODEL_ERROR", "回答生成中断，请重试。");
            } catch (IOException error) {
                if (!context.terminal.get()) {
                    log.warn("RAG 下游流读取中断 trace_id={} userId={}",
                            context.traceId, context.userId);
                    finishWithError(context, "UPSTREAM_MODEL_ERROR", "回答生成中断，请重试。");
                }
            } finally {
                closeHandle(context);
            }
        } finally {
            cancelTimer(context);
            TraceContext.clear();
        }
    }

    private void forward(StreamContext context, SseFrame frame) {
        try {
            switch (frame.event()) {
                case "citation" -> forwardCitation(context, frame.data());
                case "rejection_notice" -> forwardRejection(context, frame.data());
                case "token" -> forwardToken(context, frame.data());
                default -> log.debug("忽略未知 RAG 事件 event={} trace_id={}",
                        frame.event(), context.traceId);
            }
        } catch (JsonProcessingException error) {
            log.warn("RAG 下游帧不是合法 JSON event={} trace_id={}",
                    frame.event(), context.traceId);
            finishWithError(context, "INTERNAL_ERROR", "回答生成失败，请稍后重试。");
        }
    }

    private void forwardCitation(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = (ObjectNode) objectMapper.readTree(data);
        context.citationsJson = node.has("citations") ? node.get("citations").toString() : null;
        node.put("user_message_id", String.valueOf(context.userMessageId));
        node.put("course_index_status", context.course.getIndexStatus());
        node.putNull("rebuild_progress_note");
        send(context, "citation", node.toString());
    }

    private void forwardRejection(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = (ObjectNode) objectMapper.readTree(data);
        JsonNode notice = node.get("notice_text");
        if (notice != null && notice.isTextual()) {
            context.rejectionNotice = notice.asText();
        }
        node.put("user_message_id", String.valueOf(context.userMessageId));
        send(context, "rejection_notice", node.toString());
    }

    private void forwardToken(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = (ObjectNode) objectMapper.readTree(data);
        JsonNode delta = node.get("delta");
        if (delta != null && delta.isTextual()) {
            context.answer.append(delta.asText());
        }
        send(context, "token", node.toString());
    }

    private void handleDone(StreamContext context, String data) {
        JsonNode node;
        try {
            node = objectMapper.readTree(data);
        } catch (JsonProcessingException error) {
            finishWithError(context, "INTERNAL_ERROR", "回答生成失败，请稍后重试。");
            return;
        }
        if (!claimTerminal(context)) {
            return;
        }
        cancelTimer(context);
        boolean rejected = node.path("is_rejected").asBoolean(false);
        int retrievalCostMs = Math.max(0, node.path("retrieval_cost_ms").asInt(0));
        int llmFirstTokenMs = Math.max(0, node.path("llm_first_token_ms").asInt(0));
        int stripped = Math.max(0, node.path("stripped_citation_count").asInt(0));
        String content = context.answer.toString();
        if (content.isBlank() && rejected && context.rejectionNotice != null) {
            content = context.rejectionNotice;
        }
        if (content.isBlank()) {
            // 不把空回答/不完整回答当作成功落库
            sendTerminal(context, "error", errorJson("UPSTREAM_MODEL_ERROR",
                    "回答生成中断，请重试。", context.traceId));
            completeQuietly(context);
            closeHandle(context);
            return;
        }
        try {
            Long assistantMessageId = chatMessageService.appendAssistant(
                    context.userId, context.courseId, content,
                    parseCitations(context.citationsJson), rejected, context.traceId,
                    retrievalCostMs, llmFirstTokenMs);
            chatWindowService.append(context.userId, context.courseId,
                    ChatMessageService.ROLE_ASSISTANT, content);
            ObjectNode done = objectMapper.createObjectNode();
            done.put("user_message_id", String.valueOf(context.userMessageId));
            done.put("assistant_message_id", String.valueOf(assistantMessageId));
            done.put("is_rejected", rejected);
            done.put("retrieval_cost_ms", retrievalCostMs);
            done.put("llm_first_token_ms", llmFirstTokenMs);
            done.put("stripped_citation_count", stripped);
            done.put("trace_id", context.traceId);
            sendTerminal(context, "done", done.toString());
        } catch (RuntimeException error) {
            log.error("RAG 回答落库失败 trace_id={} userId={}",
                    context.traceId, context.userId, error);
            sendTerminal(context, "error", errorJson("INTERNAL_ERROR",
                    "回答保存失败，请稍后重试。", context.traceId));
        } finally {
            completeQuietly(context);
            closeHandle(context);
        }
    }

    private void completeQuietly(StreamContext context) {
        try {
            context.emitter.complete();
        } catch (RuntimeException ignored) {
            // 客户端已断开
        }
    }

    private void handleErrorFrame(StreamContext context, String data) {
        String code = "INTERNAL_ERROR";
        String message = "回答生成失败，请稍后重试。";
        try {
            JsonNode node = objectMapper.readTree(data);
            String rawCode = node.path("code").asText("");
            ErrorCode mapped = ErrorCode.fromCode(rawCode);
            if (mapped != null) {
                code = mapped.code();
                message = mapped.defaultMessage();
            }
        } catch (JsonProcessingException ignored) {
            // 保持通用错误码与脱敏文案
        }
        finishWithError(context, code, message);
    }

    private void send(StreamContext context, String event, String json) {
        synchronized (context.sendLock) {
            if (context.terminal.get()) {
                return;
            }
            try {
                context.emitter.send(SseEmitter.event().name(event)
                        .data(json, MediaType.TEXT_PLAIN));
            } catch (IOException | IllegalStateException error) {
                // 客户端已断开：终止且不落库半截回答
                context.terminal.set(true);
                try {
                    context.emitter.complete();
                } catch (RuntimeException ignored) {
                    // 连接已断开时 complete 也可能失败
                }
                closeHandle(context);
            }
        }
    }

    private void sendTerminal(StreamContext context, String event, String json) {
        synchronized (context.sendLock) {
            try {
                context.emitter.send(SseEmitter.event().name(event)
                        .data(json, MediaType.TEXT_PLAIN));
            } catch (IOException | IllegalStateException error) {
                log.debug("RAG 终止帧发送失败（客户端可能已断开） trace_id={}", context.traceId);
            }
        }
    }

    private void finishWithError(StreamContext context, String code, String message) {
        if (!claimTerminal(context)) {
            return;
        }
        cancelTimer(context);
        sendTerminal(context, "error", errorJson(code, message, context.traceId));
        try {
            context.emitter.complete();
        } catch (RuntimeException ignored) {
            // 客户端已断开
        }
        closeHandle(context);
    }

    private boolean claimTerminal(StreamContext context) {
        synchronized (context.sendLock) {
            if (context.terminal.get()) {
                return false;
            }
            context.terminal.set(true);
            return true;
        }
    }

    private void cancel(StreamContext context) {
        if (claimTerminal(context)) {
            cancelTimer(context);
        }
        closeHandle(context);
    }

    private void closeHandle(StreamContext context) {
        SseStreamHandle handle = context.handle.getAndSet(null);
        if (handle != null) {
            handle.close();
        }
    }

    private void cancelTimer(StreamContext context) {
        ScheduledFuture<?> timer = context.firstPacketTimer;
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private List<CitationItemVO> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CitationItemVO>>() {
            });
        } catch (JsonProcessingException error) {
            log.warn("引用卡片无法映射，按空引用落库");
            return null;
        }
    }

    private String errorJson(String code, String message, String traceId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", code);
        node.put("message", message);
        node.put("trace_id", traceId);
        return node.toString();
    }

    private RagQaInternalRequest buildInternalRequest(Long userId, CourseNamespaceEntity course,
                                                      List<Long> validDocIds, String query,
                                                      List<ChatMessageService.RagQaInternalHistory> history,
                                                      RagModelConfig config) {
        List<RagQaInternalRequest.HistoryTurn> turns = history.stream()
                .map(item -> new RagQaInternalRequest.HistoryTurn(item.role(), item.content()))
                .toList();
        return new RagQaInternalRequest(
                String.valueOf(userId), String.valueOf(course.getCourseId()),
                course.getCourseName(), course.getIndexVersion(),
                validDocIds.stream().map(String::valueOf).toList(), query, turns,
                new RagQaInternalRequest.ChatConfig(
                        config.chat().provider(), config.chat().baseUrl(),
                        config.chat().apiKey(), config.chat().modelName()),
                new RagQaInternalRequest.EmbeddingConfig(
                        config.embedding().provider(), config.embedding().baseUrl(),
                        config.embedding().apiKey(), config.embedding().modelName(),
                        config.embeddingDim()));
    }

    private static String normalizeClientMsgId(String clientMsgId) {
        if (clientMsgId == null) {
            return null;
        }
        String trimmed = clientMsgId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long parsePositiveId(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
    }

    /** 单次流式请求的线程内状态；仅在 worker 线程与容器回调之间共享。 */
    private static final class StreamContext {
        private final Long userId;
        private final Long courseId;
        private final CourseNamespaceEntity course;
        private final RagQaInternalRequest body;
        private final String traceId;
        private final Long userMessageId;
        private final SseEmitter emitter;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean firstFrame = new AtomicBoolean(false);
        private final AtomicReference<SseStreamHandle> handle = new AtomicReference<>();
        private final Object sendLock = new Object();
        private final StringBuilder answer = new StringBuilder();
        private volatile String citationsJson;
        private volatile String rejectionNotice;
        private volatile ScheduledFuture<?> firstPacketTimer;

        private StreamContext(Long userId, Long courseId, CourseNamespaceEntity course,
                              RagQaInternalRequest body, String traceId,
                              Long userMessageId, SseEmitter emitter) {
            this.userId = userId;
            this.courseId = courseId;
            this.course = course;
            this.body = body;
            this.traceId = traceId;
            this.userMessageId = userMessageId;
            this.emitter = emitter;
        }
    }
}
