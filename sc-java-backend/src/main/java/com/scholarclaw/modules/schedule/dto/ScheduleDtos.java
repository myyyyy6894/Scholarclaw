package com.scholarclaw.modules.schedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 课表 API 载荷；业务 ID 一律用十进制字符串。 */
public final class ScheduleDtos {
    private ScheduleDtos() { }

    public record TermWrite(String name, LocalDate firstMonday, Integer weekCount, String timezone,
                            Boolean active, Long version, Boolean confirmScheduleChange) { }
    public record TermView(String termId, String name, LocalDate firstMonday, int weekCount, String timezone,
                           boolean active, long version, long scheduleVersion) { }
    public record Period(int periodNo, LocalTime startTime, LocalTime endTime) { }
    public record PeriodsWrite(Long version, List<Period> periods, Boolean confirmAffected) { }
    public record PeriodImpact(String ruleId, String title, LocalTime oldStart, LocalTime oldEnd,
                               LocalTime newStart, LocalTime newEnd) { }
    public record PeriodPreview(List<PeriodImpact> affected) { }
    public record RuleWrite(String termId, String courseId, String title, String teacher, String room,
                            Integer weekday, LocalTime startTime, LocalTime endTime,
                            Integer startPeriod, Integer endPeriod, List<Integer> weeks, Long version,
                            Boolean confirmOverlap) { }
    public record RuleView(String ruleId, String termId, String courseId, String title, String teacher,
                           String room, int weekday, LocalTime startTime, LocalTime endTime,
                           Integer startPeriod, Integer endPeriod, List<Integer> weeks, long version) { }
    public record ExceptionWrite(String kind, LocalDate targetDate, LocalTime startTime, LocalTime endTime,
                                 String room, Long version, Boolean confirmOverlap) { }
    public record ExceptionView(String exceptionId, String ruleId, LocalDate originalDate, String kind,
                                LocalDate targetDate, LocalTime startTime, LocalTime endTime, String room,
                                long version) { }
    public record OccurrenceView(String key, String ruleId, String title, String teacher, String room,
                                 LocalDate originalDate, LocalDate date, String startAt, String endAt,
                                 Integer startPeriod, Integer endPeriod, int originalWeek, Integer actualWeek,
                                 boolean moved, boolean conflict) { }
    public record OccurrencesView(String termId, String timezone, long scheduleVersion, Integer weekNo,
                                  List<OccurrenceView> occurrences) { }
}
