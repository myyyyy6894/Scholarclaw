package com.scholarclaw.modules.planning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.planning.dto.StudyPlanDtos.*;
import com.scholarclaw.modules.planning.repository.StudyPlanRepository;
import com.scholarclaw.modules.planning.repository.StudyPlanRepository.Block;
import com.scholarclaw.modules.planning.repository.StudyPlanRepository.Plan;
import com.scholarclaw.modules.schedule.service.ScheduleService;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 创建草稿和显式采纳；输入不可信，任何采纳均在事务中重新核验。 */
@Service
public class StudyPlanService {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final StudyPlanRepository repository;
    private final PlanningSnapshotService snapshots;
    private final DdlTaskService ddl;
    private final UserConfigService users;
    private final ObjectMapper mapper;

    public StudyPlanService(StudyPlanRepository repository, PlanningSnapshotService snapshots,
                            DdlTaskService ddl, UserConfigService users, ObjectMapper mapper) {
        this.repository = repository; this.snapshots = snapshots; this.ddl = ddl; this.users = users; this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlanView create(long userId, CreatePlan request) {
        if (request == null || request.fromDate() == null || request.toDate() == null) throw invalid("请填写规划日期范围。");
        if (request.fromDate().getYear() < 1000 || request.toDate().getYear() > 9998
                || request.fromDate().isAfter(request.toDate()) || ChronoUnit.DAYS.between(request.fromDate(), request.toDate()) > 30) {
            throw invalid("规划范围须为1至31天且在支持的日期内。");
        }
        String requestId = requestId(request.clientRequestId());
        String requestHash = hash(request.fromDate() + "|" + request.toDate() + "|" + Boolean.TRUE.equals(request.confirmNoActiveTerm()));
        lockUser(userId);
        var replay = repository.byRequest(userId, requestId);
        if (replay.isPresent()) {
            if (!replay.get().requestFingerprint().equals(requestHash)) throw idempotencyConflict();
            return view(userId, replay.get());
        }
        if (repository.draftCount(userId) >= 20) throw invalid("未处理的计划草稿已达20个，请先取消不用的草稿。");
        var snapshot = snapshots.read(userId, request.fromDate(), request.toDate());
        ZoneId zone = ZoneId.of(snapshot.preference().timezone());
        Instant now = Instant.now();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (request.fromDate().isBefore(today) || request.fromDate().isAfter(today.plusDays(90))) {
            throw invalid("规划开始日期须为今天至未来90天内。");
        }
        if (!snapshot.hasActiveTerm() && !Boolean.TRUE.equals(request.confirmNoActiveTerm())) {
            throw invalid("尚未设置当前学期；请先设置，或明确确认本次不检查课表。");
        }
        var existing = existing(userId, snapshot, now);
        Map<String, Integer> reserved = new HashMap<>();
        for (Block block : existing.planned()) {
            int minutes = Math.toIntExact(Duration.between(instant(block.startUtc()), instant(block.endUtc())).toMinutes());
            reserved.merge(Long.toString(block.taskId()), minutes, Integer::sum);
        }
        List<PlanningEngine.Task> remaining = new ArrayList<>();
        for (var task : snapshot.tasks()) {
            int minutes = Math.max(0, task.remainingMinutes() - reserved.getOrDefault(task.id(), 0));
            if (minutes > 0) remaining.add(new PlanningEngine.Task(task.id(), task.title(), task.deadline(), task.earliest(),
                    minutes, task.priority(), task.splittable(), task.minBlockMinutes()));
        }
        List<PlanningEngine.Interval> occupied = new ArrayList<>(snapshot.occupied());
        existing.planned().forEach(block -> occupied.add(new PlanningEngine.Interval(instant(block.startUtc()), instant(block.endUtc()))));
        PlanningEngine.Result result;
        try {
            result = PlanningEngine.plan(snapshot.windows(), occupied, existing.dailyUsed(), remaining, now,
                    snapshot.preference().focusMinutes(), snapshot.preference().dailyLimitMinutes());
        } catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
        List<String> warnings = warnings(snapshot, result.blocks());
        LocalDateTime updated = ScTimeZone.now().toLocalDateTime();
        Plan value = new Plan(0, userId, requestId, requestHash, request.fromDate(), request.toDate(), zone.getId(),
                snapshot.fingerprint(), existing.fingerprint(), "DRAFT", 1, json(warnings), json(result.shortages()),
                json(snapshot.unavailableTasks()), null, null, updated);
        long planId = repository.insertPlan(value, updated);
        for (var block : result.blocks()) {
            repository.insertBlock(new Block(0, planId, userId, ScheduleService.parseId(block.taskId()), block.title(),
                    utc(block.start()), utc(block.end()), block.day(), "PROPOSED", false, block.overdue(), 1), updated);
        }
        return view(userId, requirePlan(userId, planId));
    }

    @Transactional(readOnly = true)
    public List<PlanSummary> list(long userId) {
        return repository.recent(userId).stream().map(plan -> new PlanSummary(Long.toString(plan.id()), plan.from(), plan.to(),
                plan.timezone(), plan.state(), plan.revision(),
                plan.createdAt().atZone(ScTimeZone.zoneId()).toOffsetDateTime().toString())).toList();
    }

    @Transactional(readOnly = true)
    public PlanView get(long userId, long planId) { return view(userId, requirePlan(userId, planId)); }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PlanView accept(long userId, long planId, AcceptPlan request) {
        if (request == null || request.revision() == null || request.blockIds() == null || request.blockIds().isEmpty()
                || request.blockIds().size() > 2000) throw invalid("请选择需要采纳的时间块并提供计划版本。");
        String requestId = requestId(request.clientRequestId());
        Set<Long> selected = new LinkedHashSet<>();
        for (String id : request.blockIds()) {
            if (!selected.add(ScheduleService.parseId(id))) throw invalid("时间块不能重复选择。");
        }
        String acceptHash = hash(planId + "|" + selected.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        lockUser(userId);
        var replay = repository.byAcceptRequest(userId, requestId);
        if (replay.isPresent()) {
            if (replay.get().id() != planId || !acceptHash.equals(replay.get().acceptFingerprint())) throw idempotencyConflict();
            return view(userId, replay.get());
        }
        Plan plan = requirePlan(userId, planId);
        if (!"DRAFT".equals(plan.state()) || plan.revision() != request.revision()) throw stale();
        var sourceTasks = ddl.listForPlanning(userId);
        try { ddl.lockForPlanning(userId, sourceTasks.stream().map(task -> task.taskId()).toList()); }
        catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) throw stale();
            throw e;
        }
        var snapshot = snapshots.read(userId, plan.from(), plan.to());
        Instant now = Instant.now();
        if (!plan.sourceFingerprint().equals(snapshot.fingerprint())
                || !plan.occupancyFingerprint().equals(existing(userId, snapshot, now).fingerprint())) throw stale();
        List<Block> blocks = repository.blocks(userId, planId);
        Map<Long, Block> byId = new HashMap<>();
        blocks.forEach(block -> byId.put(block.id(), block));
        for (long id : selected) {
            Block block = byId.get(id);
            if (block == null || !"PROPOSED".equals(block.state())) throw notFound();
            if (!instant(block.startUtc()).isAfter(now)) throw conflict("所选时间块已开始或过去，请重新规划。");
        }
        LocalDateTime updated = ScTimeZone.now().toLocalDateTime();
        if (repository.acceptPlan(userId, planId, plan.revision(), requestId, acceptHash, updated) != 1) throw stale();
        for (Block block : blocks) {
            String state = selected.contains(block.id()) ? "PLANNED" : "CANCELED";
            if (repository.setBlockState(userId, planId, block.id(), block.version(), state, updated) != 1) throw stale();
        }
        return view(userId, requirePlan(userId, planId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void cancelDraft(long userId, long planId, Long revision) {
        lockUser(userId);
        Plan plan = requirePlan(userId, planId);
        if (revision == null || revision != plan.revision() || !"DRAFT".equals(plan.state())) throw stale();
        LocalDateTime updated = ScTimeZone.now().toLocalDateTime();
        if (repository.cancelPlan(userId, planId, revision, updated) != 1) throw stale();
        repository.cancelProposals(userId, planId, updated);
    }

    private record Existing(List<Block> planned, Map<LocalDate, Integer> dailyUsed, String fingerprint) { }
    private Existing existing(long userId, PlanningSnapshotService.Snapshot snapshot, Instant now) {
        ZoneId zone = ZoneId.of(snapshot.preference().timezone());
        Instant from = PlanningTime.resolve(snapshot.from(), 0, zone), to = PlanningTime.resolve(snapshot.to(), 1440, zone);
        List<Block> planned = repository.futurePlanned(userId, utc(now));
        if (planned.size() > 5000) throw invalid("已采纳的时间块过多，请先整理计划。");
        List<Block> done = repository.doneBetween(userId, utc(from), utc(to));
        Map<LocalDate, Integer> used = new HashMap<>();
        for (Block block : planned) addDaily(used, block, zone, from, to);
        for (Block block : done) addDaily(used, block, zone, from, to);
        StringBuilder fingerprint = new StringBuilder();
        for (Block block : planned) fingerprint.append('P').append(block.id()).append(':').append(block.version())
                .append(':').append(block.startUtc()).append(':').append(block.endUtc()).append(';');
        for (Block block : done) fingerprint.append('D').append(block.id()).append(':').append(block.version())
                .append(':').append(block.startUtc()).append(':').append(block.endUtc()).append(';');
        return new Existing(List.copyOf(planned), Map.copyOf(used), hash(fingerprint.toString()));
    }

    private static void addDaily(Map<LocalDate, Integer> result, Block block, ZoneId zone, Instant from, Instant to) {
        Instant cursor = instant(block.startUtc()).isAfter(from) ? instant(block.startUtc()) : from;
        Instant end = instant(block.endUtc()).isBefore(to) ? instant(block.endUtc()) : to;
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.atZone(zone).toLocalDate();
            Instant boundary = PlanningTime.resolve(day, 1440, zone);
            if (!boundary.isAfter(cursor)) throw invalid("已采纳时间块的时区日期无法展开。");
            Instant until = boundary.isBefore(end) ? boundary : end;
            long seconds = Duration.between(cursor, until).getSeconds();
            int minutes = Math.toIntExact((seconds + 59) / 60);
            result.merge(day, minutes, Integer::sum);
            cursor = until;
        }
    }

    private PlanView view(long userId, Plan plan) {
        boolean stale = false;
        if (!"CANCELED".equals(plan.state())) {
            try {
                var snapshot = snapshots.read(userId, plan.from(), plan.to());
                stale = !plan.sourceFingerprint().equals(snapshot.fingerprint())
                        || "DRAFT".equals(plan.state())
                        && !plan.occupancyFingerprint().equals(existing(userId, snapshot, Instant.now()).fingerprint());
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.VALIDATION_ERROR) throw e;
                stale = true;
            }
        }
        ZoneId zone = ZoneId.of(plan.timezone());
        List<BlockView> blocks = repository.blocks(userId, plan.id()).stream().map(block -> new BlockView(
                Long.toString(block.id()), Long.toString(block.taskId()), block.title(),
                instant(block.startUtc()).atZone(zone).toOffsetDateTime().toString(),
                instant(block.endUtc()).atZone(zone).toOffsetDateTime().toString(),
                block.state(), block.locked(), block.overdue(), block.version())).toList();
        return new PlanView(Long.toString(plan.id()), plan.from(), plan.to(), plan.timezone(), plan.state(), stale,
                plan.revision(), parse(plan.warningsJson(), new TypeReference<List<String>>() { }),
                parse(plan.shortagesJson(), new TypeReference<List<PlanningEngine.Shortage>>() { }),
                parse(plan.unavailableJson(), new TypeReference<List<PlanningSnapshotService.UnavailableTask>>() { }), blocks);
    }

    private static List<String> warnings(PlanningSnapshotService.Snapshot snapshot, List<PlanningEngine.Block> blocks) {
        Set<String> warnings = new LinkedHashSet<>();
        if (!snapshot.hasActiveTerm()) warnings.add("未设置当前学期，本次未检查课表。");
        for (var block : blocks) for (var event : snapshot.softEvents()) {
            if (block.start().isBefore(event.end()) && block.end().isAfter(event.start())) {
                warnings.add("建议与可调整日程「" + event.title() + "」重叠，请确认。");
            }
        }
        return List.copyOf(warnings);
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计划数据无法保存。"); }
    }
    private <T> T parse(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计划数据无法读取。"); }
    }
    private Plan requirePlan(long userId, long id) { return repository.plan(userId, id).orElseThrow(StudyPlanService::notFound); }
    private void lockUser(long userId) {
        if (!"ACTIVE".equals(users.lockForUpdate(userId).getAccountStatus())) throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
    }
    private static String requestId(String id) {
        if (id == null || !REQUEST_ID.matcher(id).matches()) throw invalid("请求标识须为1至64位字母、数字、点、下划线或横线。");
        return id;
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 不可用", e); }
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime utc) { return utc.toInstant(ZoneOffset.UTC); }
    private static BusinessException stale() { return conflict("计划或相关资料已变化，请刷新并重新规划。"); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.STATE_CONFLICT, message); }
    private static BusinessException idempotencyConflict() { return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_ERROR, message); }
    private static BusinessException notFound() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
