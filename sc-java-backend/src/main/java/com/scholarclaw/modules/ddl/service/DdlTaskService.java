package com.scholarclaw.modules.ddl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DeleteDdlTaskVO;
import com.scholarclaw.modules.ddl.dto.TaskChangeDraftVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlTimeRuleValidator.DdlTimeFields;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DDL 任务领域服务（TASK-703，{@code 04} `API-DDL-002/003/004/006/007`）。
 *
 * <p>职责与边界：
 * <ul>
 *   <li>{@code t_ddl_task} 的唯一读写入口；所有读写强制 {@code user_id} 过滤（防 IDOR）。</li>
 *   <li>手动建单/编辑的时间校验与 {@code due_at} 计算委托 {@link DdlTimeRuleValidator}。</li>
 *   <li>状态机：{@code PENDING_TIME} / {@code PENDING_CONFIRM} / {@code ACTIVE} /
 *       {@code OVERDUE} / {@code COMPLETED} / {@code CANCELED}；非法或并发覆盖由行锁 + 条件更新拒绝。</li>
 *   <li><b>不在本任务</b>：Python 提取落库、三层幂等、Redis 变更草案（TASK-704）；提醒扫描（TASK-801）。</li>
 * </ul>
 */
@Service
public class DdlTaskService {

    private static final String SOURCE_WEB = DdlIdempotencyKey.SOURCE_PLATFORM_WEB;
    private static final BigDecimal MANUAL_CONFIDENCE = new BigDecimal("1.000");
    private static final DateTimeFormatter DUE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final LocalTime DATE_ONLY_SENTINEL = LocalTime.of(23, 59, 59);
    private static final int COMPLETED_LIST_LIMIT = 50;
    private static final int FOLLOW_UP_HOURS = 24;
    private static final int REMINDED_T24H = 1;
    private static final int REMINDED_T6H = 2;
    private static final int REMINDED_OVERDUE = 4;

    private final DdlTaskMapper ddlTaskMapper;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final DdlTimeRuleValidator timeRuleValidator;
    private final DdlDraftStore draftStore;

    public DdlTaskService(DdlTaskMapper ddlTaskMapper,
                          CourseService courseService,
                          UserConfigService userConfigService,
                          DdlTimeRuleValidator timeRuleValidator,
                          DdlDraftStore draftStore) {
        this.ddlTaskMapper = ddlTaskMapper;
        this.courseService = courseService;
        this.userConfigService = userConfigService;
        this.timeRuleValidator = timeRuleValidator;
        this.draftStore = draftStore;
    }

    /**
     * `API-DDL-002`：手动表单新建任务。
     *
     * <p>这里实现的是请求级唯一键幂等（{@code uk_ddl_idempotency_key}）；14 天
     * {@code content_hash} 语义防重与 Redis 接入层去重属 TASK-704。
     *
     * <p>返回值 `created=false` 表示命中同一请求 ID 的幂等重放：HTTP 语义应为 `200`，
     * 新建成单才是 `201`。
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskCreationResult createManualTask(Long userId, CreateDdlTaskRequest request) {
        String clientRequestId = requireClientRequestId(request.clientRequestId());
        String idempotencyKey = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);
        String requestFingerprint = DdlRequestFingerprint.forManual(request);

        // 幂等查询必须先于课程/时间校验：课程可能已被删除，但原请求重试仍必须返回原任务。
        DdlTaskEntity replayedTask = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey, userId);
        if (replayedTask != null) {
            requireSameRequestIdentity(replayedTask,
                    DdlRequestFingerprint.OPERATION_MANUAL_CREATE, requestFingerprint);
            UserContext replayUser = resolveUserContext(userId);
            return new DdlTaskCreationResult(toVO(replayedTask, replayUser.zoneId()), false);
        }

        UserContext user = resolveUserContext(userId);
        CourseRef course = resolveCourse(userId, request.courseId(), request.courseName(), true);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolve(
                request.timePrecision(), request.dueDate(), request.dueTime(),
                user.zoneId(), now);

        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setUserId(userId);
        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(requireTaskTitle(request.taskTitle()));
        entity.setDeliverableFormat(optionalText(request.deliverableFormat(), 255,
                "deliverable_format"));
        entity.setSubmissionChannel(optionalText(request.submissionChannel(), 255,
                "submission_channel"));
        entity.setRawSourceText(null);
        entity.setRawTimeText(null);
        entity.setTimePrecision(time.timePrecision().name());
        entity.setIsRelativeTime(0);
        entity.setDueDate(time.dueDate());
        entity.setDueTime(time.dueTime());
        entity.setDueAt(time.dueAt());
        entity.setTimeInferred(time.timeInferred() ? 1 : 0);
        entity.setTimeAmbiguityReason(editAmbiguityReason(time.status(), time.timePrecision()));
        entity.setUserTimezone(user.timezoneName());
        entity.setConfidence(MANUAL_CONFIDENCE);
        entity.setStatus(time.status().name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(time.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform(SOURCE_WEB);
        entity.setSourceMsgId(clientRequestId);
        entity.setRequestOperation(DdlRequestFingerprint.OPERATION_MANUAL_CREATE);
        entity.setRequestFingerprint(requestFingerprint);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setContentHash(null);
        entity.setCompletedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            ddlTaskMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey, userId);
            if (existing == null) {
                throw e;
            }
            requireSameRequestIdentity(existing,
                    DdlRequestFingerprint.OPERATION_MANUAL_CREATE, requestFingerprint);
            return new DdlTaskCreationResult(toVO(existing, user.zoneId()), false);
        }
        return new DdlTaskCreationResult(toVO(entity, user.zoneId()), true);
    }

    /** `API-DDL-003`：任务看板；变更草案取自 Redis（TASK-704）。 */
    public DdlTaskBoardVO listTasks(Long userId, String statusGroupRaw, String courseIdRaw) {
        UserContext user = resolveUserContext(userId);
        Long courseId = parseOptionalPositiveId(courseIdRaw, "course_id");
        StatusGroup group = StatusGroup.parse(statusGroupRaw);

        List<DdlTaskVO> pending = List.of();
        List<DdlTaskVO> active = List.of();
        List<DdlTaskVO> completed = List.of();
        if (group.includesPending()) {
            pending = queryTasks(userId, courseId, StatusGroup.PENDING_STATUSES,
                    query -> query.orderByDesc(DdlTaskEntity::getCreatedAt),
                    null, user.zoneId());
        }
        if (group.includesActive()) {
            active = queryTasks(userId, courseId, StatusGroup.ACTIVE_STATUSES,
                    query -> query.orderByAsc(DdlTaskEntity::getDueAt),
                    null, user.zoneId());
        }
        if (group.includesCompleted()) {
            completed = queryTasks(userId, courseId, StatusGroup.COMPLETED_STATUSES,
                    query -> query.orderByDesc(DdlTaskEntity::getCompletedAt),
                    COMPLETED_LIST_LIMIT, user.zoneId());
        }
        List<TaskChangeDraftVO> drafts = draftStore.listDrafts(userId).stream()
                // 目标已取消/已完成、或目标版本已变化的草案不得展示：否则用户会看到一张
                // 已经不可能成功应用的确认卡。Redis 侧已过滤过期/旧 schema 草案。
                .filter(draft -> isDraftDisplayable(userId, draft))
                // 看板按课程过滤时，草案也必须按目标任务的 course_id 过滤；
                // 否则前端会拿到不在当前列表里的任务草案并回退浏览器时区展示。
                .filter(draft -> courseId == null || courseId.equals(draft.courseId()))
                .map(DdlTaskDraft::toVO)
                .toList();
        return new DdlTaskBoardVO(pending, drafts, active, completed);
    }

    /** 草案展示校验：目标任务仍存在、未取消/完成，且 `updated_at` 与草案快照一致。 */
    private boolean isDraftDisplayable(Long userId, DdlTaskDraft draft) {
        if (draft.targetTaskId() == null) {
            return false;
        }
        DdlTaskEntity target = ddlTaskMapper.selectByTaskIdAndUserId(draft.targetTaskId(), userId);
        boolean stale = target == null
                || TaskStatus.CANCELED.name().equals(target.getStatus())
                || TaskStatus.COMPLETED.name().equals(target.getStatus())
                || !sameUpdatedAt(target.getUpdatedAt(), draft);
        if (stale) {
            draftStore.deleteDraft(userId, draft.targetTaskId());
            return false;
        }
        return true;
    }

    private static boolean sameUpdatedAt(LocalDateTime current, DdlTaskDraft draft) {
        try {
            LocalDateTime snapshot = draft.targetTaskUpdatedAtValue();
            if (snapshot == null || current == null) {
                return false;
            }
            java.time.temporal.ChronoUnit millis = java.time.temporal.ChronoUnit.MILLIS;
            return current.truncatedTo(millis).equals(snapshot.truncatedTo(millis));
        } catch (BusinessException e) {
            return false;
        }
    }

    /** `API-DDL-004`：编辑字段、补充时间激活、或将待定任务改为待补充时间。 */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO updateTask(Long userId, Long taskId, UpdateDdlTaskRequest request) {
        UserContext user = resolveUserContext(userId);
        // PO-05 锁序：先课程行、后任务行。课程删除事务（课程行 → 任务行）与本路径同序，
        // 否则“删课 vs 编辑/确认”会形成反向锁序，在 MySQL 上表现为死锁或 500。
        CourseRef course = resolveCourse(userId, request.courseId(), request.courseName(), true);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        // 未修改时间时必须按任务保存的时区快照解释同一组墙钟字段，
        // 否则用户改了资料里的时区后只改标题，也会隐式挪动 due_at 与时区。
        ZoneId taskZone = entityZone(entity.getUserTimezone(), user.zoneId());
        DdlTimeFields resolved = timeRuleValidator.resolve(
                request.timePrecision(), request.dueDate(), request.dueTime(), taskZone, now);
        boolean explicitReconfirm = Boolean.TRUE.equals(request.reconfirmTime());
        boolean timeChanged = explicitReconfirm || !sameTimeSemantics(entity, resolved);
        DdlTimeFields time = resolved;
        String timezoneName = trimToNull(entity.getUserTimezone()) == null
                ? user.timezoneName() : entity.getUserTimezone();
        if (timeChanged) {
            if (!taskZone.equals(user.zoneId())) {
                // 用户明确补充/修改时间：按当前用户时区解释，并刷新任务时区快照。
                time = timeRuleValidator.resolve(request.timePrecision(), request.dueDate(),
                        request.dueTime(), user.zoneId(), now);
            }
            timezoneName = user.timezoneName();
            if (explicitReconfirm && time.status() != TaskStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "确认时间必须晚于当前时间；如需补录过去任务，请编辑并保持待确认状态。");
            }
        }

        boolean staysCompleted = TaskStatus.COMPLETED.name().equals(entity.getStatus());
        boolean dueChanged = !Objects.equals(entity.getDueAt(), time.dueAt());
        int flags;
        if (staysCompleted) {
            // 完成态下延期：旧提醒位对应旧截止时间，必须清零，避免取消完成后漏提醒/误提醒。
            flags = dueChanged ? 0
                    : (entity.getRemindedFlags() == null ? 0 : entity.getRemindedFlags());
        } else if (!timeChanged) {
            flags = entity.getRemindedFlags() == null ? 0 : entity.getRemindedFlags();
        } else {
            flags = computeRemindedFlags(entity, time.status(), time.dueAt(), now);
        }

        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(requireTaskTitle(request.taskTitle()));
        entity.setDeliverableFormat(optionalText(request.deliverableFormat(), 255,
                "deliverable_format"));
        entity.setSubmissionChannel(optionalText(request.submissionChannel(), 255,
                "submission_channel"));
        entity.setUserTimezone(timezoneName);
        entity.setRemindedFlags(flags);
        entity.setCompletedAt(staysCompleted ? entity.getCompletedAt() : null);
        entity.setUpdatedAt(now);

        if (!timeChanged) {
            // 时间语义没有变化：保留原精度、原状态、原确认原因、原时区与原截止时间。
            // 关键边界：PENDING_CONFIRM / INVALID_OR_PAST 不会因为“保存原字段”被悄悄改成 ACTIVE。
            entity.setTimePrecision(entity.getTimePrecision());
            entity.setDueDate(entity.getDueDate());
            entity.setDueTime(entity.getDueTime());
            entity.setDueAt(entity.getDueAt());
            entity.setTimeInferred(entity.getTimeInferred());
            entity.setStatus(entity.getStatus());
            entity.setTimeAmbiguityReason(entity.getTimeAmbiguityReason());
            LocalDateTime followUpAt = entity.getFollowUpAt();
            if (!staysCompleted && isPendingStatus(entity.getStatus()) && followUpAt == null) {
                // 旧数据兜底：待确认任务必须带 follow_up_at（数据库约束）。
                followUpAt = now.plusHours(FOLLOW_UP_HOURS);
            }
            entity.setFollowUpAt(staysCompleted ? null : followUpAt);
            entity.setFollowUpCount(entity.getFollowUpCount() == null ? 0 : entity.getFollowUpCount());
        } else {
            entity.setTimePrecision(time.timePrecision().name());
            entity.setDueDate(time.dueDate());
            entity.setDueTime(time.dueTime());
            entity.setDueAt(time.dueAt());
            entity.setTimeInferred(time.timeInferred() ? 1 : 0);
            entity.setTimeAmbiguityReason(staysCompleted ? null
                    : editAmbiguityReason(time.status(), time.timePrecision()));
            entity.setStatus(staysCompleted ? TaskStatus.COMPLETED.name() : time.status().name());
            entity.setFollowUpAt(staysCompleted ? null : time.followUpAt());
            entity.setFollowUpCount(staysCompleted ? entity.getFollowUpCount() : 0);
        }

        // is_relative_time / confidence / raw_* / source_* / idempotency_key / content_hash
        // 保留原值：编辑不改变来源与原始提取可信度。
        int updated = ddlTaskMapper.update(null, taskUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 编辑已解决待确认变更，草案不再有效。
        draftStore.deleteDraftAfterCommit(userId, taskId);
        return toVO(entity, user.zoneId());
    }

    /**
     * 判断“时间语义是否变化”。
     *
     * <p>精度比较对 {@code INVALID_OR_PAST} 做兼容：Web 编辑表单只能展示三种可编辑精度，
     * 原任务若是 {@code INVALID_OR_PAST}，表单会提交等价的具体精度。只要日期/时刻/`due_at`/
     * `time_inferred` 完全一致，就仍视为“用户没有改时间”，必须保留原精度与确认原因，
     * 不得把“非法或已过期”无提示地升级为 `EXACT_DATETIME`。
     */
    private static boolean sameTimeSemantics(DdlTaskEntity entity, DdlTimeFields resolved) {
        if (!Objects.equals(entity.getDueDate(), resolved.dueDate())
                || !Objects.equals(entity.getDueTime(), resolved.dueTime())
                || !sameInstant(entity.getDueAt(), resolved.dueAt())
                || isOne(entity.getTimeInferred()) != resolved.timeInferred()) {
            return false;
        }
        String stored = entity.getTimePrecision();
        String incoming = resolved.timePrecision().name();
        if (Objects.equals(stored, incoming)) {
            return true;
        }
        return TimePrecision.INVALID_OR_PAST.name().equals(stored)
                && (TimePrecision.EXACT_DATETIME.name().equals(incoming)
                || TimePrecision.DATE_ONLY.name().equals(incoming)
                || TimePrecision.VAGUE_OR_MISSING.name().equals(incoming));
    }

    private static boolean sameInstant(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.withNano(0).equals(right.withNano(0));
    }

    private static boolean isPendingStatus(String status) {
        return TaskStatus.PENDING_TIME.name().equals(status)
                || TaskStatus.PENDING_CONFIRM.name().equals(status);
    }

    /**
     * 按用户 + 任务 ID 读取（内部编排使用；跨用户或已取消返回 404）。
     *
     * <p>`API-DDL-007` 把取消实现为软删除：任务置为 {@code CANCELED} 后不再作为可查询资源，
     * 与列表默认排除 CANCELED 的口径一致，避免“已删除任务仍能按 ID 读到”的契约矛盾。
     */
    public DdlTaskVO getTask(Long userId, Long taskId) {
        UserContext user = resolveUserContext(userId);
        DdlTaskEntity entity = ddlTaskMapper.selectByTaskIdAndUserId(taskId, userId);
        if (entity == null || TaskStatus.CANCELED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toVO(entity, user.zoneId());
    }

    /**
     * `API-DDL-004` 的“确认激活”专用入口（`/confirm`）。
     *
     * <p>只允许 {@code PENDING_TIME} / {@code PENDING_CONFIRM}；ACTIVE、OVERDUE、COMPLETED
     * 一律返回 {@code 409 STATE_CONFLICT}，避免 `/confirm` 变成可修改任意任务截止时间的后门。
     * 确认后的截止时间必须晚于当前时间，否则仍属“待确认”，确认动作不成立。
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO confirmTask(Long userId, Long taskId, UpdateDdlTaskRequest request) {
        UserContext user = resolveUserContext(userId);
        // 锁序：先课程行，后任务行（与课程删除路径一致）。
        CourseRef course = resolveCourse(userId, request.courseId(), request.courseName(), true);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        requireConfirmable(entity);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolve(
                request.timePrecision(), request.dueDate(), request.dueTime(),
                user.zoneId(), now);
        if (time.status() != TaskStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认时间必须晚于当前时间；如需补录过去任务，请编辑并保持待确认状态。");
        }
        applyConfirmed(entity, course, request, time, now, user.timezoneName());
        int updated = ddlTaskMapper.update(null, taskUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        draftStore.deleteDraftAfterCommit(userId, taskId);
        return toVO(entity, user.zoneId());
    }

    /** 批量确认激活；整批同一事务，任何一个任务状态不合法都会整批回滚（零执行）。 */
    @Transactional(rollbackFor = Exception.class)
    public List<DdlTaskVO> confirmBatch(Long userId, List<Long> taskIds, LocalDate dueDate,
                                        LocalTime dueTime) {
        UserContext user = resolveUserContext(userId);
        List<Long> ids = requireDistinctTaskIds(taskIds);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要一个任务编号。");
        }
        if (dueDate == null || dueTime == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "批量确认需要提供日期与时刻。");
        }
        // 锁序：先课程行（按 ID 升序），后任务行（按请求顺序），与课程删除路径一致。
        List<DdlTaskEntity> snapshots = readTasksForBatch(userId, ids);
        Set<Long> deletedCourses = lockCourseRows(userId, snapshots);
        List<DdlTaskEntity> entities = new ArrayList<>(ids.size());
        for (Long taskId : ids) {
            entities.add(lockOwnedTask(userId, taskId));
        }
        // 先整批校验状态与时间，再开始写入；数据库事务与预校验双保险。
        for (DdlTaskEntity entity : entities) {
            requireConfirmable(entity);
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolve("EXACT_DATETIME", dueDate.toString(),
                dueTime.toString(), user.zoneId(), now);
        if (time.status() != TaskStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认时间必须晚于当前时间。");
        }
        List<DdlTaskVO> result = new ArrayList<>(entities.size());
        for (DdlTaskEntity entity : entities) {
            CourseRef course = batchCourse(userId, entity, deletedCourses);
            UpdateDdlTaskRequest request = new UpdateDdlTaskRequest(
                    course.courseId() == null ? null : course.courseId().toString(),
                    course.courseName(),
                    entity.getTaskTitle(),
                    "EXACT_DATETIME",
                    dueDate.toString(),
                    dueTime.toString(),
                    entity.getDeliverableFormat(),
                    entity.getSubmissionChannel());
            applyConfirmed(entity, course, request, time, now, user.timezoneName());
            int updated = ddlTaskMapper.update(null, taskUpdateWrapper(entity));
            if (updated == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            draftStore.deleteDraftAfterCommit(userId, entity.getTaskId());
            result.add(toVO(entity, user.zoneId()));
        }
        return result;
    }

    /** 批量编辑（`/edit` 多任务）；整批同一事务，失败整批回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public List<DdlTaskVO> updateBatch(Long userId, List<Long> taskIds, LocalDate dueDate,
                                       LocalTime dueTime) {
        List<Long> ids = requireDistinctTaskIds(taskIds);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要一个任务编号。");
        }
        if (dueDate == null || dueTime == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "批量修改需要提供日期与时刻。");
        }
        // 锁序：先课程行，后任务行。先做非锁定读取只是为了知道要锁哪些课程；
        // 真正的状态校验在任务行锁之后进行。
        List<DdlTaskEntity> snapshots = readTasksForBatch(userId, ids);
        Set<Long> deletedCourses = lockCourseRows(userId, snapshots);
        for (Long taskId : ids) {
            lockOwnedTask(userId, taskId);
        }
        List<DdlTaskVO> result = new ArrayList<>(ids.size());
        for (DdlTaskEntity snapshot : snapshots) {
            CourseRef course = batchCourse(userId, snapshot, deletedCourses);
            result.add(updateTask(userId, snapshot.getTaskId(),
                    sameTimeRequest(snapshot, course, dueDate, dueTime)));
        }
        return result;
    }

    private static UpdateDdlTaskRequest sameTimeRequest(DdlTaskEntity entity, CourseRef course,
                                                        LocalDate dueDate, LocalTime dueTime) {
        return new UpdateDdlTaskRequest(
                course.courseId() == null ? null : course.courseId().toString(),
                course.courseName(),
                entity.getTaskTitle(),
                "EXACT_DATETIME",
                dueDate.toString(),
                dueTime.toString(),
                entity.getDeliverableFormat(),
                entity.getSubmissionChannel());
    }

    /** 批量写操作的非锁定读取：缺失或已取消一律 404，避免部分执行。 */
    private List<DdlTaskEntity> readTasksForBatch(Long userId, List<Long> ids) {
        List<DdlTaskEntity> snapshots = new ArrayList<>(ids.size());
        for (Long taskId : ids) {
            DdlTaskEntity entity = ddlTaskMapper.selectByTaskIdAndUserId(taskId, userId);
            if (entity == null || TaskStatus.CANCELED.name().equals(entity.getStatus())) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            snapshots.add(entity);
        }
        return snapshots;
    }

    /**
     * 批量写路径的课程行预锁定，返回“已删除、无法锁定”的课程 ID。
     *
     * <p>按 {@code course_id} 升序加锁，保证同一批请求内部顺序稳定；已删除课程不阻断批量操作
     * （`PO-05`：任务解绑为 {@code course_id=NULL}，保留 {@code course_name}）。
     */
    private Set<Long> lockCourseRows(Long userId, List<DdlTaskEntity> tasks) {
        Set<Long> courseIds = new java.util.TreeSet<>();
        for (DdlTaskEntity task : tasks) {
            if (task.getCourseId() != null) {
                courseIds.add(task.getCourseId());
            }
        }
        Set<Long> deleted = new java.util.LinkedHashSet<>();
        for (Long courseId : courseIds) {
            try {
                if (courseService.getActiveCourseForUpdate(userId, courseId) == null) {
                    deleted.add(courseId);
                }
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                    deleted.add(courseId);
                } else {
                    throw e;
                }
            }
        }
        return deleted;
    }

    private CourseRef batchCourse(Long userId, DdlTaskEntity entity, Set<Long> deletedCourses) {
        if (entity.getCourseId() == null || deletedCourses.contains(entity.getCourseId())) {
            return new CourseRef(null, entity.getCourseName());
        }
        return resolveCourse(userId, entity.getCourseId().toString(), entity.getCourseName(), true);
    }

    private static void requireConfirmable(DdlTaskEntity entity) {
        if (!TaskStatus.PENDING_TIME.name().equals(entity.getStatus())
                && !TaskStatus.PENDING_CONFIRM.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "只有待补充时间或待确认任务可以执行确认激活；当前状态为 "
                            + entity.getStatus() + "。");
        }
    }

    private static void applyConfirmed(DdlTaskEntity entity, CourseRef course,
                                       UpdateDdlTaskRequest request, DdlTimeFields time,
                                       LocalDateTime now, String timezoneName) {
        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(requireTaskTitle(request.taskTitle()));
        entity.setDeliverableFormat(optionalText(request.deliverableFormat(), 255,
                "deliverable_format"));
        entity.setSubmissionChannel(optionalText(request.submissionChannel(), 255,
                "submission_channel"));
        entity.setTimePrecision(time.timePrecision().name());
        entity.setDueDate(time.dueDate());
        entity.setDueTime(time.dueTime());
        entity.setDueAt(time.dueAt());
        entity.setTimeInferred(time.timeInferred() ? 1 : 0);
        entity.setUserTimezone(timezoneName);
        entity.setTimeAmbiguityReason(null);
        entity.setStatus(TaskStatus.ACTIVE.name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(null);
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setCompletedAt(null);
        entity.setUpdatedAt(now);
    }

    /**
     * `API-DDL-006`：标记完成 / 取消完成勾选；相同请求幂等。
     *
     * <p>`completed=false` **只对 {@code COMPLETED} 任务有效**：其他状态返回
     * {@code 409 STATE_CONFLICT}，不得重新计算状态。恢复目标优先取
     * {@code status_before_completion} 快照，保证
     * `PENDING_CONFIRM -> COMPLETED -> 取消完成` 仍回到 `PENDING_CONFIRM`，
     * 不会产生“ACTIVE + 旧歧义原因”的矛盾状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO setCompletion(Long userId, Long taskId, boolean completed) {
        UserContext user = resolveUserContext(userId);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();

        if (completed) {
            if (TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
                return toVO(entity, user.zoneId());
            }
            markCompleted(entity, now);
        } else if (!TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "只有已完成任务才能取消完成；当前状态为 " + entity.getStatus() + "。");
        } else {
            restoreFromCompletion(entity, now);
        }
        entity.setUpdatedAt(now);

        int updated = ddlTaskMapper.update(null, completionUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        draftStore.deleteDraftAfterCommit(userId, taskId);
        return toVO(entity, user.zoneId());
    }

    /**
     * 批量标记完成/取消完成；整批在同一事务内校验并写入。
     *
     * <p>`FR-DDL-004` 的批量动作必须在二次确认后“全部执行或全部零执行”，
     * 不允许只处理第一个编号。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<DdlTaskVO> setCompletionBatch(Long userId, List<Long> taskIds, boolean completed) {
        UserContext user = resolveUserContext(userId);
        List<Long> ids = requireDistinctTaskIds(taskIds);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要一个任务编号。");
        }
        List<DdlTaskEntity> entities = new ArrayList<>(ids.size());
        for (Long taskId : ids) {
            DdlTaskEntity entity = lockOwnedTask(userId, taskId);
            if (!completed && !TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT,
                        "任务 #T" + taskId + " 当前状态为 " + entity.getStatus()
                                + "，只有已完成任务才能取消完成。");
            }
            entities.add(entity);
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        List<DdlTaskVO> result = new ArrayList<>(entities.size());
        for (DdlTaskEntity entity : entities) {
            if (completed && TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
                // 重复完成不重复写库：直接返回原 VO，避免无意义的 UPDATE 与 updated_at 漂移。
                result.add(toVO(entity, user.zoneId()));
                continue;
            }
            if (completed) {
                markCompleted(entity, now);
            } else {
                restoreFromCompletion(entity, now);
            }
            entity.setUpdatedAt(now);
            int updated = ddlTaskMapper.update(null, completionUpdateWrapper(entity));
            if (updated == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            draftStore.deleteDraftAfterCommit(userId, entity.getTaskId());
            result.add(toVO(entity, user.zoneId()));
        }
        return result;
    }

    private static void markCompleted(DdlTaskEntity entity, LocalDateTime now) {
        entity.setStatusBeforeCompletion(entity.getStatus());
        entity.setStatus(TaskStatus.COMPLETED.name());
        entity.setCompletedAt(now);
        entity.setFollowUpAt(null);
    }

    /**
     * 取消完成时恢复状态：
     * <ol>
     *   <li>优先按完成前快照恢复 {@code PENDING_TIME} / {@code PENDING_CONFIRM}；</li>
     *   <li>快照为 ACTIVE/OVERDUE 时按当前时间重新判定 ACTIVE/OVERDUE；</li>
     *   <li>旧数据没有快照时，若仍有确认原因则回到 PENDING_CONFIRM，否则按 {@code due_at} 判定。</li>
     * </ol>
     * 恢复为 ACTIVE/OVERDUE 时清空不再适用的确认原因。
     */
    private void restoreFromCompletion(DdlTaskEntity entity, LocalDateTime now) {
        TaskStatus restored = resolveRestoredStatus(entity, now);
        entity.setStatus(restored.name());
        entity.setCompletedAt(null);
        entity.setStatusBeforeCompletion(null);
        if (restored == TaskStatus.PENDING_TIME) {
            entity.setDueDate(null);
            entity.setDueTime(null);
            entity.setDueAt(null);
            entity.setFollowUpAt(now.plusHours(FOLLOW_UP_HOURS));
            entity.setFollowUpCount(0);
            entity.setRemindedFlags(0);
        } else if (restored == TaskStatus.PENDING_CONFIRM) {
            entity.setFollowUpAt(now.plusHours(FOLLOW_UP_HOURS));
            entity.setFollowUpCount(0);
            entity.setRemindedFlags(0);
            if (entity.getTimeAmbiguityReason() == null) {
                entity.setTimeAmbiguityReason("时间早于当前时间或存在歧义，请核对");
            }
        } else {
            entity.setFollowUpAt(null);
            entity.setTimeAmbiguityReason(null);
            if (restored == TaskStatus.ACTIVE) {
                int flags = entity.getRemindedFlags() == null ? 0 : entity.getRemindedFlags();
                entity.setRemindedFlags(restoreActiveFlags(flags, entity.getDueAt(), now));
            }
        }
    }

    private static TaskStatus resolveRestoredStatus(DdlTaskEntity entity, LocalDateTime now) {
        TaskStatus before = parseStatusOrNull(entity.getStatusBeforeCompletion());
        if (before == TaskStatus.PENDING_TIME) {
            return TaskStatus.PENDING_TIME;
        }
        if (before == TaskStatus.PENDING_CONFIRM) {
            return entity.getDueAt() == null
                    ? TaskStatus.PENDING_TIME : TaskStatus.PENDING_CONFIRM;
        }
        if (before == TaskStatus.ACTIVE || before == TaskStatus.OVERDUE) {
            return restoreStatus(entity.getDueAt(), now);
        }
        // 旧数据没有完成前快照：保留原歧义语义，仍回到待确认而不是 ACTIVE。
        if (entity.getTimeAmbiguityReason() != null
                && !entity.getTimeAmbiguityReason().isBlank()
                && entity.getDueAt() != null) {
            return TaskStatus.PENDING_CONFIRM;
        }
        return restoreStatus(entity.getDueAt(), now);
    }

    private static TaskStatus parseStatusOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Long> requireDistinctTaskIds(List<Long> taskIds) {
        if (taskIds == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务编号不能为空。");
        }
        java.util.LinkedHashSet<Long> distinct = new java.util.LinkedHashSet<>();
        for (Long taskId : taskIds) {
            if (taskId == null || taskId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "任务编号必须为正整数。");
            }
            distinct.add(taskId);
        }
        return List.copyOf(distinct);
    }

    /** `API-DDL-007`：取消任务；重复取消幂等返回。 */
    @Transactional(rollbackFor = Exception.class)
    public DeleteDdlTaskVO cancelTask(Long userId, Long taskId) {
        resolveUserContext(userId);
        DdlTaskEntity entity = ddlTaskMapper.selectByIdForUpdate(taskId, userId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (TaskStatus.CANCELED.name().equals(entity.getStatus())) {
            draftStore.deleteDraft(userId, taskId);
            return new DeleteDdlTaskVO(taskId.toString(), TaskStatus.CANCELED.name());
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        entity.setStatus(TaskStatus.CANCELED.name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(null);
        entity.setUpdatedAt(now);
        int updated = ddlTaskMapper.update(null, cancelUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        draftStore.deleteDraftAfterCommit(userId, taskId);
        return new DeleteDdlTaskVO(taskId.toString(), TaskStatus.CANCELED.name());
    }

    /** 批量取消；整批同一事务，任一任务不属于当前用户则整批回滚（零执行）。 */
    @Transactional(rollbackFor = Exception.class)
    public List<DeleteDdlTaskVO> cancelBatch(Long userId, List<Long> taskIds) {
        resolveUserContext(userId);
        List<Long> ids = requireDistinctTaskIds(taskIds);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少需要一个任务编号。");
        }
        List<DdlTaskEntity> entities = new ArrayList<>(ids.size());
        for (Long taskId : ids) {
            DdlTaskEntity entity = ddlTaskMapper.selectByIdForUpdate(taskId, userId);
            if (entity == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            entities.add(entity);
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        List<DeleteDdlTaskVO> result = new ArrayList<>(entities.size());
        for (DdlTaskEntity entity : entities) {
            if (!TaskStatus.CANCELED.name().equals(entity.getStatus())) {
                entity.setStatus(TaskStatus.CANCELED.name());
                entity.setStatusBeforeCompletion(null);
                entity.setFollowUpAt(null);
                entity.setUpdatedAt(now);
                int updated = ddlTaskMapper.update(null, cancelUpdateWrapper(entity));
                if (updated == 0) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
            draftStore.deleteDraftAfterCommit(userId, entity.getTaskId());
            result.add(new DeleteDdlTaskVO(entity.getTaskId().toString(),
                    TaskStatus.CANCELED.name()));
        }
        return result;
    }

    /**
     * 插入 TASK-704 已构建好的提取任务；唯一键冲突时回查已有任务。
     *
     * <p>{@code created=false} 表示命中 `uk_ddl_idempotency_key`（并发重试/重复提交），
     * 调用方应返回 `IDEMPOTENT_HIT`。
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskCreationResult createExtractedTask(DdlTaskEntity entity) {
        // 建单事务内重新锁定课程行：提取阶段解析课程发生在事务外，期间课程可能已被删除。
        // 课程已删除时按 PO-05 解绑为 NULL，保留 course_name，不得写回已删除的 course_id。
        if (entity.getCourseId() != null) {
            try {
                CourseNamespaceEntity course = courseService.getActiveCourseForUpdate(
                        entity.getUserId(), entity.getCourseId());
                entity.setCourseName(course.getCourseName());
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                    entity.setCourseId(null);
                } else {
                    throw e;
                }
            }
        }
        try {
            ddlTaskMapper.insert(entity);
            return new DdlTaskCreationResult(
                    toVO(entity, entityZone(entity.getUserTimezone(), ScTimeZone.zoneId())), true);
        } catch (DuplicateKeyException e) {
            DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(
                    entity.getIdempotencyKey(), entity.getUserId());
            if (existing == null) {
                throw e;
            }
            // 唯一键只证明“请求 ID 相同”；必须再证明操作类型与规范化内容相同。
            requireSameRequestIdentity(existing, entity.getRequestOperation(),
                    entity.getRequestFingerprint());
            return new DdlTaskCreationResult(
                    toVO(existing, entityZone(existing.getUserTimezone(), ScTimeZone.zoneId())), false);
        }
    }

    /** `API-DDL-005` `CONFIRM_UPDATE`：用草案覆盖原任务。 */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO applyDraftUpdate(Long userId, Long taskId, DdlTaskDraft draft) {
        UserContext user = resolveUserContext(userId);
        // 锁序：先课程行，后任务行（与课程删除路径一致）。
        CourseRef course = resolveDraftCourse(userId, draft.courseId(), draft.courseName(), true);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        requireDraftTargetVersion(entity, draft);
        if (TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "任务已完成，旧变更草案不再适用；请重新提取通知。");
        }
        ParsedDraft parsed = parseDraft(draft, ScTimeZone.now().toLocalDateTime());
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        // 必须在覆盖 dueAt/status 之前计算，才能判断“是否延期”并重置提醒位。
        int flags = computeRemindedFlags(entity, parsed.status(), parsed.dueAt(), now);

        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(requireDraftText(draft.taskTitle(), 255, "task_title"));
        entity.setDeliverableFormat(optionalDraftText(draft.deliverableFormat(), 255,
                "deliverable_format"));
        entity.setSubmissionChannel(optionalDraftText(draft.submissionChannel(), 255,
                "submission_channel"));
        entity.setRawSourceText(optionalDraftText(draft.rawSourceText(), 4000, "raw_source_text"));
        entity.setRawTimeText(optionalDraftText(draft.rawTimeText(), 128, "raw_time_text"));
        entity.setTimePrecision(parsed.precision().name());
        entity.setIsRelativeTime(draft.isRelativeTime() ? 1 : 0);
        entity.setDueDate(parsed.dueDate());
        entity.setDueTime(parsed.dueTime());
        entity.setDueAt(parsed.dueAt());
        entity.setTimeInferred(draft.timeInferred() ? 1 : 0);
        entity.setTimeAmbiguityReason(parsed.ambiguityReason());
        entity.setUserTimezone(timeRuleValidator.normalizeUserTimezone(draft.userTimezone()));
        entity.setConfidence(draft.confidence());
        entity.setStatus(parsed.status().name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(parsed.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(flags);
        entity.setCompletedAt(null);
        entity.setContentHash(draft.contentHash());
        entity.setUpdatedAt(now);

        int updated = ddlTaskMapper.update(null, taskUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toVO(entity, user.zoneId());
    }

    /** `API-DDL-005` `CREATE_NEW`：把草案另存为全新的独立任务。 */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskCreationResult createFromDraft(Long userId, DdlTaskDraft draft,
                                                 String clientRequestIdRaw) {
        String clientRequestId = requireClientRequestId(clientRequestIdRaw);
        UserContext user = resolveUserContext(userId);
        String idempotencyKey = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);
        String requestFingerprint = DdlRequestFingerprint.forDraftCreateNew(draft);
        DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey, userId);
        if (existing != null) {
            // 同一新请求 ID 必须对应同一份草案；否则是 ID 碰撞/内容被换，返回冲突。
            requireSameRequestIdentity(existing,
                    DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW, requestFingerprint);
            return new DdlTaskCreationResult(toVO(existing, user.zoneId()), false);
        }

        ParsedDraft parsed = parseDraft(draft, ScTimeZone.now().toLocalDateTime());
        CourseRef course = resolveDraftCourse(userId, draft.courseId(), draft.courseName(), true);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setUserId(userId);
        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(requireDraftText(draft.taskTitle(), 255, "task_title"));
        entity.setDeliverableFormat(optionalDraftText(draft.deliverableFormat(), 255,
                "deliverable_format"));
        entity.setSubmissionChannel(optionalDraftText(draft.submissionChannel(), 255,
                "submission_channel"));
        entity.setRawSourceText(optionalDraftText(draft.rawSourceText(), 4000, "raw_source_text"));
        entity.setRawTimeText(optionalDraftText(draft.rawTimeText(), 128, "raw_time_text"));
        entity.setTimePrecision(parsed.precision().name());
        entity.setIsRelativeTime(draft.isRelativeTime() ? 1 : 0);
        entity.setDueDate(parsed.dueDate());
        entity.setDueTime(parsed.dueTime());
        entity.setDueAt(parsed.dueAt());
        entity.setTimeInferred(draft.timeInferred() ? 1 : 0);
        entity.setTimeAmbiguityReason(parsed.ambiguityReason());
        entity.setUserTimezone(timeRuleValidator.normalizeUserTimezone(draft.userTimezone()));
        entity.setConfidence(draft.confidence());
        entity.setStatus(parsed.status().name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(parsed.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform(SOURCE_WEB);
        entity.setSourceMsgId(clientRequestId);
        entity.setRequestOperation(DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW);
        entity.setRequestFingerprint(requestFingerprint);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setContentHash(draft.contentHash());
        entity.setCompletedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            ddlTaskMapper.insert(entity);
            return new DdlTaskCreationResult(toVO(entity, user.zoneId()), true);
        } catch (DuplicateKeyException e) {
            DdlTaskEntity raced = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey, userId);
            if (raced == null) {
                throw e;
            }
            requireSameRequestIdentity(raced,
                    DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW, requestFingerprint);
            return new DdlTaskCreationResult(toVO(raced, user.zoneId()), false);
        }
    }

    /** 校验持久化的操作类型与请求指纹；缺失或不同一律幂等冲突。 */
    private static void requireSameRequestIdentity(DdlTaskEntity existing, String operation,
                                                   String fingerprint) {
        if (operation == null || fingerprint == null
                || !operation.equals(existing.getRequestOperation())
                || !fingerprint.equals(existing.getRequestFingerprint())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    /** 校验草案生成时记录的目标任务版本；缺失视为旧草案，拒绝应用。 */
    private static void requireDraftTargetVersion(DdlTaskEntity entity, DdlTaskDraft draft) {
        LocalDateTime snapshot = draft.targetTaskUpdatedAtValue();
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                    "变更草案缺少目标任务版本，请重新提取通知。");
        }
        LocalDateTime current = entity.getUpdatedAt() == null
                ? null
                : entity.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (current == null || !current.equals(snapshot.truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS))) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                    "目标任务在草案生成后已被修改，请重新提取通知。");
        }
    }

    /**
     * 草案中的 course_id 必须仍属于当前用户且课程活动；课程已删除时解绑为 NULL，
     * 永久保留课程名称文本（`PO-05`），不允许把任务重新挂回已删除课程。
     */
    private CourseRef resolveDraftCourse(Long userId, Long courseId, String courseNameRaw,
                                         boolean lockCourseRow) {
        String courseName = requireDraftText(courseNameRaw, 128, "course_name");
        if (courseId == null) {
            return new CourseRef(null, courseName);
        }
        try {
            CourseNamespaceEntity course = lockCourseRow
                    ? courseService.getActiveCourseForUpdate(userId, courseId)
                    : courseService.getActiveCourse(userId, courseId);
            if (course == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return new CourseRef(course.getCourseId(), course.getCourseName());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return new CourseRef(null, courseName);
            }
            throw e;
        }
    }

    private ParsedDraft parseDraft(DdlTaskDraft draft, LocalDateTime now) {
        if (draft == null || draft.confidence() == null
                || draft.confidence().compareTo(BigDecimal.ZERO) < 0
                || draft.confidence().compareTo(BigDecimal.ONE) > 0) {
            throw invalidDraft();
        }
        TimePrecision precision;
        TaskStatus status;
        try {
            precision = TimePrecision.valueOf(draft.timePrecision());
            status = TaskStatus.valueOf(draft.status());
        } catch (RuntimeException e) {
            throw invalidDraft();
        }
        LocalDate dueDate = draft.dueDateValue();
        LocalTime dueTime = draft.dueTimeValue();
        LocalDateTime dueAt = draft.dueAtValue();
        LocalDateTime followUpAt = draft.followUpAtValue();
        if (status != TaskStatus.ACTIVE
                && status != TaskStatus.PENDING_TIME
                && status != TaskStatus.PENDING_CONFIRM) {
            throw invalidDraft();
        }
        if (precision == TimePrecision.DATE_ONLY
                && (dueTime != null || !draft.timeInferred() || dueAt == null)) {
            throw invalidDraft();
        }
        if (precision == TimePrecision.EXACT_DATETIME
                && (dueDate == null || dueTime == null || dueAt == null)) {
            throw invalidDraft();
        }
        if (precision == TimePrecision.VAGUE_OR_MISSING
                && (dueDate != null || dueTime != null || dueAt != null)) {
            throw invalidDraft();
        }
        if (precision == TimePrecision.INVALID_OR_PAST
                && dueDate != null && dueAt == null) {
            throw invalidDraft();
        }
        if (status == TaskStatus.PENDING_TIME
                && (dueDate != null || dueTime != null || dueAt != null)) {
            throw invalidDraft();
        }
        if (status == TaskStatus.PENDING_CONFIRM
                && (dueAt == null || dueDate == null)) {
            throw invalidDraft();
        }
        if ((status == TaskStatus.PENDING_TIME || status == TaskStatus.PENDING_CONFIRM)
                && followUpAt == null) {
            throw invalidDraft();
        }
        if (status == TaskStatus.ACTIVE && followUpAt != null) {
            throw invalidDraft();
        }
        if ((status == TaskStatus.ACTIVE || status == TaskStatus.OVERDUE)
                && (dueDate == null || dueAt == null)) {
            throw invalidDraft();
        }

        // 决策时必须重新校验时间：草案可能生成于数小时前，期间截止时间已经过去；
        // 同时校验 dueAt 与 dueDate/dueTime/userTimezone 的一致性，防止被篡改的草案写入。
        String timezone = timeRuleValidator.normalizeUserTimezone(draft.userTimezone());
        LocalDateTime expectedDueAt = expectedDueAt(precision, dueDate, dueTime,
                timeRuleValidator.resolveUserZone(timezone));
        if (expectedDueAt != null && (dueAt == null
                || !expectedDueAt.withNano(0).equals(dueAt.withNano(0)))) {
            throw invalidDraft();
        }
        String ambiguityReason = optionalDraftText(draft.timeAmbiguityReason(), 512,
                "time_ambiguity_reason");
        TaskStatus effectiveStatus = status;
        LocalDateTime effectiveFollowUpAt = followUpAt;
        if (effectiveStatus == TaskStatus.ACTIVE && dueAt != null && !dueAt.isAfter(now)) {
            effectiveStatus = TaskStatus.PENDING_CONFIRM;
            ambiguityReason = ambiguityReason == null
                    ? "草案中的截止时间已过去，请重新确认" : ambiguityReason;
            effectiveFollowUpAt = now.plusHours(FOLLOW_UP_HOURS);
        }
        if (effectiveStatus == TaskStatus.PENDING_CONFIRM) {
            if (ambiguityReason == null) {
                ambiguityReason = "时间早于当前时间或存在歧义，请核对";
            }
            if (effectiveFollowUpAt == null || !effectiveFollowUpAt.isAfter(now)) {
                effectiveFollowUpAt = now.plusHours(FOLLOW_UP_HOURS);
            }
        }
        if (effectiveStatus == TaskStatus.PENDING_TIME
                && (effectiveFollowUpAt == null || !effectiveFollowUpAt.isAfter(now))) {
            effectiveFollowUpAt = now.plusHours(FOLLOW_UP_HOURS);
        }
        if (effectiveStatus == TaskStatus.ACTIVE) {
            ambiguityReason = null;
            effectiveFollowUpAt = null;
        }
        return new ParsedDraft(precision, effectiveStatus, dueDate, dueTime, dueAt,
                effectiveFollowUpAt, ambiguityReason);
    }

    private LocalDateTime expectedDueAt(TimePrecision precision, LocalDate dueDate,
                                        LocalTime dueTime, ZoneId zone) {
        if (precision == TimePrecision.VAGUE_OR_MISSING || dueDate == null) {
            return null;
        }
        if (precision == TimePrecision.EXACT_DATETIME) {
            return timeRuleValidator.combineInAppZone(dueDate, dueTime, zone);
        }
        LocalTime effectiveTime = dueTime == null ? DATE_ONLY_SENTINEL : dueTime;
        return timeRuleValidator.combineInAppZone(dueDate, effectiveTime, zone);
    }

    private static String requireDraftText(String raw, int maxLength, String field) {
        String value = trimToNull(raw);
        if (value == null || value.length() > maxLength) {
            throw invalidDraft();
        }
        return value;
    }

    private static String optionalDraftText(String raw, int maxLength, String field) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw invalidDraft();
        }
        return value;
    }

    private static BusinessException invalidDraft() {
        return new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                "变更草案已失效，请重新提取通知。");
    }

    private DdlTaskEntity lockOwnedTask(Long userId, Long taskId) {
        DdlTaskEntity entity = ddlTaskMapper.selectByIdForUpdate(taskId, userId);
        if (entity == null || TaskStatus.CANCELED.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private List<DdlTaskVO> queryTasks(Long userId, Long courseId, List<String> statuses,
                                       Consumer<LambdaQueryWrapper<DdlTaskEntity>> order,
                                       Integer limit, ZoneId userZone) {
        LambdaQueryWrapper<DdlTaskEntity> query = Wrappers.<DdlTaskEntity>lambdaQuery()
                .eq(DdlTaskEntity::getUserId, userId)
                .in(DdlTaskEntity::getStatus, statuses);
        if (courseId != null) {
            query.eq(DdlTaskEntity::getCourseId, courseId);
        }
        order.accept(query);
        if (limit != null) {
            // 常量整数，不拼接任何用户输入。
            query.last("LIMIT " + limit);
        }
        return ddlTaskMapper.selectList(query).stream()
                .map(entity -> toVO(entity, userZone))
                .toList();
    }

    private UserContext resolveUserContext(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (UserConfigService.isDeactivating(config)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        String timezoneName = timeRuleValidator.normalizeUserTimezone(config.getTimezone());
        return new UserContext(timezoneName, timeRuleValidator.resolveUserZone(timezoneName));
    }

    private CourseRef resolveCourse(Long userId, String courseIdRaw, String courseNameRaw,
                                    boolean lockCourseRow) {
        Long courseId = parseOptionalPositiveId(courseIdRaw, "course_id");
        if (courseId != null) {
            // 写路径必须锁定课程行：否则“读到 is_deleting=0 → 删课事务解绑 → 本请求写回”
            // 会让 PO-05 在并发下失效。读取路径仍使用非锁定查询。
            CourseNamespaceEntity course = lockCourseRow
                    ? courseService.getActiveCourseForUpdate(userId, courseId)
                    : courseService.getActiveCourse(userId, courseId);
            if (course == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return new CourseRef(course.getCourseId(), course.getCourseName());
        }
        String courseName = trimToNull(courseNameRaw);
        if (courseName == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "course_name 不能为空。");
        }
        if (courseName.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "course_name 长度不能超过 128。");
        }
        return new CourseRef(null, courseName);
    }

    private static String requireTaskTitle(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "task_title 不能为空。");
        }
        if (value.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "task_title 长度不能超过 255。");
        }
        return value;
    }

    private static String optionalText(String raw, int maxLength, String field) {
        String value = trimToNull(raw);
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 长度不能超过 " + maxLength + "。");
        }
        return value;
    }

    static String requireClientRequestId(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (value.length() < 8 || value.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "client_request_id 长度必须为 8~128 字符。");
        }
        return value;
    }

    static Long parseOptionalPositiveId(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > 19) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
    }

    /** 编辑后的确认原因：只有“仍需确认”的情况保留说明；明确未来时间必须清空。 */
    private static String editAmbiguityReason(TaskStatus status, TimePrecision precision) {
        if (status == TaskStatus.PENDING_CONFIRM) {
            return precision == TimePrecision.INVALID_OR_PAST
                    ? "原时间被判定为非法或已过期，请核对后重新确认时间"
                    : "时间早于当前时间或存在歧义，请核对";
        }
        return null;
    }

    /**
     * 取消完成恢复 `ACTIVE` 时，按新剩余时长重算提醒位：
     * 剩余超过 24h 清除 T-24h 位，超过 6h 清除 T-6h 位，并始终清除逾期位。
     */
    private static int restoreActiveFlags(int flags, LocalDateTime dueAt, LocalDateTime now) {
        int result = flags & ~REMINDED_OVERDUE;
        if (dueAt == null) {
            return result;
        }
        Duration remaining = Duration.between(now, dueAt);
        if (remaining.compareTo(Duration.ofHours(24)) > 0) {
            result &= ~REMINDED_T24H;
        }
        if (remaining.compareTo(Duration.ofHours(6)) > 0) {
            result &= ~REMINDED_T6H;
        }
        return result;
    }

    private static int computeRemindedFlags(DdlTaskEntity existing, TaskStatus newStatus,
                                            LocalDateTime newDueAt, LocalDateTime now) {
        if (newStatus != TaskStatus.ACTIVE) {
            return 0;
        }
        int flags = existing.getRemindedFlags() == null ? 0 : existing.getRemindedFlags();
        if (newDueAt != null && !Objects.equals(existing.getDueAt(), newDueAt)) {
            Duration remaining = Duration.between(now, newDueAt);
            if (remaining.compareTo(Duration.ofHours(24)) > 0) {
                flags &= ~REMINDED_T24H;
            }
            if (remaining.compareTo(Duration.ofHours(6)) > 0) {
                flags &= ~REMINDED_T6H;
            }
        }
        return flags & ~REMINDED_OVERDUE;
    }

    private static TaskStatus restoreStatus(LocalDateTime dueAt, LocalDateTime now) {
        if (dueAt == null) {
            return TaskStatus.PENDING_TIME;
        }
        return dueAt.isAfter(now) ? TaskStatus.ACTIVE : TaskStatus.OVERDUE;
    }

    private static LambdaUpdateWrapper<DdlTaskEntity> taskUpdateWrapper(DdlTaskEntity entity) {
        return Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getTaskId, entity.getTaskId())
                .eq(DdlTaskEntity::getUserId, entity.getUserId())
                .set(DdlTaskEntity::getCourseId, entity.getCourseId())
                .set(DdlTaskEntity::getCourseName, entity.getCourseName())
                .set(DdlTaskEntity::getTaskTitle, entity.getTaskTitle())
                .set(DdlTaskEntity::getDeliverableFormat, entity.getDeliverableFormat())
                .set(DdlTaskEntity::getSubmissionChannel, entity.getSubmissionChannel())
                .set(DdlTaskEntity::getTimePrecision, entity.getTimePrecision())
                .set(DdlTaskEntity::getDueDate, entity.getDueDate())
                .set(DdlTaskEntity::getDueTime, entity.getDueTime())
                .set(DdlTaskEntity::getDueAt, entity.getDueAt())
                .set(DdlTaskEntity::getTimeInferred, entity.getTimeInferred())
                .set(DdlTaskEntity::getTimeAmbiguityReason, entity.getTimeAmbiguityReason())
                .set(DdlTaskEntity::getUserTimezone, entity.getUserTimezone())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getFollowUpCount, entity.getFollowUpCount())
                .set(DdlTaskEntity::getRemindedFlags, entity.getRemindedFlags())
                .set(DdlTaskEntity::getCompletedAt, entity.getCompletedAt())
                .set(DdlTaskEntity::getRawSourceText, entity.getRawSourceText())
                .set(DdlTaskEntity::getRawTimeText, entity.getRawTimeText())
                .set(DdlTaskEntity::getIsRelativeTime, entity.getIsRelativeTime())
                .set(DdlTaskEntity::getConfidence, entity.getConfidence())
                .set(DdlTaskEntity::getContentHash, entity.getContentHash())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    private static LambdaUpdateWrapper<DdlTaskEntity> completionUpdateWrapper(DdlTaskEntity entity) {
        return Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getTaskId, entity.getTaskId())
                .eq(DdlTaskEntity::getUserId, entity.getUserId())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getStatusBeforeCompletion, entity.getStatusBeforeCompletion())
                .set(DdlTaskEntity::getCompletedAt, entity.getCompletedAt())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getFollowUpCount, entity.getFollowUpCount())
                .set(DdlTaskEntity::getRemindedFlags, entity.getRemindedFlags())
                .set(DdlTaskEntity::getTimeAmbiguityReason, entity.getTimeAmbiguityReason())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    private static LambdaUpdateWrapper<DdlTaskEntity> cancelUpdateWrapper(DdlTaskEntity entity) {
        return Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getTaskId, entity.getTaskId())
                .eq(DdlTaskEntity::getUserId, entity.getUserId())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getStatusBeforeCompletion, entity.getStatusBeforeCompletion())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    static DdlTaskVO toVO(DdlTaskEntity entity, ZoneId userZone) {
        int flags = entity.getRemindedFlags() == null ? 0 : entity.getRemindedFlags();
        // 优先使用任务创建时的时区快照，保证 due_date/due_time 与 due_at 的墙钟语义一致；
        // 快照缺失/非法时才退回当前用户时区。
        ZoneId displayZone = entityZone(entity.getUserTimezone(), userZone);
        return new DdlTaskVO(
                entity.getTaskId(),
                "#T" + entity.getTaskId(),
                entity.getCourseId(),
                entity.getCourseName(),
                entity.getTaskTitle(),
                entity.getDeliverableFormat(),
                entity.getSubmissionChannel(),
                entity.getRawSourceText(),
                entity.getRawTimeText(),
                entity.getTimePrecision(),
                isOne(entity.getIsRelativeTime()),
                entity.getDueDate() == null ? null : entity.getDueDate().toString(),
                entity.getDueTime() == null ? null : entity.getDueTime().format(DUE_TIME_FORMAT),
                toUserOffset(entity.getDueAt(), displayZone),
                isOne(entity.getTimeInferred()),
                entity.getTimeAmbiguityReason(),
                entity.getUserTimezone(),
                entity.getConfidence(),
                entity.getStatus(),
                ScTimeZone.toOffsetDateTime(entity.getFollowUpAt()),
                entity.getFollowUpCount() == null ? 0 : entity.getFollowUpCount(),
                (flags & REMINDED_T24H) != 0,
                (flags & REMINDED_T6H) != 0,
                (flags & REMINDED_OVERDUE) != 0,
                entity.getSourcePlatform(),
                ScTimeZone.toOffsetDateTime(entity.getCompletedAt()),
                ScTimeZone.toOffsetDateTime(entity.getCreatedAt()),
                ScTimeZone.toOffsetDateTime(entity.getUpdatedAt()));
    }

    /**
     * 数据库 DATETIME 按应用时区解释；输出时换算回用户时区，保证 {@code due_at} 带用户偏移量。
     */
    static OffsetDateTime toUserOffset(LocalDateTime appLocal, ZoneId userZone) {
        if (appLocal == null) {
            return null;
        }
        return ScTimeZone.toOffsetDateTime(appLocal)
                .atZoneSameInstant(userZone)
                .toOffsetDateTime();
    }

    private static ZoneId entityZone(String timezone, ZoneId fallback) {
        if (timezone == null || timezone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            return fallback;
        }
    }

    private static boolean isOne(Integer value) {
        return value != null && value == 1;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record DdlTaskCreationResult(DdlTaskVO task, boolean created) {
    }

    private record ParsedDraft(TimePrecision precision, TaskStatus status,
                               LocalDate dueDate, LocalTime dueTime,
                               LocalDateTime dueAt, LocalDateTime followUpAt,
                               String ambiguityReason) {
    }

    private record UserContext(String timezoneName, ZoneId zoneId) {
    }

    private record CourseRef(Long courseId, String courseName) {
    }

    private enum StatusGroup {
        ALL(true, true, true),
        PENDING_INBOX(true, false, false),
        ACTIVE_BOARD(false, true, false),
        COMPLETED(false, false, true);

        static final List<String> PENDING_STATUSES = List.of(
                TaskStatus.PENDING_TIME.name(), TaskStatus.PENDING_CONFIRM.name());
        static final List<String> ACTIVE_STATUSES = List.of(
                TaskStatus.ACTIVE.name(), TaskStatus.OVERDUE.name());
        static final List<String> COMPLETED_STATUSES = List.of(TaskStatus.COMPLETED.name());

        private final boolean pending;
        private final boolean active;
        private final boolean completed;

        StatusGroup(boolean pending, boolean active, boolean completed) {
            this.pending = pending;
            this.active = active;
            this.completed = completed;
        }

        boolean includesPending() {
            return pending;
        }

        boolean includesActive() {
            return active;
        }

        boolean includesCompleted() {
            return completed;
        }

        static StatusGroup parse(String raw) {
            String value = trimToNull(raw);
            if (value == null) {
                return ALL;
            }
            try {
                return StatusGroup.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "status_group 取值非法。");
            }
        }
    }
}
