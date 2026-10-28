package com.scholarclaw.modules.notification.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.notification.dto.MarkReadResultVO;
import com.scholarclaw.modules.notification.dto.NotificationListVO;
import com.scholarclaw.modules.notification.dto.NotificationVO;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内通知领域服务（TASK-802、{@code 04} §4.7）。
 *
 * <p><b>落库优先：</b>任何通知先写 {@code t_notification_log}（{@code read_status='UNREAD'}），
 * 再根据用户 IM 绑定与免打扰规则设置 {@code outbound_status}。外部 IM 是否成功不影响
 * 站内信记录，因此 Web 端不依赖 QQ 也能 100% 查看。
 *
 * <p><b>事务契约：</b>{@link #createInTx(NotificationCreateCommand)} 使用默认
 * {@code REQUIRED} 传播：被 DDL 提醒领取或文档回调调用时加入调用方事务；通知插入失败
 * 会随业务状态一起回滚，下一轮重新领取/回调，绝不出现“业务已标记但站内信丢失”。
 *
 * <p><b>本轮边界：</b>只实现初始状态判定、计划推送时间与站内信查询/已读；真正的
 * 令牌桶限流、重试与 {@code FALLBACK_WEB_ONLY} 出站降级属 TASK-804。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_CONTENT_LENGTH = 10_000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_MARK_IDS = 100;
    private static final DateTimeFormatter DUE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotificationLogMapper notificationLogMapper;
    private final UserConfigService userConfigService;
    private final NotificationOutboundPolicy outboundPolicy;
    private final Clock clock;
    private final boolean imPushEnabled;

    public NotificationService(NotificationLogMapper notificationLogMapper,
                               UserConfigService userConfigService,
                               NotificationOutboundPolicy outboundPolicy,
                               Clock clock,
                               @Value("${sc.im.push-enabled:false}") boolean imPushEnabled) {
        this.notificationLogMapper = notificationLogMapper;
        this.userConfigService = userConfigService;
        this.outboundPolicy = outboundPolicy;
        this.clock = clock;
        this.imPushEnabled = imPushEnabled;
    }

    /**
     * 在当前事务内创建一条站内通知，返回自增主键。
     *
     * <p>调用方必须保证 {@code userId} 来自已认证身份；通知内容由调用方按业务语义生成。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createInTx(NotificationCreateCommand command) {
        if (command == null || command.userId() == null) {
            throw new IllegalArgumentException("通知必须指定用户");
        }
        if (command.bizType() == null) {
            throw new IllegalArgumentException("通知必须指定 biz_type");
        }
        String title = requireText(command.title(), "通知标题", MAX_TITLE_LENGTH);
        String content = requireText(command.content(), "通知内容", MAX_CONTENT_LENGTH);
        LocalDateTime now = LocalDateTime.now(clock);

        UserConfigEntity config = userConfigService.findById(command.userId()).orElse(null);
        NotificationOutboundPolicy.Decision decision = outboundPolicy.resolve(
                imPushEnabled, config, command.bizType(), now);

        NotificationLogEntity entity = new NotificationLogEntity();
        entity.setUserId(command.userId());
        entity.setBizType(command.bizType().name());
        entity.setBizId(command.bizId());
        entity.setTitle(title);
        entity.setContent(content);
        entity.setReadStatus("UNREAD");
        entity.setOutboundStatus(decision.status().name());
        entity.setTargetImPlatform(decision.targetImPlatform());
        entity.setTargetImUid(decision.targetImUid());
        entity.setImRetryCount(0);
        entity.setImErrorMsg(decision.imErrorMsg());
        entity.setScheduledPushAt(decision.scheduledPushAt());
        entity.setTraceId(normalizeTraceId(command.traceId()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (notificationLogMapper.insert(entity) != 1 || entity.getNotificationId() == null) {
            throw new IllegalStateException("站内通知写入失败");
        }
        log.info("站内通知已创建 notificationId={} userId={} bizType={} outboundStatus={}",
                entity.getNotificationId(), entity.getUserId(), entity.getBizType(),
                entity.getOutboundStatus());
        return entity.getNotificationId();
    }

    /** 由 TASK-801 的 {@code DdlReminderDispatch} 映射后调用；格式化 DDL 提醒卡片文案。 */
    @Transactional(rollbackFor = Exception.class)
    public Long createDdlReminderInTx(DdlReminderNotification notification) {
        if (notification == null || notification.taskId() == null) {
            throw new IllegalArgumentException("DDL 提醒必须指定任务");
        }
        if (notification.bizType() == null || !notification.bizType().name().startsWith("DDL_")) {
            throw new IllegalArgumentException("DDL 提醒的 biz_type 非法");
        }
        NotificationContent content = ddlReminderContent(notification);
        return createInTx(new NotificationCreateCommand(
                notification.userId(), notification.bizType(), notification.taskId(),
                content.title(), content.body(), notification.traceId()));
    }

    /** `API-NOTIF-001`：分页查询 + 全局未读数。 */
    public NotificationListVO list(Long userId, String readStatusRaw, int page, int size) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        int safePage = page <= 0 ? 1 : page;
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        if (safeSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "size 不能超过 " + MAX_PAGE_SIZE + "。");
        }
        String readStatus = normalizeReadStatus(readStatusRaw);
        long offset = (long) (safePage - 1) * safeSize;
        List<NotificationLogEntity> rows = notificationLogMapper.selectPage(
                userId, readStatus, offset, safeSize);
        long total = notificationLogMapper.countByUser(userId, readStatus);
        long unreadCount = notificationLogMapper.countUnread(userId);
        int totalPages = total == 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
        List<NotificationVO> items = new ArrayList<>(rows.size());
        for (NotificationLogEntity row : rows) {
            items.add(toVO(row));
        }
        return new NotificationListVO(unreadCount, safePage, safeSize, total, totalPages, items);
    }

    /**
     * `API-NOTIF-002`：把指定通知或全部未读标记为已读。
     *
     * <p>只更新当前用户、且当前仍为 {@code UNREAD} 的记录；重复调用幂等返回
     * {@code updated_count=0}。不存在的/属于他人的 ID 静默忽略，避免 IDOR 信息泄露。
     */
    @Transactional(rollbackFor = Exception.class)
    public MarkReadResultVO markRead(Long userId, Boolean markAll, List<String> rawIds) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        if (markAll == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "mark_all 不能为空。");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        var update = Wrappers.<NotificationLogEntity>lambdaUpdate()
                .eq(NotificationLogEntity::getUserId, userId)
                .eq(NotificationLogEntity::getReadStatus, "UNREAD")
                .set(NotificationLogEntity::getReadStatus, "READ")
                .set(NotificationLogEntity::getReadAt, now)
                .set(NotificationLogEntity::getUpdatedAt, now);
        if (!markAll) {
            List<Long> ids = parseNotificationIds(rawIds);
            update.in(NotificationLogEntity::getNotificationId, ids);
        }
        int updated = notificationLogMapper.update(null, update);
        long remaining = notificationLogMapper.countUnread(userId);
        return new MarkReadResultVO(updated, remaining);
    }

    public long unreadCount(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        return notificationLogMapper.countUnread(userId);
    }

    private static NotificationVO toVO(NotificationLogEntity entity) {
        return new NotificationVO(
                entity.getNotificationId(),
                entity.getBizType(),
                entity.getBizId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getReadStatus(),
                entity.getOutboundStatus(),
                ScTimeZone.toOffsetDateTime(entity.getSentImAt()),
                ScTimeZone.toOffsetDateTime(entity.getReadAt()),
                ScTimeZone.toOffsetDateTime(entity.getCreatedAt()));
    }

    private static NotificationContent ddlReminderContent(DdlReminderNotification data) {
        String course = trimToNull(data.courseName());
        String taskTitle = trimToNull(data.taskTitle());
        String subject = taskTitle == null ? "DDL 任务" : taskTitle;
        String prefix = course == null ? "" : course + "：";
        String due = dueText(data);
        String inferred = data.timeInferred()
                ? "（原通知未注明具体时刻，系统暂按当日 23:59 统计）" : "";
        String taskRef = "#T" + data.taskId();
        return switch (data.bizType()) {
            case DDL_T24H -> new NotificationContent(
                    "⏰ [T-24h 提醒] " + prefix + subject,
                    "任务 " + taskRef + " 距离截止时间（" + due + "）不足 24 小时，"
                            + "请及时完成提交。" + inferred);
            case DDL_T6H -> new NotificationContent(
                    "🚨 [T-6h 紧急提醒] " + prefix + subject,
                    "任务 " + taskRef + " 距离截止时间（" + due + "）不足 6 小时，"
                            + "请尽快完成提交。" + inferred);
            case DDL_OVERDUE -> new NotificationContent(
                    "⚠️ [逾期提醒] " + prefix + subject,
                    "任务 " + taskRef + " 已超过截止时间（" + due + "），"
                            + "请尽快完成或调整截止时间。" + inferred);
            case DDL_FOLLOW_UP -> new NotificationContent(
                    "❓ [待确认提醒] " + prefix + subject,
                    "任务 " + taskRef + " "
                            + (data.dueAtUserZone() == null
                            ? "尚未确认截止时间，请补充明确时间"
                            : "当前暂定截止时间为 " + due + "，请确认或补充明确时间")
                            + "（本次为第 " + Math.max(1, data.followUpCount())
                            + "/2 次提醒）。");
            default -> throw new IllegalArgumentException("非 DDL 提醒类型");
        };
    }

    private static String dueText(DdlReminderNotification data) {
        if (data.dueAtUserZone() == null) {
            return "未设置截止时间";
        }
        String zone = trimToNull(data.userTimezone());
        return data.dueAtUserZone().format(DUE_FORMAT)
                + (zone == null ? "" : "（" + zone + "）");
    }

    private static String normalizeReadStatus(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase();
        if (!"UNREAD".equals(normalized) && !"READ".equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "read_status 仅允许 UNREAD 或 READ。");
        }
        return normalized;
    }

    private static List<Long> parseNotificationIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "mark_all=false 时 notification_ids 不能为空。");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : rawIds) {
            String value = trimToNull(raw);
            if (value == null || value.length() > 19 || !value.chars().allMatch(Character::isDigit)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "notification_id 必须为正的十进制字符串。");
            }
            long parsed;
            try {
                parsed = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "notification_id 必须为正的十进制字符串。");
            }
            if (parsed <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "notification_id 必须为正的十进制字符串。");
            }
            ids.add(parsed);
        }
        if (ids.size() > MAX_MARK_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "notification_ids 单次最多 " + MAX_MARK_IDS + " 个。");
        }
        return List.copyOf(ids);
    }

    private static String normalizeTraceId(String raw) {
        String value = trimToNull(raw);
        return value == null ? TraceContext.currentOrGeneratedTraceId() : value;
    }

    private static String requireText(String raw, String field, int maxLength) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxLength - 1);
        return value.substring(0, end) + "…";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record NotificationContent(String title, String body) {
    }
}
