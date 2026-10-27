package com.scholarclaw.modules.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 待二次确认的动作（Redis `sc:u:{uid}:pending_action`，TTL 10 分钟）。
 *
 * <p>用于 `FR-DDL-004` 的硬性边界：删除/取消、批量修改、以及置信度 `< 0.80` 的
 * 自然语言修改必须先让用户确认。
 *
 * <p>批量动作保存**全部**任务编号：确认后整批执行或整批零执行，绝不只处理第一个。
 * `task_id` 保留为第一个编号，兼容旧回执与现有前端日志；新代码应读取 `task_ids`。
 */
public record PendingAction(
        AgentToolAction action,
        @JsonSerialize(using = ToStringSerializer.class) Long taskId,
        @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> taskIds,
        LocalDate dueDate,
        LocalTime dueTime,
        String rawText,
        String createdAt) {

    public PendingAction {
        if (taskIds == null || taskIds.isEmpty()) {
            taskIds = taskId == null ? List.of() : List.of(taskId);
        } else {
            taskIds = List.copyOf(taskIds);
        }
        if (taskId == null && !taskIds.isEmpty()) {
            taskId = taskIds.getFirst();
        }
    }

    public static PendingAction cancel(Long taskId, String rawText, String createdAt) {
        return cancel(List.of(taskId), rawText, createdAt);
    }

    public static PendingAction cancel(List<Long> taskIds, String rawText, String createdAt) {
        return new PendingAction(AgentToolAction.TASK_CANCEL, first(taskIds), taskIds,
                null, null, rawText, createdAt);
    }

    public static PendingAction done(List<Long> taskIds, String rawText, String createdAt) {
        return new PendingAction(AgentToolAction.TASK_DONE, first(taskIds), taskIds,
                null, null, rawText, createdAt);
    }

    public static PendingAction update(Long taskId, LocalDate dueDate, LocalTime dueTime,
                                       String rawText, String createdAt) {
        return update(List.of(taskId), AgentToolAction.TASK_UPDATE, dueDate, dueTime,
                rawText, createdAt);
    }

    public static PendingAction update(List<Long> taskIds, AgentToolAction action,
                                       LocalDate dueDate, LocalTime dueTime,
                                       String rawText, String createdAt) {
        return new PendingAction(action, first(taskIds), taskIds, dueDate, dueTime,
                rawText, createdAt);
    }

    public boolean isBatch() {
        return taskIds.size() > 1;
    }

    private static Long first(List<Long> taskIds) {
        return taskIds == null || taskIds.isEmpty() ? null : taskIds.getFirst();
    }
}
