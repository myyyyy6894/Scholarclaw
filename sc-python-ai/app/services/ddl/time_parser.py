"""DDL 四级时间语义与时区处理（TASK-702，`01` FR-DDL-001、`02` §5.2）。

职责：

* 在 TASK-701 的结构化提取结果之上，按**用户时区**做确定性的相对日期/自然周/
  跨年/非法公历/默认截止时刻处理；
* 区分“原文明确时间”与“系统推断时间”：`DATE_ONLY` 的 `23:59:59` 哨兵必须标记
  `time_inferred=True`，模糊时间绝不生成 `due_at`；
* 输出 :class:`TimeResolution`（含 `due_at`、`time_inferred`、初始状态建议），
  供后续 TASK-703 的 Java 状态机/持久化集成；**本模块不持久化、不写数据库**。

契约边界：`04` `API-INT-AI-002` 当前响应只有 12 个 DDL 字段，未包含 `due_at`/
`time_inferred`/初始状态。因此本模块只把 `TimeResolution` 交给 `DdlExtractor`
内部使用，**不擅自扩展 API 响应**；是否扩展需在 TASK-703 集成时由负责人确认。

设计原则：

* 所有日历计算都在 `user_timezone` 下进行，绝不依赖宿主机/容器默认时区；
* 不确定时宁可标歧义/置空，也不猜测；跨年顺延、周末/凌晨歧义等必须显式标注；
* 模型已经判定 `VAGUE_OR_MISSING` 时不做“升级”，避免把模糊通知变成确定任务。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from enum import StrEnum
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.schemas.ddl_extract import MAX_AMBIGUITY_REASON_LENGTH, DdlPayload, TimePrecision
from app.services.ddl.time_keywords import ABSOLUTE_DATE_PATTERN, find_vague_marker

#: 与 `03` §4.5 一致：置信度 `< 0.800` 强制进入待确认流程。
CONFIDENCE_CONFIRM_THRESHOLD = 0.80
#: `DATE_ONLY` 的默认截止哨兵（`PO-02`）。
DATE_ONLY_SENTINEL = time(23, 59, 59)
#: 自然周以周一为第一天（`01` FR-DDL-001）。
_WEEKDAY_INDEX = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6}
_CN_DIGITS = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
_RELATIVE_DAY_OFFSETS = {
    "今天": 0,
    "今日": 0,
    "今晚": 0,
    "明天": 1,
    "明日": 1,
    "明晚": 1,
    "后天": 2,
    "大后天": 3,
    "昨天": -1,
    "昨日": -1,
    "昨晚": -1,
}
_EARLY_MORNING_TOKENS = frozenset({"今天", "今日", "今晚", "明天", "明日", "明晚"})

_FULL_YEAR_DATE = re.compile(
    r"(?<!\d)(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})\s*[日号]?"
)
_MONTH_DAY_DATE = re.compile(r"(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]")
_SLASH_MONTH_DAY_DATE = re.compile(r"(?<!\d)(\d{1,2})/(\d{1,2})(?!/?\d)")
_RELATIVE_DAY = re.compile(r"(大后天|后天|明天|明日|今天|今日|明晚|今晚|昨晚|昨天|昨日)")
_WEEKDAY_EXPR = re.compile(
    r"(?P<prefix>下下|下个|下|本|这个|这)?\s*"
    r"(?P<unit>周|星期|礼拜)\s*(?P<day>[一二三四五六日天])"
)
_WEEK_RANGE = re.compile(
    r"(?P<prefix>下下|下个|下|本|这个|这)\s*(?P<unit>周|星期|礼拜)"
    r"(?![一二三四五六日天])"
)
_WEEKEND_RANGE = re.compile(r"(?P<prefix>本|下|这个|这)?\s*周末")
_MONTH_RANGE = re.compile(r"(月底|月初|下个月|下月|本月|这个月)")
_TIME_COLON = re.compile(r"(?<!\d)([01]?\d|2[0-3])[:：]([0-5]\d)(?::([0-5]\d))?(?!\d)")
_TIME_AMPM = re.compile(r"(?<!\d)(\d{1,2})\s*(?P<ampm>am|pm|AM|PM)(?!\w)")
_TIME_CN = re.compile(
    r"(?P<period>凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚)?\s*"
    r"(?P<hour>[一二三四五六七八九十]{1,2}|\d{1,2})\s*(?:点|时)\s*"
    r"(?:(?P<half>半)|(?P<minute>[一二三四五六七八九十]{1,2}|\d{1,2})\s*分?)?"
)
_TIME_PERIOD_ONLY = re.compile(r"(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚)")


class DdlInitialStatus(StrEnum):
    """建议的初始任务状态（最终状态机与持久化属 TASK-703）。"""

    ACTIVE = "ACTIVE"
    PENDING_TIME = "PENDING_TIME"
    PENDING_CONFIRM = "PENDING_CONFIRM"


@dataclass(frozen=True)
class TimeResolution:
    """四级时间解析结果；`due_at` 为带用户时区偏移量的绝对时间。"""

    time_precision: TimePrecision
    due_date: date | None
    due_time: time | None
    due_at: datetime | None
    time_inferred: bool
    is_relative_time: bool
    has_ambiguity: bool
    ambiguity_reason: str | None
    initial_status: DdlInitialStatus


@dataclass(frozen=True)
class _AbsoluteDate:
    value: date | None
    has_year: bool
    invalid: bool
    multiple: bool


@dataclass(frozen=True)
class _RelativeDate:
    value: date
    token: str
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class _DateResult:
    value: date | None
    has_year: bool
    invalid: bool
    is_relative: bool
    range_reason: str | None
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class _TimeResult:
    value: time | None
    found: bool
    ambiguous: bool
    period_only: bool
    cross_day: bool
    reasons: tuple[str, ...]


class DdlTimeParser:
    """确定性的 DDL 时间解析器；不访问网络、数据库或缓存。"""

    def refine(
        self,
        payload: DdlPayload,
        *,
        raw_text: str,
        reference_time: datetime,
        user_timezone: str,
    ) -> tuple[DdlPayload, TimeResolution]:
        """返回修正后的 payload 与包含 `due_at`/`time_inferred` 的解析结果。"""
        tz = _resolve_timezone(user_timezone)
        if reference_time.tzinfo is None or reference_time.utcoffset() is None:
            raise NonRetryableAiError(
                AiErrorCode.VALIDATION_ERROR, "reference_time 必须带时区偏移量。"
            )
        local_reference = reference_time.astimezone(tz)

        text = (payload.raw_time_text or raw_text or "").strip()
        if not text:
            text = (raw_text or "").strip()

        reasons: list[str] = []
        if payload.has_ambiguity:
            reasons.append(payload.ambiguity_reason or "模型标注存在不确定信息")

        date_result = _parse_date_text(text, local_reference)
        reasons.extend(date_result.reasons)
        time_result = _parse_time_text(text, payload.parsed_due_time)
        reasons.extend(time_result.reasons)

        vague_marker = find_vague_marker(text)
        has_absolute_date = ABSOLUTE_DATE_PATTERN.search(text) is not None
        forced_vague = False
        if payload.time_precision is TimePrecision.VAGUE_OR_MISSING:
            # 模型已经判定模糊：TASK-702 不做“升级”，避免把模糊通知变成确定任务。
            forced_vague = True
        if vague_marker and not has_absolute_date and not date_result.is_relative:
            forced_vague = True
            reasons.append("原文仅含模糊时间表述，未生成确定截止时间")
        elif vague_marker and (has_absolute_date or date_result.is_relative):
            reasons.append("原文时间含模糊限定词，解析结果可能不精确")
        if date_result.range_reason and date_result.value is None:
            forced_vague = True
            reasons.append(date_result.range_reason)

        precision: TimePrecision | None
        if forced_vague:
            precision = TimePrecision.VAGUE_OR_MISSING
            due_date = None
            due_time = None
            cross_year_rolled = False
        elif date_result.invalid:
            precision = TimePrecision.INVALID_OR_PAST
            due_date = None
            due_time = None
            cross_year_rolled = False
        else:
            due_date = date_result.value
            due_time = time_result.value
            cross_year_rolled = False
            if due_date is not None and not date_result.has_year and not date_result.is_relative:
                due_date, cross_year_rolled, roll_failed = _maybe_roll_year(
                    due_date, local_reference, due_time
                )
                if cross_year_rolled:
                    reasons.append("已按跨年顺延至次年，请核对")
                elif roll_failed:
                    reasons.append("跨年顺延失败（次年无该日期），请核对")

            if due_date is None and time_result.found:
                # 只解析出时刻：借用模型给出的日期。
                due_date = payload.parsed_due_date
                precision = (
                    TimePrecision.EXACT_DATETIME if due_date is not None else None
                )
            elif due_date is not None:
                precision = (
                    TimePrecision.EXACT_DATETIME
                    if time_result.found
                    else TimePrecision.DATE_ONLY
                )
            else:
                # 文本中没有可识别的日期/时刻：使用模型结果，但仍校验时刻可复核性。
                due_date = payload.parsed_due_date
                due_time = payload.parsed_due_time
                precision = payload.time_precision
                if (
                    payload.time_precision is TimePrecision.EXACT_DATETIME
                    and not time_result.found
                ):
                    due_time = None
                    precision = (
                        TimePrecision.DATE_ONLY
                        if due_date is not None
                        else TimePrecision.INVALID_OR_PAST
                    )
                    reasons.append("模型给出的具体时刻无法从原文复核，已置空")

            # 模型明确判定非法/过去时，除跨年顺延外不擅自“修正”为确定时间。
            if (
                payload.time_precision is TimePrecision.INVALID_OR_PAST
                and not cross_year_rolled
            ):
                precision = TimePrecision.INVALID_OR_PAST
                if due_date is None:
                    due_date = payload.parsed_due_date
                if due_time is None:
                    due_time = payload.parsed_due_time

            if precision is None:
                precision = TimePrecision.INVALID_OR_PAST
                due_date = None
                due_time = None
                reasons.append("缺少可用日期，无法确定截止时间")

        precision, due_date, due_time = _finalize_precision(
            precision, due_date, due_time
        )
        if time_result.cross_day and due_date is not None:
            due_date = due_date + timedelta(days=1)
        if (
            precision in (TimePrecision.EXACT_DATETIME, TimePrecision.DATE_ONLY)
            and due_date is not None
            and _is_past(precision, due_date, due_time, local_reference, tz)
        ):
            precision = TimePrecision.INVALID_OR_PAST
            reasons.append("解析出的截止时间早于消息基准时间")

        if cross_year_rolled and due_date is not None:
            # 跨年顺延成功后，“早于基准时间/过去”已不成立，必须移除模型或早前步骤留下的
            # 矛盾原因，并改为带顺延后年份的准确表述。
            reasons = [
                reason
                for reason in reasons
                if "早于" not in reason and "过去" not in reason and "跨年顺延" not in reason
            ]
            reasons.append(
                f"按当年计算已过期，已跨年顺延至 {due_date.isoformat()}，请核对"
            )

        due_at, time_inferred = _compute_due_at(precision, due_date, due_time, tz)
        if due_at is not None:
            reasons.extend(_dst_reasons(due_at, tz))

        if (
            payload.parsed_due_time is not None
            and not time_result.found
            and precision in (TimePrecision.DATE_ONLY, TimePrecision.INVALID_OR_PAST)
        ):
            reasons.append("模型给出的具体时刻无法从原文复核，已置空")

        is_relative = _resolve_is_relative(date_result, due_date, payload)
        if payload.confidence < CONFIDENCE_CONFIRM_THRESHOLD:
            reasons.append("模型提取置信度低于 0.80，建议人工确认")

        deduped = _dedupe(reasons)
        has_ambiguity = bool(deduped)
        ambiguity_reason = (
            _truncate("；".join(deduped), MAX_AMBIGUITY_REASON_LENGTH)
            if has_ambiguity
            else None
        )
        initial_status = _initial_status(precision, due_date, has_ambiguity,
                                        payload.confidence)

        refined = payload.model_copy(
            update={
                "time_precision": precision,
                "parsed_due_date": due_date,
                "parsed_due_time": due_time,
                "is_relative_time": is_relative,
                "has_ambiguity": has_ambiguity,
                "ambiguity_reason": ambiguity_reason,
            }
        )
        resolution = TimeResolution(
            time_precision=precision,
            due_date=due_date,
            due_time=due_time,
            due_at=due_at,
            time_inferred=time_inferred,
            is_relative_time=is_relative,
            has_ambiguity=has_ambiguity,
            ambiguity_reason=ambiguity_reason,
            initial_status=initial_status,
        )
        return refined, resolution


def _finalize_precision(
    precision: TimePrecision,
    due_date: date | None,
    due_time: time | None,
) -> tuple[TimePrecision, date | None, time | None]:
    """保证四级精度的字段不变量：VAGUE 全空、DATE_ONLY 无时刻、EXACT 有日期+时刻。"""
    if precision is TimePrecision.VAGUE_OR_MISSING:
        return precision, None, None
    if precision is TimePrecision.DATE_ONLY:
        return precision, due_date, None
    if precision is TimePrecision.EXACT_DATETIME:
        if due_date is None or due_time is None:
            return TimePrecision.DATE_ONLY, due_date, None
        return precision, due_date, due_time
    # INVALID_OR_PAST：日期可空；没有日期的孤立时刻一律丢弃。
    if due_date is None:
        return precision, None, None
    return precision, due_date, due_time


def _parse_date_text(text: str, local_reference: datetime) -> _DateResult:
    absolute = _parse_absolute_date(text, local_reference)
    relative = _parse_relative_date(text, local_reference)
    is_relative = relative is not None
    relative_reasons = relative.reasons if relative is not None else ()

    reasons: list[str] = list(relative_reasons)
    if absolute is not None:
        if absolute.value is not None:
            if absolute.multiple:
                reasons.append("原文包含多个日期，已按首个匹配解析，请核对")
            return _DateResult(
                value=absolute.value,
                has_year=absolute.has_year,
                invalid=False,
                is_relative=is_relative,
                range_reason=None,
                reasons=tuple(reasons),
            )
        if absolute.invalid:
            reasons.append("原文日期非法或无法解析")
            return _DateResult(
                value=None,
                has_year=absolute.has_year,
                invalid=True,
                is_relative=is_relative,
                range_reason=None,
                reasons=tuple(reasons),
            )
    if relative is not None:
        return _DateResult(
            value=relative.value,
            has_year=False,
            invalid=False,
            is_relative=True,
            range_reason=None,
            reasons=tuple(reasons),
        )
    range_reason = _detect_range(text)
    return _DateResult(
        value=None,
        has_year=False,
        invalid=False,
        # “下周/周末/月底”本身是相对基准时间的表述，尽管无法落到单一日期。
        is_relative=range_reason is not None,
        range_reason=range_reason,
        reasons=tuple(),
    )


def _parse_absolute_date(text: str, local_reference: datetime) -> _AbsoluteDate | None:
    full_matches = list(_FULL_YEAR_DATE.finditer(text))
    if full_matches:
        match = full_matches[0]
        try:
            value = date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
        except ValueError:
            return _AbsoluteDate(None, True, True, len(full_matches) > 1)
        return _AbsoluteDate(value, True, False, len(full_matches) > 1)

    month_day_matches = list(_MONTH_DAY_DATE.finditer(text))
    if not month_day_matches:
        month_day_matches = list(_SLASH_MONTH_DAY_DATE.finditer(text))
    if month_day_matches:
        match = month_day_matches[0]
        try:
            value = date(
                local_reference.year, int(match.group(1)), int(match.group(2))
            )
        except ValueError:
            return _AbsoluteDate(None, False, True, len(month_day_matches) > 1)
        return _AbsoluteDate(value, False, False, len(month_day_matches) > 1)
    return None


def _parse_relative_date(text: str, local_reference: datetime) -> _RelativeDate | None:
    day_match = _RELATIVE_DAY.search(text)
    if day_match:
        token = day_match.group(1)
        value = local_reference.date() + timedelta(days=_RELATIVE_DAY_OFFSETS[token])
        reasons: list[str] = []
        if local_reference.hour < 4 and token in _EARLY_MORNING_TOKENS:
            reasons.append(
                "凌晨 00:00-04:00 收到“今天/明天/今晚”，日期可能跨日，请核对"
            )
        return _RelativeDate(value, token, tuple(reasons))

    weekday_match = _WEEKDAY_EXPR.search(text)
    if weekday_match:
        prefix = weekday_match.group("prefix") or ""
        day_index = _WEEKDAY_INDEX[weekday_match.group("day")]
        monday = local_reference.date() - timedelta(days=local_reference.weekday())
        reasons = []
        if prefix == "下下":
            value = monday + timedelta(days=14 + day_index)
        elif prefix in ("下", "下个"):
            value = monday + timedelta(days=7 + day_index)
            if day_index == 0 and local_reference.weekday() >= 5:
                reasons.append(
                    "周六/周日收到“下周一”，已按下一周周一换算，请核对"
                )
        else:
            value = monday + timedelta(days=day_index)
            if not prefix and value < local_reference.date():
                value = value + timedelta(days=7)
                reasons.append("原文未明确本周/下周，已按最近的下一次周X换算")
        return _RelativeDate(value, weekday_match.group(0), tuple(reasons))
    return None


def _detect_range(text: str) -> str | None:
    if _WEEK_RANGE.search(text):
        return "原文仅给出周范围，未确定具体日期"
    if _WEEKEND_RANGE.search(text):
        return "原文仅给出周末范围，未确定具体日期"
    if _MONTH_RANGE.search(text):
        return "原文仅给出月份范围，未确定具体日期"
    return None


def _parse_time_text(text: str, model_time: time | None) -> _TimeResult:
    colon_match = _TIME_COLON.search(text)
    if colon_match:
        return _TimeResult(
            value=time(
                int(colon_match.group(1)),
                int(colon_match.group(2)),
                int(colon_match.group(3) or 0),
            ),
            found=True,
            ambiguous=False,
            period_only=False,
            cross_day=False,
            reasons=tuple(),
        )

    ampm_match = _TIME_AMPM.search(text)
    if ampm_match:
        hour = int(ampm_match.group(1))
        if not 1 <= hour <= 12:
            return _TimeResult(None, False, False, False, False, ("原文时刻非法，已忽略",))
        ampm = ampm_match.group("ampm").lower()
        if ampm == "pm" and hour < 12:
            hour += 12
        elif ampm == "am" and hour == 12:
            hour = 0
        return _TimeResult(time(hour, 0), True, False, False, False, tuple())

    cn_match = _TIME_CN.search(text)
    if cn_match:
        hour = _parse_number(cn_match.group("hour"))
        minute = 0
        if cn_match.group("half"):
            minute = 30
        elif cn_match.group("minute"):
            minute = _parse_number(cn_match.group("minute"))
        if hour is None or minute is None or not 0 <= hour <= 23 or not 0 <= minute <= 59:
            return _TimeResult(None, False, False, False, False, ("原文时刻非法，已忽略",))

        period = cn_match.group("period")
        resolved_hour, cross_day = _apply_period(hour, period)
        if resolved_hour is None:
            return _TimeResult(None, False, False, False, False, ("原文时刻非法，已忽略",))

        reasons: list[str] = []
        if period is None and 1 <= resolved_hour <= 12:
            reasons.append("原文时刻未注明上午/下午")
            if (
                model_time is not None
                and model_time.minute == minute
                and model_time.hour in {resolved_hour, (resolved_hour + 12) % 24}
            ):
                reasons.append("已按模型解析结果保留，请核对")
                return _TimeResult(
                    model_time, True, True, False, False, tuple(reasons)
                )
            reasons.append("已按 24 小时制保留，请核对")
            return _TimeResult(
                time(resolved_hour, minute), True, True, False, False, tuple(reasons)
            )
        if cross_day:
            reasons.append("“晚上12点”已按次日 00:00 解释，请核对")
        return _TimeResult(
            time(resolved_hour, minute), True, False, False, cross_day, tuple(reasons)
        )

    period_match = _TIME_PERIOD_ONLY.search(text)
    if period_match:
        return _TimeResult(
            None, False, False, True, False, ("原文仅给出时段，未给出具体时刻",)
        )
    return _TimeResult(None, False, False, False, False, tuple())


def _apply_period(hour: int, period: str | None) -> tuple[int | None, bool]:
    if period in ("下午", "傍晚"):
        return (hour if hour == 12 else hour + 12), False
    if period in ("晚上", "夜里", "晚"):
        if hour == 12:
            return 0, True
        return (hour + 12 if hour < 12 else hour), False
    if period == "凌晨":
        return (0 if hour == 12 else hour), False
    if period == "中午":
        return (hour + 12 if hour < 11 else hour), False
    if period in ("早上", "早晨", "上午"):
        return hour, False
    return hour, False


def _parse_number(raw: str) -> int | None:
    if raw.isdigit():
        return int(raw)
    if raw == "十":
        return 10
    if raw.startswith("十"):
        ones = _CN_DIGITS.get(raw[1:], None)
        return 10 + ones if ones is not None else None
    if "十" in raw:
        tens_part, ones_part = raw.split("十", 1)
        tens = _CN_DIGITS.get(tens_part)
        ones = _CN_DIGITS.get(ones_part, 0) if ones_part else 0
        if tens is None:
            return None
        return tens * 10 + ones
    return _CN_DIGITS.get(raw)


def _maybe_roll_year(
    value: date, local_reference: datetime, due_time: time | None = None
) -> tuple[date, bool, bool]:
    """按 docs/01 §相对日期规则做跨年顺延。

    原文仅含月日（没有年份、也不是相对日期）时：若按当年计算出的截止时刻早于
    ``reference_time`` **超过 24 小时**，自动顺延至次年。超过 24 小时而不是“只要过去”，
    是为了容忍消息延迟与当日边界；与参考月相差多少个月无关。

    返回 ``(date, rolled, roll_failed)``；次年没有该日期（如 2028-02-29 → 2029-02-29）
    时 ``roll_failed=True``，由调用方保留原日期并标注歧义。
    """
    effective_time = due_time if due_time is not None else DATE_ONLY_SENTINEL
    due_moment = datetime.combine(value, effective_time, tzinfo=local_reference.tzinfo)
    if local_reference - due_moment <= timedelta(hours=24):
        return value, False, False
    try:
        rolled = value.replace(year=value.year + 1)
    except ValueError:
        return value, False, True
    return rolled, True, False


def _is_past(
    precision: TimePrecision,
    due_date: date,
    due_time: time | None,
    local_reference: datetime,
    tz: ZoneInfo,
) -> bool:
    if precision is TimePrecision.EXACT_DATETIME and due_time is not None:
        return datetime.combine(due_date, due_time, tzinfo=tz) < local_reference
    if precision is TimePrecision.DATE_ONLY:
        return due_date < local_reference.date()
    return False


def _compute_due_at(
    precision: TimePrecision,
    due_date: date | None,
    due_time: time | None,
    tz: ZoneInfo,
) -> tuple[datetime | None, bool]:
    if due_date is None:
        return None, False
    if precision is TimePrecision.EXACT_DATETIME and due_time is not None:
        return datetime.combine(due_date, due_time, tzinfo=tz), False
    if precision in (TimePrecision.DATE_ONLY, TimePrecision.INVALID_OR_PAST):
        resolved_time = due_time or DATE_ONLY_SENTINEL
        return (
            datetime.combine(due_date, resolved_time, tzinfo=tz),
            due_time is None,
        )
    return None, False


def _dst_reasons(due_at: datetime, tz: ZoneInfo) -> list[str]:
    # 先判“不存在”：夏令时前跳的本地时刻在往返换算后墙钟时间会改变。
    roundtrip = due_at.astimezone(timezone.utc).astimezone(tz)
    if roundtrip.replace(tzinfo=None) != due_at.replace(tzinfo=None):
        return ["该本地时刻在时区切换日不存在，请核对"]
    # 再判“出现两次”：同一墙钟时间对应两个不同偏移量。
    if due_at.utcoffset() != due_at.replace(fold=1).utcoffset():
        return ["该本地时刻在时区切换日出现两次，已按第一次解释，请核对"]
    return []


def _resolve_is_relative(
    date_result: _DateResult, due_date: date | None, payload: DdlPayload
) -> bool:
    if date_result.is_relative:
        return True
    if due_date is not None:
        return False
    return payload.is_relative_time


def _initial_status(
    precision: TimePrecision,
    due_date: date | None,
    has_ambiguity: bool,
    confidence: float,
) -> DdlInitialStatus:
    if precision is TimePrecision.VAGUE_OR_MISSING:
        return DdlInitialStatus.PENDING_TIME
    if precision is TimePrecision.INVALID_OR_PAST and due_date is None:
        return DdlInitialStatus.PENDING_TIME
    if precision is TimePrecision.INVALID_OR_PAST:
        return DdlInitialStatus.PENDING_CONFIRM
    if has_ambiguity or confidence < CONFIDENCE_CONFIRM_THRESHOLD:
        return DdlInitialStatus.PENDING_CONFIRM
    return DdlInitialStatus.ACTIVE


def _resolve_timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR, "user_timezone 必须为合法 IANA 时区名。"
        ) from exc


def _dedupe(reasons: list[str]) -> list[str]:
    seen: set[str] = set()
    deduped: list[str] = []
    for reason in reasons:
        cleaned = reason.strip()
        if cleaned and cleaned not in seen:
            seen.add(cleaned)
            deduped.append(cleaned)
    return deduped


def _truncate(value: str, limit: int) -> str:
    return value if len(value) <= limit else value[: limit - 1] + "…"
