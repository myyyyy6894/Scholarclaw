package com.scholarclaw.modules.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 会话历史中的引用卡片（`citations_json` 反序列化结果）。 */
public record CitationItemVO(
        String parentChunkId,
        @JsonSerialize(using = ToStringSerializer.class) Long docId,
        String docName,
        int pageStart,
        int pageEnd,
        String sectionTitle,
        String content) {
}
