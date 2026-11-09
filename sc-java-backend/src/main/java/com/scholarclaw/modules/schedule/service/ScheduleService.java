package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository.ExceptionRow;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository.Rule;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository.Term;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 学期、节次与人工课表事务入口；时间展开完全不依赖模型或知识库。 */
@Service
public class ScheduleService {
    private static final int MAX_RULES_PER_TERM = 500;
    private static final TypeReference<List<Integer>> WEEKS_TYPE = new TypeReference<>() { };
    private final ScheduleRepository repository;
    private final UserConfigService userConfigs;
    private final CourseService courses;
    private final ObjectMapper mapper;

    public ScheduleService(ScheduleRepository repository, UserConfigService userConfigs,
                           CourseService courses, ObjectMapper mapper) {
        this.repository = repository;
        this.userConfigs = userConfigs;
        this.courses = courses;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TermView> terms(long userId) {
        return repository.terms(userId).stream().map(ScheduleService::termView).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TermView createTerm(long userId, TermWrite request) {
        lockActiveUser(userId);
        TermWrite clean = validateTerm(request);
        boolean active = Boolean.TRUE.equals(clean.active());
        if (active) repository.deactivateTerms(userId, null, now());
        long id = repository.insertTerm(userId, clean.name(), clean.firstMonday(), clean.weekCount(),
                clean.timezone(), active, now());
        return termView(requireTerm(userId, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TermView updateTerm(long userId, long termId, TermWrite request) {
        lockActiveUser(userId);
        Term current = requireTermForUpdate(userId, termId);
        requireVersion(request == null ? null : request.version(), current.version());
        TermWrite clean = validateTerm(request);
        boolean calendarChanged = !current.firstMonday().equals(clean.firstMonday())
                || current.weekCount() != clean.weekCount() || !current.timezone().equals(clean.timezone());
        List<Rule> rules = repository.rules(userId, termId);
        if (calendarChanged && !rules.isEmpty()) {
            Term proposed = new Term(current.id(), current.userId(), clean.name(), clean.firstMonday(),
                    clean.weekCount(), clean.timezone(), Boolean.TRUE.equals(clean.active()), current.version(),
                    current.scheduleVersion());
            Map<Long, Rule> byId = new HashMap<>();
            for (Rule rule : rules) {
                validateRuleDates(proposed, rule);
                byId.put(rule.id(), rule);
            }
            for (ExceptionRow change : repository.exceptions(userId, termId)) {
                Rule rule = byId.get(change.ruleId());
                if (rule == null || !occursOn(proposed, rule, change.originalDate())) {
                    throw conflict("学期调整会使已有停调课记录失去原课程，请先处理这些记录。");
                }
                if ("MOVE".equals(change.kind())) {
                    ScheduleTimeEngine.resolve(change.targetDate(), change.start(), zone(proposed.timezone()));
                    ScheduleTimeEngine.resolve(change.targetDate(), change.end(), zone(proposed.timezone()));
                }
            }
            if (!Boolean.TRUE.equals(clean.confirmScheduleChange())) {
                throw conflict("修改教学周或时区会调整已有课程，请先确认。");
            }
        }
        boolean active = Boolean.TRUE.equals(clean.active());
        if (active) repository.deactivateTerms(userId, termId, now());
        if (repository.updateTerm(userId, termId, current.version(), clean.name(), clean.firstMonday(),
                clean.weekCount(), clean.timezone(), active, now()) != 1) throw stale();
        return termView(requireTerm(userId, termId));
    }

    @Transactional(readOnly = true)
    public List<Period> periods(long userId, long termId) {
        requireTerm(userId, termId);
        return repository.periods(userId, termId).stream().map(ScheduleService::periodView).toList();
    }

    @Transactional(readOnly = true)
    public PeriodPreview previewPeriods(long userId, long termId, PeriodsWrite request) {
        Term term = requireTerm(userId, termId);
        requireVersion(request == null ? null : request.version(), term.version());
        List<ScheduleRepository.Period> periods = validatePeriods(request.periods());
        return new PeriodPreview(periodImpacts(term, repository.rules(userId, termId), periods));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PeriodPreview savePeriods(long userId, long termId, PeriodsWrite request) {
        lockActiveUser(userId);
        Term term = requireTermForUpdate(userId, termId);
        requireVersion(request == null ? null : request.version(), term.version());
        List<ScheduleRepository.Period> periods = validatePeriods(request.periods());
        List<PeriodImpact> impacts = periodImpacts(term, repository.rules(userId, termId), periods);
        if (!impacts.isEmpty() && !Boolean.TRUE.equals(request.confirmAffected())) {
            throw conflict("节次调整会改变已有课程时间，请先查看影响并确认。");
        }
        repository.replacePeriods(userId, termId, periods);
        for (PeriodImpact impact : impacts) {
            repository.updateRuleTimes(userId, parseId(impact.ruleId()), impact.newStart(), impact.newEnd(), now());
        }
        repository.touchTerm(userId, termId, now());
        return new PeriodPreview(impacts);
    }

    @Transactional(readOnly = true)
    public List<RuleView> rules(long userId, long termId) {
        requireTerm(userId, termId);
        return repository.rules(userId, termId).stream().map(this::ruleView).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RuleView createRule(long userId, RuleWrite request) {
        lockActiveUser(userId);
        long termId = parseId(request == null ? null : request.termId());
        lockCourseIfPresent(userId, request.courseId());
        Term term = requireTermForUpdate(userId, termId);
        List<Rule> existing = repository.rules(userId, termId);
        if (existing.size() >= MAX_RULES_PER_TERM) throw invalid("单个学期课程规则不能超过500条。");
        Rule candidate = buildRule(userId, Long.MAX_VALUE, term, request);
        requireOverlapConfirmation(term, candidate, existing, repository.exceptions(userId, termId), request.confirmOverlap());
        long id = repository.insertRule(candidate, now());
        repository.touchTerm(userId, termId, now());
        return ruleView(requireRule(userId, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RuleView updateRule(long userId, long ruleId, RuleWrite request) {
        lockActiveUser(userId);
        Rule old = requireRule(userId, ruleId);
        lockCourseIfPresent(userId, request == null ? null : request.courseId());
        Term term = requireTermForUpdate(userId, old.termId());
        old = requireRule(userId, ruleId);
        requireVersion(request == null ? null : request.version(), old.version());
        if (parseId(request.termId()) != old.termId()) throw invalid("不能将课程规则移到其他学期。");
        Rule candidate = buildRule(userId, ruleId, term, request);
        List<ExceptionRow> changes = repository.exceptions(userId, old.termId());
        for (ExceptionRow change : changes) {
            if (change.ruleId() == ruleId && !occursOn(term, candidate, change.originalDate())) {
                throw conflict("该课程已有停调课记录；请先处理受影响的记录，再修改星期或周次。");
            }
        }
        requireOverlapConfirmation(term, candidate, repository.rules(userId, old.termId()), changes,
                request.confirmOverlap());
        if (repository.updateRule(candidate, old.version(), now()) != 1) throw stale();
        repository.touchTerm(userId, old.termId(), now());
        return ruleView(requireRule(userId, ruleId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteRule(long userId, long ruleId, Long version) {
        lockActiveUser(userId);
        Rule old = requireRule(userId, ruleId);
        requireTermForUpdate(userId, old.termId());
        old = requireRule(userId, ruleId);
        requireVersion(version, old.version());
        repository.deleteRule(userId, ruleId);
        repository.touchTerm(userId, old.termId(), now());
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> exceptions(long userId, long ruleId) {
        Rule rule = requireRule(userId, ruleId);
        return repository.exceptions(userId, rule.termId()).stream().filter(e -> e.ruleId() == ruleId)
                .map(ScheduleService::exceptionView).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExceptionView saveException(long userId, long ruleId, LocalDate originalDate, ExceptionWrite request) {
        lockActiveUser(userId);
        Rule rule = requireRule(userId, ruleId);
        Term term = requireTermForUpdate(userId, rule.termId());
        rule = requireRule(userId, ruleId);
        if (originalDate == null || !occursOn(term, rule, originalDate)) throw invalid("原日期不是此课程的上课日。");
        if (request == null || !("CANCEL".equals(request.kind()) || "MOVE".equals(request.kind()))) {
            throw invalid("停调课类型须为 CANCEL 或 MOVE。");
        }
        LocalDate target = request.targetDate();
        LocalTime start = request.startTime();
        LocalTime end = request.endTime();
        String room = optionalText(request.room(), 200, "教室");
        if ("CANCEL".equals(request.kind())) {
            if (target != null || start != null || end != null || room != null) throw invalid("停课不能包含调课目标。");
        } else {
            if (target == null || target.getYear() < 1000 || target.getYear() > 9999) throw invalid("调课目标日期无效。");
            validateTimePrecision(start, end);
            ScheduleTimeEngine.resolve(target, start, zone(term.timezone()));
            ScheduleTimeEngine.resolve(target, end, zone(term.timezone()));
        }
        ExceptionRow old = repository.exception(userId, ruleId, originalDate).orElse(null);
        if (old == null && request.version() != null) throw stale();
        if (old != null) requireVersion(request.version(), old.version());
        var value = new ExceptionRow(old == null ? 0 : old.id(), userId, term.id(), ruleId, originalDate,
                request.kind(), target, start, end, room, old == null ? 0 : old.version());
        if ("MOVE".equals(request.kind()) && !Boolean.TRUE.equals(request.confirmOverlap())) {
            List<ExceptionRow> changes = new ArrayList<>(repository.exceptions(userId, term.id()));
            changes.removeIf(change -> change.ruleId() == ruleId && change.originalDate().equals(originalDate));
            changes.add(value);
            boolean overlap = ScheduleTimeEngine.expand(engineTerm(term), repository.rules(userId, term.id()).stream()
                            .map(this::engineRule).toList(), changes.stream().map(ScheduleService::engineChange).toList(),
                            target, target).stream()
                    .anyMatch(item -> item.key().equals(ruleId + ":" + originalDate) && item.conflict());
            if (overlap) throw conflict("调课后与已有课程时间重叠；请查看后确认仍要保存。");
        }
        if (old == null) repository.insertException(value, now());
        else {
            if (repository.updateException(value, old.version(), now()) != 1) throw stale();
        }
        repository.touchTerm(userId, term.id(), now());
        return exceptionView(repository.exception(userId, ruleId, originalDate).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteException(long userId, long ruleId, LocalDate originalDate, Long version) {
        lockActiveUser(userId);
        Rule rule = requireRule(userId, ruleId);
        requireTermForUpdate(userId, rule.termId());
        ExceptionRow change = repository.exception(userId, ruleId, originalDate).orElseThrow(ScheduleService::notFound);
        requireVersion(version, change.version());
        repository.deleteException(userId, change.id());
        repository.touchTerm(userId, rule.termId(), now());
    }

    @Transactional(readOnly = true)
    public OccurrencesView occurrences(long userId, long termId, LocalDate from, LocalDate to) {
        Term term = requireTerm(userId, termId);
        var engineTerm = engineTerm(term);
        var rules = repository.rules(userId, termId).stream().map(this::engineRule).toList();
        var changes = repository.exceptions(userId, termId).stream().map(ScheduleService::engineChange).toList();
        var occurrences = ScheduleTimeEngine.expand(engineTerm, rules, changes, from, to);
        return new OccurrencesView(Long.toString(termId), term.timezone(), term.scheduleVersion(),
                ScheduleTimeEngine.weekFor(engineTerm, from), occurrences.stream().map(ScheduleService::occurrenceView).toList());
    }

    private TermWrite validateTerm(TermWrite value) {
        if (value == null || value.firstMonday() == null || value.weekCount() == null || value.active() == null) {
            throw invalid("请填写学期、第一教学周、总周数、时区和启用状态。");
        }
        String name = requiredText(value.name(), 100, "学期名称");
        ZoneId zone = zone(value.timezone());
        ScheduleTimeEngine.validateTerm(new ScheduleTimeEngine.Term(value.firstMonday(), value.weekCount(), zone));
        if (value.firstMonday().getYear() < 1000 || value.firstMonday().getYear() > 9999
                || value.firstMonday().plusWeeks(value.weekCount()).getYear() > 9999) {
            throw invalid("学期日期超出支持范围。");
        }
        return new TermWrite(name, value.firstMonday(), value.weekCount(), zone.getId(), value.active(), value.version(),
                value.confirmScheduleChange());
    }

    private List<ScheduleRepository.Period> validatePeriods(List<Period> input) {
        if (input == null || input.size() > 30) throw invalid("节次表最多30项。");
        List<ScheduleRepository.Period> result = new ArrayList<>();
        Set<Integer> seen = new TreeSet<>();
        for (Period period : input) {
            if (period == null || period.periodNo() < 1 || period.periodNo() > 30 || !seen.add(period.periodNo())) {
                throw invalid("节次号必须为1至30且不能重复。");
            }
            validateTimePrecision(period.startTime(), period.endTime());
            result.add(new ScheduleRepository.Period(period.periodNo(), period.startTime(), period.endTime()));
        }
        result.sort(Comparator.comparingInt(ScheduleRepository.Period::number));
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i).start().isBefore(result.get(i - 1).end())) throw invalid("节次时间不能重叠或倒序。");
        }
        return result;
    }

    private List<PeriodImpact> periodImpacts(Term term, List<Rule> rules, List<ScheduleRepository.Period> periods) {
        Map<Integer, ScheduleRepository.Period> byNumber = new HashMap<>();
        periods.forEach(period -> byNumber.put(period.number(), period));
        List<PeriodImpact> result = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.startPeriod() == null) continue;
            for (int number = rule.startPeriod(); number <= rule.endPeriod(); number++) {
                if (!byNumber.containsKey(number)) throw invalid("已有课程所用节次 " + number + " 缺失，请先调整课程。");
            }
            LocalTime start = byNumber.get(rule.startPeriod()).start();
            LocalTime end = byNumber.get(rule.endPeriod()).end();
            if (!start.equals(rule.start()) || !end.equals(rule.end())) {
                Rule changed = new Rule(rule.id(), rule.userId(), rule.termId(), rule.courseId(), rule.title(),
                        rule.teacher(), rule.room(), rule.weekday(), start, end, rule.startPeriod(), rule.endPeriod(),
                        rule.weeksJson(), rule.version());
                validateRuleDates(term, changed);
                result.add(new PeriodImpact(Long.toString(rule.id()), rule.title(), rule.start(), rule.end(), start, end));
            }
        }
        return result;
    }

    private Rule buildRule(long userId, long id, Term term, RuleWrite input) {
        if (input == null || input.weekday() == null || input.weeks() == null) throw invalid("请填写课程星期与周次。");
        String title = requiredText(input.title(), 200, "课程名称");
        String teacher = optionalText(input.teacher(), 100, "教师");
        String room = optionalText(input.room(), 200, "教室");
        Long courseId = input.courseId() == null || input.courseId().isBlank() ? null : parseId(input.courseId());
        if (courseId != null) courses.getActiveCourseForUpdate(userId, courseId);
        Set<Integer> weeks = new TreeSet<>();
        for (Integer week : input.weeks()) {
            if (week == null || week < 1 || week > term.weekCount()) throw invalid("课程周次超出学期范围。");
            weeks.add(week);
        }
        LocalTime start = input.startTime();
        LocalTime end = input.endTime();
        if ((input.startPeriod() == null) != (input.endPeriod() == null)) throw invalid("起止节次必须同时填写。");
        if (input.startPeriod() != null) {
            if (input.startPeriod() < 1 || input.endPeriod() < input.startPeriod() || input.endPeriod() > 30) {
                throw invalid("课程节次范围无效。");
            }
            Map<Integer, ScheduleRepository.Period> periods = new HashMap<>();
            repository.periods(userId, term.id()).forEach(period -> periods.put(period.number(), period));
            for (int number = input.startPeriod(); number <= input.endPeriod(); number++) {
                if (!periods.containsKey(number)) throw invalid("请先配置第 " + number + " 节的时间。");
            }
            LocalTime mappedStart = periods.get(input.startPeriod()).start();
            LocalTime mappedEnd = periods.get(input.endPeriod()).end();
            if (start != null && !start.equals(mappedStart) || end != null && !end.equals(mappedEnd)) {
                throw invalid("填写的时刻与节次表不一致，请重新选择。");
            }
            start = mappedStart;
            end = mappedEnd;
        }
        validateTimePrecision(start, end);
        String weeksJson;
        try { weeksJson = mapper.writeValueAsString(weeks); }
        catch (JsonProcessingException e) { throw new IllegalStateException("课表周次序列化失败", e); }
        Rule rule = new Rule(id, userId, term.id(), courseId, title, teacher, room, input.weekday(), start, end,
                input.startPeriod(), input.endPeriod(), weeksJson, 1);
        validateRuleDates(term, rule);
        return rule;
    }

    private void validateRuleDates(Term term, Rule rule) {
        var engineTerm = engineTerm(term);
        var engineRule = engineRule(rule);
        ScheduleTimeEngine.validateRule(engineTerm, engineRule);
        for (int week : engineRule.weeks()) {
            LocalDate date = term.firstMonday().plusWeeks(week - 1L).plusDays(rule.weekday() - 1L);
            ScheduleTimeEngine.resolve(date, rule.start(), engineTerm.timezone());
            ScheduleTimeEngine.resolve(date, rule.end(), engineTerm.timezone());
        }
    }

    private void requireOverlapConfirmation(Term term, Rule candidate, List<Rule> existing,
                                            List<ExceptionRow> changes, Boolean confirmed) {
        if (Boolean.TRUE.equals(confirmed)) return;
        List<Rule> rules = new ArrayList<>(existing.stream().filter(rule -> rule.id() != candidate.id()).toList());
        rules.add(candidate);
        var engineRules = rules.stream().map(this::engineRule).toList();
        var engineChanges = changes.stream().map(ScheduleService::engineChange).toList();
        Map<LocalDate, ExceptionRow> candidateChanges = new HashMap<>();
        changes.stream().filter(change -> change.ruleId() == candidate.id())
                .forEach(change -> candidateChanges.put(change.originalDate(), change));
        Set<LocalDate> dates = new TreeSet<>();
        for (int week : weeks(candidate)) {
            LocalDate original = term.firstMonday().plusWeeks(week - 1L).plusDays(candidate.weekday() - 1L);
            ExceptionRow change = candidateChanges.get(original);
            if (change != null && "CANCEL".equals(change.kind())) continue;
            dates.add(change == null ? original : change.targetDate());
        }
        for (LocalDate date : dates) {
            boolean overlap = ScheduleTimeEngine.expand(engineTerm(term), engineRules, engineChanges, date, date).stream()
                    .anyMatch(item -> item.ruleId().equals(Long.toString(candidate.id())) && item.conflict());
            if (overlap) throw conflict("课程与已有课程时间重叠；请查看后确认仍要保存。");
        }
    }

    private boolean occursOn(Term term, Rule rule, LocalDate date) {
        Integer week = ScheduleTimeEngine.weekFor(engineTerm(term), date);
        return week != null && date.getDayOfWeek().getValue() == rule.weekday() && weeks(rule).contains(week);
    }
    private Set<Integer> weeks(Rule rule) {
        try { return new TreeSet<>(mapper.readValue(rule.weeksJson(), WEEKS_TYPE)); }
        catch (Exception e) { throw new IllegalStateException("课表周次数据损坏", e); }
    }
    private ScheduleTimeEngine.Rule engineRule(Rule rule) {
        return new ScheduleTimeEngine.Rule(rule.id(), rule.title(), rule.teacher(), rule.room(), rule.weekday(),
                rule.start(), rule.end(), rule.startPeriod(), rule.endPeriod(), weeks(rule));
    }
    private static ScheduleTimeEngine.Term engineTerm(Term term) {
        return new ScheduleTimeEngine.Term(term.firstMonday(), term.weekCount(), zone(term.timezone()));
    }
    private static ScheduleTimeEngine.Change engineChange(ExceptionRow change) {
        return new ScheduleTimeEngine.Change(change.ruleId(), change.originalDate(),
                ScheduleTimeEngine.ChangeKind.valueOf(change.kind()), change.targetDate(), change.start(), change.end(), change.room());
    }
    private RuleView ruleView(Rule rule) {
        return new RuleView(Long.toString(rule.id()), Long.toString(rule.termId()),
                rule.courseId() == null ? null : rule.courseId().toString(), rule.title(), rule.teacher(), rule.room(),
                rule.weekday(), rule.start(), rule.end(), rule.startPeriod(), rule.endPeriod(), List.copyOf(weeks(rule)), rule.version());
    }
    private static TermView termView(Term term) {
        return new TermView(Long.toString(term.id()), term.name(), term.firstMonday(), term.weekCount(),
                term.timezone(), term.active(), term.version(), term.scheduleVersion());
    }
    private static Period periodView(ScheduleRepository.Period period) {
        return new Period(period.number(), period.start(), period.end());
    }
    private static ExceptionView exceptionView(ExceptionRow value) {
        return new ExceptionView(Long.toString(value.id()), Long.toString(value.ruleId()), value.originalDate(),
                value.kind(), value.targetDate(), value.start(), value.end(), value.room(), value.version());
    }
    private static OccurrenceView occurrenceView(ScheduleTimeEngine.Occurrence item) {
        // 明确输出学期时区的 offset，避免全局 Jackson 时区将 OffsetDateTime 改写为应用时区。
        return new OccurrenceView(item.key(), item.ruleId(), item.title(), item.teacher(), item.room(),
                item.originalDate(), item.date(), item.startAt().toString(), item.endAt().toString(),
                item.startPeriod(), item.endPeriod(), item.originalWeek(), item.actualWeek(), item.moved(), item.conflict());
    }
    private Term requireTerm(long userId, long termId) { return repository.term(userId, termId).orElseThrow(ScheduleService::notFound); }
    private Term requireTermForUpdate(long userId, long termId) {
        return repository.termForUpdate(userId, termId).orElseThrow(ScheduleService::notFound);
    }
    private Rule requireRule(long userId, long ruleId) { return repository.rule(userId, ruleId).orElseThrow(ScheduleService::notFound); }
    private void lockActiveUser(long userId) {
        if (!"ACTIVE".equals(userConfigs.lockForUpdate(userId).getAccountStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
    }
    /** 课程删除先锁课程再锁学期，写课表保持相同顺序，避免反向锁序。 */
    private void lockCourseIfPresent(long userId, String courseId) {
        if (courseId != null && !courseId.isBlank()) courses.getActiveCourseForUpdate(userId, parseId(courseId));
    }
    public static long parseId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw invalid("ID 必须为正的十进制字符串。"); }
    }
    private static ZoneId zone(String name) {
        if (name == null || !ZoneId.getAvailableZoneIds().contains(name)) throw invalid("请选择有效的 IANA 时区。");
        return ZoneId.of(name);
    }
    private static void validateTimePrecision(LocalTime start, LocalTime end) {
        ScheduleTimeEngine.validateTimes(start, end);
        if (start.getNano() != 0 || end.getNano() != 0) throw invalid("课程时刻最多精确到秒。");
    }
    private static String requiredText(String value, int max, String label) {
        String clean = optionalText(value, max, label);
        if (clean == null) throw invalid(label + "不能为空。");
        return clean;
    }
    private static String optionalText(String value, int max, String label) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() > max) throw invalid(label + "长度不能超过" + max + "。");
        return clean;
    }
    private static void requireVersion(Long actual, long expected) {
        if (actual == null || actual != expected) throw stale();
    }
    private static java.time.LocalDateTime now() { return ScTimeZone.now().toLocalDateTime(); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.STATE_CONFLICT, message); }
    private static BusinessException stale() { return conflict("课表数据已变化，请刷新后重试。"); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
