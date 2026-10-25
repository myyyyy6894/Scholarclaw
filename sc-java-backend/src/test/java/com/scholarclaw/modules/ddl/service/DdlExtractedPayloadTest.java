package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** `API-INT-AI-002` 提取响应解析的契约校验（TASK-704）。 */
class DdlExtractedPayloadTest {

    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(Jackson2ObjectMapperBuilder.json(), ZoneId.of("Asia/Shanghai")).build();

    @Test
    @DisplayName("合法 payload 完整映射，confidence 归一化到 3 位小数")
    void parsesValidPayload() throws Exception {
        DdlExtractedPayload payload = DdlExtractedPayload.fromData(json("""
                {"intent":"DDL_EXTRACT","intent_confidence":0.98,"ddl_payload":{
                  "course_name":"操作系统原理","task_title":"提交实验二报告",
                  "raw_time_text":"10月25日 22:00 前","time_precision":"EXACT_DATETIME",
                  "is_relative_time":false,"parsed_due_date":"2099-12-31",
                  "parsed_due_time":"22:00:00","deliverable_format":"zip",
                  "submission_channel":"学习通","confidence":0.96,
                  "has_ambiguity":false,"ambiguity_reason":null}}
                """));

        assertThat(payload.courseName()).isEqualTo("操作系统原理");
        assertThat(payload.taskTitle()).isEqualTo("提交实验二报告");
        assertThat(payload.timePrecision()).isEqualTo(TimePrecision.EXACT_DATETIME);
        assertThat(payload.parsedDueDate()).isEqualTo(LocalDate.of(2099, 12, 31));
        assertThat(payload.parsedDueTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(payload.confidence()).isEqualByComparingTo("0.960");
        assertThat(payload.hasAmbiguity()).isFalse();
    }

    @Test
    @DisplayName("空字符串归一化为 null")
    void emptyStringsBecomeNull() throws Exception {
        DdlExtractedPayload payload = DdlExtractedPayload.fromData(json("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "course_name":"  ","task_title":"","raw_time_text":null,
                  "time_precision":"VAGUE_OR_MISSING","is_relative_time":false,
                  "parsed_due_date":null,"parsed_due_time":null,
                  "deliverable_format":null,"submission_channel":null,
                  "confidence":0.90,"has_ambiguity":true,"ambiguity_reason":"  "}}
                """));

        assertThat(payload.courseName()).isNull();
        assertThat(payload.taskTitle()).isNull();
        assertThat(payload.rawTimeText()).isNull();
        assertThat(payload.ambiguityReason()).isNull();
    }

    @Test
    @DisplayName("非法 intent / 缺 ddl_payload / 非法枚举 / 非法日期 / 缺布尔 / 非法类型均拒绝")
    void rejectsInvalidPayloads() {
        assertInvalid("""
                {"intent":"KB_QA","ddl_payload":{}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT"}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"SOMETIME","is_relative_time":false,
                  "confidence":0.9,"has_ambiguity":false}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"DATE_ONLY","is_relative_time":false,
                  "parsed_due_date":"2099-02-30","confidence":0.9,"has_ambiguity":false}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"DATE_ONLY","confidence":0.9,"has_ambiguity":false}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"DATE_ONLY","is_relative_time":false,
                  "parsed_due_date":123,"confidence":0.9,"has_ambiguity":false}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"DATE_ONLY","is_relative_time":false,
                  "confidence":1.5,"has_ambiguity":false}}
                """);
        assertInvalid("""
                {"intent":"DDL_EXTRACT","ddl_payload":{
                  "time_precision":"DATE_ONLY","is_relative_time":false,
                  "confidence":"0.9","has_ambiguity":false}}
                """);
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static void assertInvalid(String text) {
        assertThatThrownBy(() -> DdlExtractedPayload.fromData(json(text)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
    }
}
