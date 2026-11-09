package com.scholarclaw.modules.planning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.*;
import com.scholarclaw.modules.planning.repository.PlanningInputRepository;
import com.scholarclaw.modules.planning.repository.PlanningInputRepository.Event;
import com.scholarclaw.modules.planning.repository.PlanningInputRepository.Preference;
import com.scholarclaw.modules.planning.repository.PlanningInputRepository.Profile;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 所有输入由用户显式指定；不从免打扰、课程或截止日期推测作息或工时。 */
@Service
public class PlanningInputService {
    private final PlanningInputRepository repository;
    private final UserConfigService users;
    private final DdlTaskService tasks;
    private final ObjectMapper mapper;
    public PlanningInputService(PlanningInputRepository repository, UserConfigService users,
                                DdlTaskService tasks, ObjectMapper mapper) {
        this.repository = repository; this.users = users; this.tasks = tasks; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PreferenceView preference(long userId) { return repository.preference(userId).map(this::view).orElse(null); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreferenceView savePreference(long userId, PreferenceWrite request) {
        lockActiveUser(userId);
        if (request == null || request.focusMinutes() == null || request.dailyLimitMinutes() == null
                || request.bufferMinutes() == null) throw invalid("请填写计划时区、可学习时间、专注长度、每日上限和缓冲时间。");
        String timezone = zone(request.timezone());
        requireRange(request.focusMinutes(), 5, 480, "专注长度");
        requireRange(request.dailyLimitMinutes(), 5, 1440, "每日上限");
        requireRange(request.bufferMinutes(), 0, 120, "缓冲时间");
        List<Window> windows = validateWindows(request.windows());
        String json;
        try { json = mapper.writeValueAsString(windows); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
        Preference value = new Preference(userId, timezone, json, request.focusMinutes(), request.dailyLimitMinutes(),
                request.bufferMinutes(), 1);
        var existing = repository.preference(userId);
        if (existing.isEmpty()) {
            if (request.version() != null) throw conflict();
            repository.insertPreference(value, now());
        } else {
            requireVersion(request.version(), existing.get().version());
            if (repository.updatePreference(value, request.version(), now()) != 1) throw conflict();
        }
        return view(repository.preference(userId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<EventView> events(long userId) { return repository.events(userId).stream().map(PlanningInputService::view).toList(); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventView createEvent(long userId, EventWrite request) {
        lockActiveUser(userId);
        if (repository.events(userId).size() >= 500) throw invalid("固定日程最多 500 条，请先删除不需要的日程。");
        Event event = validateEvent(userId, 0, request);
        if (request.version() != null) throw invalid("新日程不能指定版本。");
        return view(repository.event(userId, repository.insertEvent(event, now())).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventView updateEvent(long userId, long id, EventWrite request) {
        lockActiveUser(userId);
        Event existing = repository.event(userId, id).orElseThrow(PlanningInputService::notFound);
        requireVersion(request == null ? null : request.version(), existing.version());
        Event value = validateEvent(userId, id, request);
        if (repository.updateEvent(value, request.version(), now()) != 1) throw conflict();
        return view(repository.event(userId, id).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteEvent(long userId, long id, Long version) {
        lockActiveUser(userId);
        Event existing = repository.event(userId, id).orElseThrow(PlanningInputService::notFound);
        requireVersion(version, existing.version());
        if (repository.deleteEvent(userId, id, version) != 1) throw conflict();
    }

    @Transactional(readOnly = true)
    public List<ProfileView> profiles(long userId) { return repository.profiles(userId).stream().map(PlanningInputService::view).toList(); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProfileView saveProfile(long userId, long taskId, ProfileWrite request) {
        lockActiveUser(userId);
        tasks.getTask(userId, taskId); // Only ddl reads its task; this also rejects other owners and canceled tasks.
        if (request == null || request.estimatedMinutes() == null || request.remainingMinutes() == null
                || request.priority() == null || request.splittable() == null || request.minBlockMinutes() == null) {
            throw invalid("请填写预计工时、剩余工时、优先级与拆分方式。");
        }
        requireRange(request.estimatedMinutes(), 1, 100000, "预计工时");
        requireRange(request.remainingMinutes(), 0, 100000, "剩余工时");
        requireRange(request.priority(), 1, 5, "优先级");
        requireRange(request.minBlockMinutes(), 5, 480, "最小时间块");
        LocalDateTime earliest = null;
        if (request.earliestStartAt() != null) {
            if (request.earliestStartAt().getNano() % 1_000_000 != 0) throw invalid("最早开始时间最多精确到毫秒。");
            earliest = LocalDateTime.ofInstant(request.earliestStartAt().toInstant(), ZoneOffset.UTC);
            validateDate(earliest.toLocalDate());
        }
        Profile value = new Profile(userId, taskId, request.estimatedMinutes(), request.remainingMinutes(),
                request.priority(), earliest, request.splittable(), request.minBlockMinutes(), 1);
        var existing = repository.profile(userId, taskId);
        if (existing.isEmpty()) {
            if (request.version() != null) throw conflict();
            repository.insertProfile(value, now());
        } else {
            requireVersion(request.version(), existing.get().version());
            if (repository.updateProfile(value, request.version(), now()) != 1) throw conflict();
        }
        return view(repository.profile(userId, taskId).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteProfile(long userId, long taskId, Long version) {
        lockActiveUser(userId);
        Profile existing = repository.profile(userId, taskId).orElseThrow(PlanningInputService::notFound);
        requireVersion(version, existing.version());
        if (repository.deleteProfile(userId, taskId, version) != 1) throw conflict();
    }

    private PreferenceView view(Preference preference) {
        try {
            List<Window> windows = mapper.readerForListOf(Window.class).readValue(preference.windowsJson());
            return new PreferenceView(preference.timezone(), windows, preference.focusMinutes(), preference.dailyLimitMinutes(),
                    preference.bufferMinutes(), preference.version());
        } catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "作息数据不可读取。"); }
    }
    private static EventView view(Event event) {
        return new EventView(Long.toString(event.id()), event.title(), event.timezone(), event.startDate(), event.endDate(),
                event.weekday(), event.startMinute(), event.endMinute(), event.hard(), event.version());
    }
    private static ProfileView view(Profile p) {
        return new ProfileView(Long.toString(p.taskId()), p.estimatedMinutes(), p.remainingMinutes(), p.priority(),
                p.earliestStartAt() == null ? null : p.earliestStartAt().atOffset(ZoneOffset.UTC).toString(),
                p.splittable(), p.minBlockMinutes(), p.version());
    }
    private static Event validateEvent(long userId, long id, EventWrite request) {
        if (request == null || request.title() == null || request.title().isBlank() || request.title().trim().length() > 200
                || request.startDate() == null || request.startMinute() == null || request.endMinute() == null || request.hard() == null) {
            throw invalid("请填写日程名称、时区、日期、起止时间和约束类型。");
        }
        String timezone = zone(request.timezone());
        validateDate(request.startDate());
        LocalDate end = request.endDate();
        if (request.weekday() == null) {
            if (end != null && !end.equals(request.startDate())) throw invalid("单次日程结束日期必须与开始日期相同。");
            end = request.startDate();
        } else {
            requireRange(request.weekday(), 1, 7, "星期");
            if (end != null) {
                validateDate(end);
                if (end.isBefore(request.startDate())) throw invalid("日程结束日期不能早于开始日期。");
            }
        }
        validateMinuteRange(request.startMinute(), request.endMinute());
        return new Event(id, userId, request.title().trim(), timezone, request.startDate(), end, request.weekday(),
                request.startMinute(), request.endMinute(), request.hard(), 1);
    }
    private static List<Window> validateWindows(List<Window> input) {
        if (input == null || input.isEmpty() || input.size() > 42) throw invalid("请至少设置一段可学习时间，最多 42 段。");
        List<Window> windows = new ArrayList<>(input);
        for (Window window : windows) {
            if (window == null || window.weekday() == null || window.startMinute() == null || window.endMinute() == null)
                throw invalid("可学习时间需要星期和起止时间。");
            requireRange(window.weekday(), 1, 7, "星期");
            validateMinuteRange(window.startMinute(), window.endMinute());
        }
        windows.sort(Comparator.comparing(Window::weekday).thenComparing(Window::startMinute).thenComparing(Window::endMinute));
        for (int i = 1; i < windows.size(); i++) {
            Window previous = windows.get(i - 1), next = windows.get(i);
            if (previous.weekday().equals(next.weekday()) && next.startMinute() < previous.endMinute())
                throw invalid("同一天的可学习时间不能重叠。");
        }
        return List.copyOf(windows);
    }
    private static void validateMinuteRange(int start, int end) {
        if (start < 0 || start > 1439 || end < 1 || end > 1440 || start >= end)
            throw invalid("起止时间无效；跨日作息请拆成两条。");
    }
    private static String zone(String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) throw invalid("请选择有效的 IANA 时区。");
        return value;
    }
    private static void validateDate(LocalDate date) {
        if (date.getYear() < 1000 || date.getYear() > 9999) throw invalid("日期超出支持范围。");
    }
    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw invalid(name + "应在 " + min + " 至 " + max + " 之间。");
    }
    private void lockActiveUser(long userId) {
        if (!"ACTIVE".equals(users.lockForUpdate(userId).getAccountStatus())) throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
    }
    private static void requireVersion(Long actual, long expected) { if (actual == null || actual != expected) throw conflict(); }
    private static LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.STATE_CONFLICT, "资料已变化，请刷新后重试。"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
