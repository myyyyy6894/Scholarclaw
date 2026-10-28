package com.scholarclaw.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.common.enums.OutboundStatus;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-802 通知初始出站状态、免打扰顺延与 IM 目标快照。 */
class NotificationOutboundPolicyTest {

    private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 10, 28, 2, 0);
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final LocalDateTime LATE_NIGHT = LocalDateTime.of(2026, 10, 28, 23, 45);

    private final NotificationOutboundPolicy policy = new NotificationOutboundPolicy();

    @Test
    @DisplayName("未绑定 IM：FALLBACK_WEB_ONLY，无计划推送时间")
    void unboundUserFallsBackToWeb() {
        UserConfigEntity config = config();
        config.setBoundImPlatform(null);
        config.setBoundImUid(null);

        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config, NotificationBizType.DDL_T24H, DAY);

        assertThat(decision.status()).isEqualTo(OutboundStatus.FALLBACK_WEB_ONLY);
        assertThat(decision.scheduledPushAt()).isNull();
        assertThat(decision.imErrorMsg()).contains("未绑定");
        assertThat(decision.targetImPlatform()).isNull();
        assertThat(decision.targetImUid()).isNull();
    }

    @Test
    @DisplayName("全局推送关闭：即使已绑定也只落站内信，但保留目标快照")
    void disabledPushFallsBackWithTargetSnapshot() {
        NotificationOutboundPolicy.Decision decision = policy.resolve(
                false, config(), NotificationBizType.DDL_T24H, DAY);

        assertThat(decision.status()).isEqualTo(OutboundStatus.FALLBACK_WEB_ONLY);
        assertThat(decision.scheduledPushAt()).isNull();
        assertThat(decision.imErrorMsg()).contains("总开关关闭");
        assertThat(decision.targetImPlatform()).isEqualTo("QQ_ONEBOT");
        assertThat(decision.targetImUid()).isEqualTo("123456");
    }

    @Test
    @DisplayName("紧急 T-6h 在免打扰窗口内仍进入 PENDING_IM")
    void urgentReminderIgnoresDnd() {
        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config(), NotificationBizType.DDL_T6H, NIGHT);

        assertThat(decision.status()).isEqualTo(OutboundStatus.PENDING_IM);
        assertThat(decision.scheduledPushAt()).isEqualTo(NIGHT);
    }

    @Test
    @DisplayName("非紧急提醒在免打扰窗口内顺延到窗口结束")
    void nonUrgentReminderDelayedToDndEnd() {
        NotificationOutboundPolicy.Decision earlyMorning = policy.resolve(
                true, config(), NotificationBizType.DDL_T24H, NIGHT);
        assertThat(earlyMorning.status()).isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED);
        assertThat(earlyMorning.scheduledPushAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 7, 30));

        NotificationOutboundPolicy.Decision beforeMidnight = policy.resolve(
                true, config(), NotificationBizType.DDL_FOLLOW_UP, LATE_NIGHT);
        assertThat(beforeMidnight.status()).isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED);
        assertThat(beforeMidnight.scheduledPushAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 29, 7, 30));
    }

    @Test
    @DisplayName("非紧急提醒在非免打扰时段立即进入 PENDING_IM")
    void nonUrgentReminderOutsideDndIsPending() {
        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config(), NotificationBizType.DDL_OVERDUE, DAY);

        assertThat(decision.status()).isEqualTo(OutboundStatus.PENDING_IM);
        assertThat(decision.scheduledPushAt()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("注销中的用户不推送 IM，仅站内信")
    void deactivatingUserFallsBackToWeb() {
        UserConfigEntity config = config();
        config.setAccountStatus("DEACTIVATING");

        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config, NotificationBizType.DDL_T6H, DAY);

        assertThat(decision.status()).isEqualTo(OutboundStatus.FALLBACK_WEB_ONLY);
        assertThat(decision.imErrorMsg()).contains("不可用");
    }

    @Test
    @DisplayName("支持同日自定义免打扰窗口")
    void supportsSameDayDndWindow() {
        UserConfigEntity config = config();
        config.setDndStartTime(LocalTime.of(12, 0));
        config.setDndEndTime(LocalTime.of(14, 0));

        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config, NotificationBizType.DDL_T24H,
                LocalDateTime.of(2026, 10, 28, 13, 0));

        assertThat(decision.status()).isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED);
        assertThat(decision.scheduledPushAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 14, 0));
    }

    @Test
    @DisplayName("dnd_start == dnd_end 视为未配置免打扰")
    void equalDndBoundsDisableWindow() {
        UserConfigEntity config = config();
        config.setDndStartTime(LocalTime.of(0, 0));
        config.setDndEndTime(LocalTime.of(0, 0));

        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config, NotificationBizType.DDL_T24H, NIGHT);

        assertThat(decision.status()).isEqualTo(OutboundStatus.PENDING_IM);
        assertThat(decision.scheduledPushAt()).isEqualTo(NIGHT);
    }

    @Test
    @DisplayName("免打扰按用户时区解释：纽约 00:00 顺延到纽约 07:30")
    void dndUsesUserTimezone() {
        UserConfigEntity config = config();
        config.setTimezone("America/New_York");

        // 应用时区 2026-10-28 12:00 = 纽约 2026-10-28 00:00（EDT）
        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, config, NotificationBizType.DDL_T24H,
                LocalDateTime.of(2026, 10, 28, 12, 0));

        assertThat(decision.status()).isEqualTo(OutboundStatus.RATE_LIMITED_DELAYED);
        // 纽约 07:30 = 应用时区 19:30
        assertThat(decision.scheduledPushAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 19, 30));
    }

    @Test
    @DisplayName("配置缺失时仍可落站内信，不抛异常")
    void missingConfigFallsBackToWeb() {
        NotificationOutboundPolicy.Decision decision = policy.resolve(
                true, null, NotificationBizType.DDL_T24H, DAY);

        assertThat(decision.status()).isEqualTo(OutboundStatus.FALLBACK_WEB_ONLY);
        assertThat(decision.scheduledPushAt()).isNull();
    }

    private static UserConfigEntity config() {
        UserConfigEntity config = new UserConfigEntity();
        config.setAccountStatus("ACTIVE");
        config.setTimezone("Asia/Shanghai");
        config.setDndStartTime(LocalTime.of(23, 30));
        config.setDndEndTime(LocalTime.of(7, 30));
        config.setBoundImPlatform("QQ_ONEBOT");
        config.setBoundImUid("123456");
        return config;
    }
}
