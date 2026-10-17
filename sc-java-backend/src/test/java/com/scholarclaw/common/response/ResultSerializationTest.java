package com.scholarclaw.common.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 统一响应与 JSON 契约的序列化断言（{@code 04} §2.3.1 / §2.5 / §2.6）。
 *
 * <p>这里验证的是 TASK-101 建立的两条全局规则：{@code snake_case} 命名与 {@code null} 字段保留；
 * 以及 {@code BIGINT} ID 用十进制字符串、而同为 BIGINT 的计数字段保持 JSON 数字这一关键区分。
 */
class ResultSerializationTest {

    private final ObjectMapper objectMapper =
            JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(), ScTimeZone.zoneId()).build();

    @Test
    @DisplayName("成功响应包含 code/message/data/trace_id/timestamp 五个字段且 trace_id 为下划线命名")
    void successResultUsesDocumentedFieldNames() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok(new CoursePayload(2001L, "数据库系统概念", 12, 2048L, null)));

        assertThat(json).contains("\"code\":\"OK\"");
        assertThat(json).contains("\"message\":\"操作成功\"");
        assertThat(json).contains("\"trace_id\":\"tr_");
        assertThat(json).contains("\"timestamp\":\"");
        assertThat(json).doesNotContain("\"traceId\"");
        // 04 §2.3.1 只锁定五个字段：Result.isSuccess() 不得被 Jackson 序列化成额外的 success 属性
        assertThat(json).doesNotContain("success");
    }

    @Test
    @DisplayName("timestamp 使用应用时区的带偏移量 ISO-8601（默认 Asia/Shanghai 即 +08:00）")
    void timestampCarriesApplicationZoneOffset() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok("x"));

        assertThat(json).containsPattern("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+08:00\"");
    }

    @Test
    @DisplayName("失败响应 data 固定为 null 且保留键名，code 取错误码字典原值")
    void failureResultKeepsNullDataField() throws Exception {
        String json = objectMapper.writeValueAsString(Result.failure(ErrorCode.E_SYS_02));

        assertThat(json).contains("\"data\":null");
        assertThat(json).contains("\"code\":\"E-SYS-02\"");
        assertThat(json).contains(ErrorCode.E_SYS_02.defaultMessage());
    }

    @Test
    @DisplayName("BIGINT 业务 ID 序列化为十进制字符串，同为 BIGINT 的计数字段仍是 JSON 数字")
    void bigintIdIsStringWhileBigintCounterStaysNumber() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok(new CoursePayload(2001L, "数据库系统概念", 12, 2048L, null)));

        assertThat(json).contains("\"course_id\":\"2001\"");
        assertThat(json).contains("\"file_size_bytes\":2048");
        assertThat(json).contains("\"page_count\":12");
        assertThat(json).contains("\"due_time\":null");
    }

    @Test
    @DisplayName("isSuccess 只对 OK 返回 true")
    void isSuccessOnlyForOkCode() {
        assertThat(Result.ok("x").isSuccess()).isTrue();
        assertThat(Result.failure(ErrorCode.INTERNAL_ERROR).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("反序列化忽略未知字段：前端多传 user_id 必须被忽略而不是报 400")
    void unknownPropertiesAreIgnoredOnDeserialization() throws Exception {
        CoursePayload payload = objectMapper.readValue(
                "{\"course_id\":\"2001\",\"course_name\":\"操作系统原理\",\"user_id\":\"99999\","
                        + "\"page_count\":3,\"file_size_bytes\":10240}",
                CoursePayload.class);

        assertThat(payload.courseId()).isEqualTo(2001L);
        assertThat(payload.courseName()).isEqualTo("操作系统原理");
        assertThat(payload.pageCount()).isEqualTo(3);
    }

    /** 测试用载荷：覆盖 ID（字符串）、计数值（数字）与可空字段三类序列化行为。 */
    private record CoursePayload(
            @JsonSerialize(using = ToStringSerializer.class) Long courseId,
            String courseName,
            Integer pageCount,
            Long fileSizeBytes,
            String dueTime) {
    }
}
