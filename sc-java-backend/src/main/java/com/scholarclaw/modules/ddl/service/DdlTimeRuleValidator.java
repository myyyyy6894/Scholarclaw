package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Web 手动建单/编辑时的四级时间校验与 {@code due_at} 计算（TASK-703）。
 *
 * <p>边界：
 * <ul>
 *   <li>手动表单只接受 {@code EXACT_DATETIME} / {@code DATE_ONLY} / {@code VAGUE_OR_MISSING}；
 *       {@code INVALID_OR_PAST} 是模型提取结果，不允许用户手动选择。</li>
 *   <li>{@code due_date}/{@code due_time} 按**用户时区**解释；{@code due_at} 换算到应用时区
 *       存储，保证与 MySQL 其他 DATETIME 列可直接比较。</li>
 *   <li>{@code DATE_ONLY} 强制 {@code due_time = NULL}、{@code due_at = 当日 23:59:59}、
 *       {@code time_inferred = 1}（PO-02）；{@code VAGUE_OR_MISSING} 强制全部为空。</li>
 *   <li>未来时间 → {@code ACTIVE}；过去时间 → {@code PENDING_CONFIRM}；
 *       待补充时间 → {@code PENDING_TIME}。</li>
 * </ul>
 */
@Component
public class DdlTimeRuleValidator {

    private static final Pattern DATE_SHAPE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME_SHAPE = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");
    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter
            .ofPattern("HH:mm[:ss]")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final LocalTime DATE_ONLY_SENTINEL = LocalTime.of(23, 59, 59);
    private static final int FOLLOW_UP_HOURS = 24;

    /**
     * 校验并计算手动建单/编辑的时间字段。
     *
     * @param precisionRaw 原始精度字符串；为空时按 {@code EXACT_DATETIME} 处理
     * @param dueDateRaw   {@code YYYY-MM-DD}；按用户时区解释
     * @param dueTimeRaw   {@code HH:mm} 或 {@code HH:mm:ss}；按用户时区解释
     * @param userZone     用户 IANA 时区
     * @param now          应用时区当前时间
     */
    public DdlTimeFields resolve(String precisionRaw, String dueDateRaw, String dueTimeRaw,
                                 ZoneId userZone, LocalDateTime now) {
        TimePrecision precision = parsePrecision(precisionRaw);
        String dueDateText = trimToNull(dueDateRaw);
        String dueTimeText = trimToNull(dueTimeRaw);

        return switch (precision) {
            case EXACT_DATETIME -> resolveExact(dueDateText, dueTimeText, userZone, now);
            case DATE_ONLY -> resolveDateOnly(dueDateText, dueTimeText, userZone, now);
            case VAGUE_OR_MISSING -> resolveVague(dueDateText, dueTimeText, now);
            case INVALID_OR_PAST -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "手动建单/编辑不支持 INVALID_OR_PAST，请选择明确时间或待补充时间。");
        };
    }

    /** 校验用户 IANA 时区；空值回退应用默认时区。 */
    public ZoneId resolveUserZone(String timezone) {
        String candidate = trimToNull(timezone);
        if (candidate == null) {
            return ScTimeZone.zoneId();
        }
        try {
            return ZoneId.of(candidate);
        } catch (DateTimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "用户时区配置不是合法的 IANA 时区。");
        }
    }

    /** 归一化后的用户时区名称（用于写入 {@code t_ddl_task.user_timezone} 快照）。 */
    public String normalizeUserTimezone(String timezone) {
        return trimToNull(timezone) == null ? ScTimeZone.zoneId().getId() : timezone.trim();
    }

    private DdlTimeFields resolveExact(String dueDateText, String dueTimeText,
                                       ZoneId userZone, LocalDateTime now) {
        if (dueDateText == null || dueTimeText == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "EXACT_DATETIME 必须同时提供 due_date 与 due_time。");
        }
        LocalDate dueDate = parseDate(dueDateText);
        LocalTime dueTime = parseTime(dueTimeText);
        LocalDateTime dueAt = toAppZone(dueDate, dueTime, userZone);
        TaskStatus status = dueAt.isAfter(now) ? TaskStatus.ACTIVE : TaskStatus.PENDING_CONFIRM;
        return new DdlTimeFields(TimePrecision.EXACT_DATETIME, dueDate, dueTime, dueAt,
                false, status, followUpAt(status, now));
    }

    private DdlTimeFields resolveDateOnly(String dueDateText, String dueTimeText,
                                          ZoneId userZone, LocalDateTime now) {
        if (dueDateText == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "DATE_ONLY 必须提供 due_date。");
        }
        if (dueTimeText != null) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "DATE_ONLY 不允许具体时刻，due_time 必须为空。");
        }
        LocalDate dueDate = parseDate(dueDateText);
        LocalDateTime dueAt = toAppZone(dueDate, DATE_ONLY_SENTINEL, userZone);
        TaskStatus status = dueAt.isAfter(now) ? TaskStatus.ACTIVE : TaskStatus.PENDING_CONFIRM;
        return new DdlTimeFields(TimePrecision.DATE_ONLY, dueDate, null, dueAt,
                true, status, followUpAt(status, now));
    }

    private DdlTimeFields resolveVague(String dueDateText, String dueTimeText, LocalDateTime now) {
        if (dueDateText != null || dueTimeText != null) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "VAGUE_OR_MISSING 不允许 due_date 或 due_time，严禁编造截止时间。");
        }
        return new DdlTimeFields(TimePrecision.VAGUE_OR_MISSING, null, null, null,
                false, TaskStatus.PENDING_TIME, now.plusHours(FOLLOW_UP_HOURS));
    }

    private static TimePrecision parsePrecision(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return TimePrecision.EXACT_DATETIME;
        }
        try {
            return TimePrecision.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "time_precision 取值非法。");
        }
    }

    private static LocalDate parseDate(String raw) {
        if (!DATE_SHAPE.matcher(raw).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "due_date 必须为 YYYY-MM-DD 格式。");
        }
        try {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "due_date 不是合法公历日期。");
        }
    }

    private static LocalTime parseTime(String raw) {
        if (!TIME_SHAPE.matcher(raw).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "due_time 必须为 HH:mm 或 HH:mm:ss 格式。");
        }
        try {
            return LocalTime.parse(raw, INPUT_TIME);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "due_time 不是合法时刻。");
        }
    }

    /**
     * 用户时区墙钟时间 → 应用时区 DATETIME。
     *
     * <p>MySQL 的 {@code DATETIME(3)} 不携带偏移量；本系统统一按应用时区解释数据库时间，
     * 因此必须先把用户本地时间换算成同一瞬间的应用时区墙钟时间。
     */
    private static LocalDateTime toAppZone(LocalDate date, LocalTime time, ZoneId userZone) {
        return ZonedDateTime.of(date, time, userZone)
                .withZoneSameInstant(ScTimeZone.zoneId())
                .toLocalDateTime();
    }

    private static LocalDateTime followUpAt(TaskStatus status, LocalDateTime now) {
        return status == TaskStatus.PENDING_TIME || status == TaskStatus.PENDING_CONFIRM
                ? now.plusHours(FOLLOW_UP_HOURS)
                : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 手动建单/编辑后的时间字段快照。 */
    public record DdlTimeFields(
            TimePrecision timePrecision,
            LocalDate dueDate,
            LocalTime dueTime,
            LocalDateTime dueAt,
            boolean timeInferred,
            TaskStatus status,
            LocalDateTime followUpAt) {
    }
}
