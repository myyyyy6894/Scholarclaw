package com.scholarclaw.modules.agent.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 显式斜杠指令的确定性解析结果（`01` FR-IM-002 第一级）。
 *
 * <p>解析只发生在 Java：Python `AUTO_ROUTE` 只服务无前缀自然语言，避免同一消息在两层
 * 得到不同意图。参数非法时由 {@code SlashCommandRouter} 抛出可读错误，编排层回复指引，
 * 不猜测、不执行写操作。
 *
 * <p>{@code taskIds} 保存**全部**任务编号：`/done T95 T96` 不允许只处理第一个。
 * 多任务写操作由编排层写入 `pending_action` 二次确认，再整批执行或整批零执行。
 */
public record AgentCommand(
        Kind kind,
        String argument,
        List<Long> taskIds,
        LocalDate dueDate,
        LocalTime dueTime) {

    public enum Kind {
        DDL,
        LIST,
        DONE,
        CONFIRM,
        EDIT,
        KB
    }

    public AgentCommand {
        taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
    }

    /** 单任务兼容访问器；多任务时返回第一个，调用方应优先使用 {@link #taskIds()}。 */
    public Long taskId() {
        return taskIds.isEmpty() ? null : taskIds.getFirst();
    }

    public boolean isBatchTaskCommand() {
        return taskIds.size() > 1;
    }

    public static AgentCommand ddl(String text) {
        return new AgentCommand(Kind.DDL, text, List.of(), null, null);
    }

    public static AgentCommand list(String group) {
        return new AgentCommand(Kind.LIST, group, List.of(), null, null);
    }

    public static AgentCommand kb(String query) {
        return new AgentCommand(Kind.KB, query, List.of(), null, null);
    }

    public static AgentCommand task(Kind kind, Long taskId, LocalDate dueDate, LocalTime dueTime) {
        return new AgentCommand(kind, null, List.of(taskId), dueDate, dueTime);
    }

    public static AgentCommand tasks(Kind kind, List<Long> taskIds, LocalDate dueDate,
                                     LocalTime dueTime) {
        return new AgentCommand(kind, null, taskIds, dueDate, dueTime);
    }
}
