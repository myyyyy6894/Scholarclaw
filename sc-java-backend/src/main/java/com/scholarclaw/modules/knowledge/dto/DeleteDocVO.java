package com.scholarclaw.modules.knowledge.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 删除单个课件响应（{@code 04} `API-DOC-005`）。 */
public record DeleteDocVO(@JsonSerialize(using = ToStringSerializer.class) Long deletedDocId,
                          String status) {
}
