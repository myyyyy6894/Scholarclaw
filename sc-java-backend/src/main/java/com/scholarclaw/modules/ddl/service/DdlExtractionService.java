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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final Logger log = LoggerFactory.getLogger(DdlExtractionService.class);
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
    private final DdlDecisionReceiptStore decisionReceiptStore;

    public DdlExtractionService(PythonAiEngineClient pythonAiEngineClient,
                                CourseService courseService,
                                DdlTaskMapper ddlTaskMapper,
                                DdlTaskService ddlTaskService,
                                DdlDraftStore draftStore,
                                DdlSemanticMatcher semanticMatcher,
                                DdlUserConfigResolver userConfigResolver,
                                DdlTimeRuleValidator timeRuleValidator,
                                DdlDecisionReceiptStore decisionReceiptStore) {
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.courseService = courseService;
        this.ddlTaskMapper = ddlTaskMapper;
        this.ddlTaskService = ddlTaskService;
        this.draftStore = draftStore;
        this.semanticMatcher = semanticMatcher;
        this.userConfigResolver = userConfigResolver;
        this.timeRuleValidator = timeRuleValidator;
        this.decisionReceiptStore = decisionReceiptStore;
    }

    /** `API-DDL-001`：通知文本 → Python 提取 → 三层幂等 → 建单/草案。 */
    public DdlExtractVO extract(Long userId, ExtractDdlTaskRequest request) {
        return extractForPlatform(userId, DdlIdempotencyKey.SOURCE_PLATFORM_WEB,
                DdlTaskService.requireClientRequestId(request.clientRequestId()),
                request.rawText(), request.courseId(), request.referenceTime(),
                Boolean.TRUE.equals(request.forceCreate()));
    }

    /**
     * 带来源平台的提取入口（IM 自然语言或 `/ddl` 指令）。
     *
     * <p>{@code sourcePlatform + sourceMsgId} 同时参与 Redis 接入键与请求级幂等键，
     * 保证同一平台消息重试不会重复建单。
     */
    public DdlExtractVO extractForPlatform(Long userId, String sourcePlatform,
                                           String sourceMsgIdRaw, String rawTextRaw,
                                           String courseIdRaw, String referenceTime,
                                           boolean forceCreate) {
        String sourceMsgId = requireSourceMsgId(sourceMsgIdRaw, sourcePlatform);
        String rawText = requireRawText(rawTextRaw);
        return executeExtraction(userId, sourcePlatform, sourceMsgId, rawText, courseIdRaw,
                referenceTime, forceCreate,
                (user, courses) -> requestPayload(
                        userId, user, courses, rawText, referenceTime));
    }

    /**
     * 路由层已完成提取时的落库入口（`AUTO_ROUTE` 且意图为 `DDL_EXTRACT`）。
     *
     * <p>不再调用 Python：直接复用三层幂等、语义判定与落库逻辑，避免重复提取。
     */
    public DdlExtractVO persistRouted(Long userId, String sourcePlatform,
                                      String sourceMsgIdRaw, String rawTextRaw,
                                      String courseIdRaw, boolean forceCreate,
                                      DdlExtractedPayload payload) {
        String sourceMsgId = requireSourceMsgId(sourceMsgIdRaw, sourcePlatform);
        String rawText = requireRawText(rawTextRaw);
        return executeExtraction(userId, sourcePlatform, sourceMsgId, rawText, courseIdRaw,
                null, forceCreate, (user, courses) -> payload);
    }

    private DdlExtractVO executeExtraction(Long userId, String sourcePlatform,
                                           String sourceMsgId, String rawText,
                                           String courseIdRaw, String referenceTime,
                                           boolean forceCreate, PayloadSupplier supplier) {
        ResolvedUser user = userConfigResolver.resolve(userId);
        List<CourseNamespaceVO> courses = courseService.listCourses(userId);
        String idempotencyKey = DdlIdempotencyKey.forRequest(
                userId, sourcePlatform, sourceMsgId);
        String fingerprint = DdlRequestFingerprint.forExtract(rawText, courseIdRaw, forceCreate);

        // 第 2 层：请求级幂等（唯一键全局唯一，但查询仍强制带 user_id）。
        DdlTaskEntity created = ddlTaskMapper.selectByIdempotencyKey(idempotencyKey, userId);
        if (created != null) {
            DdlExtractVO replay = replayForExistingTask(userId, sourcePlatform, sourceMsgId,
                    fingerprint, created, user.zoneId());
            if (replay != null) {
                return replay;
            }
        }

        // 第 1 层：Redis 接入锁 + 终态回执。
        DdlDraftStore.RequestLock lock = draftStore.acquireRequestLock(
                sourcePlatform, userId, sourceMsgId);
        if (lock.state() == LockState.PROCESSING) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        if (lock.state() == LockState.PROCESSED) {
            Optional<DdlRequestReceipt> receipt =
                    draftStore.loadReceipt(sourcePlatform, userId, sourceMsgId);
            if (receipt.isPresent()) {
                requireSameFingerprint(receipt.get(), fingerprint);
                DdlExtractVO replayed = replayReceipt(userId, receipt.get(), user.zoneId());
                if (replayed != null) {
                    return replayed;
                }
            }
            // 回执缺失/损坏/指向已取消任务：用 CAS 接管为 PROCESSING，避免误删别人的新锁。
            String takeoverToken = draftStore.takeOverProcessed(
                    sourcePlatform, userId, sourceMsgId, lock.rawValue());
            if (takeoverToken == null) {
                throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            }
            lock = DdlDraftStore.RequestLock.acquired(takeoverToken);
        }

        boolean handled = false;
        try {
            DdlExtractedPayload payload = supplier.load(user, courses);
            DdlExtractVO result = persistPayload(userId, sourcePlatform, sourceMsgId,
                    idempotencyKey, fingerprint, rawText, courseIdRaw, forceCreate,
                    payload, user, courses);
            handled = true;
            return result;
        } finally {
            if (!handled) {
                draftStore.releaseRequest(sourcePlatform, userId, sourceMsgId,
                        lock.ownerToken());
            }
        }
    }

    private DdlExtractVO persistPayload(Long userId, String sourcePlatform, String sourceMsgId,
                                        String idempotencyKey, String fingerprint,
                                        String rawText, String courseIdRaw, boolean forceCreate,
                                        DdlExtractedPayload payload, ResolvedUser user,
                                        List<CourseNamespaceVO> courses) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        DdlTimeFields time = timeRuleValidator.resolveExtracted(
                payload.timePrecision(), payload.parsedDueDate(), payload.parsedDueTime(),
                payload.hasAmbiguity(), payload.confidence(), user.zoneId(), now);
        CourseRef course = resolveCourse(userId, courses, courseIdRaw, payload.courseName());
        DdlTaskEntity entity = buildEntity(userId, sourcePlatform, sourceMsgId, idempotencyKey,
                fingerprint, rawText, payload, time, course, user, now);
        String contentHash = semanticMatcher.contentHash(entity.getCourseName(), rawText);
        entity.setContentHash(contentHash);

        // 内容锁必须覆盖“语义判定 + 建单/草案 + 终态回执”整个临界区：
        // `createExtractedTask` 的事务提交发生在方法返回前，因此锁释放时数据已提交。
        String contentOwner = null;
        if (!forceCreate) {
            contentOwner = draftStore.acquireContentLock(userId, contentHash);
            if (contentOwner == null) {
                throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
            }
        }
        try {
            if (!forceCreate) {
                DdlExtractVO semantic = evaluateSemantic(userId, sourcePlatform, sourceMsgId,
                        fingerprint, entity, contentHash, user.zoneId(), now);
                if (semantic != null) {
                    return semantic;
                }
            }

            DdlTaskService.DdlTaskCreationResult result =
                    ddlTaskService.createExtractedTask(entity);
            DdlExtractOutcome outcome = result.created()
                    ? DdlExtractOutcome.CREATED : DdlExtractOutcome.IDEMPOTENT_HIT;
            Long resultTaskId = result.created()
                    ? entity.getTaskId() : result.task().taskId();
            // 已建单终态可由 MySQL 唯一键兜底，回执写失败不阻断响应。
            markProcessed(sourcePlatform, userId, sourceMsgId, fingerprint, outcome,
                    resultTaskId, null, now, false);
            return new DdlExtractVO(outcome, result.task(), null);
        } finally {
            if (contentOwner != null) {
                draftStore.releaseContentLock(userId, contentHash, contentOwner);
            }
        }
    }

    private DdlExtractedPayload requestPayload(Long userId, ResolvedUser user,
                                               List<CourseNamespaceVO> courses,
                                               String rawText, String referenceTimeRaw) {
        JsonNode data = pythonAiEngineClient.postJson(
                PythonAiEngineClient.ROUTE_AND_EXTRACT_PATH,
                pythonRequest(userId, user, courses, rawText, referenceTimeRaw),
                userId, EXTRACT_READ_TIMEOUT);
        return DdlExtractedPayload.fromData(data);
    }

    /**
     * `API-DDL-005`：确认更新 / 仍然新建 / 忽略变更草案；三种决策均有持久化终态回执。
     *
     * <p>并发边界：
     * <ul>
     *   <li>锁按 {@code user_id + target_task_id} 加，两个不同 {@code client_request_id}
     *       同时决策同一草案时只允许一个进入；</li>
     *   <li>决策动作与 MySQL 回执在同一事务内提交，回执写入失败会回滚业务更新；</li>
     *   <li>草案必须携带目标任务 {@code updated_at} 快照，目标被编辑/完成/取消时拒绝旧草案；</li>
     *   <li>草案删除失败不回滚已提交的决策，但返回错误；重试通过持久化回执重放并再次清理。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public DdlTaskVO decideDraft(Long userId, Long taskId, DraftDecisionRequest request) {
        Decision decision = parseDecision(request.decision());
        String clientRequestId = DdlTaskService.requireClientRequestId(request.clientRequestId());
        ResolvedUser user = userConfigResolver.resolve(userId);
        String fingerprint = decisionFingerprint(decision, taskId);

        DdlTaskVO replayed = replayPriorDecision(
                userId, clientRequestId, fingerprint, user.zoneId());
        if (replayed != null) {
            // 已完成的决策不再依赖草案；尽力清理，避免看板残留旧卡。
            draftStore.deleteDraft(userId, taskId);
            return replayed;
        }

        // 同一草案的并发决策锁：与请求 ID 无关，第二个请求直接 409。
        String lockOwner = draftStore.acquireDraftDecisionLock(userId, taskId);
        if (lockOwner == null) {
            throw new BusinessException(ErrorCode.REQUEST_IN_PROGRESS);
        }
        // 关键：锁必须在事务 commit/rollback 完成后释放，不能在 finally 立即释放。
        draftStore.releaseDraftDecisionLockAfterCompletion(userId, taskId, lockOwner);
        // 拿到锁后重读回执：并发请求可能刚刚完成。
        replayed = replayPriorDecision(
                userId, clientRequestId, fingerprint, user.zoneId());
        if (replayed != null) {
            draftStore.deleteDraft(userId, taskId);
            return replayed;
        }

        // 锁序必须是“先课程、后任务”：课程删除事务先锁课程行再解绑任务行，
        // 若这里先锁目标任务行再锁课程行，会形成反向锁序（MySQL 死锁/500）。
        // 草案本身存放在 Redis，读取它不需要数据库行锁。
        Optional<DdlTaskDraft> draftOpt = draftStore.loadDraft(userId, taskId);
        draftOpt.ifPresent(draft -> lockDraftCourse(userId, draft));

        DdlTaskEntity target = ddlTaskMapper.selectByIdForUpdate(taskId, userId);
        if (target == null || TaskStatus.CANCELED.name().equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (TaskStatus.COMPLETED.name().equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "任务已完成，旧变更草案不再适用；请重新提取通知。");
        }

        if (draftOpt.isEmpty()) {
            if (decision == Decision.CREATE_NEW) {
                String key = DdlIdempotencyKey.forWebRequest(userId, clientRequestId);
                DdlTaskEntity existing = ddlTaskMapper.selectByIdempotencyKey(key, userId);
                if (existing != null) {
                    if (!DdlRequestFingerprint.OPERATION_DRAFT_CREATE_NEW.equals(
                            existing.getRequestOperation())) {
                        throw idempotencyConflict();
                    }
                    saveDecisionReceipt(userId, clientRequestId, fingerprint, decision,
                            taskId, existing.getTaskId());
                    return DdlTaskService.toVO(existing, user.zoneId());
                }
            }
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND);
        }
        DdlTaskDraft draft = draftOpt.get();
        if (!Objects.equals(draft.targetTaskId(), taskId)) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND);
        }
        requireDraftTargetVersion(target, draft);

        DdlTaskVO result = switch (decision) {
            case CONFIRM_UPDATE -> ddlTaskService.applyDraftUpdate(userId, taskId, draft);
            case CREATE_NEW -> ddlTaskService.createFromDraft(
                    userId, draft, clientRequestId).task();
            case DISCARD -> DdlTaskService.toVO(target, user.zoneId());
        };
        // 持久化回执与业务更新在同一事务内；失败会回滚上面的 UPDATE/INSERT。
        saveDecisionReceipt(userId, clientRequestId, fingerprint, decision, taskId,
                result.taskId());
        // 事务提交后严格删除草案；失败返回 500，重试通过持久化回执重放并再次清理。
        draftStore.deleteDraftAfterCommitStrict(userId, taskId);
        return result;
    }

    private static String decisionFingerprint(Decision decision, Long taskId) {
        return decision.name() + ":" + taskId;
    }

    /** 决策前先锁定草案关联课程行；课程已删除时跳过（应用路径按 PO-05 解绑）。 */
    private void lockDraftCourse(Long userId, DdlTaskDraft draft) {
        if (draft.courseId() == null) {
            return;
        }
        try {
            if (courseService.getActiveCourseForUpdate(userId, draft.courseId()) == null) {
                return;
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
        }
    }

    /** 决策草案的目标任务版本校验（与 DdlTaskService 的写入前校验形成双重防线）。 */
    private static void requireDraftTargetVersion(DdlTaskEntity target, DdlTaskDraft draft) {
        java.time.LocalDateTime snapshot = draft.targetTaskUpdatedAtValue();
        java.time.LocalDateTime current = target.getUpdatedAt() == null
                ? null
                : target.getUpdatedAt().truncatedTo(ChronoUnit.MILLIS);
        if (snapshot == null || current == null
                || !current.equals(snapshot.truncatedTo(ChronoUnit.MILLIS))) {
            throw new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                    "目标任务在草案生成后已被修改，请重新提取通知。");
        }
    }

    /**
     * 第 3 层语义判定；命中时写入终态回执并返回结果，未命中返回 `null` 由调用方建单。
     *
     * <p>调用方必须已持有 content_hash 串行锁。
     */
    private DdlExtractVO evaluateSemantic(Long userId, String sourcePlatform,
                                          String sourceMsgId, String fingerprint,
                                          DdlTaskEntity entity, String contentHash,
                                          ZoneId zone, LocalDateTime now) {
        DdlTaskEntity exact = findExactContentHash(userId, contentHash, now);
        if (exact != null) {
            return completeWithReceipt(sourcePlatform, userId, sourceMsgId, fingerprint,
                    DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT, exact, null, null, zone, now);
        }
        DdlTaskEntity similar = findSimilarTask(userId, entity);
        if (similar == null) {
            return null;
        }
        if (!requirementsChanged(similar, entity)) {
            return completeWithReceipt(sourcePlatform, userId, sourceMsgId, fingerprint,
                    DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT, similar, null, null, zone, now);
        }
        DdlTaskDraft draft = DdlTaskDraft.fromEntity(entity, similar.getTaskId(),
                similar.getUpdatedAt(), now.plusHours(DRAFT_TTL_HOURS).toString());
        draftStore.saveDraft(userId, draft);
        return completeWithReceipt(sourcePlatform, userId, sourceMsgId, fingerprint,
                DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED, similar,
                similar.getTaskId(), draft, zone, now);
    }

    private DdlExtractVO completeWithReceipt(String sourcePlatform, Long userId,
                                             String sourceMsgId, String fingerprint,
                                             DdlExtractOutcome outcome, DdlTaskEntity task,
                                             Long draftTargetTaskId, DdlTaskDraft draft,
                                             ZoneId zone, LocalDateTime now) {
        // 语义提示/变更草案没有 MySQL 任务兜底：回执必须写入成功，否则重试会再次调用模型。
        markProcessed(sourcePlatform, userId, sourceMsgId, fingerprint, outcome,
                task.getTaskId(), draftTargetTaskId, now, true);
        return new DdlExtractVO(outcome, DdlTaskService.toVO(task, zone),
                draft == null ? null : draft.toVO());
    }

    private void markProcessed(String sourcePlatform, Long userId, String sourceMsgId,
                               String fingerprint, DdlExtractOutcome outcome, Long taskId,
                               Long draftTargetTaskId, LocalDateTime now, boolean failClosed) {
        draftStore.markProcessed(sourcePlatform, userId, sourceMsgId,
                DdlRequestReceipt.of(fingerprint, outcome.name(), taskId, draftTargetTaskId,
                        now.plusHours(DRAFT_TTL_HOURS).toString()), failClosed);
    }

    /**
     * 数据库已存在同幂等键任务时的重放。
     *
     * <p>唯一键只说明请求 ID 相同，**不能**说明业务内容相同：必须比较持久化的
     * {@code request_operation + request_fingerprint}。手工建单与通知提取复用同一 ID、
     * 同一 ID 换原文、旧 ID 改 {@code force_create=true} 都必须返回
     * {@code 409 IDEMPOTENCY_CONFLICT}。
     */
    private DdlExtractVO replayForExistingTask(Long userId, String sourcePlatform,
                                               String sourceMsgId, String fingerprint,
                                               DdlTaskEntity existing, ZoneId zone) {
        Optional<DdlRequestReceipt> receipt =
                draftStore.loadReceipt(sourcePlatform, userId, sourceMsgId);
        if (receipt.isPresent()) {
            requireSameFingerprint(receipt.get(), fingerprint);
            DdlExtractVO replayed = replayReceipt(userId, receipt.get(), zone);
            if (replayed != null) {
                return replayed;
            }
        }
        requireSameExtractIdentity(existing, fingerprint);
        return new DdlExtractVO(DdlExtractOutcome.IDEMPOTENT_HIT,
                DdlTaskService.toVO(existing, zone), null);
    }

    private static void requireSameExtractIdentity(DdlTaskEntity existing, String fingerprint) {
        if (!DdlRequestFingerprint.OPERATION_EXTRACT.equals(existing.getRequestOperation())
                || !fingerprint.equals(existing.getRequestFingerprint())) {
            throw idempotencyConflict();
        }
    }

    /** 依据终态回执原样重放；任务或草案已不存在时返回 `null`（调用方按过期处理）。 */
    private DdlExtractVO replayReceipt(Long userId, DdlRequestReceipt receipt, ZoneId zone) {
        DdlExtractOutcome outcome;
        try {
            outcome = DdlExtractOutcome.valueOf(receipt.outcome());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Long taskId = parseIdOrNull(receipt.taskId());
        DdlTaskEntity task = taskId == null ? null : selectOwnedTask(userId, taskId);
        if (task == null) {
            return null;
        }
        boolean canceled = TaskStatus.CANCELED.name().equals(task.getStatus());
        if (canceled && (outcome == DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT
                || outcome == DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED)) {
            // 终态回执指向已取消任务：不能把已取消卡片原样返回给用户；
            // 交给调用方用 CAS 接管并重新处理。
            return null;
        }
        if (outcome == DdlExtractOutcome.CREATED
                || outcome == DdlExtractOutcome.IDEMPOTENT_HIT) {
            // 数据库已存在同一幂等键：重试统一返回 IDEMPOTENT_HIT，不因 Redis 回执内容而变。
            return new DdlExtractVO(DdlExtractOutcome.IDEMPOTENT_HIT,
                    DdlTaskService.toVO(task, zone), null);
        }
        if (outcome == DdlExtractOutcome.CHANGE_DRAFT_CONFIRM_REQUIRED) {
            Long draftTargetId = parseIdOrNull(receipt.draftTargetTaskId());
            if (draftTargetId != null) {
                Optional<DdlTaskDraft> draft = draftStore.loadDraft(userId, draftTargetId);
                if (draft.isPresent()) {
                    return new DdlExtractVO(outcome, DdlTaskService.toVO(task, zone),
                            draft.get().toVO());
                }
            }
            // 草案已过期：不得伪装成“仍可确认”，退化为语义重复提示。
            return new DdlExtractVO(DdlExtractOutcome.SEMANTIC_DUPLICATE_HINT,
                    DdlTaskService.toVO(task, zone), null);
        }
        return new DdlExtractVO(outcome, DdlTaskService.toVO(task, zone), null);
    }

    private DdlTaskVO replayPriorDecision(Long userId, String clientRequestId,
                                          String fingerprint, ZoneId zone) {
        // MySQL 回执是权威：存在即说明该请求已经执行，绝不能再执行一次。
        Optional<DdlDecisionReceipt> durable =
                decisionReceiptStore.find(userId, clientRequestId);
        if (durable.isPresent()) {
            if (!fingerprint.equals(durable.get().fingerprint())) {
                throw idempotencyConflict();
            }
            DdlTaskVO replayed = replayDecision(userId, durable.get(), zone);
            if (replayed == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return replayed;
        }
        // Redis 只是缓存：命中时尽量重放，失效时允许重新走数据库校验。
        Optional<DdlDecisionReceipt> cached =
                draftStore.loadDecisionReceipt(userId, clientRequestId);
        if (cached.isEmpty()) {
            return null;
        }
        if (!fingerprint.equals(cached.get().fingerprint())) {
            throw idempotencyConflict();
        }
        return replayDecision(userId, cached.get(), zone);
    }

    private DdlTaskVO replayDecision(Long userId, DdlDecisionReceipt receipt, ZoneId zone) {
        Long resultTaskId = parseIdOrNull(receipt.resultTaskId());
        DdlTaskEntity task = resultTaskId == null ? null : selectOwnedTask(userId, resultTaskId);
        return task == null ? null : DdlTaskService.toVO(task, zone);
    }

    private void saveDecisionReceipt(Long userId, String clientRequestId, String fingerprint,
                                     Decision decision, Long taskId, Long resultTaskId) {
        DdlDecisionReceipt receipt = DdlDecisionReceipt.of(fingerprint, decision.name(), taskId,
                resultTaskId, ScTimeZone.now().plusHours(DRAFT_TTL_HOURS).toString());
        // 持久化回执与业务更新同事务；Redis 缓存失败只告警，不影响幂等权威。
        decisionReceiptStore.saveStrict(userId, clientRequestId, receipt);
        try {
            draftStore.saveDecisionReceipt(userId, clientRequestId, receipt);
        } catch (RuntimeException e) {
            log.warn("DDL 决策回执缓存写入失败 userId={}", userId);
        }
    }

    private DdlTaskEntity selectOwnedTask(Long userId, Long taskId) {
        DdlTaskEntity entity = ddlTaskMapper.selectByTaskIdAndUserId(taskId, userId);
        return entity != null && Objects.equals(entity.getUserId(), userId) ? entity : null;
    }

    private static void requireSameFingerprint(DdlRequestReceipt receipt, String fingerprint) {
        if (!Objects.equals(receipt.fingerprint(), fingerprint)) {
            throw idempotencyConflict();
        }
    }

    private static BusinessException idempotencyConflict() {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    private static Long parseIdOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private DdlTaskEntity buildEntity(Long userId, String sourcePlatform, String sourceMsgId,
                                      String idempotencyKey, String requestFingerprint,
                                      String rawText,
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
        entity.setTimeAmbiguityReason(limit(payload.ambiguityReason(), 512));
        entity.setUserTimezone(user.timezoneName());
        entity.setConfidence(payload.confidence());
        entity.setStatus(time.status().name());
        entity.setStatusBeforeCompletion(null);
        entity.setFollowUpAt(time.followUpAt());
        entity.setFollowUpCount(0);
        entity.setRemindedFlags(0);
        entity.setHasSubtasks(0);
        entity.setSourcePlatform(sourcePlatform);
        entity.setSourceMsgId(sourceMsgId);
        entity.setRequestOperation(DdlRequestFingerprint.OPERATION_EXTRACT);
        entity.setRequestFingerprint(requestFingerprint);
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

    /**
     * 来源消息 ID：Web 的 `client_request_id` 由上层按 8~128 校验；
     * IM 的 `message_id` 只要求非空且不超过 `source_msg_id` 列宽（128）。
     */
    private static String requireSourceMsgId(String raw, String sourcePlatform) {
        String value = trimToNull(raw);
        if (value == null || value.length() > 128) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    sourcePlatform + " 来源消息 ID 缺失或超过 128 字符。");
        }
        return value;
    }

    private static String requireRawText(String raw) {        String value = trimToNull(raw);
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

    /** 提取结果来源：FORCE 走 Python，路由模式直接复用已解析的 payload。 */
    @FunctionalInterface
    private interface PayloadSupplier {
        DdlExtractedPayload load(ResolvedUser user, List<CourseNamespaceVO> courses);
    }

    private enum Decision {
        CONFIRM_UPDATE,
        CREATE_NEW,
        DISCARD
    }
}
