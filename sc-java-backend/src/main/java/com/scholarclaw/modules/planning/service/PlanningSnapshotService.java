package com.scholarclaw.modules.planning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.EventView;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.PreferenceView;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.ProfileView;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.TermView;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 汇集各领域公开的只读数据；本服务不写课表、DDL，也不调用模型。 */
@Service
public class PlanningSnapshotService {
    public record UnavailableTask(String taskId, String title, String reason) { }
    public record TaskFingerprint(String id, String title, String status, String precision,
                                  String dueAt, ProfileView profile) { }
    private record Fingerprint(LocalDate from, LocalDate to, PreferenceView preference, List<EventView> events,
                               String termId, long scheduleVersion, List<TaskFingerprint> tasks) { }
    public record Snapshot(LocalDate from, LocalDate to, PreferenceView preference, String termId,
                           long scheduleVersion, boolean hasActiveTerm, String fingerprint,
                           List<PlanningEngine.Window> windows, List<PlanningEngine.Interval> occupied,
                           List<PlanningTime.EventOccurrence> softEvents, List<PlanningEngine.Task> tasks,
                           List<UnavailableTask> unavailableTasks) { }

    private final PlanningInputService inputs;
    private final DdlTaskService ddl;
    private final ScheduleService schedule;
    private final ObjectMapper mapper;

    public PlanningSnapshotService(PlanningInputService inputs, DdlTaskService ddl, ScheduleService schedule, ObjectMapper mapper) {
        this.inputs = inputs; this.ddl = ddl; this.schedule = schedule; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Snapshot read(long userId, LocalDate from, LocalDate to) {
        PreferenceView preference = inputs.preference(userId);
        if (preference == null) throw invalid("请先设置可学习时间和规划时区。");
        List<PlanningEngine.Window> windows = PlanningTime.windows(preference, from, to);
        ZoneId zone = ZoneId.of(preference.timezone());
        Instant horizonStart = PlanningTime.resolve(from, 0, zone);
        Instant horizonEnd = PlanningTime.resolve(to, 1440, zone);
        int buffer = preference.bufferMinutes();

        List<EventView> events = inputs.events(userId).stream().sorted(Comparator.comparing(EventView::eventId)).toList();
        var expandedEvents = PlanningTime.events(events, horizonStart.minus(buffer, ChronoUnit.MINUTES),
                horizonEnd.plus(buffer, ChronoUnit.MINUTES));
        List<PlanningEngine.Interval> occupied = new ArrayList<>();
        expandedEvents.stream().filter(PlanningTime.EventOccurrence::hard).forEach(event ->
                occupied.add(buffered(event.start(), event.end(), buffer)));
        List<PlanningTime.EventOccurrence> soft = expandedEvents.stream().filter(event -> !event.hard()).toList();

        TermView term = schedule.terms(userId).stream().filter(TermView::active).findFirst().orElse(null);
        if (term != null) addCourses(userId, term, horizonStart, horizonEnd, buffer, occupied);

        Map<String, ProfileView> profiles = inputs.profiles(userId).stream()
                .collect(Collectors.toMap(ProfileView::taskId, Function.identity()));
        List<DdlTaskVO> sourceTasks = ddl.listForPlanning(userId);
        List<PlanningEngine.Task> tasks = new ArrayList<>();
        List<UnavailableTask> unavailable = new ArrayList<>();
        List<TaskFingerprint> taskFingerprints = new ArrayList<>();
        for (DdlTaskVO task : sourceTasks) {
            String id = Long.toString(task.taskId());
            ProfileView profile = profiles.get(id);
            taskFingerprints.add(new TaskFingerprint(id, task.taskTitle(), task.status(), task.timePrecision(),
                    task.dueAt() == null ? null : task.dueAt().toInstant().toString(), profile));
            if ("PENDING_TIME".equals(task.status()) || "PENDING_CONFIRM".equals(task.status())
                    || task.dueAt() == null || !("DATE_ONLY".equals(task.timePrecision())
                    || "EXACT_DATETIME".equals(task.timePrecision()))) {
                unavailable.add(new UnavailableTask(id, task.taskTitle(), "请先确认任务截止时间。"));
            } else if (profile == null) {
                unavailable.add(new UnavailableTask(id, task.taskTitle(), "请先填写预计和剩余工时。"));
            } else if (profile.remainingMinutes() > 0) {
                Instant earliest = profile.earliestStartAt() == null ? null : OffsetDateTime.parse(profile.earliestStartAt()).toInstant();
                tasks.add(new PlanningEngine.Task(id, task.taskTitle(), task.dueAt().toInstant(), earliest,
                        profile.remainingMinutes(), profile.priority(), profile.splittable(), profile.minBlockMinutes()));
            }
        }
        String termId = term == null ? null : term.termId();
        long scheduleVersion = term == null ? 0 : term.scheduleVersion();
        String fingerprint = digest(new Fingerprint(from, to, preference, events, termId, scheduleVersion, taskFingerprints));
        return new Snapshot(from, to, preference, termId, scheduleVersion, term != null, fingerprint,
                windows, List.copyOf(occupied), soft, List.copyOf(tasks), List.copyOf(unavailable));
    }

    private void addCourses(long userId, TermView term, Instant start, Instant end, int buffer,
                            List<PlanningEngine.Interval> occupied) {
        ZoneId zone = ZoneId.of(term.timezone());
        LocalDate first = start.minus(buffer, ChronoUnit.MINUTES).atZone(zone).toLocalDate();
        LocalDate last = end.plus(buffer, ChronoUnit.MINUTES).atZone(zone).toLocalDate();
        // 课表展开单次最多31天；按日期分片，实际发生日期不重复。
        for (LocalDate from = first; !from.isAfter(last); ) {
            LocalDate to = from.plusDays(30).isBefore(last) ? from.plusDays(30) : last;
            var occurrences = schedule.occurrences(userId, ScheduleService.parseId(term.termId()), from, to);
            for (var occurrence : occurrences.occurrences()) {
                Instant begin = OffsetDateTime.parse(occurrence.startAt()).toInstant();
                Instant finish = OffsetDateTime.parse(occurrence.endAt()).toInstant();
                if (begin.minus(buffer, ChronoUnit.MINUTES).isBefore(end)
                        && finish.plus(buffer, ChronoUnit.MINUTES).isAfter(start)) {
                    occupied.add(buffered(begin, finish, buffer));
                }
            }
            from = to.plusDays(1);
        }
    }

    private String digest(Fingerprint data) {
        try {
            byte[] canonical = mapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "规划快照无法计算。");
        }
    }
    private static PlanningEngine.Interval buffered(Instant start, Instant end, int minutes) {
        return new PlanningEngine.Interval(start.minus(minutes, ChronoUnit.MINUTES), end.plus(minutes, ChronoUnit.MINUTES));
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
}
