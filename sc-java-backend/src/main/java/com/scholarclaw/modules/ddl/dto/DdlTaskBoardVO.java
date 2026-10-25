package com.scholarclaw.modules.ddl.dto;

import java.util.List;

/**
 * DDL 任务看板响应（{@code 04} `API-DDL-003`）。
 *
 * <p>{@code pendingChangeDrafts} 在 TASK-703 固定为空列表；Redis 草案读取属 TASK-704。
 */
public record DdlTaskBoardVO(
        List<DdlTaskVO> pendingInboxTasks,
        List<TaskChangeDraftVO> pendingChangeDrafts,
        List<DdlTaskVO> activeTasks,
        List<DdlTaskVO> completedTasks) {
}
