package com.scholarclaw.modules.ddl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DeleteDdlTaskVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlTimeRuleValidator.DdlTimeFields;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
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
    private static final int COMPLETED_LIST_LIMIT = 50;
    private static final int FOLLOW_UP_HOURS = 24;
    private static final int REMINDED_T24H = 1;
    private static final int REMINDED_T6H = 2;
    private static final int REMINDED_OVERDUE = 4;

    private final DdlTaskMapper ddlTaskMapper;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final DdlTimeRuleValidator timeRuleValidator;

    public DdlTaskService(DdlTaskMapper ddlTaskMapper,
                          CourseService courseService,
                          UserConfigService userConfigService,
                          DdlTimeRuleValidator timeRuleValidator) {
        this.ddlTaskMapper = ddlTaskMapper;
        this.courseService = courseService;
        this.userConfigService = userConfigService;
        this.timeRuleValidator = timeRuleValidator;
    }

    /**
     * `API-DDL-002`：手动表单新建任务。
     *
     * <p>这里实现的是请求级唯一键幂等（{@code uk_ddl_idempotency_key}）；14 天
     * {@code content_hash} 语义防重与 Redis 接入层去重属 TASK-704。
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO createManualTask(Long userId, CreateDdlTaskRequest request) {
        String clientRequestId = requireClientRequestId(request.clientRequestId());
        UserContext user = resolveUserContext(userId);
        CourseRef course = resolveCourse(userId, request.courseId(), request.courseName());
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolve(
                request.timePrecision(), request.dueDate(), request.dueTime(),
                user.zoneId(), now);
        String idempotencyKey = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);

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
        entity.setUserTimezone(user.timezoneName());
        entity.setConfidence(MANUAL_CONFIDENCE);
        entity.setStatus(time.status().name());
        entity.setFollowUpAt(time.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform(SOURCE_WEB);
        entity.setSourceMsgId(clientRequestId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setContentHash(null);
        entity.setCompletedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            ddlTaskMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing == null || !Objects.equals(existing.getUserId(), userId)) {
                throw e;
            }
            return toVO(existing, user.zoneId());
        }
        return toVO(entity, user.zoneId());
    }

    /** `API-DDL-003`：任务看板；变更草案在 TASK-703 固定为空列表。 */
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
        return new DdlTaskBoardVO(pending, List.of(), active, completed);
    }

    /** `API-DDL-004`：编辑字段、补充时间激活、或将待定任务改为待补充时间。 */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO updateTask(Long userId, Long taskId, UpdateDdlTaskRequest request) {
        UserContext user = resolveUserContext(userId);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        CourseRef course = resolveCourse(userId, request.courseId(), request.courseName());
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolve(
                request.timePrecision(), request.dueDate(), request.dueTime(),
                user.zoneId(), now);
        int flags = computeRemindedFlags(entity, time.status(), time.dueAt(), now);

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
        entity.setUserTimezone(user.timezoneName());
        entity.setStatus(time.status().name());
        entity.setFollowUpAt(time.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(flags);
        entity.setCompletedAt(null);
        entity.setUpdatedAt(now);

        // is_relative_time / confidence / raw_* / source_* / idempotency_key / content_hash
        // 保留原值：编辑不改变来源与原始提取可信度。
        int updated = ddlTaskMapper.update(null, taskUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toVO(entity, user.zoneId());
    }

    /** `API-DDL-006`：标记完成 / 取消完成勾选；相同请求幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO setCompletion(Long userId, Long taskId, boolean completed) {
        UserContext user = resolveUserContext(userId);
        DdlTaskEntity entity = lockOwnedTask(userId, taskId);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();

        if (completed) {
            if (TaskStatus.COMPLETED.name().equals(entity.getStatus())) {
                return toVO(entity, user.zoneId());
            }
            entity.setStatus(TaskStatus.COMPLETED.name());
            entity.setCompletedAt(now);
            entity.setFollowUpAt(null);
        } else {
            TaskStatus restored = restoreStatus(entity.getDueAt(), now);
            entity.setStatus(restored.name());
            entity.setCompletedAt(null);
            if (restored == TaskStatus.PENDING_TIME) {
                entity.setFollowUpAt(now.plusHours(FOLLOW_UP_HOURS));
                // 用户主动取消完成，重新给予完整的跟进提醒额度。
                entity.setFollowUpCount(0);
            } else {
                entity.setFollowUpAt(null);
            }
            if (restored == TaskStatus.ACTIVE) {
                int flags = entity.getRemindedFlags() == null ? 0 : entity.getRemindedFlags();
                entity.setRemindedFlags(flags & ~REMINDED_OVERDUE);
            }
        }
        entity.setUpdatedAt(now);

        int updated = ddlTaskMapper.update(null, completionUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toVO(entity, user.zoneId());
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
            return new DeleteDdlTaskVO(taskId.toString(), TaskStatus.CANCELED.name());
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        entity.setStatus(TaskStatus.CANCELED.name());
        entity.setFollowUpAt(null);
        entity.setUpdatedAt(now);
        int updated = ddlTaskMapper.update(null, cancelUpdateWrapper(entity));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return new DeleteDdlTaskVO(taskId.toString(), TaskStatus.CANCELED.name());
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

    private CourseRef resolveCourse(Long userId, String courseIdRaw, String courseNameRaw) {
        Long courseId = parseOptionalPositiveId(courseIdRaw, "course_id");
        if (courseId != null) {
            CourseNamespaceEntity course = courseService.getActiveCourse(userId, courseId);
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

    private static String requireClientRequestId(String raw) {
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

    private static Long parseOptionalPositiveId(String raw, String field) {
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
                .set(DdlTaskEntity::getUserTimezone, entity.getUserTimezone())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getFollowUpCount, entity.getFollowUpCount())
                .set(DdlTaskEntity::getRemindedFlags, entity.getRemindedFlags())
                .set(DdlTaskEntity::getCompletedAt, entity.getCompletedAt())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    private static LambdaUpdateWrapper<DdlTaskEntity> completionUpdateWrapper(DdlTaskEntity entity) {
        return Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getTaskId, entity.getTaskId())
                .eq(DdlTaskEntity::getUserId, entity.getUserId())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getCompletedAt, entity.getCompletedAt())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getFollowUpCount, entity.getFollowUpCount())
                .set(DdlTaskEntity::getRemindedFlags, entity.getRemindedFlags())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    private static LambdaUpdateWrapper<DdlTaskEntity> cancelUpdateWrapper(DdlTaskEntity entity) {
        return Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getTaskId, entity.getTaskId())
                .eq(DdlTaskEntity::getUserId, entity.getUserId())
                .set(DdlTaskEntity::getStatus, entity.getStatus())
                .set(DdlTaskEntity::getFollowUpAt, entity.getFollowUpAt())
                .set(DdlTaskEntity::getUpdatedAt, entity.getUpdatedAt());
    }

    private static DdlTaskVO toVO(DdlTaskEntity entity, ZoneId userZone) {
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
    private static OffsetDateTime toUserOffset(LocalDateTime appLocal, ZoneId userZone) {
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
