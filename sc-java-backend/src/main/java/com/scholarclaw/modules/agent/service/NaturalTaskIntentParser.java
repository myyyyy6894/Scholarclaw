package com.scholarclaw.modules.agent.service;

import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.agent.dto.PendingAction;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 自然语言任务管理的严格结构化解析（`01` FR-DDL-004、`06` TASK-706）。
 *
 * <p>刻意采用**保守规则**而不是关键词子串直接执行：
 * <ul>
 *   <li>先扫描全文所有“像任务编号”的 token；出现任何非法 token（`T87abc`、`xT87`、
 *       `T87.5`、`T87_foo`、超出 long 的编号）时，整条命令返回 {@code CLARIFY}，
 *       不对任何合法编号执行写操作；</li>
 *   <li>按**分句**识别动作，并只把该分句内的任务编号绑定到该分句的动作，
 *       不使用全句全局编号列表去执行分句动作；</li>
 *   <li>否定词只在同一分句内约束动作词；出现否定词的写操作只能查询或澄清；</li>
 *   <li>`吗`、`呢`、问号与查询词统一识别为只读查询；</li>
 *   <li>多个任务的写操作进入批量二次确认，由编排层整批执行或整批零执行。</li>
 * </ul>
 */
@Component
public final class NaturalTaskIntentParser {

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CLOCK =
            Pattern.compile("(?<!\\d)(\\d{2}:\\d{2}(?::\\d{2})?)(?!\\d)");

    /** 中文分句边界；问号/吗/呢保留在分句内，用于识别“完成 T36 吗”这类查询。 */
    private static final Pattern CLAUSE_DELIMITER = Pattern.compile("[，,。.；;！!\\n]");
    private static final Pattern NEGATION = Pattern.compile(
            "(?<!特)别|不要|不用|不必|不想|无需|尚未|还没|没有|未|不|勿|莫");

    private static final String[] QUERY_WORDS = {
            "查询", "查看", "看看", "详情", "状态", "完成了吗", "完成了没", "完成情况",
            "怎么样了", "是否", "什么情况", "进度", "list", "show"};
    private static final String[] LIST_WORDS = {
            "列出", "列表", "清单", "有哪些", "所有任务", "任务列表"};
    private static final String[] CANCEL_WORDS = {"删除", "删掉", "取消", "作废", "delete", "cancel"};
    private static final String[] COMPLETE_WORDS = {"完成", "做完", "搞定", "已交", "done", "finished"};
    private static final String[] CONFIRM_WORDS = {"确认", "激活", "confirm"};
    private static final String[] UPDATE_WORDS = {"修改", "改成", "改到", "延期", "推迟", "调整", "edit"};

    /** 任务编号最大位数：与 MySQL BIGINT 有符号范围一致。 */
    private static final int MAX_TASK_CODE_DIGITS = 19;

    public TaskCommand parse(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        ScanResult scan = scanTaskCodes(text);
        LocalDate dueDate = isoDate(text);
        LocalTime dueTime = clockTime(text);

        // 任何一个非法编号都让整条命令失去写资格：宁可澄清，不得误完成/误取消。
        if (scan.invalid()) {
            return new TaskCommand(AgentToolAction.CLARIFY, scan.ids(), dueDate, dueTime);
        }

        List<Clause> clauses = splitClauses(text);
        ClauseActions clauseActions = analyzeClauses(clauses, scan.codes());

        // 有否定写操作时，绝不执行任何写操作。
        if (clauseActions.negatedWrite()) {
            return new TaskCommand(scan.ids().isEmpty()
                    ? AgentToolAction.CLARIFY : AgentToolAction.TASK_QUERY,
                    scan.ids(), dueDate, dueTime);
        }
        if (clauseActions.actions().contains(Action.UNCOMPLETE)) {
            return new TaskCommand(AgentToolAction.CLARIFY, scan.ids(), null, null);
        }

        Set<Action> writes = EnumSet.noneOf(Action.class);
        for (Action action : clauseActions.actions()) {
            if (action.isWrite()) {
                writes.add(action);
            }
        }
        boolean hasReadAction = clauseActions.actions().contains(Action.QUERY)
                || clauseActions.actions().contains(Action.LIST);

        // 多种写动作 / 写动作与查询混合 / 有编号却不属于任何动作的分句：无法确定用户意图，必须澄清。
        if (writes.size() > 1
                || (writes.size() == 1 && hasReadAction)
                || clauseActions.danglingTaskIds()) {
            return new TaskCommand(AgentToolAction.CLARIFY, scan.ids(), dueDate, dueTime);
        }

        if (writes.size() == 1) {
            Action action = writes.iterator().next();
            List<Long> boundIds = clauseActions.idsFor(action);
            if (boundIds.isEmpty()) {
                // 例如“完成 T87abc”：动作明确但编号非法/不在同一分句，不得猜测写库。
                return new TaskCommand(AgentToolAction.CLARIFY, scan.ids(), dueDate, dueTime);
            }
            return new TaskCommand(action.toolAction(), boundIds, dueDate, dueTime);
        }

        if (clauseActions.actions().contains(Action.LIST)) {
            return new TaskCommand(AgentToolAction.TASK_LIST, scan.ids(), dueDate, dueTime);
        }
        if (clauseActions.actions().contains(Action.QUERY)) {
            // 查询是只读操作：可以返回全句提到的编号（不受分句绑定限制）。
            return new TaskCommand(scan.ids().isEmpty()
                    ? AgentToolAction.CLARIFY : AgentToolAction.TASK_QUERY,
                    scan.ids(), dueDate, dueTime);
        }
        return new TaskCommand(AgentToolAction.CLARIFY, scan.ids(), dueDate, dueTime);
    }

    private static ClauseActions analyzeClauses(List<Clause> clauses, List<TaskCode> codes) {
        Set<Action> actions = EnumSet.noneOf(Action.class);
        List<Set<Action>> perClauseActions = new ArrayList<>(clauses.size());
        List<List<Long>> perClauseIds = new ArrayList<>(clauses.size());
        boolean negatedWrite = false;
        boolean dangling = false;

        for (int i = 0; i < clauses.size(); i++) {
            Clause clause = clauses.get(i);
            List<Long> clauseIds = new ArrayList<>();
            for (TaskCode code : codes) {
                if (clause.contains(code.start())) {
                    clauseIds.add(code.value());
                }
            }
            perClauseIds.add(List.copyOf(clauseIds));
            String lower = clause.text().toLowerCase(Locale.ROOT).trim();
            Set<Action> clauseActions = EnumSet.noneOf(Action.class);
            if (lower.isEmpty()) {
                perClauseActions.add(clauseActions);
                continue;
            }
            if (lower.contains("取消完成") || lower.contains("撤销完成")
                    || lower.contains("取消标记完成") || lower.contains("取消勾选完成")) {
                clauseActions.add(Action.UNCOMPLETE);
            } else if (isQuery(lower)) {
                clauseActions.add(Action.QUERY);
            } else {
                clauseActions.addAll(clauseActions(lower));
            }
            if (clauseActions.stream().anyMatch(Action::isWrite)
                    && NEGATION.matcher(lower).find()) {
                negatedWrite = true;
            }
            if (!clauseIds.isEmpty() && clauseActions.isEmpty()) {
                // 例如“完成 T36，T37”或“完成 T36，那一份”：编号/指代没有绑定动作。
                dangling = true;
            }
            actions.addAll(clauseActions);
            perClauseActions.add(clauseActions);
        }
        return new ClauseActions(actions, perClauseActions, perClauseIds, negatedWrite, dangling);
    }

    private static boolean isQuery(String lower) {
        return lower.contains("?") || lower.contains("？")
                || lower.contains("吗") || lower.contains("呢")
                || containsAny(lower, QUERY_WORDS);
    }

    private static Set<Action> clauseActions(String lower) {
        Set<Action> actions = EnumSet.noneOf(Action.class);
        if (containsAny(lower, LIST_WORDS)) {
            actions.add(Action.LIST);
        }
        if (containsAny(lower, CANCEL_WORDS)) {
            actions.add(Action.CANCEL);
        }
        if (containsAny(lower, COMPLETE_WORDS)) {
            actions.add(Action.DONE);
        }
        if (containsAny(lower, CONFIRM_WORDS)) {
            actions.add(Action.CONFIRM);
        }
        if (containsAny(lower, UPDATE_WORDS)) {
            actions.add(Action.UPDATE);
        }
        return actions;
    }

    /**
     * 扫描全文任务编号，同时记录非法 token。
     *
     * <p>非法 token 的判定必须宽松于合法编号：只要形如 `T<数字>` 但不是完整合法编号
     * （前后边界被字母/数字/下划线/点破坏，或数字溢出），就认定为非法。
     * 这样 `T87abc` 不会退化为“什么都没有”而让同一句里的 `T36` 被误执行。
     */
    private static ScanResult scanTaskCodes(String text) {
        List<TaskCode> codes = new ArrayList<>();
        boolean invalid = false;
        int i = 0;
        while (i < text.length()) {
            char current = text.charAt(i);
            if ((current != 'T' && current != 't') || i + 1 >= text.length()
                    || !isDigit(text.charAt(i + 1))) {
                i++;
                continue;
            }
            int tokenStart = i;
            if (i > 0 && (text.charAt(i - 1) == '#' || text.charAt(i - 1) == '＃')) {
                tokenStart = i - 1;
            }
            boolean badBoundary = tokenStart > 0 && isTaskCodeBody(text.charAt(tokenStart - 1));
            int digitEnd = i + 1;
            while (digitEnd < text.length() && isDigit(text.charAt(digitEnd))) {
                digitEnd++;
            }
            boolean badSuffix = digitEnd < text.length() && isTaskCodeBody(text.charAt(digitEnd));
            String digits = text.substring(i + 1, digitEnd);
            Long value = null;
            if (digits.length() <= MAX_TASK_CODE_DIGITS) {
                try {
                    long parsed = Long.parseLong(digits);
                    if (parsed > 0) {
                        value = parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // 19 位但超出 Long.MAX_VALUE：按非法编号处理。
                }
            }
            if (badBoundary || badSuffix || value == null) {
                invalid = true;
            } else {
                codes.add(new TaskCode(value, tokenStart));
            }
            i = digitEnd;
        }
        return new ScanResult(codes, invalid);
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    /** 编号 token 允许携带的“粘连字符”；出现即说明不是独立编号。 */
    private static boolean isTaskCodeBody(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || isDigit(value)
                || value == '_'
                || value == '．' || value == '.';
    }

    private static List<Clause> splitClauses(String text) {
        List<Clause> clauses = new ArrayList<>();
        Matcher matcher = CLAUSE_DELIMITER.matcher(text);
        int start = 0;
        while (matcher.find()) {
            clauses.add(new Clause(text.substring(start, matcher.start()), start));
            start = matcher.end();
        }
        clauses.add(new Clause(text.substring(start), start));
        return clauses;
    }

    private static LocalDate isoDate(String text) {
        Matcher matcher = ISO_DATE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime clockTime(String text) {
        Matcher matcher = CLOCK.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalTime.parse(matcher.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private enum Action {
        CANCEL(AgentToolAction.TASK_CANCEL, true),
        DONE(AgentToolAction.TASK_DONE, true),
        CONFIRM(AgentToolAction.TASK_CONFIRM, true),
        UPDATE(AgentToolAction.TASK_UPDATE, true),
        LIST(AgentToolAction.TASK_LIST, false),
        QUERY(AgentToolAction.TASK_QUERY, false),
        UNCOMPLETE(AgentToolAction.CLARIFY, false);

        private final AgentToolAction toolAction;
        private final boolean write;

        Action(AgentToolAction toolAction, boolean write) {
            this.toolAction = toolAction;
            this.write = write;
        }

        AgentToolAction toolAction() {
            return toolAction;
        }

        boolean isWrite() {
            return write;
        }
    }

    /** 分句分析结果：动作集合、每个分句的动作/编号、否定写标记与“编号悬空”标记。 */
    private record ClauseActions(Set<Action> actions, List<Set<Action>> perClauseActions,
                                 List<List<Long>> perClauseIds, boolean negatedWrite,
                                 boolean danglingTaskIds) {

        /** 只返回与指定写动作同分句的任务编号（保持出现顺序、去重）。 */
        List<Long> idsFor(Action action) {
            Set<Long> ids = new LinkedHashSet<>();
            for (int i = 0; i < perClauseActions.size(); i++) {
                if (perClauseActions.get(i).contains(action)) {
                    ids.addAll(perClauseIds.get(i));
                }
            }
            return List.copyOf(ids);
        }
    }

    private record Clause(String text, int start) {
        boolean contains(int position) {
            return position >= start && position < start + text.length();
        }
    }

    private record TaskCode(long value, int start) {
    }

    private record ScanResult(List<TaskCode> codes, boolean invalid) {

        List<Long> ids() {
            Set<Long> ids = new LinkedHashSet<>();
            for (TaskCode code : codes) {
                ids.add(code.value());
            }
            return List.copyOf(ids);
        }
    }

    /** 解析结果；`taskIds` 为写操作实际绑定的编号（查询时为全句去重编号）。 */
    public record TaskCommand(AgentToolAction action, List<Long> taskIds,
                              LocalDate dueDate, LocalTime dueTime) {

        public boolean isWriteAction() {
            return action == AgentToolAction.TASK_DONE
                    || action == AgentToolAction.TASK_CANCEL
                    || action == AgentToolAction.TASK_UPDATE
                    || action == AgentToolAction.TASK_CONFIRM;
        }

        public List<Long> ids() {
            return new ArrayList<>(taskIds);
        }

        public PendingAction toPendingCancel(String rawText, String createdAt) {
            return PendingAction.cancel(taskIds.getFirst(), rawText, createdAt);
        }
    }
}
