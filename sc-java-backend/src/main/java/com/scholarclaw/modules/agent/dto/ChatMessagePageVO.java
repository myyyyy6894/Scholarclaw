package com.scholarclaw.modules.agent.dto;

import java.util.List;

/** `API-CHAT-001` 分页响应。 */
public record ChatMessagePageVO(List<ChatMessageVO> items, String nextCursor, boolean hasMore) {
}
