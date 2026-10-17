package com.scholarclaw.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 对外 JSON 契约的基础配置（{@code 04} §2.5 / §2.6）。
 *
 * <p>这里只固定四条全局规则，其余交给使用处显式声明：
 * <ol>
 *   <li>{@code snake_case}：与 MySQL 字段名、Python Pydantic 模型保持一致；</li>
 *   <li>保留 {@code null} 字段键名：前端 TypeScript 结构稳定，且 {@code due_at: null}
 *       本身承载“待补充截止时间”的业务语义；</li>
 *   <li>时间以带偏移量的 ISO-8601 字符串输出（关闭 {@code WRITE_DATES_AS_TIMESTAMPS}），
 *       基准时区为应用时区（{@link ScTimeZone}），不依赖 JVM 默认时区；</li>
 *   <li>反序列化忽略未知字段：{@code 04} §2.7.2 要求“即使前端多传了 {@code user_id} 字段
 *       也由后端反序列化直接忽略”，若开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}，
 *       前端多传字段会变成 400，直接违反该契约。</li>
 * </ol>
 *
 * <p>这些规则在本类中自洽声明，不依赖 Spring Boot 自动配置的默认值——否则单元测试与
 * 手工构造的 {@code ObjectMapper} 会得到与运行时不同的行为。
 *
 * <p><b>刻意不做全局 {@code Long -> String} 序列化：</b>{@code 04} §2.6 只要求 BIGINT
 * <em>业务 ID</em> 序列化为十进制字符串，而 {@code file_size_bytes} 等同为 BIGINT 的计数字段
 * 必须输出 JSON 数字。因此由各 DTO 在 ID 字段上显式标注
 * {@code @JsonSerialize(using = ToStringSerializer.class)}，不得用全局序列化器一刀切。
 */
@Configuration
public class JacksonConfig {

    /** 供 {@link Jackson2ObjectMapperBuilderCustomizer} 与单元测试共用，避免配置漂移。 */
    public static Jackson2ObjectMapperBuilder applyTo(Jackson2ObjectMapperBuilder builder, ZoneId zoneId) {
        return builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .timeZone(TimeZone.getTimeZone(zoneId))
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer scholarClawJacksonCustomizer(ZoneId scholarClawZoneId) {
        return builder -> applyTo(builder, scholarClawZoneId);
    }
}
