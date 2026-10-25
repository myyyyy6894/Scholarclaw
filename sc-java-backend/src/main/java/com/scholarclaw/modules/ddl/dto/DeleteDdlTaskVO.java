package com.scholarclaw.modules.ddl.dto;

/** 删除/取消任务响应（{@code 04} `API-DDL-007`）。 */
public record DeleteDdlTaskVO(String deletedTaskId, String status) {
}
