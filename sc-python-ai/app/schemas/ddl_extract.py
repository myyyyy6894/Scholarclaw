"""DDL 结构化提取的请求/响应契约（`04` `API-INT-AI-002`、`06` TASK-701）。

范围边界：

* 本模块只定义**提取结果**（课程、事项、原始时间文本、四级时间精度、已解析日期/时刻、
  交付格式、提交渠道、置信度、歧义状态）。
* 不包含 `due_at`、`time_inferred`、`follow_up_at`、任务状态等 Java 业务字段——这些
  属于 TASK-702/703 的时间换算与持久化职责，Python 不得提前写入或伪造。

字段命名与 `04` §4.9.2 严格对齐（`snake_case`）；可选字段缺失时一律为 `null`，
不使用空字符串或编造值。
"""

from __future__ import annotations

from datetime import date, datetime, time
from enum import StrEnum
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.probe import ModelEndpointPayload

#: 内部接口单次原文上限（`04` §4.9.2：`raw_text` 1~4000 字符）。
MAX_RAW_TEXT_LENGTH = 4000
#: 候选课程数量上限：仅用于防御异常大请求，不改变契约语义。
MAX_CANDIDATE_COURSES = 200
#: 与 `t_course_namespace.course_name`（VARCHAR(128)）对齐。
MAX_COURSE_NAME_LENGTH = 128
#: 与 `t_ddl_task.task_title`（VARCHAR(255)）对齐。
MAX_TASK_TITLE_LENGTH = 255
#: 与 `t_ddl_task.raw_time_text`（VARCHAR(128)）对齐。
MAX_RAW_TIME_TEXT_LENGTH = 128
#: 与 `t_ddl_task.deliverable_format` / `submission_channel`（VARCHAR(255)）对齐。
MAX_DETAIL_LENGTH = 255
#: 歧义原因仅作说明，不应无限增长。
MAX_AMBIGUITY_REASON_LENGTH = 512


class ExtractionMode(StrEnum):
    """`04` §4.9.2 的执行模式。"""

    FORCE_DDL_EXTRACT = "FORCE_DDL_EXTRACT"
    AUTO_ROUTE = "AUTO_ROUTE"


class TimePrecision(StrEnum):
    """四级时间精度（`01` FR-DDL-001 / `03` §4.5）。"""

    EXACT_DATETIME = "EXACT_DATETIME"
    DATE_ONLY = "DATE_ONLY"
    VAGUE_OR_MISSING = "VAGUE_OR_MISSING"
    INVALID_OR_PAST = "INVALID_OR_PAST"


class DdlExtractRequest(BaseModel):
    """`POST /internal/ai/v1/agent/route-and-extract` 请求体。"""

    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(pattern=r"^\d{1,19}$")
    mode: ExtractionMode
    raw_text: str = Field(min_length=1, max_length=MAX_RAW_TEXT_LENGTH)
    reference_time: datetime
    user_timezone: str = Field(min_length=1, max_length=64)
    candidate_courses: list[str] = Field(
        default_factory=list, max_length=MAX_CANDIDATE_COURSES
    )
    chat_config: ModelEndpointPayload

    @field_validator("raw_text")
    @classmethod
    def _raw_text_must_not_be_blank(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("raw_text 不能为空白字符")
        return stripped

    @field_validator("reference_time", mode="before")
    @classmethod
    def _reference_time_must_be_iso_string(cls, value: object) -> object:
        """只接受 ISO-8601 字符串，拒绝数字时间戳等隐式形式。"""
        if not isinstance(value, str):
            raise ValueError("reference_time 必须为带时区的 ISO-8601 字符串")
        return value

    @field_validator("reference_time")
    @classmethod
    def _reference_time_must_have_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("reference_time 必须带时区偏移量")
        return value

    @field_validator("user_timezone")
    @classmethod
    def _user_timezone_must_be_iana(cls, value: str) -> str:
        name = value.strip()
        try:
            ZoneInfo(name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("user_timezone 必须为合法 IANA 时区名") from exc
        return name

    @field_validator("candidate_courses")
    @classmethod
    def _normalize_candidate_courses(cls, values: list[str]) -> list[str]:
        seen: set[str] = set()
        normalized: list[str] = []
        for raw in values:
            name = raw.strip()
            if not name:
                raise ValueError("candidate_courses 不能包含空白项")
            if len(name) > MAX_COURSE_NAME_LENGTH:
                raise ValueError("candidate_courses 单项超过 128 字符")
            if name in seen:
                continue
            seen.add(name)
            normalized.append(name)
        return normalized


class DdlPayload(BaseModel):
    """`ddl_payload`：保守提取结果。"""

    course_name: str | None = Field(default=None, max_length=MAX_COURSE_NAME_LENGTH)
    task_title: str | None = Field(default=None, max_length=MAX_TASK_TITLE_LENGTH)
    raw_time_text: str | None = Field(default=None, max_length=MAX_RAW_TIME_TEXT_LENGTH)
    time_precision: TimePrecision
    is_relative_time: bool
    parsed_due_date: date | None = None
    parsed_due_time: time | None = None
    deliverable_format: str | None = Field(default=None, max_length=MAX_DETAIL_LENGTH)
    submission_channel: str | None = Field(default=None, max_length=MAX_DETAIL_LENGTH)
    confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    has_ambiguity: bool
    ambiguity_reason: str | None = Field(
        default=None, max_length=MAX_AMBIGUITY_REASON_LENGTH
    )
