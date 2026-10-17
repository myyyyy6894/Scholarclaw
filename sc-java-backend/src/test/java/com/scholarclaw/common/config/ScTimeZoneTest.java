package com.scholarclaw.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 应用时区固定行为的断言（{@code ENVIRONMENT.md} ENV-01）。
 *
 * <p>宿主机与 JVM 默认时区不可信，因此这里验证：默认值就是产品基线 {@code Asia/Shanghai}，
 * 且 {@link ScTimeZone#now()} 始终带 {@code +08:00} 偏移量。
 */
class ScTimeZoneTest {

    @AfterEach
    void restoreDefaultZone() {
        ScTimeZone.configure(ZoneId.of(ScTimeZone.DEFAULT_ZONE_ID));
    }

    @Test
    @DisplayName("默认应用时区为产品基线 Asia/Shanghai")
    void defaultsToProductBaselineZone() {
        assertThat(ScTimeZone.zoneId()).isEqualTo(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    @DisplayName("now() 输出带 +08:00 偏移量的时间，不受 JVM 默认时区影响")
    void nowCarriesApplicationZoneOffset() {
        assertThat(ScTimeZone.now().getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(8));
    }

    @Test
    @DisplayName("重新固定时区后 now() 立即跟随新时区")
    void honoursReconfiguredZone() {
        ScTimeZone.configure(ZoneId.of("UTC"));

        assertThat(ScTimeZone.now().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    @DisplayName("不允许配置 null 时区")
    void rejectsNullZone() {
        assertThatThrownBy(() -> ScTimeZone.configure(null)).isInstanceOf(NullPointerException.class);
    }
}
