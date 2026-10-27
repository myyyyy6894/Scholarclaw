package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 请求级幂等指纹（TASK-704 审阅修复）。
 *
 * <p>固定幂等键公式
 * {@code sha256(user_id + ":" + source_platform + ":" + source_msg_id + ":DDL")}
 * **不改变**；本类只回答“同一个幂等键是否承载了同一次业务请求”。
 *
 * <p>指纹必须持久化到 {@code t_ddl_task.request_fingerprint}：Redis 回执 24 小时过期后，
 * 同一 {@code client_request_id} 换内容、换操作类型或改 {@code force_create} 仍必须返回
 * {@code 409 IDEMPOTENCY_CONFLICT}，不能退化为“只要能查到任务就返回成功”。
 */
public final class DdlRequestFingerprint {

    public static final String OPERATION_MANUAL_CREATE = "MANUAL_CREATE";
    public static final String OPERATION_EXTRACT = "EXTRACT";
    public static final String OPERATION_DRAFT_CREATE_NEW = "DRAFT_CREATE_NEW";

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");

    private DdlRequestFingerprint() {
    }

    /** 通知提取（`API-DDL-001`）：原文 + 显式课程 + force_create。 */
    public static String forExtract(String rawText, String courseIdRaw, boolean forceCreate) {
        String canonical = canonical(OPERATION_EXTRACT,
                "raw_text", rawText,
                "course_id", courseIdRaw,
                "force_create", Boolean.toString(forceCreate));
        return DdlIdempotencyKey.sha256(canonical);
    }

    /** Web 手动建单（`API-DDL-002`）：表单字段全部参与比较。 */
    public static String forManual(CreateDdlTaskRequest request) {
        String canonical = canonical(OPERATION_MANUAL_CREATE,
                "course_id", request.courseId(),
                "course_name", request.courseName(),
                "task_title", request.taskTitle(),
                "time_precision", request.timePrecision(),
                "due_date", request.dueDate(),
                "due_time", request.dueTime(),
                "deliverable_format", request.deliverableFormat(),
                "submission_channel", request.submissionChannel());
        return DdlIdempotencyKey.sha256(canonical);
    }

    /** 变更草案“仍然新建”（`API-DDL-005`）：草案内容与目标身份都参与比较。 */
    public static String forDraftCreateNew(DdlTaskDraft draft) {
        String canonical = canonical(OPERATION_DRAFT_CREATE_NEW,
                "target_task_id", draft.targetTaskId() == null
                        ? null : draft.targetTaskId().toString(),
                "target_task_updated_at", draft.targetTaskUpdatedAt(),
                "status", draft.status(),
                "due_at", draft.dueAt(),
                "follow_up_at", draft.followUpAt(),
                "time_ambiguity_reason", draft.timeAmbiguityReason(),
                "course_id", draft.courseId() == null ? null : draft.courseId().toString(),
                "course_name", draft.courseName(),
                "task_title", draft.taskTitle(),
                "time_precision", draft.timePrecision(),
                "due_date", draft.dueDate(),
                "due_time", draft.dueTime(),
                "time_inferred", Boolean.toString(draft.timeInferred()),
                "deliverable_format", draft.deliverableFormat(),
                "submission_channel", draft.submissionChannel(),
                "raw_source_text", draft.rawSourceText(),
                "raw_time_text", draft.rawTimeText(),
                "user_timezone", draft.userTimezone(),
                "confidence", draft.confidence() == null
                        ? null : draft.confidence().toPlainString(),
                "is_relative_time", Boolean.toString(draft.isRelativeTime()));
        return DdlIdempotencyKey.sha256(canonical);
    }

    private static String canonical(String operation, String... keyValues) {
        StringBuilder builder = new StringBuilder(operation);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            builder.append('\u0001')
                    .append(keyValues[i])
                    .append('=')
                    .append(normalize(keyValues[i + 1]));
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");
        return WHITESPACE.matcher(normalized.replace('\u0001', ' '))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
    }
}
