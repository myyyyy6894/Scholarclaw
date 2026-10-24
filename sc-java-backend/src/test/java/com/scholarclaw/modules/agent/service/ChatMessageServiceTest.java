package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.scholarclaw.modules.agent.dto.CitationItemVO;
import com.scholarclaw.modules.agent.entity.ChatMessageEntity;
import com.scholarclaw.modules.agent.mapper.ChatMessageMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;

    private ChatMessageMapper mapper;
    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatMessageMapper.class);
        service = new ChatMessageService(mapper,
                new ObjectMapper().setPropertyNamingStrategy(
                        PropertyNamingStrategies.SNAKE_CASE));
    }

    @Test
    @DisplayName("用户消息写入 KB_QA/WEB 并返回自增 ID")
    void appendUser() {
        doAnswer(invocation -> {
            ChatMessageEntity entity = invocation.getArgument(0);
            entity.setMessageId(90001L);
            return 1;
        }).when(mapper).insert(any(ChatMessageEntity.class));

        Long id = service.appendUser(USER_ID, COURSE_ID, "问题", "client-1", "tr_0123456789ab");

        assertThat(id).isEqualTo(90001L);
        verify(mapper).insert(org.mockito.ArgumentMatchers.<ChatMessageEntity>argThat(entity ->
                "USER".equals(entity.getRole())
                        && "WEB".equals(entity.getPlatform())
                        && "KB_QA".equals(entity.getIntentType())
                        && "client-1".equals(entity.getPlatformMsgId())
                        && "tr_0123456789ab".equals(entity.getTraceId())));
    }

    @Test
    @DisplayName("助手消息保存引用 JSON、拒答标记与耗时")
    void appendAssistant() {
        doAnswer(invocation -> {
            ChatMessageEntity entity = invocation.getArgument(0);
            entity.setMessageId(90002L);
            return 1;
        }).when(mapper).insert(any(ChatMessageEntity.class));

        Long id = service.appendAssistant(USER_ID, COURSE_ID, "答案",
                List.of(new CitationItemVO("pc_1", 50001L, "a.pdf", 1, 2, "5.1", "内容", 0.9)),
                true, "tr_0123456789ab", 185, 1120);

        assertThat(id).isEqualTo(90002L);
        verify(mapper).insert(org.mockito.ArgumentMatchers.<ChatMessageEntity>argThat(entity ->
                "ASSISTANT".equals(entity.getRole())
                        && entity.getCitationsJson().contains("\"parent_chunk_id\":\"pc_1\"")
                        && Boolean.TRUE.equals(entity.getIsRejected())
                        && entity.getRetrievalCostMs() == 185
                        && entity.getLlmFirstTokenMs() == 1120));
    }

    @Test
    @DisplayName("历史窗口按时间正序返回且跳过 SYSTEM")
    void recentHistoryIsChronological() {
        when(mapper.selectPage(USER_ID, COURSE_ID, 90001L, 12)).thenReturn(List.of(
                row(90003L, "ASSISTANT", "答二"),
                row(90002L, "SYSTEM", "系统"),
                row(90001L, "USER", "问一")));

        List<ChatMessageService.RagQaInternalHistory> history =
                service.recentHistory(USER_ID, COURSE_ID, 90001L);

        assertThat(history).extracting(ChatMessageService.RagQaInternalHistory::role)
                .containsExactly("USER", "ASSISTANT");
        assertThat(history).extracting(ChatMessageService.RagQaInternalHistory::content)
                .containsExactly("问一", "答二");
    }

    @Test
    @DisplayName("游标分页返回正序、has_more 与 next_cursor")
    void pageUsesCursorAndAscendingOrder() {
        when(mapper.selectPage(USER_ID, COURSE_ID, 90010L, 3)).thenReturn(List.of(
                row(90009L, "ASSISTANT", "答"),
                row(90008L, "USER", "问"),
                row(90007L, "ASSISTANT", "更早"),
                row(90006L, "USER", "最早")));

        var page = service.page(USER_ID, COURSE_ID, 90010L, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("90008");
        assertThat(page.items()).extracting(item -> item.messageId())
                .containsExactly(90008L, 90009L);
    }

    @Test
    @DisplayName("无更多历史时 next_cursor 为空")
    void pageWithoutMore() {
        when(mapper.selectPage(USER_ID, COURSE_ID, null, 21)).thenReturn(List.of(
                row(90002L, "ASSISTANT", "答"),
                row(90001L, "USER", "问")));

        var page = service.page(USER_ID, COURSE_ID, null, 20);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.items()).extracting(item -> item.messageId())
                .containsExactly(90001L, 90002L);
    }

    @Test
    @DisplayName("历史消息 createdAt 输出带应用时区偏移")
    void pageCreatedAtUsesOffset() {
        ChatMessageEntity row = row(90001L, "USER", "问");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0, 0));
        when(mapper.selectPage(USER_ID, COURSE_ID, null, 21)).thenReturn(List.of(row));

        var page = service.page(USER_ID, COURSE_ID, null, 20);

        assertThat(page.items().get(0).createdAt().getOffset())
                .isEqualTo(ZoneOffset.ofHours(8));
    }

    @Test
    @DisplayName("清空会话调用按用户与课程的删除")
    void clearDelegates() {
        when(mapper.deleteByCourse(USER_ID, COURSE_ID)).thenReturn(12);

        assertThat(service.clear(USER_ID, COURSE_ID)).isEqualTo(12);
        verify(mapper).deleteByCourse(USER_ID, COURSE_ID);
    }

    private static ChatMessageEntity row(long id, String role, String content) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setMessageId(id);
        entity.setUserId(USER_ID);
        entity.setCourseId(COURSE_ID);
        entity.setPlatform("WEB");
        entity.setRole(role);
        entity.setIntentType("KB_QA");
        entity.setContent(content);
        entity.setIsRejected(false);
        return entity;
    }
}
