package com.scholarclaw.modules.planning.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 分钟从本地当天 00:00 起计；结束可为 1440，跨日作息显式拆成两条。 */
public final class PlanningInputDtos {
    private PlanningInputDtos() { }
    public record Window(Integer weekday, Integer startMinute, Integer endMinute) { }
    public record PreferenceWrite(String timezone, List<Window> windows, Integer focusMinutes,
                                  Integer dailyLimitMinutes, Integer bufferMinutes, Long version) { }
    public record PreferenceView(String timezone, List<Window> windows, int focusMinutes,
                                 int dailyLimitMinutes, int bufferMinutes, long version) { }
    public record EventWrite(String title, String timezone, LocalDate startDate, LocalDate endDate,
                             Integer weekday, Integer startMinute, Integer endMinute, Boolean hard, Long version) { }
    public record EventView(String eventId, String title, String timezone, LocalDate startDate, LocalDate endDate,
                            Integer weekday, int startMinute, int endMinute, boolean hard, long version) { }
    public record ProfileWrite(Integer estimatedMinutes, Integer remainingMinutes, Integer priority,
                               OffsetDateTime earliestStartAt, Boolean splittable, Integer minBlockMinutes,
                               Long version) { }
    public record ProfileView(String taskId, int estimatedMinutes, int remainingMinutes, int priority,
                              String earliestStartAt, boolean splittable, int minBlockMinutes, long version) { }
}
