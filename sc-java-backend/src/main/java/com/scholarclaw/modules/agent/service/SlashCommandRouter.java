package com.scholarclaw.modules.agent.service;

import com.scholarclaw.modules.agent.dto.AgentCommand;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 第一级确定性指令路由（`01` FR-IM-002：显式斜杠指令零 LLM 消耗）。
 *
 * <p>只解析参数，不执行工具；执行由 {@link AgentOrchestratorService} 调用 Java 业务服务完成。
 * 未列出的斜杠文本不在此层猜测，返回空交由自然语言分类处理。
 *
 * <p>多任务参数必须**完整解析**：`/done T95 T96` 不能被静默截断成只处理 `T95`；
 * 日期/时刻之后的未知参数必须报错，不能忽略。
 */
@Component
public class SlashCommandRouter {

    private static final Pattern TASK_ID =
            Pattern.compile("^[#＃]?[Tt]?(\\d{1,19})$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}(?::\\d{2})?$");

    /** 返回解析结果；不是受支持指令时为空。参数非法时抛 {@link AgentCommandException}。 */
    public Optional<AgentCommand> parse(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (!text.startsWith("/")) {
            return Optional.empty();
        }
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        return switch (command) {
            case "/ddl" -> ddl(argument);
            case "/kb" -> Optional.of(AgentCommand.kb(argument));
            case "/list" -> Optional.of(AgentCommand.list(argument));
            case "/done" -> Optional.of(taskList(AgentCommand.Kind.DONE, argument));
            case "/confirm" -> Optional.of(confirm(argument));
            case "/edit" -> Optional.of(withDateTime(AgentCommand.Kind.EDIT, argument));
            default -> Optional.empty();
        };
    }

    private static Optional<AgentCommand> ddl(String argument) {
        if (argument.length() < 4) {
            throw new AgentCommandException(
                    "请在 /ddl 后粘贴通知原文（至少 4 个字符），例如：/ddl 本周五下午5点前提交实验报告。");
        }
        return Optional.of(AgentCommand.ddl(argument));
    }

    /** `/done T95 T96`：每个 token 都必须是任务编号。 */
    private static AgentCommand taskList(AgentCommand.Kind kind, String argument) {
        String[] tokens = argument.isBlank() ? new String[0] : argument.split("\\s+");
        if (tokens.length == 0) {
            throw new AgentCommandException("请在指令后提供任务编号，例如：/done T102。");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : tokens) {
            ids.add(parseTaskId(token));
        }
        return AgentCommand.tasks(kind, List.copyOf(ids), null, null);
    }

    /**
     * `/confirm T923`：沿用任务已有的截止时间直接确认激活；
     * `/confirm T923 2026-10-31 05:00`：先改时间再确认（兼容旧语法）。
     *
     * <p>不带日期/时刻时只允许单个任务，避免“统一时间”语义被隐式套用到多任务。
     */
    private static AgentCommand confirm(String argument) {
        String[] tokens = argument.isBlank() ? new String[0] : argument.split("\\s+");
        boolean hasDate = false;
        boolean hasTime = false;
        for (String token : tokens) {
            hasDate |= DATE.matcher(token).matches();
            hasTime |= TIME.matcher(token).matches();
        }
        if (hasDate || hasTime) {
            return withDateTime(AgentCommand.Kind.CONFIRM, argument);
        }
        if (tokens.length == 0) {
            throw new AgentCommandException(
                    "请在 /confirm 后提供任务编号，例如：/confirm T923。");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : tokens) {
            ids.add(parseTaskId(token));
        }
        if (ids.size() > 1) {
            throw new AgentCommandException(
                    "一次只能确认一个未带时间的任务；批量确认请提供统一日期与时刻，"
                            + "例如：/confirm T923 T924 2026-10-31 05:00。");
        }
        return AgentCommand.tasks(AgentCommand.Kind.CONFIRM, List.copyOf(ids), null, null);
    }

    /**
     * `/edit T85 2026-12-01 22:00 T86`：日期与时刻之外的所有 token 都必须是任务编号。
     *
     * <p>允许任务编号出现在日期之前或之后，但未知参数一律拒绝。
     */
    private static AgentCommand withDateTime(AgentCommand.Kind kind, String argument) {
        String[] tokens = argument.isBlank() ? new String[0] : argument.split("\\s+");
        if (tokens.length < 3) {
            throw new AgentCommandException(
                    "请提供任务编号、日期和时刻，例如：/confirm T104 2026-10-25 22:00。");
        }
        int dateIndex = -1;
        int timeIndex = -1;
        for (int i = 0; i < tokens.length; i++) {
            if (DATE.matcher(tokens[i]).matches()) {
                if (dateIndex >= 0) {
                    throw new AgentCommandException("一次只能提供一组日期与时刻。");
                }
                dateIndex = i;
                if (i + 1 >= tokens.length || !TIME.matcher(tokens[i + 1]).matches()) {
                    throw new AgentCommandException(
                            "日期后必须紧跟时刻，例如：2026-10-25 22:00。");
                }
                timeIndex = i + 1;
            } else if (TIME.matcher(tokens[i]).matches()) {
                if (i != timeIndex) {
                    throw new AgentCommandException("时刻必须紧跟在日期之后。");
                }
            }
        }
        if (dateIndex < 0) {
            throw new AgentCommandException(
                    "请提供 YYYY-MM-DD 日期与 HH:mm 时刻，例如：/confirm T104 2026-10-25 22:00。");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < tokens.length; i++) {
            if (i == dateIndex || i == timeIndex) {
                continue;
            }
            if (DATE.matcher(tokens[i]).matches() || TIME.matcher(tokens[i]).matches()) {
                continue;
            }
            ids.add(parseTaskId(tokens[i]));
        }
        LocalDate dueDate;
        LocalTime dueTime;
        try {
            dueDate = LocalDate.parse(tokens[dateIndex]);
            dueTime = LocalTime.parse(tokens[timeIndex]);
        } catch (DateTimeParseException e) {
            throw new AgentCommandException("日期或时刻不是合法值，请检查后重试。");
        }
        if (ids.isEmpty()) {
            throw new AgentCommandException("请在指令后提供任务编号，例如：/done T102。");
        }
        return AgentCommand.tasks(kind, new ArrayList<>(ids), dueDate, dueTime);
    }

    private static Long parseTaskId(String token) {
        Matcher matcher = TASK_ID.matcher(token);
        if (!matcher.matches()) {
            throw new AgentCommandException("任务编号格式应为 T102 或 #T102；不支持忽略多余参数。");
        }
        try {
            long value = Long.parseLong(matcher.group(1));
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AgentCommandException("任务编号必须为正的十进制数字。");
        }
    }
}
