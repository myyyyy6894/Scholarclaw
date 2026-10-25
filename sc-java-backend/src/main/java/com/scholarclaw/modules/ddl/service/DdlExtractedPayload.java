package com.scholarclaw.modules.ddl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * `API-INT-AI-002` `FORCE_DDL_EXTRACT` 返回的 {@code ddl_payload}（TASK-701/702）。
 *
 * <p>只接受契约字段；缺失/类型错误/非法枚举一律映射为 `UPSTREAM_MODEL_ERROR`，
 * 不把半成品当成功结果。`confidence` 统一归一化到 3 位小数（与 `DECIMAL(4,3)` 一致）。
 */
public record DdlExtractedPayload(
        String courseName,
        String taskTitle,
        String rawTimeText,
        TimePrecision timePrecision,
        boolean isRelativeTime,
        LocalDate parsedDueDate,
        LocalTime parsedDueTime,
        String deliverableFormat,
        String submissionChannel,
        BigDecimal confidence,
        boolean hasAmbiguity,
        String ambiguityReason) {

    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    public static DdlExtractedPayload fromData(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw invalid("模型返回的 data 结构不可识别。");
        }
        if (!"DDL_EXTRACT".equals(data.path("intent").asText(""))) {
            throw invalid("模型未返回 DDL_EXTRACT 意图。");
        }
        JsonNode payload = data.path("ddl_payload");
        if (!payload.isObject()) {
            throw invalid("模型返回缺少 ddl_payload。");
        }

        return new DdlExtractedPayload(
                textOrNull(payload, "course_name"),
                textOrNull(payload, "task_title"),
                textOrNull(payload, "raw_time_text"),
                precision(payload),
                requireBoolean(payload, "is_relative_time"),
                dateOrNull(payload, "parsed_due_date"),
                timeOrNull(payload, "parsed_due_time"),
                textOrNull(payload, "deliverable_format"),
                textOrNull(payload, "submission_channel"),
                confidence(payload),
                requireBoolean(payload, "has_ambiguity"),
                textOrNull(payload, "ambiguity_reason"));
    }

    private static TimePrecision precision(JsonNode payload) {
        String raw = payload.path("time_precision").asText("");
        try {
            return TimePrecision.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("模型返回的 time_precision 非法。");
        }
    }

    private static boolean requireBoolean(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isBoolean()) {
            throw invalid("模型返回的 " + field + " 缺失或不是布尔值。");
        }
        return node.asBoolean();
    }

    private static BigDecimal confidence(JsonNode payload) {
        JsonNode node = payload.get("confidence");
        if (node == null || !node.isNumber()) {
            throw invalid("模型返回的 confidence 缺失或不是数字。");
        }
        BigDecimal value = BigDecimal.valueOf(node.asDouble()).setScale(3, RoundingMode.HALF_UP);
        if (value.compareTo(MIN_CONFIDENCE) < 0 || value.compareTo(MAX_CONFIDENCE) > 0) {
            throw invalid("模型返回的 confidence 超出 [0,1]。");
        }
        return value;
    }

    private static LocalDate dateOrNull(JsonNode payload, String field) {
        String raw = textOrNull(payload, field);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid("模型返回的 " + field + " 不是合法日期。");
        }
    }

    private static LocalTime timeOrNull(JsonNode payload, String field) {
        String raw = textOrNull(payload, field);
        if (raw == null) {
            return null;
        }
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalid("模型返回的 " + field + " 不是合法时刻。");
        }
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid("模型返回的 " + field + " 不是字符串。");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, message);
    }
}
