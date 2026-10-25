"""DDL 时间解析共享关键词与模式（TASK-701/702）。

`DdlExtractor` 与 `DdlTimeParser` 必须对“哪些表述属于模糊时间”“哪些是绝对日期”
保持一致；集中在本模块，避免两处常量漂移。
"""

from __future__ import annotations

import re

#: 原文中的模糊时间限定词。用于在模型误判为确定时间时做保守降级；
#: 不是完整的自然语言时间解析器。
VAGUE_TIME_MARKERS: tuple[str, ...] = (
    "下周左右",
    "尽快",
    "尽早",
    "近期",
    "最近",
    "稍后",
    "有空",
    "待定",
    "另行通知",
    "另行安排",
    "自行安排",
    "不确定",
    "未定",
    "左右",
    "前后",
    "大约",
    "大概",
    "再说",
    "回头",
)

#: 绝对日历日期（含年月日）。有它才允许保留“左右/前后”等限定词的日期。
ABSOLUTE_DATE_PATTERN = re.compile(
    r"\d{4}\s*[-/年]\s*\d{1,2}\s*[-/月]\s*\d{1,2}\s*日?"
    r"|\d{1,2}\s*月\s*\d{1,2}\s*[日号]"
    r"|\d{1,2}/\d{1,2}(?!\d)"
)


def find_vague_marker(raw_text: str) -> str | None:
    """返回原文中第一个命中的模糊时间词；没有则返回 `None`。"""
    for marker in VAGUE_TIME_MARKERS:
        if marker in raw_text:
            return marker
    return None


def contains_absolute_date(raw_text: str) -> bool:
    return ABSOLUTE_DATE_PATTERN.search(raw_text) is not None
