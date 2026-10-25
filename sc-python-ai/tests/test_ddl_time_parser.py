"""TASK-702 四级时间语义与时区处理单元测试。

覆盖：明确时间、DATE_ONLY 哨兵、模糊/范围、非法公历、过去时间、相对日期、
自然周边界、跨年顺延、凌晨/周末歧义、裸周几/裸小时、时区换算、DST 边界、
`time_inferred` 与初始状态建议。
"""

from __future__ import annotations

from datetime import date, datetime, time

import pytest

from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.schemas.ddl_extract import DdlPayload, TimePrecision
from app.services.ddl.time_parser import (
    DdlInitialStatus,
    DdlTimeParser,
    TimeResolution,
)

REFERENCE = "2026-10-15T11:40:00+08:00"  # 周四


def _payload(**overrides) -> DdlPayload:
    base: dict = {
        "course_name": "操作系统原理",
        "task_title": "提交实验报告",
        "raw_time_text": None,
        "time_precision": TimePrecision.EXACT_DATETIME,
        "is_relative_time": False,
        "parsed_due_date": None,
        "parsed_due_time": None,
        "deliverable_format": None,
        "submission_channel": None,
        "confidence": 0.96,
        "has_ambiguity": False,
        "ambiguity_reason": None,
    }
    base.update(overrides)
    return DdlPayload(**base)


def _ref(value: str = REFERENCE) -> datetime:
    return datetime.fromisoformat(value)


def _resolve(
    text: str,
    payload: DdlPayload,
    *,
    reference: str = REFERENCE,
    timezone_name: str = "Asia/Shanghai",
) -> tuple[DdlPayload, TimeResolution]:
    return DdlTimeParser().refine(
        payload,
        raw_text=text,
        reference_time=_ref(reference),
        user_timezone=timezone_name,
    )


def test_exact_datetime_computes_due_at_and_is_not_inferred() -> None:
    refined, resolution = _resolve(
        "10月25日 22:00 前交实验报告",
        _payload(
            raw_time_text="10月25日 22:00 前",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0),
        ),
    )

    assert refined.time_precision is TimePrecision.EXACT_DATETIME
    assert refined.parsed_due_date == date(2026, 10, 25)
    assert refined.parsed_due_time == time(22, 0)
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-25T22:00:00+08:00"
    assert resolution.time_inferred is False
    assert resolution.initial_status is DdlInitialStatus.ACTIVE
    assert refined.has_ambiguity is False


def test_date_only_uses_235959_sentinel_and_marks_time_inferred() -> None:
    refined, resolution = _resolve(
        "10月25日交实验报告",
        _payload(
            raw_time_text="10月25日",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.time_precision is TimePrecision.DATE_ONLY
    assert refined.parsed_due_time is None
    assert resolution.due_time is None
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-25T23:59:59+08:00"
    assert resolution.time_inferred is True
    assert resolution.initial_status is DdlInitialStatus.ACTIVE


def test_vague_never_has_due_at_and_is_pending_time() -> None:
    refined, resolution = _resolve(
        "请大家尽快提交实验报告",
        _payload(
            raw_time_text="尽快",
            time_precision=TimePrecision.VAGUE_OR_MISSING,
            has_ambiguity=True,
            ambiguity_reason="原文未提供明确截止时间",
        ),
    )

    assert refined.time_precision is TimePrecision.VAGUE_OR_MISSING
    assert refined.parsed_due_date is None
    assert refined.parsed_due_time is None
    assert resolution.due_at is None
    assert resolution.time_inferred is False
    assert resolution.initial_status is DdlInitialStatus.PENDING_TIME


def test_illegal_calendar_date_has_no_due_at_and_is_pending_time() -> None:
    refined, resolution = _resolve(
        "2月30日交实验报告",
        _payload(
            raw_time_text="2月30日",
            time_precision=TimePrecision.INVALID_OR_PAST,
            parsed_due_date=None,
            has_ambiguity=True,
            ambiguity_reason="日期不存在",
        ),
    )

    assert refined.time_precision is TimePrecision.INVALID_OR_PAST
    assert refined.parsed_due_date is None
    assert resolution.due_at is None
    assert resolution.initial_status is DdlInitialStatus.PENDING_TIME


def test_past_full_year_date_is_invalid_and_pending_confirm() -> None:
    refined, resolution = _resolve(
        "2026-10-01 22:00 前交实验报告",
        _payload(
            raw_time_text="2026-10-01 22:00 前",
            parsed_due_date=date(2026, 10, 1),
            parsed_due_time=time(22, 0),
        ),
    )

    assert refined.time_precision is TimePrecision.INVALID_OR_PAST
    assert refined.parsed_due_date == date(2026, 10, 1)
    assert resolution.due_at is not None
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM
    assert "早于消息基准时间" in (refined.ambiguity_reason or "")


def test_month_day_in_december_rolls_to_next_year_with_ambiguity() -> None:
    refined, resolution = _resolve(
        "1月5日交实验报告",
        _payload(
            raw_time_text="1月5日",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 1, 5),
        ),
        reference="2026-12-20T10:00:00+08:00",
    )

    assert refined.parsed_due_date == date(2027, 1, 5)
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2027-01-05T23:59:59+08:00"
    assert resolution.time_inferred is True
    assert refined.has_ambiguity is True
    assert "跨年顺延" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_same_month_past_date_is_not_rolled_to_next_year() -> None:
    refined, _ = _resolve(
        "10月1日 22:00 前交实验报告",
        _payload(
            raw_time_text="10月1日 22:00 前",
            parsed_due_date=date(2026, 10, 1),
            parsed_due_time=time(22, 0),
        ),
    )

    assert refined.time_precision is TimePrecision.INVALID_OR_PAST
    assert refined.parsed_due_date == date(2026, 10, 1)


def test_relative_tomorrow_is_resolved_in_user_timezone_not_host_timezone() -> None:
    # 2026-10-15T23:30Z == 2026-10-16 07:30 Asia/Shanghai，因此“明天”是 10-17。
    refined, resolution = _resolve(
        "明天下午5点前交实验报告",
        _payload(
            raw_time_text="明天下午5点前",
            parsed_due_date=date(2026, 10, 16),
            parsed_due_time=time(17, 0),
            is_relative_time=True,
        ),
        reference="2026-10-15T23:30:00+00:00",
    )

    assert refined.parsed_due_date == date(2026, 10, 17)
    assert refined.parsed_due_time == time(17, 0)
    assert refined.is_relative_time is True
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-17T17:00:00+08:00"
    assert resolution.initial_status is DdlInitialStatus.ACTIVE


def test_this_week_friday_uses_monday_first_natural_week() -> None:
    refined, _ = _resolve(
        "本周五下午5点前交实验报告",
        _payload(
            raw_time_text="本周五下午5点前",
            parsed_due_date=date(2026, 10, 16),
            parsed_due_time=time(17, 0),
            is_relative_time=True,
        ),
    )

    assert refined.parsed_due_date == date(2026, 10, 16)
    assert refined.parsed_due_time == time(17, 0)
    assert refined.is_relative_time is True


def test_next_monday_on_sunday_is_flagged_for_confirmation() -> None:
    refined, resolution = _resolve(
        "下周一上午9点前交实验报告",
        _payload(
            raw_time_text="下周一上午9点前",
            parsed_due_date=date(2026, 10, 19),
            parsed_due_time=time(9, 0),
            is_relative_time=True,
        ),
        reference="2026-10-18T10:00:00+08:00",
    )

    assert refined.parsed_due_date == date(2026, 10, 19)
    assert refined.has_ambiguity is True
    assert "下周一" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_early_morning_today_is_flagged_for_confirmation() -> None:
    refined, resolution = _resolve(
        "今天下午5点前交实验报告",
        _payload(
            raw_time_text="今天下午5点前",
            parsed_due_date=date(2026, 10, 15),
            parsed_due_time=time(17, 0),
            is_relative_time=True,
        ),
        reference="2026-10-15T02:30:00+08:00",
    )

    assert refined.parsed_due_date == date(2026, 10, 15)
    assert refined.has_ambiguity is True
    assert "凌晨" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_bare_weekday_after_it_passed_uses_next_occurrence_and_flags() -> None:
    refined, resolution = _resolve(
        "周五前交实验报告",
        _payload(
            raw_time_text="周五前",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 23),
            is_relative_time=True,
        ),
        reference="2026-10-17T10:00:00+08:00",  # 周六
    )

    assert refined.parsed_due_date == date(2026, 10, 23)
    assert refined.is_relative_time is True
    assert refined.has_ambiguity is True
    assert "未明确本周/下周" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_afternoon_period_maps_to_24_hour_without_ambiguity() -> None:
    refined, resolution = _resolve(
        "10月25日下午5点前交实验报告",
        _payload(
            raw_time_text="10月25日下午5点前",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(17, 0),
        ),
    )

    assert refined.parsed_due_time == time(17, 0)
    assert refined.has_ambiguity is False
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-25T17:00:00+08:00"


def test_bare_hour_keeps_model_time_when_consistent_and_flags() -> None:
    refined, resolution = _resolve(
        "10月25日 5点前交实验报告",
        _payload(
            raw_time_text="10月25日 5点前",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(17, 0),
        ),
    )

    assert refined.parsed_due_time == time(17, 0)
    assert refined.has_ambiguity is True
    assert "上午/下午" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_bare_hour_without_model_time_uses_24_hour_and_flags() -> None:
    refined, _ = _resolve(
        "10月25日 5点半前交实验报告",
        _payload(
            raw_time_text="10月25日 5点半前",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.parsed_due_time == time(5, 30)
    assert refined.has_ambiguity is True
    assert "上午/下午" in (refined.ambiguity_reason or "")


def test_exact_model_without_time_in_text_is_downgraded_to_date_only() -> None:
    refined, resolution = _resolve(
        "10月25日交实验报告",
        _payload(
            raw_time_text="10月25日",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0),
        ),
    )

    assert refined.time_precision is TimePrecision.DATE_ONLY
    assert refined.parsed_due_time is None
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-25T23:59:59+08:00"
    assert resolution.time_inferred is True
    assert "无法从原文复核" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_date_only_model_with_explicit_time_is_upgraded_to_exact() -> None:
    refined, resolution = _resolve(
        "10月25日 22:00 前交实验报告",
        _payload(
            raw_time_text="10月25日 22:00 前",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.time_precision is TimePrecision.EXACT_DATETIME
    assert refined.parsed_due_time == time(22, 0)
    assert resolution.time_inferred is False


def test_time_only_text_borrows_model_date() -> None:
    refined, resolution = _resolve(
        "22:00 前交实验报告",
        _payload(
            raw_time_text="22:00 前",
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.parsed_due_date == date(2026, 10, 25)
    assert refined.parsed_due_time == time(22, 0)
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-25T22:00:00+08:00"


def test_dst_nonexistent_local_time_is_flagged_once() -> None:
    refined, resolution = _resolve(
        "3月8日 2:30前交实验报告",
        _payload(
            raw_time_text="3月8日 2:30前",
            parsed_due_date=date(2026, 3, 8),
            parsed_due_time=time(2, 30),
        ),
        reference="2026-03-07T12:00:00-05:00",
        timezone_name="America/New_York",
    )

    assert refined.has_ambiguity is True
    assert "不存在" in (refined.ambiguity_reason or "")
    assert "出现两次" not in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_dst_ambiguous_local_time_is_flagged() -> None:
    refined, resolution = _resolve(
        "11月1日 1:30前交实验报告",
        _payload(
            raw_time_text="11月1日 1:30前",
            parsed_due_date=date(2026, 11, 1),
            parsed_due_time=time(1, 30),
        ),
        reference="2026-10-31T12:00:00-04:00",
        timezone_name="America/New_York",
    )

    assert refined.has_ambiguity is True
    assert "出现两次" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_invalid_timezone_is_rejected() -> None:
    with pytest.raises(NonRetryableAiError) as excinfo:
        _resolve("10月25日交", _payload(), timezone_name="Mars/Base")

    assert excinfo.value.error_code is AiErrorCode.VALIDATION_ERROR


def test_naive_reference_time_is_rejected() -> None:
    with pytest.raises(NonRetryableAiError) as excinfo:
        DdlTimeParser().refine(
            _payload(),
            raw_text="10月25日交",
            reference_time=datetime(2026, 10, 15, 11, 40),
            user_timezone="Asia/Shanghai",
        )

    assert excinfo.value.error_code is AiErrorCode.VALIDATION_ERROR


def test_low_confidence_is_pending_confirm() -> None:
    refined, resolution = _resolve(
        "10月25日 22:00 前交实验报告",
        _payload(
            raw_time_text="10月25日 22:00 前",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0),
            confidence=0.55,
        ),
    )

    assert refined.has_ambiguity is True
    assert "0.80" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_model_vague_is_not_upgraded_even_if_text_has_date() -> None:
    refined, resolution = _resolve(
        "10月25日左右交实验报告",
        _payload(
            raw_time_text="10月25日左右",
            time_precision=TimePrecision.VAGUE_OR_MISSING,
            parsed_due_date=date(2026, 10, 25),
            has_ambiguity=True,
            ambiguity_reason="模型判定时间模糊",
        ),
    )

    assert refined.time_precision is TimePrecision.VAGUE_OR_MISSING
    assert refined.parsed_due_date is None
    assert resolution.due_at is None
    assert resolution.initial_status is DdlInitialStatus.PENDING_TIME


def test_model_invalid_with_future_date_is_not_silently_corrected() -> None:
    refined, resolution = _resolve(
        "10月25日 22:00 前交实验报告",
        _payload(
            raw_time_text="10月25日 22:00 前",
            time_precision=TimePrecision.INVALID_OR_PAST,
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0),
            has_ambiguity=True,
            ambiguity_reason="模型判定时间非法",
        ),
    )

    assert refined.time_precision is TimePrecision.INVALID_OR_PAST
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_model_invalid_with_cross_year_roll_is_upgraded() -> None:
    refined, _ = _resolve(
        "1月5日交实验报告",
        _payload(
            raw_time_text="1月5日",
            time_precision=TimePrecision.INVALID_OR_PAST,
            parsed_due_date=date(2026, 1, 5),
            has_ambiguity=True,
            ambiguity_reason="模型判定时间已过去",
        ),
        reference="2026-12-20T10:00:00+08:00",
    )

    assert refined.time_precision is TimePrecision.DATE_ONLY
    assert refined.parsed_due_date == date(2027, 1, 5)
    assert "跨年顺延" in (refined.ambiguity_reason or "")


@pytest.mark.parametrize(
    ("text", "raw_time_text"),
    [
        ("下周交实验报告", "下周"),
        ("周末交实验报告", "周末"),
        ("月底交实验报告", "月底"),
    ],
)
def test_time_ranges_without_concrete_date_are_vague(
    text: str, raw_time_text: str
) -> None:
    refined, resolution = _resolve(
        text,
        _payload(
            raw_time_text=raw_time_text,
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 21),
        ),
    )

    assert refined.time_precision is TimePrecision.VAGUE_OR_MISSING
    assert refined.parsed_due_date is None
    assert refined.parsed_due_time is None
    assert refined.is_relative_time is True
    assert resolution.due_at is None
    assert resolution.initial_status is DdlInitialStatus.PENDING_TIME


def test_vague_qualifier_with_absolute_date_keeps_date_but_flags() -> None:
    refined, resolution = _resolve(
        "10月25日左右交实验报告",
        _payload(
            raw_time_text="10月25日左右",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.time_precision is TimePrecision.DATE_ONLY
    assert refined.parsed_due_date == date(2026, 10, 25)
    assert refined.has_ambiguity is True
    assert "模糊限定词" in (refined.ambiguity_reason or "")
    assert resolution.initial_status is DdlInitialStatus.PENDING_CONFIRM


def test_evening_midnight_shifts_to_next_day_and_flags() -> None:
    refined, resolution = _resolve(
        "10月25日晚上12点前交实验报告",
        _payload(
            raw_time_text="10月25日晚上12点前",
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.parsed_due_date == date(2026, 10, 26)
    assert refined.parsed_due_time == time(0, 0)
    assert resolution.due_at is not None
    assert resolution.due_at.isoformat() == "2026-10-26T00:00:00+08:00"
    assert refined.has_ambiguity is True
    assert "次日 00:00" in (refined.ambiguity_reason or "")


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("10月25日凌晨1点前交", time(1, 0)),
        ("10月25日中午1点前交", time(13, 0)),
        ("10月25日下午1点前交", time(13, 0)),
        ("10月25日晚上8点前交", time(20, 0)),
    ],
)
def test_period_mapping(text: str, expected: time) -> None:
    refined, _ = _resolve(
        text,
        _payload(
            raw_time_text=text,
            parsed_due_date=date(2026, 10, 25),
        ),
    )
    assert refined.parsed_due_time == expected


def test_zero_hour_without_period_is_unambiguous() -> None:
    refined, _ = _resolve(
        "10月25日 0点前交实验报告",
        _payload(
            raw_time_text="10月25日 0点前",
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.parsed_due_time == time(0, 0)
    assert refined.has_ambiguity is False


def test_multiple_absolute_dates_use_first_and_flag() -> None:
    refined, _ = _resolve(
        "10月25日交实验报告，11月1日截止补交",
        _payload(
            raw_time_text="10月25日交实验报告，11月1日截止补交",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2026, 10, 25),
        ),
    )

    assert refined.parsed_due_date == date(2026, 10, 25)
    assert refined.has_ambiguity is True
    assert "多个日期" in (refined.ambiguity_reason or "")


def test_time_fragment_is_preferred_over_full_raw_text() -> None:
    refined, _ = _resolve(
        "通知：10月20日开会；实验报告10月25日 22:00 前交",
        _payload(
            raw_time_text="10月25日 22:00 前",
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0),
        ),
    )

    assert refined.parsed_due_date == date(2026, 10, 25)
    assert refined.parsed_due_time == time(22, 0)


def test_cross_year_roll_failure_is_flagged() -> None:
    # 2028-12-20 收到“2月29日”：当年 2028-02-29 已过去，顺延到 2029 无效。
    refined, resolution = _resolve(
        "2月29日交实验报告",
        _payload(
            raw_time_text="2月29日",
            time_precision=TimePrecision.DATE_ONLY,
            parsed_due_date=date(2028, 2, 29),
        ),
        reference="2028-12-20T10:00:00+08:00",
    )

    assert refined.time_precision is TimePrecision.INVALID_OR_PAST
    assert refined.parsed_due_date == date(2028, 2, 29)
    assert refined.has_ambiguity is True
    assert "跨年顺延失败" in (refined.ambiguity_reason or "")
