package com.scholarclaw.common.enums;

/**
 * 知识库文档状态（{@code 02} §9.1 状态机；取值与 {@code 03} 的 CHECK 约束一致）。
 *
 * <p>常量名即数据库与 JSON 中的字符串值，禁止新增同义状态或改名。
 */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    READY,
    FAILED,
    DELETING,
    DELETED
}
