package com.scholarclaw.common.config;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式固定应用时区（{@code ENVIRONMENT.md} ENV-01 的强制缓解措施）。
 *
 * <p>配套约束（在其他任务落地时必须遵守，此处仅登记）：
 * <ul>
 *   <li>TASK-104 / TASK-201：MySQL 连接串显式声明 {@code serverTimezone=Asia/Shanghai}，
 *       容器注入 {@code TZ=Asia/Shanghai}；</li>
 *   <li>TASK-801：所有 {@code @Scheduled} 必须显式声明 {@code zone}（注入 {@link ZoneId} 值），
 *       不得依赖 JVM 默认时区。</li>
 * </ul>
 */
@Configuration
public class TimeZoneConfig {

    @Value("${sc.timezone:" + ScTimeZone.DEFAULT_ZONE_ID + "}")
    private String configuredZoneId;

    @PostConstruct
    void pinApplicationTimeZone() {
        ScTimeZone.configure(ZoneId.of(configuredZoneId));
    }

    @Bean
    public ZoneId scholarClawZoneId() {
        return ScTimeZone.zoneId();
    }

    /** 供需要注入“当前时间”来源的组件使用，便于测试替换（如固定时钟）。 */
    @Bean
    public Clock scholarClawClock(ZoneId scholarClawZoneId) {
        return Clock.system(scholarClawZoneId);
    }
}
