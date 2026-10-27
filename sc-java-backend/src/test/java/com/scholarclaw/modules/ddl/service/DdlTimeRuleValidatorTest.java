package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.common.enums.TimePrecision;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-703 手动建单/编辑的时间规则（PO-02、四级时间不变量）。 */
class DdlTimeRuleValidatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 11, 40);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private DdlTimeRuleValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DdlTimeRuleValidator();
    }

    @Test
    @DisplayName("EXACT 未来时间 → ACTIVE，due_at 换算到应用时区，follow_up_at 为空")
    void exactFutureIsActive() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "EXACT_DATETIME", "2026-10-25", "22:00:00", SHANGHAI, NOW);

        assertThat(fields.timePrecision()).isEqualTo(TimePrecision.EXACT_DATETIME);
        assertThat(fields.dueDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(fields.dueTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(fields.dueAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 22, 0));
        assertThat(fields.timeInferred()).isFalse();
        assertThat(fields.status()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(fields.followUpAt()).isNull();
    }

    @Test
    @DisplayName("EXACT 过去时间 → PENDING_CONFIRM，follow_up_at = now + 24h")
    void exactPastIsPendingConfirm() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "EXACT_DATETIME", "2026-10-01", "22:00", SHANGHAI, NOW);

        assertThat(fields.status()).isEqualTo(TaskStatus.PENDING_CONFIRM);
        assertThat(fields.followUpAt()).isEqualTo(NOW.plusHours(24));
    }

    @Test
    @DisplayName("DATE_ONLY → due_time 为空、due_at 当日 23:59:59、time_inferred=true")
    void dateOnlyUsesSentinelAndInfersTime() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "DATE_ONLY", "2026-10-25", null, SHANGHAI, NOW);

        assertThat(fields.timePrecision()).isEqualTo(TimePrecision.DATE_ONLY);
        assertThat(fields.dueTime()).isNull();
        assertThat(fields.dueAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 23, 59, 59));
        assertThat(fields.timeInferred()).isTrue();
        assertThat(fields.status()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    @DisplayName("DATE_ONLY 过去日期 → PENDING_CONFIRM")
    void dateOnlyPastIsPendingConfirm() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "DATE_ONLY", "2026-10-01", null, SHANGHAI, NOW);

        assertThat(fields.status()).isEqualTo(TaskStatus.PENDING_CONFIRM);
    }

    @Test
    @DisplayName("VAGUE_OR_MISSING → PENDING_TIME，due 字段全部为空，follow_up_at = now + 24h")
    void vagueIsPendingTime() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "VAGUE_OR_MISSING", null, null, SHANGHAI, NOW);

        assertThat(fields.timePrecision()).isEqualTo(TimePrecision.VAGUE_OR_MISSING);
        assertThat(fields.dueDate()).isNull();
        assertThat(fields.dueTime()).isNull();
        assertThat(fields.dueAt()).isNull();
        assertThat(fields.timeInferred()).isFalse();
        assertThat(fields.status()).isEqualTo(TaskStatus.PENDING_TIME);
        assertThat(fields.followUpAt()).isEqualTo(NOW.plusHours(24));
    }

    @Test
    @DisplayName("未传 time_precision 时按 EXACT_DATETIME 处理")
    void defaultPrecisionIsExact() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                null, "2026-10-25", "22:00", SHANGHAI, NOW);

        assertThat(fields.timePrecision()).isEqualTo(TimePrecision.EXACT_DATETIME);
    }

    @Test
    @DisplayName("EXACT 缺少 due_date 或 due_time → VALIDATION_ERROR")
    void exactRequiresBothDateAndTime() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("EXACT_DATETIME", "2026-10-25", null, SHANGHAI, NOW));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("EXACT_DATETIME", null, "22:00", SHANGHAI, NOW));
    }

    @Test
    @DisplayName("DATE_ONLY 传具体时刻 → INVALID_CALENDAR_DATE")
    void dateOnlyRejectsTime() {
        assertError(ErrorCode.INVALID_CALENDAR_DATE,
                () -> validator.resolve("DATE_ONLY", "2026-10-25", "22:00", SHANGHAI, NOW));
    }

    @Test
    @DisplayName("VAGUE 传日期或时刻 → INVALID_CALENDAR_DATE")
    void vagueRejectsDateAndTime() {
        assertError(ErrorCode.INVALID_CALENDAR_DATE,
                () -> validator.resolve("VAGUE_OR_MISSING", "2026-10-25", null, SHANGHAI, NOW));
        assertError(ErrorCode.INVALID_CALENDAR_DATE,
                () -> validator.resolve("VAGUE_OR_MISSING", null, "22:00", SHANGHAI, NOW));
    }

    @Test
    @DisplayName("非法公历日期 2026-02-29 → INVALID_CALENDAR_DATE")
    void illegalCalendarDateIsRejected() {
        assertError(ErrorCode.INVALID_CALENDAR_DATE,
                () -> validator.resolve("DATE_ONLY", "2026-02-29", null, SHANGHAI, NOW));
    }

    @Test
    @DisplayName("日期/时刻格式错误 → VALIDATION_ERROR")
    void malformedDateAndTimeAreValidationErrors() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("DATE_ONLY", "2026-2-5", null, SHANGHAI, NOW));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("EXACT_DATETIME", "2026-10-25", "2:00", SHANGHAI, NOW));
    }

    @Test
    @DisplayName("合法格式但非法时刻 25:00 → INVALID_CALENDAR_DATE")
    void impossibleTimeIsInvalidCalendarDate() {
        assertError(ErrorCode.INVALID_CALENDAR_DATE,
                () -> validator.resolve("EXACT_DATETIME", "2026-10-25", "25:00", SHANGHAI, NOW));
    }

    @Test
    @DisplayName("非法 precision 与手动 INVALID_OR_PAST 均被拒绝")
    void unsupportedPrecisionIsRejected() {
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("SOMETIME", "2026-10-25", "22:00", SHANGHAI, NOW));
        assertError(ErrorCode.VALIDATION_ERROR,
                () -> validator.resolve("INVALID_OR_PAST", null, null, SHANGHAI, NOW));
    }

    @Test
    @DisplayName("非 Asia/Shanghai 用户：用户墙钟时间换算为应用时区的同一瞬间")
    void nonShanghaiUserIsConvertedToApplicationZone() {
        ZoneId newYork = ZoneId.of("America/New_York");

        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "EXACT_DATETIME", "2026-10-25", "22:00", newYork, NOW);

        // 2026-10-25 22:00 EDT == 2026-10-26 10:00 Asia/Shanghai
        assertThat(fields.dueAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 10, 0));
        assertThat(fields.status()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    @DisplayName("用户时区为空回退应用时区；非法 IANA 时区被拒绝")
    void userTimezoneResolution() {
        assertThat(validator.resolveUserZone(null)).isEqualTo(ScTimeZone.zoneId());
        assertThat(validator.resolveUserZone("  ")).isEqualTo(ScTimeZone.zoneId());
        assertThat(validator.resolveUserZone("America/New_York"))
                .isEqualTo(ZoneId.of("America/New_York"));
        assertError(ErrorCode.VALIDATION_ERROR, () -> validator.resolveUserZone("Mars/Base"));
    }

    private static void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(expected));
    }

    // ------------------------------ DST 边界 ------------------------------

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    @DisplayName("DST 跳变窗口内不存在的本地时刻 → 400 INVALID_CALENDAR_DATE，不得静默归一化")
    void nonexistentLocalTimeIsRejected() {
        // America/New_York 2027-03-14 02:00 -> 03:00，02:30 不存在。
        assertThatThrownBy(() -> validator.resolve(
                "EXACT_DATETIME", "2027-03-14", "02:30", NEW_YORK, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CALENDAR_DATE));
    }

    @Test
    @DisplayName("DST 回拨导致同一本地时刻出现两次 → 拒绝，要求用户改用非歧义时刻")
    void ambiguousLocalTimeIsRejected() {
        // America/New_York 2027-11-07 01:00-02:00 重复，01:30 有两个偏移量。
        assertThatThrownBy(() -> validator.resolve(
                "EXACT_DATETIME", "2027-11-07", "01:30", NEW_YORK, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CALENDAR_DATE));
    }

    @Test
    @DisplayName("DST 跳变后的正常时刻仍按真实偏移量换算到应用时区")
    void normalLocalTimeAfterDstIsConverted() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "EXACT_DATETIME", "2027-03-14", "03:30", NEW_YORK, NOW);

        // 03:30 EDT (UTC-4) = 07:30 UTC = 15:30 Asia/Shanghai。
        assertThat(fields.dueAt()).isEqualTo(LocalDateTime.of(2027, 3, 14, 15, 30));
        assertThat(fields.status()).isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    @DisplayName("默认 Asia/Shanghai 无 DST，正常时刻不受影响")
    void defaultZoneHasNoDstAmbiguity() {
        DdlTimeRuleValidator.DdlTimeFields fields = validator.resolve(
                "EXACT_DATETIME", "2027-03-14", "02:30", SHANGHAI, NOW);

        assertThat(fields.dueAt()).isEqualTo(LocalDateTime.of(2027, 3, 14, 2, 30));
    }

    @Test
    @DisplayName("提取结果落库同样拒绝不存在时刻，不能写入另一个本地时刻")
    void extractedFieldRejectsNonexistentLocalTime() {
        assertThatThrownBy(() -> validator.resolveExtracted(TimePrecision.EXACT_DATETIME,
                LocalDate.of(2027, 3, 14), LocalTime.of(2, 30), false,
                new java.math.BigDecimal("0.960"), NEW_YORK, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CALENDAR_DATE));
    }
}
