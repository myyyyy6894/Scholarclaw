"""Recognise explicit, common timetable notation without inventing missing data.

One non-empty line becomes one editable suggestion. This intentionally does not
interpret free-form instructions, call a model, or assume a school bell schedule.
"""

from __future__ import annotations

import re
from datetime import time

from app.schemas.schedule_extract import ScheduleSuggestion

_WEEKDAY = re.compile(r"(?:星期|礼拜|周)[ \t]{0,8}([一二三四五六日天1-7])(?!\d)")
_PERIOD = re.compile(r"(?<!\d)第?[ \t]{0,8}(\d{1,2})[ \t]{0,8}(?:[-~～至—][ \t]{0,8}(\d{1,2}))?[ \t]{0,8}节")
_WEEK = re.compile(
    r"(?<!\d)第?[ \t]{0,8}(\d{1,2}(?:[ \t]{0,8}[-~～至—][ \t]{0,8}\d{1,2})?"
    r"(?:[ \t]{0,8}[,，、][ \t]{0,8}\d{1,2}(?:[ \t]{0,8}[-~～至—][ \t]{0,8}\d{1,2})?){0,52})"
    r"[ \t]{0,8}周(?:[ \t]{0,8}[（(]?[ \t]{0,8}(单|双)(?:周)?[ \t]{0,8}[）)]?)?"
)
_CLOCK = re.compile(r"(?<!\d)(\d{1,2}:\d{2}(?::\d{2})?)[ \t]{0,8}[-~～至—][ \t]{0,8}(\d{1,2}:\d{2}(?::\d{2})?)(?!\d)")
_LABEL = re.compile(r"(?:教师|老师|教室|地点)[ \t]{0,8}[:：][ \t]{0,8}")
_WEEKDAY_NUMBERS = {name: index for index, name in enumerate("一二三四五六日", 1)} | {"天": 7}


def _only_match(pattern: re.Pattern[str], text: str, issues: list[str], label: str) -> re.Match[str] | None:
    matches = list(pattern.finditer(text))
    if len(matches) > 1:
        issues.append(f"检测到多个{label}，请手动核对")
        return None
    return matches[0] if matches else None


def _read_weeks(match: re.Match[str], max_week: int) -> list[int] | None:
    weeks: set[int] = set()
    for part in re.split(r"[,，、]", match.group(1)):
        ends = re.split(r"[-~～至—]", part)
        start = int(ends[0].strip())
        end = int(ends[-1].strip())
        if start < 1 or end > max_week or start > end:
            return None
        weeks.update(range(start, end + 1))
    odd_even = match.group(2)
    if odd_even:
        weeks = {week for week in weeks if week % 2 == (1 if odd_even == "单" else 0)}
    return sorted(weeks) or None


def _read_labels(text: str, issues: list[str]) -> tuple[str | None, str | None]:
    matches = list(_LABEL.finditer(text))
    found: dict[str, list[str]] = {"teacher": [], "room": []}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        for pattern in (_WEEKDAY, _PERIOD, _WEEK, _CLOCK):
            following = pattern.search(text, match.end(), end)
            if following:
                end = min(end, following.start())
        separator = re.search(r"[,，;；|｜]", text[match.end():end])
        if separator:
            end = match.end() + separator.start()
        value = text[match.end():end].strip(" \t,，;；|｜")
        kind = "teacher" if match.group().startswith(("教师", "老师")) else "room"
        if value:
            found[kind].append(value)
    result: list[str | None] = []
    for kind, label, limit in (("teacher", "教师", 100), ("room", "教室", 200)):
        values = found[kind]
        if len(values) > 1 or (values and len(values[0]) > limit):
            issues.append(f"{label}内容不明确，请手动核对")
            result.append(None)
        else:
            result.append(values[0] if values else None)
    return result[0], result[1]


def extract_text(raw_text: str, week_count: int) -> list[ScheduleSuggestion]:
    rows: list[ScheduleSuggestion] = []
    for raw_line in raw_text.splitlines():
        if not raw_line.strip():
            continue
        line = raw_line.strip()
        issues: list[str] = []
        day_match = _only_match(_WEEKDAY, line, issues, "星期")
        period_match = _only_match(_PERIOD, line, issues, "节次")
        week_match = _only_match(_WEEK, line, issues, "周次")
        # A very long list must not be silently interpreted as its last 53 entries.
        if week_match and re.search(r"\d[ \t]{0,8}[,，、][ \t]{0,8}$", line[:week_match.start()]):
            issues.append("周次列表过长或不明确，请手动核对")
            week_match = None
        clock_match = _only_match(_CLOCK, line, issues, "时间段")
        relevant = [m.start() for pattern in (_WEEKDAY, _PERIOD, _WEEK, _CLOCK, _LABEL)
                    for m in pattern.finditer(line)]
        title = re.sub(r"^(?:课程|课程名称)[ \t]{0,8}[:：][ \t]{0,8}", "", line[:min(relevant)]).strip(" \t,，;；|｜") if relevant else ""
        if not title or len(title) > 200:
            title = None
            issues.append("课程名称待填写或校对")
        weekday = None
        if day_match:
            day = day_match.group(1)
            weekday = int(day) if day.isdigit() else _WEEKDAY_NUMBERS[day]
        else:
            issues.append("星期待填写或校对")
        start_period = end_period = None
        if period_match:
            start_period = int(period_match.group(1))
            end_period = int(period_match.group(2) or start_period)
            if not 1 <= start_period <= end_period <= 30:
                start_period = end_period = None
                issues.append("节次超出范围或顺序错误")
        start_time = end_time = None
        if clock_match:
            try:
                start = time(*(int(part) for part in clock_match.group(1).split(":")))
                end = time(*(int(part) for part in clock_match.group(2).split(":")))
                if start >= end:
                    raise ValueError("end before start")
                start_time, end_time = start.isoformat(), end.isoformat()
            except ValueError:
                issues.append("时间段无效，请手动校对")
        if start_period is None and start_time is None:
            issues.append("节次或起止时间待填写")
        weeks = _read_weeks(week_match, week_count) if week_match else None
        if weeks is None:
            issues.append("教学周次缺失或超出学期范围，请手动校对")
        teacher, room = _read_labels(line, issues)
        rows.append(ScheduleSuggestion(source_text=raw_line, title=title, teacher=teacher, room=room,
                                       weekday=weekday, start_period=start_period, end_period=end_period,
                                       start_time=start_time, end_time=end_time, weeks=weeks or [], issues=issues))
    return rows
