package com.scholarclaw.modules.ddl.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.DdlExtractOutcome;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DraftDecisionRequest;
import com.scholarclaw.modules.ddl.dto.ExtractDdlTaskRequest;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlDraftStore.LockState;
import com.scholarclaw.modules.ddl.service.DdlTimeRuleValidator.DdlTimeFields;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * DDL 通知提取建单与变更草案决策（TASK-704，`04` `API-DDL-001/005`）。
 *
 * <p>三层幂等：
 * <ol>
 *   <li><b>接入层</b>：Redis `sc:idemp:msg:WEB:{uid}:{client_request_id}`，
 *       `PROCESSING(60s) -> PROCESSED(24h)`；Redis 不可用时降级放行，由数据库唯一键兜底；</li>
 *   <li><b>请求级</b>：MySQL `uk_ddl_idempotency_key = sha256(uid:WEB:client_request_id:DDL)`，
 *       先查后插，冲突回查返回 {@code IDEMPOTENT_HIT}；</li>
 *   <li><b>业务语义</b>：近 14 天相同 {@code content_hash} → {@code SEMANTIC_DUPLICATE_HINT}；
 *       同课程标题相似但截止时间/要求变更 → Redis 变更草案 + {@code CHANGE_DRAFT_CONFIRM_REQUIRED}；
 *       {@code force_create=true} 跳过本层但**不改变**幂等键公式。</li>
 * </ol>
 *
 * <p>Python 提取在数据库事务外执行；落库与草案更新由 {@link DdlTaskService} 的本地事务完成。
 * 本服务不记录原文、课程名或明文 API Key 到日志。
 */
@Service
public class DdlExtractionService {

    private static final Duration EXTRACT_READ_TIMEOUT = Duration.ofSeconds(15);
    private static final int RAW_TEXT_MIN_LENGTH = 4;
    private static final int RAW_TEXT_MAX_LENGTH = 4000;
    private static final int SEMANTIC_WINDOW_DAYS = 14;
    private static final int SIMILAR_TASK_LIMIT = 200;
    private static final int MAX_CANDIDATE_COURSES = 200;
    private static final int DRAFT_TTL_HOURS = 24;
    private static final String DEFAULT_COURSE_NAME = "未分类课程";
    private static final List<String> SIMILAR_TASK_STATUSES = List.of(
            TaskStatus.PENDING_TIME.name(),
            TaskStatus.PENDING_CONFIRM.name(),
            TaskStatus.ACTIVE.name(),
            TaskStatus.OVERDUE.name());

    private final PythonAiEngineClient pythonAiEngineClient;
    private final CourseService courseService;
    private final DdlTaskMapper ddlTaskMapper;
    private final DdlTaskService ddlTaskService;
    private final DdlDraftStore draftStore;
    private final DdlSemanticMatcher semanticMatcher;
    private final DdlUserConfigResolver userConfigResolver;
    private final DdlTimeRuleValidator timeRuleValidator;

    public DdlExtractionService(PythonAiEngineClient pythonAiEngineClient,
                                CourseService courseService,
                                DdlTaskMapper ddlTaskMapper,
                                DdlTaskService ddlTaskService,
                                DdlDraftStore draftStore,
                                DdlSemanticMatcher semanticMatcher,
                                DdlUserConfigResolver userConfigResolver,
                                DdlTimeRuleValidator timeRuleValidator) {
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.courseService = courseService;
        this.ddlTaskMapper = ddlTaskMapper;
        this.ddlTaskService = ddlTaskService;
        this.draftStore = draftStore;
        this.semanticMatcher = semanticMatcher;
        this.userConfigResolver = userConfigResolver;
        this.timeRuleValidator = timeRuleValidator;
    }

    /** `API-DDL-001`：通知文本 → Python 提取 → 三层幂等 → 建单/草案。 */
    public DdlExtractVO extract(Long userId, ExtractDdlTaskRequest request) {
        String clientRequestId = DdlTaskService.requireClientRequestId(request.clientRequestId());
        String rawText = requireRawText(request.rawText());
        ResolvedUser user = userConfigResolver.resolve(userId);
        List<CourseNamespaceVO> courses = courseService.listCourses(userId);
        String idempotencyKey = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);

        // 第 2 层：请求级幂等，命中则完全跳过 Python 调用。
        DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null && Objects.equals(existing.getUserId(), userId)) {
            return new DdlExtractVO(DdlExtractOutcome.IDEMPOTENT_HIT,
                    DdlTaskService.toVO(existing, user.zoneId()), null);
        }

        // 第 1 层：Redis 接入锁。
        LockState lock = draftStore.acquireWebRequest(userId, clientRequestId);
        if (lock == LockState.PROCESSING) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        if (lock == LockState.PROCESSED) {
            DdlTaskEntity processed = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey);
            if (processed != null && Objects.equals(processed.getUserId(), userId)) {
                return new DdlExtractVO(DdlExtractOutcome.IDEMPOTENT_HIT,
                        DdlTaskService.toVO(processed, user.zoneId()), null);
            }
            // 上次处理结果是语义提示/草案（未建单）：释放并重新处理。
            draftStore.release(userId, clientRequestId);
            lock = draftStore.acquireWebRequest(userId, clientRequestId);
            if (lock != LockState.ACQUIRED) {
                throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            }
        }

        boolean handled = false;
        try {
            JsonNode data = pythonAiEngineClient.postJson(
                    PythonAiEngineClient.ROUTE_AND_EXTRACT_PATH,
                    pythonRequest(userId, user, courses, rawText, request.referenceTime()),
                    userId, EXTRACT_READ_TIMEOUT);
            DdlExtractedPayload payload = DdlExtractedPayload.fromData(data);
            LocalDateTime now = ScTimeZone.now().toLocalDateTime();
            DdlTimeFields time = timeRuleValidator.resolveExtracted(
                    payload.timePrecision(), payload.parsedDueDate(), payload.parsedDueTime(),
                    payload.hasAmbiguity(), payload.confidence(), user.zoneId(), now);
            CourseRef course = resolveCourse(userId, courses, request.courseId(),
                    payload.courseName());
            DdlTaskEntity entity = buildEntity(userId, clientRequestId, idempotencyKey,
                    rawText, payload, time, course, user, now);
            String contentHash = semanticMatcher.contentHash(entity.getCourseName(), rawText);
            entity.setContentHash(contentHash);

            if (!Boolean.TRUE.equals(request.forceCreate())) {
                DdlTaskEntity exact = findExactContentHash(userId, contentHash, now);
                if (exact != null) {
                    draftStore.markProcessed(userId, clientRequestId);
                    handled = true;
                    return new DdlExtractVO(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT,
                            DdlTaskService.toVO(exact, user.zoneId()), null);
                }
                DdlTaskEntity similar = findSimilarTask(userId, entity);
                if (similar != null) {
                    if (requirementsChanged(similar, entity)) {
                        DdlTaskDraft draft = DdlTaskDraft.fromEntity(entity,
                                similar.getTaskId(), now.plusHours(DRAFT_TTL_HOURS).toString());
                        draftStore.saveDraft(userId, draft);
                        draftStore.markProcessed(userId, clientRequestId);
                        handled = true;
                        return new DdlExtractVO(
                                DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED,
                                DdlTaskService.toVO(similar, user.zoneId()),
                                draft.toVO(user.zoneId()));
                    }
                    draftStore.markProcessed(userId, clientRequestId);
                    handled = true;
                    return new DdlExtractVO(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT,
                            DdlTaskService.toVO(similar, user.zoneId()), null);
                }
            }

            DdlTaskService.DdlTaskCreationResult result =
                    ddlTaskService.createExtractedTask(entity);
            draftStore.markProcessed(userId, clientRequestId);
            handled = true;
            return new DdlExtractVO(
                    result.created() ? DdlExtractOutcome.CREATED : DdlExtractOutcome.IDEMPOTENT_HIT,
                    result.task(), null);
        } finally {
            if (!handled) {
                draftStore.release(userId, clientRequestId);
            }
        }
    }

    /** `API-DDL-005`：确认更新 / 仍然新建 / 忽略变更草案。 */
    public DdlTaskVO decideDraft(Long userId, Long taskId, DraftDecisionRequest request) {
        Decision decision = parseDecision(request.decision());
        String clientRequestId = DdlTaskService.requireClientRequestId(request.clientRequestId());
        ResolvedUser user = userConfigResolver.resolve(userId);

        DdlTaskEntity target = ddlTaskMapper.selectOne(Wrappers.<DdlTaskEntity>lambdaQuery()
                .eq(DdlTaskEntity::getTaskId, taskId)
                .eq(DdlTaskEntity::getUserId, userId));
        if (target == null || TaskStatus.CANCELED.name().equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        Optional<DdlTaskDraft> draftOpt = draftStore.loadDraft(userId, taskId);
        if (draftOpt.isEmpty()) {
            if (decision == Decision.CREATE_NEW) {
                String key = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);
                DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(key);
                if (existing != null && Objects.equals(existing.getUserId(), userId)) {
                    return DdlTaskService.toVO(existing, user.zoneId());
                }
            }
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND);
        }
        DdlTaskDraft draft = draftOpt.get();
        if (!Objects.equals(draft.targetTaskId(), taskId)) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND);
        }

        return switch (decision) {
            case CONFIRM_UPDATE -> {
                DdlTaskVO updated = ddlTaskService.applyDraftUpdate(userId, taskId, draft);
                draftStore.deleteDraft(userId, taskId);
                yield updated;
            }
            case CREATE_NEW -> {
                DdlTaskService.DdlTaskCreationResult result =
                        ddlTaskService.createFromDraft(userId, draft, clientRequestId);
                draftStore.deleteDraft(userId, taskId);
                yield result.task();
            }
            case DISCARD -> {
                draftStore.deleteDraft(userId, taskId);
                yield DdlTaskService.toVO(target, user.zoneId());
            }
        };
    }

    private Map<String, Object> pythonRequest(Long userId, ResolvedUser user,
                                              List<CourseNamespaceVO> courses,
                                              String rawText, String referenceTimeRaw) {
        ModelEndpoint chat = userConfigResolver.resolveChatEndpoint(user.config());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("mode", "FORCE_DDL_EXTRACT");
        body.put("raw_text", rawText);
        body.put("reference_time", referenceTime(referenceTimeRaw, user.zoneId()));
        body.put("user_timezone", user.timezoneName());
        body.put("candidate_courses", courses.stream()
                .limit(MAX_CANDIDATE_COURSES)
                .map(CourseNamespaceVO::courseName)
                .toList());
        Map<String, Object> chatConfig = new LinkedHashMap<>();
        chatConfig.put("provider", chat.provider());
        chatConfig.put("base_url", chat.baseUrl());
        chatConfig.put("api_key", chat.apiKey());
        chatConfig.put("model_name", chat.modelName());
        body.put("chat_config", chatConfig);
        return body;
    }

    private static String referenceTime(String raw, ZoneId userZone) {
        String value = trimToNull(raw);
        if (value == null) {
            return OffsetDateTime.now(userZone).truncatedTo(ChronoUnit.MILLIS).toString();
        }
        try {
            return OffsetDateTime.parse(value).toString();
        } catch (DateTimeParseException first) {
            try {
                return ZonedDateTime.parse(value).toOffsetDateTime().toString();
            } catch (DateTimeParseException second) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "reference_time 必须为带时区的 ISO-8601 字符串。");
            }
        }
    }

    private CourseRef resolveCourse(Long userId, List<CourseNamespaceVO> courses,
                                    String courseIdRaw, String payloadCourseName) {
        Long courseId = DdlTaskService.parseOptionalPositiveId(courseIdRaw, "course_id");
        if (courseId != null) {
            CourseNamespaceEntity course = courseService.getActiveCourse(userId, courseId);
            return new CourseRef(course.getCourseId(), course.getCourseName());
        }
        String name = trimToNull(payloadCourseName);
        if (name == null) {
            return new CourseRef(null, DEFAULT_COURSE_NAME);
        }
        if (name.length() > 128) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型返回的 course_name 超过长度上限。");
        }
        CourseNamespaceVO matched = null;
        int matches = 0;
        for (CourseNamespaceVO course : courses) {
            if (name.equals(trimToNull(course.courseName()))) {
                matched = course;
                matches++;
            }
        }
        if (matches == 1 && matched != null) {
            return new CourseRef(matched.courseId(), matched.courseName());
        }
        return new CourseRef(null, name);
    }

    private DdlTaskEntity buildEntity(Long userId, String clientRequestId,
                                      String idempotencyKey, String rawText,
                                      DdlExtractedPayload payload, DdlTimeFields time,
                                      CourseRef course, ResolvedUser user, LocalDateTime now) {
        DdlTaskEntity entity = new DdlTaskEntity();
        entity.setUserId(userId);
        entity.setCourseId(course.courseId());
        entity.setCourseName(course.courseName());
        entity.setTaskTitle(resolveTaskTitle(payload.taskTitle(), rawText));
        entity.setDeliverableFormat(limit(payload.deliverableFormat(), 255));
        entity.setSubmissionChannel(limit(payload.submissionChannel(), 255));
        entity.setRawSourceText(rawText);
        entity.setRawTimeText(limit(payload.rawTimeText(), 128));
        entity.setTimePrecision(time.timePrecision().name());
        entity.setIsRelativeTime(payload.isRelativeTime() ? 1 : 0);
        entity.setDueDate(time.dueDate());
        entity.setDueTime(time.dueTime());
        entity.setDueAt(time.dueAt());
        entity.setTimeInferred(time.timeInferred() ? 1 : 0);
        entity.setUserTimezone(user.timezoneName());
        entity.setConfidence(payload.confidence());
        entity.setStatus(time.status().name());
        entity.setFollowUpAt(time.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform(DdlIdempotencyKey.SOURCE_PLATFORM_WEB);
        entity.setSourceMsgId(clientRequestId);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setContentHash(null);
        entity.setCompletedAt(null);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private DdlTaskEntity findExactContentHash(Long userId, String contentHash,
                                               LocalDateTime now) {
        return ddlTaskMapper.selectOne(Wrappers.<DdlTaskEntity>lambdaQuery()
                .eq(DdlTaskEntity::getUserId, userId)
                .eq(DdlTaskEntity::getContentHash, contentHash)
                .ne(DdlTaskEntity::getStatus, TaskStatus.CANCELED.name())
                .ge(DdlTaskEntity::getCreatedAt, now.minusDays(SEMANTIC_WINDOW_DAYS))
                .orderByDesc(DdlTaskEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private DdlTaskEntity findSimilarTask(Long userId, DdlTaskEntity newEntity) {
        List<DdlTaskEntity> candidates = ddlTaskMapper.selectList(
                Wrappers.<DdlTaskEntity>lambdaQuery()
                        .eq(DdlTaskEntity::getUserId, userId)
                        .eq(DdlTaskEntity::getCourseName, newEntity.getCourseName())
                        .in(DdlTaskEntity::getStatus, SIMILAR_TASK_STATUSES)
                        .orderByDesc(DdlTaskEntity::getCreatedAt)
                        .last("LIMIT " + SIMILAR_TASK_LIMIT));
        for (DdlTaskEntity candidate : candidates) {
            if (semanticMatcher.isSimilarTitle(
                    candidate.getTaskTitle(), newEntity.getTaskTitle())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean requirementsChanged(DdlTaskEntity existing, DdlTaskEntity candidate) {
        return !Objects.equals(existing.getDueAt(), candidate.getDueAt())
                || !Objects.equals(existing.getDeliverableFormat(), candidate.getDeliverableFormat())
                || !Objects.equals(existing.getSubmissionChannel(), candidate.getSubmissionChannel());
    }

    private static String requireRawText(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() < RAW_TEXT_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "raw_text 长度必须为 4~4000 字符。");
        }
        if (value.length() > RAW_TEXT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "raw_text 长度必须为 4~4000 字符。");
        }
        return value;
    }

    private static String resolveTaskTitle(String payloadTitle, String rawText) {
        String title = trimToNull(payloadTitle);
        if (title != null) {
            return truncate(title, 255);
        }
        for (String line : rawText.split("\\R")) {
            String candidate = trimToNull(line);
            if (candidate != null) {
                return truncate(candidate, 255);
            }
        }
        return "待确认任务";
    }

    private static String limit(String raw, int maxLength) {
        String value = trimToNull(raw);
        return value == null ? null : truncate(value, maxLength);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Decision parseDecision(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "decision 不能为空。");
        }
        try {
            return Decision.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "decision 取值非法。");
        }
    }

    private record CourseRef(Long courseId, String courseName) {
    }

    private enum Decision {
        CONFIRM_UPDATE,
        CREATE_NEW,
        DISCARD
    }
}
