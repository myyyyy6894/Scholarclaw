package com.scholarclaw.modules.notification.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * 站内通知的初始外部 IM 投递判定（{@code 01} {@code FR-IM-003}、{@code 03} §4.7.1、{@code PO-03}）。
 *
 * <p>规则：
 * <ol>
 *   <li>用户未绑定 IM / 全局 {@code SC_IM_PUSH_ENABLED=false} / 用户配置不可用 →
 *       {@code FALLBACK_WEB_ONLY}，站内信照常落库；</li>
 *   <li>紧急提醒（仅 T-6h）→ {@code PENDING_IM}，即使处于免打扰窗口也允许推送；</li>
 *   <li>非紧急提醒处于用户夜间免打扰窗口 → {@code RATE_LIMITED_DELAYED}，
 *       {@code scheduled_push_at} 顺延到窗口结束（默认次日 07:30）；</li>
 *   <li>其余情况 → {@code PENDING_IM}，{@code scheduled_push_at = now}。</li>
 * </ol>
 *
 * <p>本轮（TASK-802）只负责把状态与计划时间写正确；真正的出站发送、令牌桶限流与失败降级
 * 属 TASK-804。处于 {@code PENDING_IM} 的记录会保留到 TASK-804 的调度器接管。
 */
@Component
public class NotificationOutboundPolicy {

    static final LocalTime DEFAULT_DND_START = LocalTime.of(23, 30);
    static final LocalTime DEFAULT_DND_END = LocalTime.of(7, 30);

    /** 初始判定结果；{@code scheduledPushAt} 在 {@code FALLBACK_WEB_ONLY} 时为空。 */
    public record Decision(OutboundStatus status,
                           LocalDateTime scheduledPushAt,
                           String imErrorMsg,
                           String targetImPlatform,
                           String targetImUid) {
    }

    public Decision resolve(boolean imPushEnabled, UserConfigEntity config,
                            NotificationBizType bizType, LocalDateTime now) {
        String platform = config == null ? null : trimToNull(config.getBoundImPlatform());
        String imUid = config == null ? null : trimToNull(config.getBoundImUid());
        boolean bound = platform != null && imUid != null;

        if (config == null || UserConfigService.isDeactivating(config)) {
            return new Decision(OutboundStatus.FALLBACK_WEB_ONLY, null,
                    "用户配置不可用，仅站内信", platform, imUid);
        }
        if (!imPushEnabled) {
            return new Decision(OutboundStatus.FALLBACK_WEB_ONLY, null,
                    "IM 推送总开关关闭，仅站内信", platform, imUid);
        }
        if (!bound) {
            return new Decision(OutboundStatus.FALLBACK_WEB_ONLY, null,
                    "未绑定 IM 账号，仅站内信", null, null);
        }
        if (bizType.urgent()) {
            return new Decision(OutboundStatus.PENDING_IM, now, null, platform, imUid);
        }

        ZoneId userZone = resolveZone(config.getTimezone());
        LocalTime dndStart = config.getDndStartTime() == null
                ? DEFAULT_DND_START : config.getDndStartTime();
        LocalTime dndEnd = config.getDndEndTime() == null
                ? DEFAULT_DND_END : config.getDndEndTime();
        LocalDateTime localNow = toUserZone(now, userZone);
        if (withinDnd(localNow.toLocalTime(), dndStart, dndEnd)) {
            LocalDateTime resumeUserZone = nextDndEnd(localNow, dndStart, dndEnd);
            return new Decision(OutboundStatus.RATE_LIMITED_DELAYED,
                    toAppZone(resumeUserZone, userZone), null, platform, imUid);
        }
        return new Decision(OutboundStatus.PENDING_IM, now, null, platform, imUid);
    }

    static boolean withinDnd(LocalTime time, LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    static LocalDateTime nextDndEnd(LocalDateTime localNow, LocalTime start, LocalTime end) {
        LocalDate resumeDate;
        if (start.isBefore(end)) {
            resumeDate = localNow.toLocalDate();
        } else {
            resumeDate = localNow.toLocalTime().isBefore(end)
                    ? localNow.toLocalDate()
                    : localNow.toLocalDate().plusDays(1);
        }
        return LocalDateTime.of(resumeDate, end);
    }

    static LocalDateTime toUserZone(LocalDateTime appLocal, ZoneId userZone) {
        return appLocal.atZone(ScTimeZone.zoneId())
                .withZoneSameInstant(userZone)
                .toLocalDateTime();
    }

    static LocalDateTime toAppZone(LocalDateTime userLocal, ZoneId userZone) {
        return userLocal.atZone(userZone)
                .withZoneSameInstant(ScTimeZone.zoneId())
                .toLocalDateTime();
    }

    private static ZoneId resolveZone(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return ScTimeZone.zoneId();
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            // 用户时区在保存时已校验；历史脏数据不得阻断提醒落库，按应用时区解释免打扰。
            return ScTimeZone.zoneId();
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
