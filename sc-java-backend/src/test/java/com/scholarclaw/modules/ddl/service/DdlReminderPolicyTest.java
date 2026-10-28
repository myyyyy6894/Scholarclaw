package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-801 提醒触发时刻、免打扰窗口与用户时区换算。 */
class DdlReminderPolicyTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime DND_START = LocalTime.of(23, 30);
    private static final LocalTime DND_END = LocalTime.of(7, 30);

    private final DdlReminderPolicy policy = new DdlReminderPolicy();

    @Test
    @DisplayName("默认免打扰窗口 23:30~07:30 为左闭右开，且支持跨午夜")
    void defaultDndWindowIsLeftClosedRightOpen() {
        assertThat(policy.dndStartOrDefault(null)).isEqualTo(DND_START);
        assertThat(policy.dndEndOrDefault(null)).isEqualTo(DND_END);

        assertThat(policy.withinDnd(LocalTime.of(23, 29, 59), DND_START, DND_END)).isFalse();
        assertThat(policy.withinDnd(LocalTime.of(23, 30), DND_START, DND_END)).isTrue();
        assertThat(policy.withinDnd(LocalTime.of(2, 0), DND_START, DND_END)).isTrue();
        assertThat(policy.withinDnd(LocalTime.of(7, 29, 59), DND_START, DND_END)).isTrue();
        assertThat(policy.withinDnd(LocalTime.of(7, 30), DND_START, DND_END)).isFalse();
        assertThat(policy.withinDnd(LocalTime.NOON, DND_START, DND_END)).isFalse();
    }

    @Test
    @DisplayName("免打扰窗口 start == end 视为未配置，且支持同日自定义窗口")
    void dndSupportsDisabledAndSameDayWindow() {
        assertThat(policy.isDndDisabled(LocalTime.of(0, 0), LocalTime.of(0, 0))).isTrue();
        assertThat(policy.withinDnd(LocalTime.NOON, LocalTime.of(0, 0), LocalTime.of(0, 0)))
                .isFalse();

        LocalTime start = LocalTime.of(12, 0);
        LocalTime end = LocalTime.of(14, 0);
        assertThat(policy.withinDnd(LocalTime.of(11, 59), start, end)).isFalse();
        assertThat(policy.withinDnd(LocalTime.of(12, 0), start, end)).isTrue();
        assertThat(policy.withinDnd(LocalTime.of(13, 59), start, end)).isTrue();
        assertThat(policy.withinDnd(LocalTime.of(14, 0), start, end)).isFalse();
    }

    @Test
    @DisplayName("T-24h 始终为 due_at - 24h，不因免打扰改变")
    void t24IsFixedAtTwentyFourHoursBeforeDue() {
        LocalDateTime dueAt = LocalDateTime.of(2026, 10, 29, 10, 0);
        assertThat(policy.t24TriggerAt(dueAt))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 0));
    }

    @Test
    @DisplayName("T-6h 不在免打扰窗口时保持 due_at - 6h")
    void t6KeepsThresholdOutsideDnd() {
        LocalDateTime dueAt = LocalDateTime.of(2026, 10, 28, 20, 0);
        assertThat(policy.t6TriggerAt(dueAt, SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 14, 0));
    }

    @Test
    @DisplayName("T-6h 落在 23:30~07:30 时提前到窗口开始前 1 小时（默认 22:30）")
    void t6AdvancesToBeforeDndWindow() {
        // 00:00 落在窗口内 -> 前一自然日 22:30
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 29, 6, 0),
                SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 22, 30));

        // 02:00 落在跨午夜窗口的“次日凌晨”段 -> 前一自然日 22:30
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 29, 8, 0),
                SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 22, 30));

        // 边界：23:30 属于窗口 -> 提前；07:30 已离开窗口 -> 不提前
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 30, 5, 30),
                SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 22, 30));
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 29, 13, 30),
                SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 7, 30));
    }

    @Test
    @DisplayName("T-6h 对同日自定义窗口同样提前到窗口开始前 1 小时")
    void t6AdvancesForSameDayDnd() {
        LocalTime start = LocalTime.of(12, 0);
        LocalTime end = LocalTime.of(14, 0);
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 28, 19, 0),
                SHANGHAI, start, end))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 11, 0));
    }

    @Test
    @DisplayName("跟进提醒落在免打扰窗口时顺延到窗口结束（默认次日 07:30）")
    void followUpPostponesToDndEnd() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 28, 23, 41);
        assertThat(policy.followUpEffectiveAt(
                LocalDateTime.of(2026, 10, 28, 23, 40), now, SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 7, 30));

        // 凌晨段顺延到当天 07:30
        assertThat(policy.followUpEffectiveAt(
                LocalDateTime.of(2026, 10, 29, 2, 0),
                LocalDateTime.of(2026, 10, 29, 2, 0), SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 7, 30));
    }

    @Test
    @DisplayName("跟进提醒在调度恢复时已离开窗口则立即触发")
    void followUpFiresImmediatelyWhenWindowHasEnded() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 29, 8, 0);
        assertThat(policy.followUpEffectiveAt(
                LocalDateTime.of(2026, 10, 28, 18, 0), now, SHANGHAI, DND_START, DND_END))
                .isEqualTo(now);

        // 当前时间已进入窗口、即便 follow_up_at 在白天也必须顺延
        LocalDateTime lateNight = LocalDateTime.of(2026, 10, 28, 23, 45);
        assertThat(policy.followUpEffectiveAt(
                LocalDateTime.of(2026, 10, 28, 18, 0), lateNight, SHANGHAI, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 7, 30));
    }

    @Test
    @DisplayName("免打扰未配置时跟进提醒按原时间触发")
    void followUpUnchangedWhenDndDisabled() {
        LocalDateTime followUpAt = LocalDateTime.of(2026, 10, 28, 23, 40);
        assertThat(policy.followUpEffectiveAt(followUpAt,
                LocalDateTime.of(2026, 10, 28, 23, 41),
                SHANGHAI, LocalTime.of(0, 0), LocalTime.of(0, 0)))
                .isEqualTo(followUpAt);
    }

    @Test
    @DisplayName("免打扰与 T-6h 按用户时区解释，而不是应用时区墙钟")
    void dndUsesUserTimezone() {
        ZoneId newYork = ZoneId.of("America/New_York");
        // 应用时区 2026-10-28 20:00 = 纽约 08:00；T-6h = 纽约 02:00（免打扰）
        // -> 纽约前一日 22:30 = 应用时区 2026-10-28 10:30
        assertThat(policy.t6TriggerAt(LocalDateTime.of(2026, 10, 28, 20, 0),
                newYork, DND_START, DND_END))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 30));

        assertThat(policy.toUserZone(LocalDateTime.of(2026, 10, 28, 20, 0), newYork))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 8, 0));
    }

    @Test
    @DisplayName("下一轮跟进固定从本次触发时刻顺延 24 小时")
    void nextFollowUpIsTwentyFourHoursLater() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 28, 10, 0);
        assertThat(policy.nextFollowUpAt(now))
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 10, 0));
    }

    @Test
    @DisplayName("触发时刻判断包含相等")
    void reminderDueIncludesExactTrigger() {
        LocalDateTime trigger = LocalDateTime.of(2026, 10, 28, 10, 0);
        assertThat(policy.reminderDue(trigger, trigger)).isTrue();
        assertThat(policy.reminderDue(trigger.minusNanos(1), trigger)).isFalse();
        assertThat(policy.reminderDue(trigger.plusSeconds(1), trigger)).isTrue();
    }
}
