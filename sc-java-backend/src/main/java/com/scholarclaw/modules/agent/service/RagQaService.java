package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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
    /** 父块主键规范：`pc_{doc_id}_v{parse_version}_{chunk_seq}`（`03` §4.4 / chunker）。 */
    private static final Pattern PARENT_CHUNK_ID = Pattern.compile(
            "^pc_[1-9]\\d*_v[1-9]\\d*_[1-9]\\d*$");
    private static final Pattern DECIMAL_ID = Pattern.compile("^[1-9]\\d*$");
    private static final String GENERAL_SUPPLEMENT_PREFIX =
            "💡 以下为基于通用专业知识的补充参考（非本校课件原文，请注意甄别）：";

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
        } catch (JsonProcessingException | FrameInvalidException error) {
            log.warn("RAG 下游帧结构非法 event={} trace_id={} reason={}",
                    frame.event(), context.traceId, error.getMessage());
            finishWithError(context, "INTERNAL_ERROR", "回答生成失败，请稍后重试。");
        }
    }

    private void forwardCitation(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = requireObject(data, "citation");
        JsonNode citationsNode = node.get("citations");
        if (citationsNode == null || !citationsNode.isArray()
                || citationsNode.isEmpty() || citationsNode.size() > 4) {
            throw new FrameInvalidException("citation.citations 必须为 1~4 个数组元素");
        }
        List<CitationItemVO> citations = new ArrayList<>(citationsNode.size());
        for (JsonNode item : citationsNode) {
            citations.add(parseCitation(item));
        }
        context.citations = List.copyOf(citations);
        ObjectNode outbound = objectMapper.createObjectNode();
        // 出站只序列化已验证 DTO，避免把 Python 原始 JSON 原样透传给客户端
        ArrayNode outboundCitations = outbound.putArray("citations");
        for (CitationItemVO citation : citations) {
            ObjectNode out = outboundCitations.addObject();
            out.put("parent_chunk_id", citation.parentChunkId());
            out.put("doc_id", String.valueOf(citation.docId()));
            out.put("doc_name", citation.docName());
            out.put("page_start", citation.pageStart());
            out.put("page_end", citation.pageEnd());
            if (citation.sectionTitle() == null) {
                out.putNull("section_title");
            } else {
                out.put("section_title", citation.sectionTitle());
            }
            out.put("content", citation.content());
            out.put("similarity_score", citation.similarityScore());
        }
        outbound.put("user_message_id", String.valueOf(context.userMessageId));
        outbound.put("course_index_status", context.course.getIndexStatus());
        outbound.putNull("rebuild_progress_note");
        send(context, "citation", outbound.toString());
    }

    private void forwardRejection(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = requireObject(data, "rejection_notice");
        JsonNode notice = node.get("notice_text");
        if (notice == null || !notice.isTextual() || notice.asText().isBlank()) {
            throw new FrameInvalidException("rejection_notice.notice_text 必须为非空字符串");
        }
        JsonNode hasSupplement = node.get("has_general_supplement");
        if (hasSupplement == null || !hasSupplement.isBoolean()) {
            throw new FrameInvalidException("rejection_notice.has_general_supplement 必须为布尔值");
        }
        context.rejectionNotice = notice.asText();
        node.put("user_message_id", String.valueOf(context.userMessageId));
        send(context, "rejection_notice", node.toString());
    }

    private void forwardToken(StreamContext context, String data) throws JsonProcessingException {
        ObjectNode node = requireObject(data, "token");
        JsonNode delta = node.get("delta");
        JsonNode supplement = node.get("is_general_supplement");
        if (delta == null || !delta.isTextual() || delta.asText().isEmpty()) {
            throw new FrameInvalidException("token.delta 必须为非空字符串");
        }
        if (supplement == null || !supplement.isBoolean()) {
            throw new FrameInvalidException("token.is_general_supplement 必须为布尔值");
        }
        context.answer.append(delta.asText());
        send(context, "token", node.toString());
    }

    private ObjectNode requireObject(String data, String event) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(data);
        if (node == null || !node.isObject()) {
            throw new FrameInvalidException(event + " 帧必须是 JSON 对象");
        }
        return (ObjectNode) node;
    }

    private CitationItemVO parseCitation(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new FrameInvalidException("citation 元素必须是对象");
        }
        String parentChunkId = requiredText(item, "parent_chunk_id");
        if (!PARENT_CHUNK_ID.matcher(parentChunkId).matches()) {
            throw new FrameInvalidException("parent_chunk_id 必须符合 pc_{doc_id}_v{version}_{seq}");
        }
        Long docId = requiredDecimalId(item.get("doc_id"), "doc_id");
        String docName = requiredText(item, "doc_name");
        int pageStart = requiredPositiveInt(item.get("page_start"), "page_start");
        int pageEnd = requiredPositiveInt(item.get("page_end"), "page_end");
        if (pageEnd < pageStart) {
            throw new FrameInvalidException("page_end 不能小于 page_start");
        }
        JsonNode sectionTitle = item.get("section_title");
        if (sectionTitle != null && !sectionTitle.isNull() && !sectionTitle.isTextual()) {
            throw new FrameInvalidException("section_title 必须为字符串或 null");
        }
        String content = requiredText(item, "content");
        double similarityScore = requiredFiniteScore(item.get("similarity_score"));
        return new CitationItemVO(parentChunkId, docId, docName,
                pageStart, pageEnd,
                sectionTitle == null || sectionTitle.isNull() ? null : sectionTitle.asText(),
                content, similarityScore);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new FrameInvalidException(field + " 必须为非空字符串");
        }
        return value.asText();
    }

    private static Long requiredDecimalId(JsonNode value, String field) {
        if (value == null || !value.isTextual()
                || !DECIMAL_ID.matcher(value.asText()).matches()) {
            throw new FrameInvalidException(field + " 必须为十进制字符串");
        }
        try {
            long parsed = Long.parseLong(value.asText());
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new FrameInvalidException(field + " 必须为十进制字符串");
        }
    }

    private static double requiredFiniteScore(JsonNode value) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new FrameInvalidException("similarity_score 必须为有限数值");
        }
        return value.asDouble();
    }

    private static int requiredPositiveInt(JsonNode value, String field) {
        if (value == null || !value.isIntegralNumber() || value.asInt() < 1) {
            throw new FrameInvalidException(field + " 必须为正整数");
        }
        return value.asInt();
    }

    private void handleDone(StreamContext context, String data) {
        final ObjectNode node;
        final boolean rejected;
        final int retrievalCostMs;
        final int llmFirstTokenMs;
        final int stripped;
        try {
            node = requireObject(data, "done");
            rejected = requireBoolean(node, "is_rejected");
            retrievalCostMs = requireNonNegativeInt(node, "retrieval_cost_ms");
            llmFirstTokenMs = requireNonNegativeInt(node, "llm_first_token_ms");
            stripped = requireNonNegativeInt(node, "stripped_citation_count");
        } catch (JsonProcessingException | FrameInvalidException error) {
            finishWithError(context, "INTERNAL_ERROR", "回答生成失败，请稍后重试。");
            return;
        }
        if (!rejected && (context.citations == null || context.citations.isEmpty())) {
            finishWithError(context, "E-RAG-03",
                    "引用校验未通过；未收到可核验的引用标签。请重试或换一种问法。");
            return;
        }
        if (rejected && context.citations != null && !context.citations.isEmpty()) {
            finishWithError(context, "INTERNAL_ERROR",
                    "协议错误：拒答回答不得携带课件引用。");
            return;
        }
        if (!claimTerminal(context)) {
            return;
        }
        cancelTimer(context);
        String content = context.answer.toString();
        if (rejected) {
            content = composeRejectedContent(context.rejectionNotice, content);
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
                    context.citations, rejected, context.traceId,
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

    /**
     * 拒答落库内容固定为 `{notice}\n\n{补充前缀}\n{通用补充正文}`（`PO-04`）。
     * 无补充时只保存声明；声明缺失视为协议错误，返回空串交由调用方按失败处理。
     */
    private static String composeRejectedContent(String notice, String answer) {
        if (notice == null || notice.isBlank()) {
            return "";
        }
        if (answer == null || answer.isBlank()) {
            return notice;
        }
        String supplement = answer.startsWith(GENERAL_SUPPLEMENT_PREFIX)
                ? answer : GENERAL_SUPPLEMENT_PREFIX + "\n" + answer;
        return notice + "\n\n" + supplement;
    }

    private static boolean requireBoolean(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new FrameInvalidException(field + " 必须为布尔值");
        }
        return value.asBoolean();
    }

    private static int requireNonNegativeInt(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asInt() < 0) {
            throw new FrameInvalidException(field + " 必须为非负整数");
        }
        return value.asInt();
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
            ObjectNode node = requireObject(data, "error");
            String rawCode = node.path("code").asText("");
            String rawMessage = node.path("message").asText("");
            ErrorCode mapped = ErrorCode.fromCode(rawCode);
            if (mapped != null) {
                code = mapped.code();
                message = mapped.defaultMessage();
                // 引用校验失败需要把“实际召回切片”等确定性说明带给前端；
                // 只接受来自受控 Python 错误帧的短文本，避免透传内部细节。
                if (mapped == ErrorCode.E_RAG_03 && rawMessage != null
                        && !rawMessage.isBlank() && rawMessage.length() <= 512) {
                    message = rawMessage.replaceAll("[\\p{Cntrl}]", " ");
                }
            }
        } catch (JsonProcessingException | FrameInvalidException ignored) {
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

    /** 下游帧结构不符合契约：必须中止，不得静默降级成成功回答。 */
    private static final class FrameInvalidException extends RuntimeException {
        private FrameInvalidException(String message) {
            super(message);
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
        private volatile List<CitationItemVO> citations;
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
