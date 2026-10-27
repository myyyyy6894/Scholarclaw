package com.scholarclaw.modules.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentCommand;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentIntent;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.agent.dto.PendingAction;
import com.scholarclaw.modules.agent.dto.PendingIntent;
import com.scholarclaw.modules.agent.service.NaturalTaskIntentParser.TaskCommand;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.ddl.dto.DdlExtractOutcome;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.service.DdlExtractedPayload;
import com.scholarclaw.modules.ddl.service.DdlExtractionService;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver;
import com.scholarclaw.modules.ddl.service.DdlUserConfigResolver.ResolvedUser;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 单一中枢意图路由与确定性工具执行（`06` TASK-706、`01` FR-IM-002）。
 *
 * <p>三层顺序，与 PRD 一致：
 * <ol>
 *   <li>待确认上下文（`pending_action` / `pending_intent`）：先匹配确认/取消或 1/2/3；</li>
 *   <li>显式斜杠指令：Java 确定性解析并直接调用业务服务，零 LLM；</li>
 *   <li>无前缀自然语言：调用 Python `AUTO_ROUTE` **一次**，低置信度（&lt; 0.65）暂存原文并追问。</li>
 * </ol>
 *
 * <p>工具执行只经过 Java 领域服务（`DdlTaskService` / `DdlExtractionService`），始终带 `user_id`；
 * 不经过 RabbitMQ，也没有多 Agent 自主规划。路由失败直接抛出稳定错误，不构造“执行成功”回执。
 */
@Service
public class AgentOrchestratorService {

    private static final Duration ROUTE_READ_TIMEOUT = Duration.ofSeconds(15);
    private static final BigDecimal INTENT_CONFIDENCE_THRESHOLD = new BigDecimal("0.65");
    private static final BigDecimal MODIFY_CONFIDENCE_THRESHOLD = new BigDecimal("0.80");
    private static final int MAX_CANDIDATE_COURSES = 200;
    private static final int MAX_RAW_TEXT_LENGTH = 4000;
    private static final String[] CONFIRM_REPLIES = {"确认", "是", "执行", "yes", "y", "1"};
    private static final String[] CANCEL_REPLIES = {"取消", "否", "放弃", "no", "n", "2"};
    private static final String[] WEEKDAY_LABELS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final String CLARIFY_TEXT =
            "🤔 收到你的消息，请确认需要执行哪项操作？回复【1】提取为 DDL 待办，"
                    + "回复【2】查看课程知识库问答入口（RAG 需在 Web 工作台提问），回复【3】忽略。";

    private final SlashCommandRouter slashCommandRouter;
    private final PendingIntentStore pendingIntentStore;
    private final PendingActionStore pendingActionStore;
    private final PythonAiEngineClient pythonAiEngineClient;
    private final DdlUserConfigResolver userConfigResolver;
    private final CourseService courseService;
    private final DdlTaskService ddlTaskService;
    private final DdlExtractionService ddlExtractionService;
    private final NaturalTaskIntentParser naturalTaskIntentParser;

    public AgentOrchestratorService(SlashCommandRouter slashCommandRouter,
                                    PendingIntentStore pendingIntentStore,
                                    PendingActionStore pendingActionStore,
                                    PythonAiEngineClient pythonAiEngineClient,
                                    DdlUserConfigResolver userConfigResolver,
                                    CourseService courseService,
                                    DdlTaskService ddlTaskService,
                                    DdlExtractionService ddlExtractionService,
                                    NaturalTaskIntentParser naturalTaskIntentParser) {
        this.slashCommandRouter = slashCommandRouter;
        this.pendingIntentStore = pendingIntentStore;
        this.pendingActionStore = pendingActionStore;
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.userConfigResolver = userConfigResolver;
        this.courseService = courseService;
        this.ddlTaskService = ddlTaskService;
        this.ddlExtractionService = ddlExtractionService;
        this.naturalTaskIntentParser = naturalTaskIntentParser;
    }

    /** 处理一条入站文本消息；不做 IM 发送，只返回结构化结果与回复文案。 */
    public AgentReply handle(AgentInboundMessage message) {
        Long userId = message.userId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "userId 不能为空。");
        }
        String rawText = requireRawText(message.rawText());
        String platform = requireText(message.sourcePlatform(), "sourcePlatform");
        String sourceMsgId = requireText(message.sourceMsgId(), "sourceMsgId");
        ResolvedUser user = userConfigResolver.resolve(userId);

        AgentReply pendingReply = handlePendingContexts(userId, rawText);
        if (pendingReply != null) {
            return pendingReply;
        }

        try {
            Optional<AgentCommand> command = slashCommandRouter.parse(rawText);
            if (command.isPresent()) {
                return executeCommand(userId, platform, sourceMsgId, message, command.get());
            }
        } catch (AgentCommandException e) {
            return reply(AgentIntent.CHITCHAT_HELP, BigDecimal.ONE, AgentToolAction.CLARIFY,
                    e.getMessage(), null, null, false);
        }

        RouteDecision route = classify(userId, user, rawText, message);
        if (route.confidence().compareTo(INTENT_CONFIDENCE_THRESHOLD) < 0) {
            pendingIntentStore.save(userId, new PendingIntent(rawText,
                    referenceTime(message.referenceTime(), user), platform, sourceMsgId,
                    trimToNull(message.courseId()), ScTimeZone.now().toString()));
            return clarify(route.intent(), route.confidence());
        }

        return switch (route.intent()) {
            case DDL_EXTRACT -> handleDdlPayload(userId, platform, sourceMsgId, message,
                    rawText, route);
            case TASK_MANAGE -> handleTaskManage(userId, rawText, route.confidence());
            case KB_QA -> reply(AgentIntent.KB_QA, route.confidence(),
                    AgentToolAction.KB_QA_GUIDE,
                    "请在 Web 工作台【课程问答】中选择课程提问；QQ 文本通道暂未接入 RAG 流式回答。",
                    null, null, false);
            case KB_INGEST -> reply(AgentIntent.KB_INGEST, route.confidence(),
                    AgentToolAction.KB_INGEST_GUIDE,
                    "请直接发送 PDF/MD/TXT 课件，或在 Web 工作台上传；文本指令不会伪造入库结果。",
                    null, null, false);
            case CHITCHAT_HELP -> help(route.confidence());
        };
    }

    private AgentReply handlePendingContexts(Long userId, String rawText) {
        Optional<PendingAction> action = pendingActionStore.load(userId);
        if (action.isPresent()) {
            if (matchesAny(rawText, CONFIRM_REPLIES)) {
                pendingActionStore.clear(userId);
                return executePendingAction(userId, action.get());
            }
            if (matchesAny(rawText, CANCEL_REPLIES)) {
                pendingActionStore.clear(userId);
                return reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE, AgentToolAction.IGNORE,
                        "已放弃本次待确认操作。", null, null, false);
            }
            // 未确认动作**不允许跨无关消息保留**：用户先发“/list”或查询 T103 后再说“确认”，
            // 不能把之前的删除/批量动作重新执行。这里在进入正常路由前清除。
            pendingActionStore.clear(userId);
        }

        Optional<PendingIntent> pending = pendingIntentStore.load(userId);
        if (pending.isPresent()) {
            String choice = rawText.trim();
            if ("1".equals(choice)) {
                pendingIntentStore.clear(userId);
                PendingIntent stored = pending.get();
                return handleDdlExtraction(userId, stored.platform(), stored.sourceMsgId(),
                        stored.rawText(), stored.courseId(), stored.referenceTime(), false,
                        BigDecimal.ONE);
            }
            if ("2".equals(choice)) {
                pendingIntentStore.clear(userId);
                return reply(AgentIntent.KB_QA, BigDecimal.ONE, AgentToolAction.KB_QA_GUIDE,
                        "请在 Web 工作台【课程问答】中选择课程提问；QQ 文本通道暂未接入 RAG 流式回答。",
                        null, null, false);
            }
            if ("3".equals(choice)) {
                pendingIntentStore.clear(userId);
                return reply(AgentIntent.CHITCHAT_HELP, BigDecimal.ONE, AgentToolAction.IGNORE,
                        "好的，已忽略这条消息。", null, null, false);
            }
            // 用户没有回答 1/2/3：丢弃过期上下文，继续按本条新消息路由。
            pendingIntentStore.clear(userId);
        }
        return null;
    }

    private AgentReply executeCommand(Long userId, String platform, String sourceMsgId,
                                      AgentInboundMessage message, AgentCommand command) {
        return switch (command.kind()) {
            case DDL -> handleDdlExtraction(userId, platform, sourceMsgId, command.argument(),
                    message.courseId(), message.referenceTime(), message.forceCreate(),
                    BigDecimal.ONE);
            case LIST -> listTasks(userId, command.argument(), message.courseId(),
                    BigDecimal.ONE);
            case DONE, CONFIRM, EDIT -> executeTaskCommand(userId, command,
                    message.rawText(), BigDecimal.ONE);
            case KB -> reply(AgentIntent.KB_QA, BigDecimal.ONE, AgentToolAction.KB_QA_GUIDE,
                    "请在 Web 工作台【课程问答】中选择课程提问；QQ 文本通道暂未接入 RAG 流式回答。",
                    null, null, false);
        };
    }

    /**
     * 斜杠任务指令的批量边界：多任务写操作必须先二次确认，确认后整批执行或整批零执行。
     */
    private AgentReply executeTaskCommand(Long userId, AgentCommand command, String rawText,
                                          BigDecimal confidence) {
        List<Long> taskIds = command.taskIds();
        if (taskIds.isEmpty()) {
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.CLARIFY,
                    "请在指令后提供任务编号，例如：/done T102。", null, null, false);
        }
        AgentToolAction action = switch (command.kind()) {
            case DONE -> AgentToolAction.TASK_DONE;
            case CONFIRM -> AgentToolAction.TASK_CONFIRM;
            default -> AgentToolAction.TASK_UPDATE;
        };
        if (taskIds.size() > 1) {
            return saveBatchPending(userId, taskIds, action, command.dueDate(),
                    command.dueTime(), rawText, confidence);
        }
        return switch (command.kind()) {
            case DONE -> executeDone(userId, taskIds.getFirst(), confidence,
                    AgentToolAction.TASK_DONE);
            default -> executeUpdate(userId, taskIds.getFirst(), command.dueDate(),
                    command.dueTime(), confidence, action);
        };
    }

    /** 保存多任务待确认动作，并回复确认指引；此时零写库。 */
    private AgentReply saveBatchPending(Long userId, List<Long> taskIds, AgentToolAction action,
                                        LocalDate dueDate, LocalTime dueTime, String rawText,
                                        BigDecimal confidence) {
        PendingAction pending = switch (action) {
            case TASK_DONE -> PendingAction.done(taskIds, rawText, ScTimeZone.now().toString());
            case TASK_CANCEL -> PendingAction.cancel(taskIds, rawText, ScTimeZone.now().toString());
            default -> PendingAction.update(taskIds, action, dueDate, dueTime, rawText,
                    ScTimeZone.now().toString());
        };
        pendingActionStore.save(userId, pending);
        String codes = taskIds.stream().map(id -> "#T" + id)
                .collect(java.util.stream.Collectors.joining("、"));
        String text = switch (action) {
            case TASK_DONE -> "即将把任务 " + codes + " 全部标记完成。";
            case TASK_CANCEL -> "即将取消任务 " + codes + "，提醒与变更草案将一并清理。";
            case TASK_CONFIRM -> "即将确认任务 " + codes + " 的截止时间为 " + dueDate + " "
                    + dueTime + "。";
            default -> "即将把任务 " + codes + " 的截止时间修改为 " + dueDate + " " + dueTime + "。";
        };
        return reply(AgentIntent.TASK_MANAGE, confidence, action,
                text + "本次将整批执行或整批不执行。回复【确认】执行，回复【取消】放弃。",
                null, null, true);
    }

    private AgentReply handleDdlExtraction(Long userId, String platform, String sourceMsgId,
                                           String rawText, String courseId,
                                           String referenceTime, boolean forceCreate,
                                           BigDecimal confidence) {
        DdlExtractVO result = ddlExtractionService.extractForPlatform(userId, platform,
                sourceMsgId, rawText, courseId, referenceTime, forceCreate);
        return ddlReply(result, confidence);
    }

    private AgentReply handleDdlPayload(Long userId, String platform, String sourceMsgId,
                                        AgentInboundMessage message, String rawText,
                                        RouteDecision route) {
        if (route.payload() == null) {
            // 路由声称 DDL_EXTRACT 却没有可落库结果：不得伪造建单成功。
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型返回的 DDL 提取结果不完整，请稍后重试。");
        }
        DdlExtractVO result = ddlExtractionService.persistRouted(userId, platform, sourceMsgId,
                rawText, message.courseId(), message.forceCreate(), route.payload());
        return ddlReply(result, route.confidence());
    }

    private AgentReply ddlReply(DdlExtractVO result, BigDecimal confidence) {
        String code = result.task() == null ? "任务" : result.task().displayCode();
        String text = switch (result.extractOutcome()) {
            case CREATED -> "已创建 DDL 待办 " + code + "（"
                    + (result.task() == null ? "已保存" : result.task().status()) + "）。";
            case IDEMPOTENT_HIT -> "该消息已处理过，返回已有任务 " + code + "。";
            case SEMANTIC_DUPLICATE_HINT -> "近 14 天已有相同通知创建的任务 " + code
                    + "；如确属不同事项，请使用新的请求 ID 重新提交。";
            case CHANGE_DRAFT_CONFIRM_REQUIRED -> "检测到 " + code
                    + " 的截止时间或交付要求变更，请在 Web 工作台确认更新或新建（草案 24 小时有效）。";
        };
        return reply(AgentIntent.DDL_EXTRACT, confidence, AgentToolAction.DDL_EXTRACT,
                text, result.task(), null, false);
    }

    private AgentReply handleTaskManage(Long userId, String rawText, BigDecimal confidence) {
        TaskCommand command = naturalTaskIntentParser.parse(rawText);
        if (command.action() == AgentToolAction.TASK_LIST) {
            return listTasks(userId, "", null, confidence);
        }
        if (command.taskIds().isEmpty()) {
            if (command.action() == AgentToolAction.TASK_QUERY) {
                // “完成情况”这类无编号查询直接返回看板摘要，保持只读。
                return listTasks(userId, "", null, confidence);
            }
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.CLARIFY,
                    "请提供任务编号，例如：/done T102、/confirm T102 2026-10-25 22:00。"
                            + "查询任务详情可直接说“查询 T102”。",
                    null, null, false);
        }
        if (command.taskIds().size() > 1) {
            return switch (command.action()) {
                case TASK_QUERY -> queryTasks(userId, command.taskIds(), confidence);
                case TASK_LIST -> listTasks(userId, "", null, confidence);
                case TASK_CANCEL -> saveBatchPending(userId, command.taskIds(),
                        AgentToolAction.TASK_CANCEL, null, null, rawText, confidence);
                case TASK_DONE -> saveBatchPending(userId, command.taskIds(),
                        AgentToolAction.TASK_DONE, null, null, rawText, confidence);
                case TASK_CONFIRM, TASK_UPDATE -> {
                    if (command.dueDate() == null || command.dueTime() == null) {
                        yield reply(AgentIntent.TASK_MANAGE, confidence,
                                AgentToolAction.CLARIFY,
                                "批量修改需要 YYYY-MM-DD 日期与 HH:mm 时刻，例如：/edit T102 T103 2026-10-25 22:00。",
                                null, null, false);
                    }
                    yield saveBatchPending(userId, command.taskIds(),
                            command.action() == AgentToolAction.TASK_CONFIRM
                                    ? AgentToolAction.TASK_CONFIRM : AgentToolAction.TASK_UPDATE,
                            command.dueDate(), command.dueTime(), rawText, confidence);
                }
                default -> reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.CLARIFY,
                        "请明确要对这些任务执行什么操作：查询、完成、修改、确认或取消。",
                        null, null, false);
            };
        }

        Long taskId = command.taskIds().getFirst();
        return switch (command.action()) {
            case TASK_QUERY -> queryTask(userId, taskId, confidence);
            case TASK_DONE -> executeDone(userId, taskId, confidence, AgentToolAction.TASK_DONE);
            case TASK_CANCEL -> {
                pendingActionStore.save(userId,
                        PendingAction.cancel(taskId, rawText, ScTimeZone.now().toString()));
                yield reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_CANCEL,
                        "即将取消任务 #T" + taskId
                                + "，提醒与变更草案将一并清理。回复【确认】执行，回复【取消】放弃。",
                        null, null, true);
            }
            case TASK_CONFIRM, TASK_UPDATE -> updateFromNatural(userId, taskId, command,
                    rawText, confidence);
            case CLARIFY -> reply(AgentIntent.TASK_MANAGE, confidence,
                    AgentToolAction.CLARIFY,
                    "请说明要对任务 #T" + taskId + " 做什么：查询、完成、修改、确认或取消。",
                    null, null, false);
            default -> reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.CLARIFY,
                    "暂无法识别该操作，请使用明确指令，例如 /done T102。",
                    null, null, false);
        };
    }

    private AgentReply updateFromNatural(Long userId, Long taskId, TaskCommand command,
                                         String rawText, BigDecimal confidence) {
        if (command.dueDate() == null || command.dueTime() == null) {
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.CLARIFY,
                    "请提供 YYYY-MM-DD 日期与 HH:mm 时刻，例如：/edit T102 2026-10-25 22:00。",
                    null, null, false);
        }
        if (command.action() == AgentToolAction.TASK_UPDATE
                && confidence.compareTo(MODIFY_CONFIDENCE_THRESHOLD) < 0) {
            pendingActionStore.save(userId, PendingAction.update(taskId, command.dueDate(),
                    command.dueTime(), rawText, ScTimeZone.now().toString()));
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_UPDATE,
                    "即将把任务 #T" + taskId + " 修改为 " + command.dueDate() + " "
                            + command.dueTime() + "。回复【确认】执行，回复【取消】放弃。",
                    null, null, true);
        }
        return executeUpdate(userId, taskId, command.dueDate(), command.dueTime(), confidence,
                command.action());
    }

    private AgentReply queryTask(Long userId, Long taskId, BigDecimal confidence) {
        try {
            DdlTaskVO task = ddlTaskService.getTask(userId, taskId);
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_QUERY,
                    taskDetail(task), task, null, false);
        } catch (BusinessException e) {
            // 已取消任务按 404 处理（与列表排除 CANCELED 一致），在 IM 层转为明确提示而不是抛错。
            if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                throw e;
            }
            return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_QUERY,
                    "任务 #T" + taskId + " 不存在或已取消。", null, null, false);
        }
    }

    private AgentReply queryTasks(Long userId, List<Long> taskIds, BigDecimal confidence) {
        StringBuilder text = new StringBuilder();
        DdlTaskVO first = null;
        for (Long taskId : taskIds) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            try {
                DdlTaskVO task = ddlTaskService.getTask(userId, taskId);
                if (first == null) {
                    first = task;
                }
                text.append(taskDetail(task));
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.RESOURCE_NOT_FOUND) {
                    throw e;
                }
                text.append("任务 #T").append(taskId).append(" 不存在或已取消。");
            }
        }
        return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_QUERY,
                text.toString(), first, null, false);
    }

    private static String taskDetail(DdlTaskVO task) {
        StringBuilder text = new StringBuilder();
        text.append("任务 ").append(task.displayCode()).append("《").append(task.taskTitle())
                .append("》· 课程：").append(task.courseName())
                .append(" · 状态：").append(task.status());
        text.append(" · 截止：").append(deadlineText(task));
        if (task.timeInferred() && task.dueDate() != null) {
            text.append("（原通知未注明具体时刻，按当日 23:59 统计）");
        }
        if (task.rawTimeText() != null) {
            text.append(" · 原文时间：").append(task.rawTimeText());
        }
        if (task.timeAmbiguityReason() != null) {
            text.append(" · 确认原因：").append(task.timeAmbiguityReason());
        }
        return text.toString();
    }

    /**
     * 与 Web `formatDueAt` 同口径的截止时间文本：
     * `2026-11-30（周一）22:00（Asia/Shanghai）`。
     *
     * <p>时区缺失时使用产品默认 `Asia/Shanghai` 并明确标注，绝不使用 JVM/浏览器本地时区。
     */
    private static String deadlineText(DdlTaskVO task) {
        if (task.dueAt() == null) {
            return "未生成截止时间";
        }
        String zone = task.userTimezone() == null || task.userTimezone().isBlank()
                ? ScTimeZone.DEFAULT_ZONE_ID : task.userTimezone();
        java.time.LocalDateTime local = task.dueAt().toLocalDateTime();
        String weekday = WEEKDAY_LABELS[local.getDayOfWeek().getValue() - 1];
        return String.format("%s（%s）%02d:%02d（%s）",
                local.toLocalDate(), weekday, local.getHour(), local.getMinute(), zone);
    }

    private AgentReply executePendingAction(Long userId, PendingAction action) {
        return switch (action.action()) {
            case TASK_CANCEL -> {
                if (action.isBatch()) {
                    ddlTaskService.cancelBatch(userId, action.taskIds());
                    yield reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE,
                            AgentToolAction.TASK_CANCEL,
                            "任务 " + displayCodes(action.taskIds()) + " 已全部取消。",
                            null, null, false);
                }
                ddlTaskService.cancelTask(userId, action.taskId());
                yield reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE, AgentToolAction.TASK_CANCEL,
                        "任务 #T" + action.taskId() + " 已取消。", null, null, false);
            }
            case TASK_DONE -> {
                if (action.isBatch()) {
                    ddlTaskService.setCompletionBatch(userId, action.taskIds(), true);
                    yield reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE, AgentToolAction.TASK_DONE,
                            "任务 " + displayCodes(action.taskIds()) + " 已全部标记完成。",
                            null, null, false);
                }
                yield executeDone(userId, action.taskId(), BigDecimal.ONE,
                        AgentToolAction.TASK_DONE);
            }
            case TASK_CONFIRM -> {
                if (action.isBatch()) {
                    ddlTaskService.confirmBatch(userId, action.taskIds(), action.dueDate(),
                            action.dueTime());
                    yield reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE,
                            AgentToolAction.TASK_CONFIRM,
                            "任务 " + displayCodes(action.taskIds()) + " 已全部确认并激活。",
                            null, null, false);
                }
                yield executeUpdate(userId, action.taskId(), action.dueDate(), action.dueTime(),
                        BigDecimal.ONE, AgentToolAction.TASK_CONFIRM);
            }
            default -> {
                if (action.isBatch()) {
                    ddlTaskService.updateBatch(userId, action.taskIds(), action.dueDate(),
                            action.dueTime());
                    yield reply(AgentIntent.TASK_MANAGE, BigDecimal.ONE,
                            AgentToolAction.TASK_UPDATE,
                            "任务 " + displayCodes(action.taskIds()) + " 的截止时间已全部更新。",
                            null, null, false);
                }
                yield executeUpdate(userId, action.taskId(), action.dueDate(), action.dueTime(),
                        BigDecimal.ONE, AgentToolAction.TASK_UPDATE);
            }
        };
    }

    private static String displayCodes(List<Long> taskIds) {
        return taskIds.stream().map(id -> "#T" + id)
                .collect(java.util.stream.Collectors.joining("、"));
    }

    private AgentReply executeDone(Long userId, Long taskId, BigDecimal confidence,
                                   AgentToolAction action) {
        DdlTaskVO task = ddlTaskService.setCompletion(userId, taskId, true);
        return reply(AgentIntent.TASK_MANAGE, confidence, action,
                "任务 " + task.displayCode() + " 已标记完成。", task, null, false);
    }

    private AgentReply executeUpdate(Long userId, Long taskId, LocalDate dueDate,
                                     LocalTime dueTime, BigDecimal confidence,
                                     AgentToolAction action) {
        DdlTaskVO existing = ddlTaskService.getTask(userId, taskId);
        UpdateDdlTaskRequest request = new UpdateDdlTaskRequest(
                existing.courseId() == null ? null : existing.courseId().toString(),
                existing.courseName(),
                existing.taskTitle(),
                "EXACT_DATETIME",
                dueDate.toString(),
                dueTime.toString(),
                existing.deliverableFormat(),
                existing.submissionChannel());
        // `/confirm` 只能作用于待补充时间/待确认任务；不得复用无状态限制的通用编辑。
        DdlTaskVO updated = action == AgentToolAction.TASK_CONFIRM
                ? ddlTaskService.confirmTask(userId, taskId, request)
                : ddlTaskService.updateTask(userId, taskId, request);
        return reply(AgentIntent.TASK_MANAGE, confidence, action,
                "任务 " + updated.displayCode() + " 已更新为 " + updated.dueDate() + " "
                        + updated.dueTime() + "（" + updated.status() + "）。",
                updated, null, false);
    }

    private AgentReply listTasks(Long userId, String group, String courseId,
                                 BigDecimal confidence) {
        DdlTaskBoardVO board = ddlTaskService.listTasks(userId, trimToNull(group),
                trimToNull(courseId));
        String text = "待确认 " + board.pendingInboxTasks().size()
                + " 条，进行中/已逾期 " + board.activeTasks().size()
                + " 条，已完成（最近）" + board.completedTasks().size() + " 条。";
        return reply(AgentIntent.TASK_MANAGE, confidence, AgentToolAction.TASK_LIST,
                text, null, board, false);
    }

    private AgentReply help(BigDecimal confidence) {
        return reply(AgentIntent.CHITCHAT_HELP, confidence, AgentToolAction.HELP,
                "可用指令：/ddl 通知原文、/list、/done T102、/confirm T102 2026-10-25 22:00、"
                        + "/edit T102 2026-10-25 22:00。也可以直接转发通知文本。",
                null, null, false);
    }

    private AgentReply clarify(AgentIntent intent, BigDecimal confidence) {
        return reply(intent, confidence, AgentToolAction.CLARIFY, CLARIFY_TEXT,
                null, null, false);
    }

    private static AgentReply reply(AgentIntent intent, BigDecimal confidence,
                                    AgentToolAction action, String text, DdlTaskVO task,
                                    DdlTaskBoardVO board, boolean requiresConfirmation) {
        return new AgentReply(intent, confidence, action, text, task, board,
                requiresConfirmation);
    }

    private RouteDecision classify(Long userId, ResolvedUser user, String rawText,
                                   AgentInboundMessage message) {
        Map<String, Object> body = routeRequest(userId, user, rawText, message);
        JsonNode data = pythonAiEngineClient.postJson(
                PythonAiEngineClient.ROUTE_AND_EXTRACT_PATH, body, userId, ROUTE_READ_TIMEOUT);
        AgentIntent intent = parseIntent(data.path("intent"));
        BigDecimal confidence = parseConfidence(data.path("intent_confidence"));
        DdlExtractedPayload payload = null;
        if (intent == AgentIntent.DDL_EXTRACT
                && confidence.compareTo(INTENT_CONFIDENCE_THRESHOLD) >= 0) {
            payload = DdlExtractedPayload.fromData(data);
        }
        return new RouteDecision(intent, confidence, payload);
    }

    private Map<String, Object> routeRequest(Long userId, ResolvedUser user, String rawText,
                                             AgentInboundMessage message) {
        ModelEndpoint chat = userConfigResolver.resolveChatEndpoint(user.config());
        List<CourseNamespaceVO> courses = courseService.listCourses(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("mode", "AUTO_ROUTE");
        body.put("raw_text", rawText);
        body.put("reference_time", referenceTime(message.referenceTime(), user));
        body.put("user_timezone", user.timezoneName());
        body.put("candidate_courses", courses.stream().limit(MAX_CANDIDATE_COURSES)
                .map(CourseNamespaceVO::courseName).toList());
        Map<String, Object> chatConfig = new LinkedHashMap<>();
        chatConfig.put("provider", chat.provider());
        chatConfig.put("base_url", chat.baseUrl());
        chatConfig.put("api_key", chat.apiKey());
        chatConfig.put("model_name", chat.modelName());
        body.put("chat_config", chatConfig);
        return body;
    }

    private static AgentIntent parseIntent(JsonNode node) {
        String value = node == null ? "" : node.asText("").trim().toUpperCase(Locale.ROOT);
        try {
            return AgentIntent.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型返回的意图不在 MVP 枚举内。");
        }
    }

    private static BigDecimal parseConfidence(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型返回的意图置信度缺失或不是数字。");
        }
        BigDecimal value = BigDecimal.valueOf(node.asDouble());
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型返回的意图置信度超出 [0,1]。");
        }
        return value;
    }

    private static String referenceTime(String raw, ResolvedUser user) {
        String value = trimToNull(raw);
        if (value == null) {
            return OffsetDateTime.now(user.zoneId()).truncatedTo(ChronoUnit.MILLIS).toString();
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

    private static String requireRawText(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() > MAX_RAW_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "raw_text 长度必须为 1~4000 字符。");
        }
        return value;
    }

    private static String requireText(String raw, String field) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + " 不能为空。");
        }
        return value;
    }

    private static boolean matchesAny(String text, String[] words) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (lower.equals(word)) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RouteDecision(AgentIntent intent, BigDecimal confidence,
                                 DdlExtractedPayload payload) {
    }
}
