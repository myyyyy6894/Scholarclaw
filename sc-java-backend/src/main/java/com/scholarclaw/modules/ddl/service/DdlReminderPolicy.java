package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.config.ScTimeZone;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * DDL 提醒的触发时刻与夜间免打扰换算（TASK-801、{@code 01} {@code FR-DDL-003} / {@code PO-03}）。
 *
 * <p><b>规则来源：</b>
 * <ul>
 *   <li>T-24h：{@code due_at - 24h} 触发一次，不因免打扰窗口改变；夜间只影响外部 IM 投递，
 *       由 TASK-802/TASK-804 在通知层处理（Web 站内信仍按阈值落库）。</li>
 *   <li>T-6h：{@code due_at - 6h} 若落在免打扰窗口内，按 {@code PO-03} 提前到“该窗口开始前 1 小时”。
 *       默认窗口 {@code 23:30 ~ 07:30} 即 {@code 22:30}；对自定义窗口同样取开始前 1 小时，
 *       保证提前后的时刻一定在窗口之外。</li>
 *   <li>待确认跟进：{@code follow_up_at} 落在免打扰窗口时顺延到窗口结束（默认次日 {@code 07:30}）；
 *       若调度恢复时已经离开窗口，则立即触发。</li>
 *   <li>免打扰窗口按**用户时区**解释；{@code due_at} 以应用时区墙钟存储，因此换算必须显式
 *       经过“应用时区 instant -> 用户时区”，严禁直接比较两种墙钟。</li>
 *   <li>{@code dnd_start_time == dnd_end_time} 视为未配置免打扰（0 长度窗口）。</li>
 * </ul>
 */
@Component
public class DdlReminderPolicy {

    /** {@code 03} §4.1 默认免打扰开始时刻。 */
    static final LocalTime DEFAULT_DND_START = LocalTime.of(23, 30);

    /** {@code 03} §4.1 默认免打扰结束时刻。 */
    static final LocalTime DEFAULT_DND_END = LocalTime.of(7, 30);

    static final int T24_HOURS = 24;
    static final int T6_HOURS = 6;
    static final int URGENT_ADVANCE_HOURS = 1;
    static final int FOLLOW_UP_HOURS = 24;

    /** T-24h 阈值；免打扰窗口不改变该阈值。 */
    public LocalDateTime t24TriggerAt(LocalDateTime dueAt) {
        return dueAt.minusHours(T24_HOURS);
    }

    /**
     * T-6h 的实际触发时刻。
     *
     * <p>若 {@code due_at - 6h} 落在用户时区的免打扰窗口内，返回该窗口开始前 1 小时；
     * 否则原样返回 {@code due_at - 6h}（应用时区）。
     */
    public LocalDateTime t6TriggerAt(LocalDateTime dueAt, ZoneId userZone,
                                     LocalTime dndStart, LocalTime dndEnd) {
        LocalDateTime threshold = dueAt.minusHours(T6_HOURS);
        if (isDndDisabled(dndStart, dndEnd)) {
            return threshold;
        }
        LocalDateTime local = toUserZone(threshold, userZone);
        if (!withinDnd(local.toLocalTime(), dndStart, dndEnd)) {
            return threshold;
        }
        LocalDate windowStartDate = windowStartDate(local, dndStart, dndEnd);
        LocalDateTime advanced = LocalDateTime.of(windowStartDate, dndStart)
                .minusHours(URGENT_ADVANCE_HOURS);
        return toAppZone(advanced, userZone);
    }

    /**
     * 待确认跟进提醒的实际触发时刻。
     *
     * <p>取 {@code max(followUpAt, now)}：调度恢复时若已经离开免打扰窗口则立即触发；
     * 若当前仍在窗口内，则顺延到窗口结束。返回应用时区时间。
     */
    public LocalDateTime followUpEffectiveAt(LocalDateTime followUpAt, LocalDateTime now,
                                             ZoneId userZone, LocalTime dndStart,
                                             LocalTime dndEnd) {
        if (isDndDisabled(dndStart, dndEnd)) {
            return followUpAt;
        }
        LocalDateTime baseline = followUpAt.isAfter(now) ? followUpAt : now;
        LocalDateTime local = toUserZone(baseline, userZone);
        if (!withinDnd(local.toLocalTime(), dndStart, dndEnd)) {
            return baseline;
        }
        LocalDate resumeDate;
        if (dndStart.isBefore(dndEnd)) {
            resumeDate = local.toLocalDate();
        } else {
            resumeDate = local.toLocalTime().isBefore(dndEnd)
                    ? local.toLocalDate()
                    : local.toLocalDate().plusDays(1);
        }
        return toAppZone(LocalDateTime.of(resumeDate, dndEnd), userZone);
    }

    /** 下一次跟进提醒：文档规定从本次触发时刻顺延 24 小时。 */
    public LocalDateTime nextFollowUpAt(LocalDateTime now) {
        return now.plusHours(FOLLOW_UP_HOURS);
    }

    public LocalTime dndStartOrDefault(LocalTime configured) {
        return configured == null ? DEFAULT_DND_START : configured;
    }

    public LocalTime dndEndOrDefault(LocalTime configured) {
        return configured == null ? DEFAULT_DND_END : configured;
    }

    /** {@code start == end} 表示没有免打扰窗口。 */
    public boolean isDndDisabled(LocalTime start, LocalTime end) {
        return start.equals(end);
    }

    /**
     * 判断本地墙钟时刻是否落在免打扰窗口内。
     *
     * <p>支持跨午夜（{@code 23:30 ~ 07:30}）与同日窗口；结束时刻为开区间
     * （{@code 07:30} 本身允许提醒）。
     */
    public boolean withinDnd(LocalTime time, LocalTime start, LocalTime end) {
        if (isDndDisabled(start, end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    /** 触发时刻是否已到（含相等）。 */
    public boolean reminderDue(LocalDateTime now, LocalDateTime triggerAt) {
        return !now.isBefore(triggerAt);
    }

    /** 应用时区墙钟 -> 用户时区墙钟（按同一 absolute instant 换算）。 */
    public LocalDateTime toUserZone(LocalDateTime appLocal, ZoneId userZone) {
        return appLocal.atZone(ScTimeZone.zoneId())
                .withZoneSameInstant(userZone)
                .toLocalDateTime();
    }

    /** 用户时区墙钟 -> 应用时区墙钟（按同一 absolute instant 换算）。 */
    public LocalDateTime toAppZone(LocalDateTime userLocal, ZoneId userZone) {
        return userLocal.atZone(userZone)
                .withZoneSameInstant(ScTimeZone.zoneId())
                .toLocalDateTime();
    }

    /** 当前免打扰窗口在用户时区的开始日期（用于回退到窗口开始前 1 小时）。 */
    private static LocalDate windowStartDate(LocalDateTime local, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return local.toLocalDate();
        }
        return local.toLocalTime().isBefore(end)
                ? local.toLocalDate().minusDays(1)
                : local.toLocalDate();
    }
}
