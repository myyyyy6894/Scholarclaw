"""Conservative internal text extraction contract; suggestions are never authoritative."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator


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
