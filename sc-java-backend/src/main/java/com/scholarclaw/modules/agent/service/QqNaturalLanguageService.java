package com.scholarclaw.modules.agent.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentIntent;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.agent.service.QqNaturalRoute.Action;
import com.scholarclaw.modules.agent.service.QqNaturalRoute.Decision;
import com.scholarclaw.modules.agent.service.QqNaturalRoute.ScheduleView;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.ddl.service.DdlTaskService.AssistantTaskChange;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.OccurrenceView;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Resolves scoped references and requires a fresh, single-use confirmation before business mutations. */
@Service
public class QqNaturalLanguageService {
    enum Phase { CHOOSE, TIME, CONFIRM, REFERENCE }
    record Turn(Phase phase, Action action, List<String> taskIds, LocalDate date, LocalTime time,
                String window, OffsetDateTime taskVersion, Boolean reminderEnabled, Long reminderVersion,
                String timezone, String token) { }

    private static final int MAX_CHOICES = 6;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CHOICE = Pattern.compile(
            "^(?:刚才(?:的)?|选)?(?:第)?([1-6一二两三四五六])(?:个|项|条)?(?:[，, ]*(?:就这次|只这一次))?[。！!]?$");
    private static final Pattern CANCEL = Pattern.compile("^(?:取消|算了|放弃|否|不用了|不要执行)[。！!]?$");
    private static final Pattern CONFIRM = Pattern.compile("^确认\\s*([A-Z0-9]{6})?[。！!]?$");
    private static final Pattern FOLLOW_UP = Pattern.compile("^(?:就|改到|改成|截止到)\\s*|[，,。！？!?吧的\\s]+$");
    private static final Pattern REFERENCE = Pattern.compile(".*(?:刚才|上一个|那[个项条]|这[个项条]).*");
    private static final Pattern TASK_CODE = Pattern.compile("#?[Tt]([1-9][0-9]{0,18})");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm XXX");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QqConversationStore store;
    private final QqNaturalRoute router;
    private final QqConfirmedWriteService writes;
    private final ScheduleService schedules;
    private final ScheduleReminderService reminders;
    private final DdlTaskService tasks;
    private final Clock clock;

    public QqNaturalLanguageService(QqConversationStore store, QqNaturalRoute router, QqConfirmedWriteService writes,
            ScheduleService schedules, ScheduleReminderService reminders, DdlTaskService tasks, Clock clock) {
        this.store = store;
        this.router = router;
        this.writes = writes;
        this.schedules = schedules;
        this.reminders = reminders;
        this.tasks = tasks;
        this.clock = clock;
    }

    AgentReply once(AgentInboundMessage message, Supplier<AgentReply> work) { return store.once(message, work); }

    AgentReply verifiedLegacyWrite(AgentInboundMessage message, Supplier<AgentReply> work) {
        return writes.withVerifiedBinding(message, work);
    }

    AgentReply continueTurn(AgentInboundMessage message, ResolvedUser user) {
        String scope = QqConversationStore.scope(message);
        Turn turn = store.load(scope);
        String text = message.rawText().strip();
        var confirmation = CONFIRM.matcher(text.toUpperCase(Locale.ROOT));
        boolean confirms = confirmation.matches();
        if (turn == null) return confirms && confirmation.group(1) != null ? guide("确认凭据已过期，请重新提出操作。") : null;
        if (turn.phase() != Phase.REFERENCE && CANCEL.matcher(text).matches()) {
            store.clear(scope);
            return answer(AgentToolAction.IGNORE, "已取消本次操作。", null, false);
        }
        if (turn.phase() == Phase.CONFIRM && confirms) {
            if (!Objects.equals(confirmation.group(1), turn.token())) {
                return guide("请回复本轮回显中的「确认 " + turn.token() + "」。");
            }
            store.clear(scope);
            return apply(message, turn);
        }
        var choice = CHOICE.matcher(text);
        if ((turn.phase() == Phase.CHOOSE || turn.phase() == Phase.TIME) && turn.timezone() != null
                && !turn.timezone().equals(user.timezoneName())) {
            store.clear(scope);
            return guide("你的时区已变化，请重新提出截止时间。");
        }
        if (choice.matches() && (turn.phase() == Phase.CHOOSE || turn.phase() == Phase.REFERENCE)) {
            int number = QqRelativeTime.number(choice.group(1));
            if (number < 1 || number > turn.taskIds().size()) {
                return guide("请从本轮 " + turn.taskIds().size() + " 个候选中选择。");
            }
            store.clear(scope);
            return target(message, user, turn.action(), List.of(turn.taskIds().get(number - 1)),
                    turn.date(), turn.time(), turn.window(), turn.taskVersion());
        }
        if (turn.phase() == Phase.TIME) {
            String date = QqRelativeTime.dateFragment(text);
            String time = QqRelativeTime.clockFragment(text);
            String remainder = date == null ? text : text.replace(date, "");
            if (time != null) remainder = remainder.replace(time, "");
            remainder = FOLLOW_UP.matcher(remainder).replaceAll("");
            if ((date != null || time != null) && remainder.isBlank()) {
                LocalDate day = date == null ? turn.date() : QqRelativeTime.date(date, reference(message, user).toLocalDate());
                LocalTime wallClock = time == null ? turn.time() : QqRelativeTime.clock(time, turn.window());
                return target(message, user, turn.action(), turn.taskIds(), day, wallClock, turn.window(), turn.taskVersion());
            }
        }
        if (turn.phase() != Phase.REFERENCE) store.clear(scope);
        if (confirms && confirmation.group(1) != null) return guide("确认凭据与当前操作不匹配，请重新提出操作。");
        return null;
    }

    Decision classify(AgentInboundMessage message, ResolvedUser user) { return router.classify(message, user); }

    AgentReply execute(AgentInboundMessage message, ResolvedUser user, Decision decision) {
        String scope = QqConversationStore.scope(message);
        return switch (decision.action()) {
            case SCHEDULE_QUERY -> schedule(message, user, decision);
            case REMINDER_GET -> answer(AgentToolAction.REMINDER_GET, reminderText(reminders.preference(message.userId())), null, false);
            case REMINDER_SET -> prepareReminder(message, decision.enabled());
            case DDL_LIST -> listTasks(message);
            case DDL_QUERY, DDL_RESCHEDULE, DDL_DONE, DDL_CANCEL, DDL_CONFIRM -> {
                var ref = reference(message, user);
                LocalDate date = QqRelativeTime.date(decision.date(), ref.toLocalDate());
                String window = QqRelativeTime.window(decision.time() == null ? decision.date() : decision.time());
                LocalTime time = QqRelativeTime.clock(decision.time(), window);
                List<String> candidates = candidates(message, decision, store.load(scope));
                if (candidates.isEmpty()) yield guide("没有找到对应的任务。请提供完整标题或任务编号。");
                if (candidates.size() > MAX_CHOICES) yield guide("找到多项相似任务，请提供更完整的标题或任务编号。");
                yield target(message, user, decision.action(), candidates, date, time, window, null);
            }
            case CHAT -> new AgentReply(AgentIntent.CHITCHAT_HELP, decision.confidence(), AgentToolAction.CHAT_REPLY,
                    decision.chatReply(), null, null, false);
            case IGNORE -> answer(AgentToolAction.IGNORE, "好，这次先不操作。", null, false);
            case UNSUPPORTED -> guide("QQ 目前支持查询课表、当前学期的课前 20 分钟提醒以及待办管理。");
            case CLARIFY -> guide("请说明要查哪一天的课、调整哪项提醒，或修改哪一条待办。");
            case KB_QA -> new AgentReply(AgentIntent.KB_QA, decision.confidence(), AgentToolAction.KB_QA_GUIDE,
                    "请到 Web 工作台的「课程问答」选择课程后提问。", null, null, false);
            case KB_INGEST -> new AgentReply(AgentIntent.KB_INGEST, decision.confidence(), AgentToolAction.KB_INGEST_GUIDE,
                    "请到 Web 工作台的「知识库」上传课件。", null, null, false);
            default -> guide("可以和我聊聊天，也可以问「明早第一节在哪」、查看待办或开启课前 20 分钟提醒。快捷指令有 /list、/ddl、/edit。");
        };
    }

    private AgentReply prepareReminder(AgentInboundMessage message, Boolean enabled) {
        var current = reminders.preference(message.userId());
        if (enabled == null || enabled == current.enabled()) {
            return answer(AgentToolAction.REMINDER_GET, reminderText(current), null, false);
        }
        String detail = enabled ? "开启当前学期每次开课前 20 分钟提醒。" : "关闭当前学期课前提醒。";
        if (enabled) detail += "站内提醒照常，" + delivery(current.delivery());
        String token = token();
        store.save(QqConversationStore.scope(message), new Turn(Phase.CONFIRM, Action.REMINDER_SET, List.of(),
                null, null, null, null, enabled, current.version(), null, token));
        return confirm(detail, token, AgentToolAction.REMINDER_SET, null);
    }

    private AgentReply listTasks(AgentInboundMessage message) {
        var matches = tasks.searchForAssistant(message.userId(), null, false);
        if (matches.isEmpty()) return answer(AgentToolAction.TASK_LIST, "当前没有待办。", null, false);
        List<DdlTaskVO> shown = matches.stream().limit(MAX_CHOICES).toList();
        store.save(QqConversationStore.scope(message), referenceTurn(ids(shown)));
        String more = matches.size() > shown.size() ? "\n更多待办可用 /list 或在 Web 查看。" : "";
        return answer(AgentToolAction.TASK_LIST, choices(shown) + more, null, false);
    }

    private List<String> candidates(AgentInboundMessage message, Decision decision, Turn previous) {
        String hint = decision.taskHint();
        if (hint == null) return List.of();
        if (REFERENCE.matcher(hint).matches()) {
            return previous != null && previous.phase() == Phase.REFERENCE ? previous.taskIds() : List.of();
        }
        var code = TASK_CODE.matcher(hint.strip());
        if (code.matches()) {
            // Only a literal task code from the user can enter this path; the domain checks ownership.
            try { return List.of(Long.toString(Long.parseLong(code.group(1)))); }
            catch (NumberFormatException e) { return List.of(); }
        }
        var matching = tasks.searchForAssistant(message.userId(), hint, decision.action() == Action.DDL_QUERY);
        if (matching.isEmpty() && hint.endsWith("作业") && hint.length() > 2) {
            matching = tasks.searchForAssistant(message.userId(), hint.substring(0, hint.length() - 2),
                    decision.action() == Action.DDL_QUERY);
        }
        return ids(matching);
    }

    private AgentReply target(AgentInboundMessage message, ResolvedUser user, Action action, List<String> ids,
                              LocalDate date, LocalTime time, String window, OffsetDateTime expected) {
        String scope = QqConversationStore.scope(message);
        List<DdlTaskVO> found;
        try {
            found = ids.stream().map(id -> tasks.getTask(message.userId(), Long.parseLong(id))).toList();
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) throw e;
            store.clear(scope);
            return guide("该任务已不存在或无法访问，请重新选择。");
        }
        if (found.size() > 1) {
            store.save(scope, new Turn(Phase.CHOOSE, action, ids, date, time, window, null, null, null,
                    action == Action.DDL_RESCHEDULE ? user.timezoneName() : null, null));
            return guide("找到多个候选，请回复序号。\n" + choices(found));
        }
        DdlTaskVO task = found.getFirst();
        if (action == Action.DDL_QUERY) {
            store.save(scope, referenceTurn(ids));
            return answer(AgentToolAction.TASK_QUERY, label(task), task, false);
        }
        if (expected != null && !Objects.equals(expected, task.updatedAt())) {
            store.clear(scope);
            return guide("任务在澄清期间已变化，请重新提出操作。");
        }
        if (action == Action.DDL_RESCHEDULE && (date == null || time == null)) {
            store.save(scope, new Turn(Phase.TIME, action, ids, date, time, window, task.updatedAt(), null, null, user.timezoneName(), null));
            String missing = date == null && time == null ? "哪一天几点截止？" : date == null ? "哪一天截止？" : date + " 几点截止？";
            return guide("已找到「" + task.taskTitle() + "」" + task.displayCode() + "。请问" + missing);
        }
        if (action == Action.DDL_CONFIRM && task.dueDate() == null) {
            return guide("「" + task.taskTitle() + "」需要先补齐具体截止时间。");
        }
        if (action == Action.DDL_RESCHEDULE && user.zoneId().getRules().getValidOffsets(date.atTime(time)).size() != 1) {
            store.clear(scope);
            return guide("该钟点在 " + user.timezoneName() + " 不存在或有歧义，请改用另一时刻。");
        }
        String token = token();
        String detail = switch (action) {
            case DDL_RESCHEDULE -> "将「" + task.taskTitle() + "」" + task.displayCode() + " 改到 "
                    + ZonedDateTime.of(date, time, user.zoneId()).format(DISPLAY_DATE) + "（" + user.timezoneName() + "）。";
            case DDL_CANCEL -> "取消「" + task.taskTitle() + "」" + task.displayCode() + "。";
            case DDL_DONE -> "将「" + task.taskTitle() + "」" + task.displayCode() + " 标记完成。";
            default -> "确认并激活「" + task.taskTitle() + "」" + task.displayCode() + "。";
        };
        store.save(scope, new Turn(Phase.CONFIRM, action, ids, date, time, window, task.updatedAt(), null, null,
                action == Action.DDL_RESCHEDULE ? user.timezoneName() : null, token));
        return confirm(detail, token, taskTool(action), task);
    }

    private AgentReply apply(AgentInboundMessage message, Turn turn) {
        if (turn.action() == Action.REMINDER_SET) {
            var updated = writes.reminder(message, turn.reminderEnabled(), turn.reminderVersion());
            return answer(AgentToolAction.REMINDER_SET, reminderText(updated), null, false);
        }
        AssistantTaskChange change = switch (turn.action()) {
            case DDL_RESCHEDULE -> AssistantTaskChange.RESCHEDULE;
            case DDL_DONE -> AssistantTaskChange.DONE;
            case DDL_CANCEL -> AssistantTaskChange.CANCEL;
            case DDL_CONFIRM -> AssistantTaskChange.CONFIRM;
            default -> throw new IllegalStateException("操作无法确认");
        };
        long id = Long.parseLong(turn.taskIds().getFirst());
        var task = writes.task(message, id, turn.taskVersion(), change, turn.date(), turn.time(), turn.timezone());
        String result = task == null ? "任务 #T" + id + " 已取消。" : "已处理 " + label(task);
        if (task != null) store.save(QqConversationStore.scope(message), referenceTurn(turn.taskIds()));
        return answer(taskTool(turn.action()), result, task, false);
    }

    private AgentReply schedule(AgentInboundMessage message, ResolvedUser user, Decision decision) {
        var active = schedules.activeTerm(message.userId());
        if (active.isEmpty()) return guide("还没有当前学期。请在 Web 的「我的课表」设置学期并导入课程。");
        var term = active.get();
        var reference = reference(message, user);
        var view = decision.scheduleView();
        LocalDate date = QqRelativeTime.date(decision.date(), reference.toLocalDate());
        if (decision.date() != null && date == null) return guide("请提供哪一天的课表，例如「明早」或「周五」。");
        if (date == null) date = reference.toLocalDate();
        boolean week = decision.date() != null && decision.date().matches("这周|本周|下周");
        Instant start = view == ScheduleView.NEXT ? reference.toInstant() : date.atStartOfDay(user.zoneId()).toInstant();
        Instant end = date.plusDays(week || view == ScheduleView.NEXT ? 7 : 1).atStartOfDay(user.zoneId()).toInstant();
        var termZone = ZoneId.of(term.timezone());
        var candidates = schedules.occurrences(message.userId(), Long.parseLong(term.termId()),
                start.atZone(termZone).toLocalDate(), end.minusNanos(1).atZone(termZone).toLocalDate()).occurrences();
        String window = QqRelativeTime.window(decision.time() == null ? decision.date() : decision.time());
        int[] periods = view == ScheduleView.FIRST ? null : QqRelativeTime.periods(decision.period());
        var actual = candidates.stream().filter(item -> {
            var began = OffsetDateTime.parse(item.startAt()).atZoneSameInstant(user.zoneId());
            return !began.toInstant().isBefore(start) && began.toInstant().isBefore(end)
                    && inWindow(began.toLocalTime(), window) && intersectsPeriod(item, periods)
                    && (view != ScheduleView.CONFLICTS || item.conflict());
        }).toList();
        if (actual.isEmpty()) {
            return answer(AgentToolAction.SCHEDULE_QUERY, view == ScheduleView.CONFLICTS
                    ? "所查范围内没有撞课。" : "所查范围内没有已排定的课程。", null, false);
        }
        if (view == ScheduleView.FIRST || view == ScheduleView.NEXT) {
            Instant first = OffsetDateTime.parse(actual.getFirst().startAt()).toInstant();
            actual = actual.stream().filter(item -> OffsetDateTime.parse(item.startAt()).toInstant().equals(first)).toList();
        }
        String lines = actual.stream().limit(8).map(item ->
                OffsetDateTime.parse(item.startAt()).atZoneSameInstant(user.zoneId()).format(DISPLAY_TIME)
                + " " + item.title() + " · " + (item.room() == null ? "教室待定" : item.room())
                + (item.conflict() ? "（撞课）" : "")).collect(Collectors.joining("\n"));
        return answer(AgentToolAction.SCHEDULE_QUERY,
                lines + (actual.size() > 8 ? "\n其余课程请在 Web 课表查看。" : ""), null, false);
    }

    private static boolean intersectsPeriod(OccurrenceView item, int[] periods) {
        return periods == null || item.startPeriod() != null
                && item.startPeriod() <= periods[1] && item.endPeriod() >= periods[0];
    }

    private static boolean inWindow(LocalTime time, String window) {
        if (window == null) return true;
        int hour = time.getHour();
        return switch (window) {
            case "凌晨" -> hour < 6;
            case "早晨", "早上", "上午" -> hour >= 6 && hour < 12;
            case "中午" -> hour >= 11 && hour < 14;
            case "下午" -> hour >= 12 && hour < 18;
            default -> hour >= 17;
        };
    }

    private ZonedDateTime reference(AgentInboundMessage message, ResolvedUser user) {
        try { return OffsetDateTime.parse(message.referenceTime()).atZoneSameInstant(user.zoneId()); }
        catch (RuntimeException e) { return clock.instant().atZone(user.zoneId()); }
    }

    private static String reminderText(ScheduleReminderService.PreferenceView pref) {
        return pref.enabled() ? "当前学期的课前 20 分钟提醒已开启。站内会保留通知，" + delivery(pref.delivery())
                : "当前学期的课前 20 分钟提醒已关闭。";
    }
    private static String delivery(String delivery) {
        return switch (delivery) {
            case "WEB_QQ" -> "QQ 私聊按免打扰设置投递。";
            case "WEB_ONLY_UNVERIFIED" -> "验证 QQ 绑定后可同步接收。";
            default -> "当前部署未开启 QQ 主动推送。";
        };
    }

    private static Turn referenceTurn(List<String> ids) {
        return new Turn(Phase.REFERENCE, Action.DDL_QUERY, ids, null, null, null, null, null, null, null, null);
    }
    private static List<String> ids(List<DdlTaskVO> tasks) { return tasks.stream().map(task -> task.taskId().toString()).toList(); }
    private static String choices(List<DdlTaskVO> options) {
        return IntStream.range(0, options.size()).mapToObj(i -> (i + 1) + ". " + label(options.get(i)))
                .collect(Collectors.joining("\n"));
    }
    private static String label(DdlTaskVO task) {
        return "「" + task.taskTitle() + "」" + task.displayCode()
                + (task.dueAt() == null ? " · 截止待定" : " · 截止 " + task.dueAt().format(DISPLAY_TIME));
    }
    private static String token() {
        return Integer.toHexString(RANDOM.nextInt(0x100000, 0x1000000)).toUpperCase(Locale.ROOT);
    }
    private static AgentReply confirm(String detail, String token, AgentToolAction action, DdlTaskVO task) {
        return answer(action, detail + "\n请在 5 分钟内回复「确认 " + token + "」，或回复「取消」。", task, true);
    }
    private static AgentToolAction taskTool(Action action) {
        return switch (action) {
            case DDL_RESCHEDULE -> AgentToolAction.TASK_UPDATE;
            case DDL_DONE -> AgentToolAction.TASK_DONE;
            case DDL_CANCEL -> AgentToolAction.TASK_CANCEL;
            case DDL_CONFIRM -> AgentToolAction.TASK_CONFIRM;
            default -> throw new IllegalArgumentException("unsupported action");
        };
    }
    static AgentReply guide(String text) { return answer(AgentToolAction.CLARIFY, text, null, false); }
    private static AgentReply answer(AgentToolAction action, String text, DdlTaskVO task, boolean confirmation) {
        return new AgentReply(AgentIntent.TASK_MANAGE, BigDecimal.ONE, action, text, task, null, confirmation);
    }
}
