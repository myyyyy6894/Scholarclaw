package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class QqNaturalRoute {
    enum Action {
        SCHEDULE_QUERY, REMINDER_GET, REMINDER_SET, DDL_LIST, DDL_QUERY, DDL_RESCHEDULE, DDL_DONE, DDL_CANCEL,
        DDL_CONFIRM, DDL_EXTRACT, KB_QA, KB_INGEST, CLARIFY, HELP, UNSUPPORTED, IGNORE
    }
    enum ScheduleView { LIST, FIRST, NEXT, CONFLICTS }
    record Decision(Action action, BigDecimal confidence, String taskHint, String date, String time,
                    String period, ScheduleView scheduleView, Boolean enabled) { }

    private static final BigDecimal READ_THRESHOLD = new BigDecimal("0.60");
    private static final BigDecimal WRITE_THRESHOLD = new BigDecimal("0.70");
    private static final Logger log = LoggerFactory.getLogger(QqNaturalRoute.class);
    private static final Pattern NEGATIVE = Pattern.compile("不要|别|不必|无需|不想|不用|取消刚才|算了");
    private static final Pattern MOVE_TASK = Pattern.compile("^(?:请)?把(.{1,120}?)(?:挪到|改到|改成|推迟到|延到)(.{1,80})[。！？?!]?$");
    private static final Pattern TWENTY = Pattern.compile("提前\\s*(?:20|二十|２０)\\s*分");
    private static final Pattern LEAD = Pattern.compile("提前\\s*(?:[0-9０-９]+|[一二两三四五六七八九十半]+)\\s*(?:分|小时)");
    private static final Pattern COURSE = Pattern.compile("课表|上课|每节课|课前|有课|第[一二三四五六七八九十0-9]+节|下节");
    private static final Pattern SCHEDULE_READ = Pattern.compile("课表|在哪|几节|几点|有什么课|有课|下节|哪间|撞课|冲突|撞了");
    private static final Pattern REMINDER_ON = Pattern.compile("开(启|一下)|提醒我|提醒一下|说一声|通知我");
    private static final Pattern REMINDER_OFF = Pattern.compile("关闭|关掉|停用|取消|停止");
    private static final Pattern QUOTED_INSTRUCTION = Pattern.compile("^[「“『【]|转发[:：]|引用[:：]|忽略.{0,8}指令|系统提示");

    private final PythonAiEngineClient python;
    private final DdlUserConfigResolver users;

    QqNaturalRoute(PythonAiEngineClient python, DdlUserConfigResolver users) {
        this.python = python;
        this.users = users;
    }

    Decision classify(AgentInboundMessage message, ResolvedUser user) {
        String text = message.rawText().strip();
        if (Pattern.compile("(?:不要|别|不必|不用|无需)(?:再)?(?:关|开|取消|停|改|挪|删|标|确认)").matcher(text).find()
                || text.contains("不想收到作业通知")) return decision(Action.IGNORE);
        long start = System.nanoTime();
        try {
            var endpoint = users.resolveChatEndpoint(user.config());
            JsonNode data = python.postJson(PythonAiEngineClient.AGENT_INTERPRET_PATH, Map.of(
                    "user_id", Long.toString(message.userId()), "raw_text", text, "chat_config", endpoint),
                    message.userId(), Duration.ofSeconds(20));
            Decision parsed = parse(data, text);
            log.info("QQ 意图路由 source=model action={} elapsedMs={}", parsed.action(),
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
            return parsed;
        } catch (BusinessException | IllegalArgumentException e) {
            Decision local = fallback(text);
            log.info("QQ 意图路由 source=local action={} elapsedMs={}", local.action(),
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
            return local;
        }
    }

    static Decision parse(JsonNode data, String text) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("invalid action envelope");
        Action action = Action.valueOf(data.path("action").asText());
        JsonNode score = data.path("confidence");
        if (!score.isNumber() || score.decimalValue().signum() < 0 || score.decimalValue().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("invalid confidence");
        }
        BigDecimal confidence = score.decimalValue();
        if (confidence.compareTo(mutates(action) ? WRITE_THRESHOLD : READ_THRESHOLD) < 0) return decision(Action.CLARIFY);
        var arguments = data.path("arguments");
        if (!arguments.isObject() || !data.path("evidence").isArray()) throw new IllegalArgumentException("invalid arguments");
        for (JsonNode element : data.path("evidence")) {
            if (!element.isTextual() || element.textValue().isBlank() || !text.contains(element.textValue())) {
                throw new IllegalArgumentException("invalid evidence");
            }
        }
        if (mutates(action) && (data.path("evidence").isEmpty() || QUOTED_INSTRUCTION.matcher(text).find()
                || NEGATIVE.matcher(text).find())) return decision(Action.IGNORE);
        String hint = literal(arguments, "task_hint", text, 120);
        String date = literal(arguments, "date_expression", text, 32);
        String time = literal(arguments, "time_expression", text, 32);
        String period = literal(arguments, "period_expression", text, 24);
        var viewValue = arguments.path("schedule_view");
        var view = viewValue.isTextual() ? ScheduleView.valueOf(viewValue.textValue()) : ScheduleView.LIST;
        var enabledValue = arguments.path("reminder_enabled");
        Boolean enabled = enabledValue.isBoolean() ? enabledValue.booleanValue() : null;
        if (action == Action.REMINDER_SET && !canSetReminder(text, enabled)) return decision(Action.UNSUPPORTED);
        return new Decision(action, confidence, hint, date, time, period, view, enabled);
    }

    private static String literal(JsonNode args, String key, String text, int max) {
        var value = args.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max
                || !text.contains(value.textValue())) throw new IllegalArgumentException("not a literal expression");
        return value.textValue();
    }

    static boolean mutates(Action action) {
        return List.of(Action.REMINDER_SET, Action.DDL_RESCHEDULE, Action.DDL_DONE, Action.DDL_CANCEL, Action.DDL_CONFIRM)
                .contains(action);
    }

    static Decision fallback(String text) {
        if (QUOTED_INSTRUCTION.matcher(text).find() || NEGATIVE.matcher(text).find()) return decision(Action.IGNORE);
        String date = QqRelativeTime.dateFragment(text);
        String time = QqRelativeTime.clockFragment(text);
        if (COURSE.matcher(text).find() && text.contains("提醒")) {
            Boolean enabled = REMINDER_OFF.matcher(text).find() ? Boolean.FALSE
                    : REMINDER_ON.matcher(text).find() ? Boolean.TRUE : null;
            if (enabled == null) return decision(Action.REMINDER_GET);
            if (!canSetReminder(text, enabled)) return decision(Action.UNSUPPORTED);
            return new Decision(Action.REMINDER_SET, BigDecimal.ONE, null, null, null, null, null, enabled);
        }
        if (SCHEDULE_READ.matcher(text).find() && (date != null || COURSE.matcher(text).find())) {
            ScheduleView view = text.contains("下节") ? ScheduleView.NEXT
                    : text.contains("第一节") || text.contains("最早") ? ScheduleView.FIRST
                    : Pattern.compile("撞|重叠|冲突").matcher(text).find() ? ScheduleView.CONFLICTS : ScheduleView.LIST;
            return new Decision(Action.SCHEDULE_QUERY, BigDecimal.ONE, null, date, time,
                    view == ScheduleView.FIRST ? null : QqRelativeTime.periodFragment(text), view, null);
        }
        var move = MOVE_TASK.matcher(text);
        if (move.matches()) {
            return new Decision(Action.DDL_RESCHEDULE, BigDecimal.ONE, move.group(1).strip(),
                    QqRelativeTime.dateFragment(move.group(2)), QqRelativeTime.clockFragment(move.group(2)), null, null, null);
        }
        if (text.matches(".*(?:待办列表|查看待办|我的任务|有哪些作业).*")) return decision(Action.DDL_LIST);
        return decision(Action.HELP);
    }

    private static boolean canSetReminder(String text, Boolean enabled) {
        if (enabled == null || !COURSE.matcher(text).find() || !text.contains("提醒") && !text.contains("通知")
                && !text.contains("说一声") || text.matches(".*(?:只这|一次|这节|这次|单次).*")) return false;
        if (!enabled) return REMINDER_OFF.matcher(text).find();
        var lead = LEAD.matcher(text);
        while (lead.find()) if (!TWENTY.matcher(lead.group()).matches()) return false;
        return REMINDER_ON.matcher(text).find();
    }

    private static Decision decision(Action action) {
        return new Decision(action, BigDecimal.ONE, null, null, null, null, ScheduleView.LIST, null);
    }
}
