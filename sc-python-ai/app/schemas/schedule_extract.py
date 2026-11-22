"""Conservative internal text extraction contract; suggestions are never authoritative."""

from __future__ import annotations
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.probe import ModelEndpointPayload


class ScheduleTextExtractRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(pattern=r"^[0-9]{1,19}$")
    raw_text: str = Field(min_length=1, max_length=20_000)
    week_count: int = Field(strict=True, ge=1, le=53)

    @field_validator("raw_text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        lines = [line for line in value.splitlines() if line.strip()]
        if not lines:
            raise ValueError("课表原文不能为空")
        if len(lines) > 200:
            raise ValueError("每次最多处理200行，请拆分后重试")
        return value


class ScheduleSuggestion(BaseModel):
    source_text: str
    title: str | None = None
    teacher: str | None = None
    room: str | None = None
    weekday: int | None = None
    start_period: int | None = None
    end_period: int | None = None
    start_time: str | None = None
    end_time: str | None = None
    weeks: list[int] = Field(default_factory=list)
    issues: list[str] = Field(default_factory=list)


class ScheduleTextExtractData(BaseModel):
    rows: list[ScheduleSuggestion]


class ScheduleImageExtractRequest(BaseModel):
    """Java-owned, sanitized image identifier; never an arbitrary path or URL."""

    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(pattern=r"^[0-9]{1,19}$")
    source_name: str = Field(pattern=r"^[0-9a-f]{32}\.(png|jpg)$")
    week_count: int = Field(strict=True, ge=1, le=53)
    chat_config: ModelEndpointPayload


class ScheduleFileExtractRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(pattern=r"^[0-9]{1,19}$")
    source_format: Literal["XLSX", "CSV", "DOCX"]
    content: str = Field(min_length=1, max_length=2_796_204)
    table_index: int | None = Field(default=None, ge=1, le=20)
    header_row: int | None = Field(default=None, ge=1, le=400)
    mapping: dict[str, Annotated[int, Field(strict=True, ge=1, le=64)]] | None = Field(default=None, max_length=12)


class ScheduleFileSuggestion(ScheduleSuggestion):
    source_ref: str
    segment_id: str
    section_code: str | None = None
    state: Literal["COURSE", "UNPARSED", "UNSCHEDULED"]


class ScheduleTableSummary(BaseModel):
    index: int
    label: str
    layout: Literal["GRID", "DETAIL", "UNKNOWN"]


class ScheduleFileExtractData(BaseModel):
    rows: list[ScheduleFileSuggestion]
    tables: list[ScheduleTableSummary]
    minimum_weeks: int


class ScheduleModelFragment(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    segment_id: str = Field(max_length=100)
    title: str | None = Field(max_length=200)
    section_code: str | None = Field(max_length=64)
    teacher: str | None = Field(max_length=100)
    room: str | None = Field(max_length=200)
    weekday: int | None = Field(ge=1, le=7)
    start_period: int | None = Field(ge=1, le=30)
    end_period: int | None = Field(ge=1, le=30)
    start_time: str | None = Field(max_length=8)
    end_time: str | None = Field(max_length=8)
    weeks: list[Annotated[int, Field(ge=1, le=53)]] = Field(max_length=53)


class ScheduleFileAssistRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    user_id: str = Field(pattern=r"^[0-9]{1,19}$")
    rows: list[ScheduleModelFragment] = Field(min_length=1, max_length=200)
    chat_config: ModelEndpointPayload


class ScheduleFileAssistData(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    rows: list[ScheduleModelFragment] = Field(min_length=1, max_length=200)
