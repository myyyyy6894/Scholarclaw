package com.scholarclaw.modules.schedule.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按学期墙钟、明确周次和单次例外展开事件，不读取浏览器时区或模型结果。 */
public final class ScheduleTimeEngine {
    private ScheduleTimeEngine() { }

    public record Term(LocalDate firstMonday, int weekCount, ZoneId timezone) { }
    public record Rule(long id, String title, String teacher, String room, int weekday,
                       LocalTime start, LocalTime end, Integer startPeriod, Integer endPeriod,
                       Set<Integer> weeks) { }
    public enum ChangeKind { CANCEL, MOVE }
    public record Change(long ruleId, LocalDate originalDate, ChangeKind kind, LocalDate targetDate,
                         LocalTime start, LocalTime end, String room) { }
    public record Occurrence(String key, String ruleId, String title, String teacher, String room,
                             LocalDate originalDate, LocalDate date, OffsetDateTime startAt,
                             OffsetDateTime endAt, Integer startPeriod, Integer endPeriod,
                             int originalWeek, Integer actualWeek, boolean moved, boolean conflict) { }
    private record Key(long ruleId, LocalDate date) { }

    public static List<Occurrence> expand(Term term, List<Rule> rules, List<Change> changes,
                                          LocalDate from, LocalDate to) {
        validateTerm(term);
        if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 30) {
            throw invalid("查询日期范围须为1至31天。");
        }
        Map<Key, Change> byOccurrence = new HashMap<>();
        for (Change change : changes) {
            if (change == null || change.originalDate() == null || change.kind() == null
                    || byOccurrence.putIfAbsent(new Key(change.ruleId(), change.originalDate()), change) != null) {
                throw invalid("同一次课程的停调课记录无效或重复。");
            }
        }
        List<Occurrence> result = new ArrayList<>();
        for (Rule rule : rules) {
            validateRule(term, rule);
            for (int week : rule.weeks()) {
                LocalDate original = term.firstMonday().plusWeeks(week - 1L).plusDays(rule.weekday() - 1L);
                Change change = byOccurrence.get(new Key(rule.id(), original));
                if (change != null && change.kind() == ChangeKind.CANCEL) {
                    continue;
                }
                boolean moved = change != null;
                LocalDate actual = moved ? change.targetDate() : original;
                if (actual == null) {
                    throw invalid("调课日期不能为空。");
                }
                if (actual.isBefore(from) || actual.isAfter(to)) {
                    continue;
                }
                LocalTime start = moved ? change.start() : rule.start();
                LocalTime end = moved ? change.end() : rule.end();
                validateTimes(start, end);
                OffsetDateTime startAt = resolve(actual, start, term.timezone());
                OffsetDateTime endAt = resolve(actual, end, term.timezone());
                if (!startAt.toInstant().isBefore(endAt.toInstant())) {
                    throw invalid("课程结束时间必须晚于开始时间。");
                }
                result.add(new Occurrence(rule.id() + ":" + original, Long.toString(rule.id()), rule.title(),
                        rule.teacher(), moved && change.room() != null ? change.room() : rule.room(),
                        original, actual, startAt, endAt, moved ? null : rule.startPeriod(),
                        moved ? null : rule.endPeriod(), week, weekFor(term, actual), moved, false));
            }
        }
        result.sort(Comparator.comparing(Occurrence::startAt).thenComparing(Occurrence::endAt)
                .thenComparing(Occurrence::key));
        boolean[] conflicts = new boolean[result.size()];
        for (int i = 0; i < result.size(); i++) {
            for (int j = i + 1; j < result.size(); j++) {
                if (!result.get(j).startAt().toInstant().isBefore(result.get(i).endAt().toInstant())) {
                    break;
                }
                conflicts[i] = true;
                conflicts[j] = true;
            }
        }
        List<Occurrence> marked = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            Occurrence item = result.get(i);
            marked.add(new Occurrence(item.key(), item.ruleId(), item.title(), item.teacher(), item.room(),
                    item.originalDate(), item.date(), item.startAt(), item.endAt(), item.startPeriod(),
                    item.endPeriod(), item.originalWeek(), item.actualWeek(), item.moved(), conflicts[i]));
        }
        return List.copyOf(marked);
    }

    public static void validateTerm(Term term) {
        if (term == null || term.firstMonday() == null || term.firstMonday().getDayOfWeek() != DayOfWeek.MONDAY
                || term.weekCount() < 1 || term.weekCount() > 53 || term.timezone() == null) {
            throw invalid("学期须设置第一周周一、1至53教学周和有效时区。");
        }
    }

    public static void validateRule(Term term, Rule rule) {
        if (rule == null || rule.id() <= 0 || rule.title() == null || rule.title().isBlank()
                || rule.weekday() < 1 || rule.weekday() > 7 || rule.weeks() == null || rule.weeks().isEmpty()
                || rule.weeks().stream().anyMatch(week -> week == null || week < 1 || week > term.weekCount())) {
            throw invalid("课程名称、星期或教学周次无效。");
        }
        validateTimes(rule.start(), rule.end());
        if ((rule.startPeriod() == null) != (rule.endPeriod() == null)
                || rule.startPeriod() != null && (rule.startPeriod() < 1 || rule.endPeriod() < rule.startPeriod()
                || rule.endPeriod() > 30)) {
            throw invalid("课程节次范围无效。");
        }
    }

    public static void validateTimes(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw invalid("课程须在同一天内结束，且结束时间晚于开始时间。");
        }
    }

    public static OffsetDateTime resolve(LocalDate date, LocalTime time, ZoneId zone) {
        LocalDateTime local = LocalDateTime.of(date, time);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) {
            throw invalid("课程时间在所选时区不存在或有歧义，请调整日期、时刻或时区：" + local);
        }
        return ZonedDateTime.ofStrict(local, offsets.get(0), zone).toOffsetDateTime();
    }

    public static Integer weekFor(Term term, LocalDate date) {
        long days = ChronoUnit.DAYS.between(term.firstMonday(), date);
        long week = Math.floorDiv(days, 7) + 1;
        return week >= 1 && week <= term.weekCount() ? (int) week : null;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
