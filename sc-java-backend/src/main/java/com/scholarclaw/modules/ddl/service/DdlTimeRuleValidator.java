package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.math.BigDecimal;
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
    private static final BigDecimal CONFIDENCE_CONFIRM_THRESHOLD = new BigDecimal("0.800");

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

    /**
     * 把 TASK-701/702 的模型提取结果映射为落库时间字段（TASK-704）。
     *
     * <p>与手动建单的区别：
     * <ul>
     *   <li>{@code INVALID_OR_PAST} 是模型提取的合法结果：有日期时按过去草案保留
     *       {@code due_at} 并置 {@code PENDING_CONFIRM}；无日期时置 {@code PENDING_TIME}；</li>
     *   <li>置信度 {@code < 0.800} 或模型标注歧义时，即使时间明确也进入
     *       {@code PENDING_CONFIRM}；</li>
     *   <li>相对日期、跨年等规则已在 Python `TimeParser` 完成，这里只做时区换算与状态映射。</li>
     * </ul>
     */
    public DdlTimeFields resolveExtracted(TimePrecision precision,
                                          LocalDate parsedDueDate,
                                          LocalTime parsedDueTime,
                                          boolean hasAmbiguity,
                                          BigDecimal confidence,
                                          ZoneId userZone,
                                          LocalDateTime now) {
        boolean confirmed = confidence != null
                && confidence.compareTo(CONFIDENCE_CONFIRM_THRESHOLD) >= 0
                && !hasAmbiguity;

        return switch (precision) {
            case EXACT_DATETIME -> {
                if (parsedDueDate == null || parsedDueTime == null) {
                    throw upstreamIncomplete("EXACT_DATETIME 缺少日期或时刻");
                }
                LocalDateTime dueAt = toAppZone(parsedDueDate, parsedDueTime, userZone);
                TaskStatus status = confirmed && dueAt.isAfter(now)
                        ? TaskStatus.ACTIVE : TaskStatus.PENDING_CONFIRM;
                yield new DdlTimeFields(precision, parsedDueDate, parsedDueTime, dueAt,
                        false, status, followUpAt(status, now));
            }
            case DATE_ONLY -> {
                if (parsedDueDate == null) {
                    throw upstreamIncomplete("DATE_ONLY 缺少日期");
                }
                LocalDateTime dueAt = toAppZone(parsedDueDate, DATE_ONLY_SENTINEL, userZone);
                TaskStatus status = confirmed && dueAt.isAfter(now)
                        ? TaskStatus.ACTIVE : TaskStatus.PENDING_CONFIRM;
                yield new DdlTimeFields(precision, parsedDueDate, null, dueAt,
                        true, status, followUpAt(status, now));
            }
            case VAGUE_OR_MISSING -> new DdlTimeFields(precision, null, null, null,
                    false, TaskStatus.PENDING_TIME, now.plusHours(FOLLOW_UP_HOURS));
            case INVALID_OR_PAST -> {
                if (parsedDueDate == null) {
                    yield new DdlTimeFields(precision, null, null, null,
                            false, TaskStatus.PENDING_TIME, now.plusHours(FOLLOW_UP_HOURS));
                }
                boolean inferred = parsedDueTime == null;
                LocalTime effectiveTime = parsedDueTime == null ? DATE_ONLY_SENTINEL : parsedDueTime;
                LocalDateTime dueAt = toAppZone(parsedDueDate, effectiveTime, userZone);
                yield new DdlTimeFields(precision, parsedDueDate, parsedDueTime, dueAt,
                        inferred, TaskStatus.PENDING_CONFIRM, now.plusHours(FOLLOW_UP_HOURS));
            }
        };
    }

    private static BusinessException upstreamIncomplete(String detail) {
        return new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                "模型返回的 DDL 时间字段不完整（" + detail + "）。");
    }

    /** 校验用户 IANA 时区；空值回退应用默认时区。 */
    public ZoneId resolveUserZone(String timezone) {        String candidate = trimToNull(timezone);
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

    /**
     * 把用户时区的墙钟日期/时刻换算为应用时区 DATETIME；供草案决策重新校验一致性使用。
     */
    public LocalDateTime combineInAppZone(LocalDate date, LocalTime time, ZoneId userZone) {
        return toAppZone(date, time, userZone);
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
     *
     * <p><b>DST 往返校验</b>：{@link ZonedDateTime#of} 会把夏令时跳变窗口内不存在的本地时刻
     * 静默归一化为另一个时刻（例如 `America/New_York` 的 `2027-03-14 02:30` 变成 `03:30`），
     * 也会对回拨窗口内出现两次的本地时刻任选一个偏移量。两种情况下“用户看到的本地时刻”
     * 与“实际保存的瞬间”不再一致，因此这里显式拒绝：
     * <ul>
     *   <li>{@code getValidOffsets} 为空 → 不存在时刻，抛 {@code 400 INVALID_CALENDAR_DATE}；</li>
     *   <li>返回两个偏移量 → fall-back 重复时刻，同样拒绝并要求用户改用明确时刻；</li>
     *   <li>换算到应用时区再换回用户时区，本地日期/时刻必须逐字段一致。</li>
     * </ul>
     * 手工新建、手工编辑、提取结果落库、草案应用与 `CREATE_NEW` 均通过本方法，保证同一口径。
     */
    private static LocalDateTime toAppZone(LocalDate date, LocalTime time, ZoneId userZone) {
        LocalDateTime local = LocalDateTime.of(date, time);
        java.util.List<java.time.ZoneOffset> offsets = userZone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "本地时间 " + date + " " + time + " 在时区 " + userZone.getId()
                            + " 的夏令时跳变窗口中不存在，请填写该时区真实存在的时刻。");
        }
        if (offsets.size() > 1) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "本地时间 " + date + " " + time + " 在时区 " + userZone.getId()
                            + " 因夏令时回拨出现两次，请填写非歧义时刻或改用其它时区。");
        }
        ZonedDateTime zoned = ZonedDateTime.of(local, userZone);
        LocalDateTime appLocal = zoned.withZoneSameInstant(ScTimeZone.zoneId()).toLocalDateTime();
        LocalDateTime roundTrip = appLocal.atZone(ScTimeZone.zoneId())
                .withZoneSameInstant(userZone).toLocalDateTime();
        if (!roundTrip.equals(local)) {
            throw new BusinessException(ErrorCode.INVALID_CALENDAR_DATE,
                    "本地时间 " + date + " " + time + " 在时区 " + userZone.getId()
                            + " 无法稳定换算（夏令时边界），请改用其它时刻。");
        }
        return appLocal;
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
