package com.scholarclaw.common.config;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 应用时区的唯一显式出口。
 *
 * <p>存在理由（{@code ENVIRONMENT.md} ENV-01）：宿主机与 JVM 默认时区不可信，而 DDL 四级时间解析、
 * {@code DATE_ONLY} 的 23:59:59 哨兵、T-24h/T-6h 梯度提醒、逾期判定与夜间免打扰窗口全都依赖用户时区。
 * 因此时区必须由配置显式装载，任何组件需要“当前时间”或“用户时区”时都从这里取，不得调用
 * {@code ZoneId.systemDefault()} 或 {@code LocalDateTime.now()}。
 *
 * <p>默认值 {@code Asia/Shanghai} 来自产品基线（{@code 01} §FR-DDL-001 的用户默认时区）。
 * 启动时由 {@link TimeZoneConfig} 用 {@code sc.timezone} 覆盖。
 */
public final class ScTimeZone {

    public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";

    private static volatile ZoneId zoneId = ZoneId.of(DEFAULT_ZONE_ID);

    private ScTimeZone() {
    }

    /** 当前生效的应用时区。 */
    public static ZoneId zoneId() {
        return zoneId;
    }

    /** 由 {@link TimeZoneConfig} 在启动阶段调用；不允许运行期随意改写。 */
    static void configure(ZoneId value) {
        zoneId = Objects.requireNonNull(value, "zoneId");
    }

    /** 带应用时区偏移量的当前时间，用于 {@code Result.timestamp} 等对外时间戳。 */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(zoneId);
    }
}
