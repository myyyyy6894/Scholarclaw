package com.scholarclaw.modules.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** `API-INT-JAVA-002` 的单条父块（全部字段来自 MySQL 权威存储）。 */
public record ParentChunkVO(
        String parentChunkId,
        @JsonSerialize(using = ToStringSerializer.class) Long docId,
        String docName,
        int pageStart,
        int pageEnd,
        String sectionTitle,
        String content) {
}
