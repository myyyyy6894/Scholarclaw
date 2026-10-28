package com.scholarclaw.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-804 发送抖动与限流顺延范围。 */
class ImPushJitterTest {

    @Test
    @DisplayName("抖动被限制在配置区间；相等时可确定性返回")
    void randomDelayStaysWithinConfiguredRange() {
        ImPushJitter fixed = new ImPushJitter(1500, 1500, 5000, 5000);
        assertThat(fixed.randomDelayMillis()).isEqualTo(1500);
        assertThat(fixed.randomRateDelayMillis()).isEqualTo(5000);

        ImPushJitter ranged = new ImPushJitter(1500, 3500, 5000, 15000);
        for (int i = 0; i < 100; i++) {
            assertThat(ranged.randomDelayMillis()).isBetween(1500L, 3500L);
            assertThat(ranged.randomRateDelayMillis()).isBetween(5000L, 15000L);
        }
    }

    @Test
    @DisplayName("零抖动不睡眠")
    void zeroJitterDoesNotSleep() throws InterruptedException {
        new ImPushJitter(0, 0, 0, 0).sleepBeforeSend();
    }
}
