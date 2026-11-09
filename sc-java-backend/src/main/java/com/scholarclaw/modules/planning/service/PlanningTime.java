package com.scholarclaw.modules.planning.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.EventView;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.PreferenceView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 将显式墙钟转为绝对时间；不存在或重复的 DST 时刻拒绝静默猜测。 */
public final class PlanningTime {
    private PlanningTime() { }
    public record EventOccurrence(String id, String title, Instant start, Instant end, boolean hard) { }

    public static Instant resolve(LocalDate date, int minute, ZoneId zone) {
        if (date == null || zone == null || minute < 0 || minute > 1440) throw invalid("日期或时刻无效。");
        LocalDateTime local = date.atStartOfDay().plusMinutes(minute);
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) {
            throw invalid("本地时间 " + local + " 在 " + zone + " 不存在或有歧义，请调整该时段。");
        }
        return local.toInstant(offsets.getFirst());
    }

    public static List<PlanningEngine.Window> windows(PreferenceView preference, LocalDate from, LocalDate to) {
        if (preference == null || from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) > 30) throw invalid("规划范围须为1至31天。");
        ZoneId zone = ZoneId.of(preference.timezone());
        List<PlanningEngine.Window> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (var window : preference.windows()) {
                if (window.weekday() != date.getDayOfWeek().getValue()) continue;
                Instant start = resolve(date, window.startMinute(), zone), end = resolve(date, window.endMinute(), zone);
                if (!start.isBefore(end)) throw invalid("学习时间的结束时刻必须晚于开始时刻。");
                result.add(new PlanningEngine.Window(date, start, end));
            }
        }
        return List.copyOf(result);
    }

    /** 根据事件自身时区展开同日、每周日程，并筛选与绝对时间范围相交的实例。 */
    public static List<EventOccurrence> events(List<EventView> events, Instant from, Instant to) {
        if (events == null || from == null || to == null || !from.isBefore(to)) throw invalid("规划时间范围无效。");
        List<EventOccurrence> result = new ArrayList<>();
        for (EventView event : events) {
            ZoneId zone = ZoneId.of(event.timezone());
            LocalDate first = from.atZone(zone).toLocalDate();
            LocalDate last = to.atZone(zone).toLocalDate();
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                if (date.isBefore(event.startDate()) || event.endDate() != null && date.isAfter(event.endDate())) continue;
                if (event.weekday() == null && !date.equals(event.startDate())
                        || event.weekday() != null && event.weekday() != date.getDayOfWeek().getValue()) continue;
                Instant start = resolve(date, event.startMinute(), zone), end = resolve(date, event.endMinute(), zone);
                if (!start.isBefore(end)) throw invalid("固定日程的结束时刻必须晚于开始时刻。");
                if (start.isBefore(to) && end.isAfter(from)) {
                    result.add(new EventOccurrence(event.eventId(), event.title(), start, end, event.hard()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
}
