package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.agent.dto.ChatMessagePageVO;
import com.scholarclaw.modules.agent.dto.ChatMessageVO;
import com.scholarclaw.modules.agent.dto.CitationItemVO;
import com.scholarclaw.modules.agent.entity.ChatMessageEntity;
import com.scholarclaw.modules.agent.mapper.ChatMessageMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * `t_chat_message` 的唯一写入与读取服务（TASK-604，`03` §4.6 / `04` §4.5.2~4.5.3）。
 *
 * <p>问答流式期间**不持有数据库事务**：用户消息在开流前短事务写入，助手消息在收到
 * `done` 后短事务写入；断流或错误不写助手消息。
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);
    public static final String PLATFORM_WEB = "WEB";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String INTENT_KB_QA = "KB_QA";
    private static final int MAX_HISTORY_MESSAGES = 12;

    private final ChatMessageMapper mapper;
    private final ObjectMapper objectMapper;

    public ChatMessageService(ChatMessageMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long appendUser(Long userId, Long courseId, String content,
                           String platformMsgId, String traceId) {
        ChatMessageEntity entity = base(userId, courseId, ROLE_USER, content, traceId);
        entity.setPlatformMsgId(platformMsgId);
        mapper.insert(entity);
        return entity.getMessageId();
    }

    @Transactional
    public Long appendAssistant(Long userId, Long courseId, String content,
                                List<CitationItemVO> citations, boolean rejected,
                                String traceId, int retrievalCostMs, int llmFirstTokenMs) {
        ChatMessageEntity entity = base(userId, courseId, ROLE_ASSISTANT, content, traceId);
        entity.setCitationsJson(toJson(citations));
        entity.setIsRejected(rejected);
        entity.setRetrievalCostMs(retrievalCostMs);
        entity.setLlmFirstTokenMs(llmFirstTokenMs);
        mapper.insert(entity);
        return entity.getMessageId();
    }

    /** 供 Python 的 `chat_history_window`：当前提问之前的最近 6 轮（最多 12 条），按时间正序。 */
    public List<RagQaInternalHistory> recentHistory(Long userId, Long courseId,
                                                    Long beforeMessageId) {
        List<ChatMessageEntity> rows = mapper.selectPage(
                userId, courseId, beforeMessageId, MAX_HISTORY_MESSAGES);
        List<RagQaInternalHistory> history = new ArrayList<>(rows.size());
        for (int index = rows.size() - 1; index >= 0; index--) {
            ChatMessageEntity row = rows.get(index);
            if (ROLE_USER.equals(row.getRole()) || ROLE_ASSISTANT.equals(row.getRole())) {
                history.add(new RagQaInternalHistory(row.getRole(), row.getContent()));
            }
        }
        return history;
    }

    public ChatMessagePageVO page(Long userId, Long courseId, Long beforeMessageId, int limit) {
        List<ChatMessageEntity> rows = mapper.selectPage(
                userId, courseId, beforeMessageId, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        List<ChatMessageEntity> ascending = new ArrayList<>(rows);
        Collections.reverse(ascending);
        List<ChatMessageVO> items = ascending.stream().map(this::toVO).toList();
        String nextCursor = hasMore && !ascending.isEmpty()
                ? String.valueOf(ascending.get(0).getMessageId()) : null;
        return new ChatMessagePageVO(items, nextCursor, hasMore);
    }

    @Transactional
    public int clear(Long userId, Long courseId) {
        return mapper.deleteByCourse(userId, courseId);
    }

    private ChatMessageEntity base(Long userId, Long courseId, String role,
                                   String content, String traceId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(userId);
        entity.setCourseId(courseId);
        entity.setPlatform(PLATFORM_WEB);
        entity.setRole(role);
        entity.setIntentType(INTENT_KB_QA);
        entity.setContent(content == null ? "" : content);
        entity.setTraceId(traceId);
        return entity;
    }

    private ChatMessageVO toVO(ChatMessageEntity entity) {
        return new ChatMessageVO(
                entity.getMessageId(), entity.getCourseId(), entity.getPlatform(),
                entity.getRole(), entity.getIntentType(), entity.getContent(),
                fromJson(entity.getCitationsJson()), Boolean.TRUE.equals(entity.getIsRejected()),
                entity.getTraceId(), entity.getRetrievalCostMs(),
                entity.getLlmFirstTokenMs(), ScTimeZone.toOffsetDateTime(entity.getCreatedAt()));
    }

    private String toJson(List<CitationItemVO> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            // 引用卡片序列化失败不能伪装成成功：交由上层按持久化失败处理
            throw new IllegalStateException("citations_json 序列化失败", e);
        }
    }

    private List<CitationItemVO> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CitationItemVO>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("citations_json 无法反序列化，按空引用返回");
            return null;
        }
    }

    /** 内部历史窗口条目（与 `RagQaInternalRequest.HistoryTurn` 字段一致）。 */
    public record RagQaInternalHistory(String role, String content) {
    }
}
