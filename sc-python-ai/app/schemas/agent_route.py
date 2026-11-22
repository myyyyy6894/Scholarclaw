"""意图路由的响应契约（`04` §4.9.2 `API-INT-AI-002`、`06` TASK-706）。

与 `ddl_extract.DdlExtractRequest` 分离的原因：请求体同时服务两种模式，而响应里的
`intent` 不再固定为 `DDL_EXTRACT`——引入显式枚举避免把“意图”写成裸字符串。

约定：

* `intent` 只允许 `FR-IM-002` 列出的 5 个 MVP 意图；后续版本意图（`CODE_SCAFFOLD`
  等）不在 MVP 范围，出现即视为模型输出非法。
* `ddl_payload` 仅在意图为 `DDL_EXTRACT` 且实际完成提取时非空；低置信度
  （`< 0.65`）或非 DDL 意图一律为 `null`，**不得**用空对象伪装成提取成功。
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.ddl_extract import DdlPayload
from app.schemas.probe import ModelEndpointPayload


class AgentIntent(StrEnum):
    """MVP 激活意图（`01` FR-IM-002）。"""

    DDL_EXTRACT = "DDL_EXTRACT"
    TASK_MANAGE = "TASK_MANAGE"
    KB_INGEST = "KB_INGEST"
    KB_QA = "KB_QA"
    CHITCHAT_HELP = "CHITCHAT_HELP"


class RouteAndExtractData(BaseModel):
    """`POST /internal/ai/v1/agent/route-and-extract` 的 `data` 节点。"""

    intent: AgentIntent
    intent_confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    ddl_payload: DdlPayload | None = None


class NaturalAction(StrEnum):
    SCHEDULE_QUERY = "SCHEDULE_QUERY"
    REMINDER_GET = "REMINDER_GET"
    REMINDER_SET = "REMINDER_SET"
    DDL_LIST = "DDL_LIST"
    DDL_QUERY = "DDL_QUERY"
    DDL_RESCHEDULE = "DDL_RESCHEDULE"
    DDL_DONE = "DDL_DONE"
    DDL_CANCEL = "DDL_CANCEL"
    DDL_CONFIRM = "DDL_CONFIRM"
    DDL_EXTRACT = "DDL_EXTRACT"
    KB_QA = "KB_QA"
    KB_INGEST = "KB_INGEST"
    CLARIFY = "CLARIFY"
    HELP = "HELP"
    UNSUPPORTED = "UNSUPPORTED"
    IGNORE = "IGNORE"


class NaturalArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    task_hint: str | None = Field(default=None, max_length=120)
    date_expression: str | None = Field(default=None, max_length=32)
    time_expression: str | None = Field(default=None, max_length=32)
    period_expression: str | None = Field(default=None, max_length=24)
    schedule_view: Literal["LIST", "FIRST", "NEXT", "CONFLICTS"] | None = None
    reminder_enabled: bool | None = None


class NaturalRouteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    user_id: str = Field(pattern=r"^[0-9]{1,19}$")
    raw_text: str = Field(min_length=1, max_length=4000)
    chat_config: ModelEndpointPayload


class NaturalRouteData(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    action: NaturalAction
    arguments: NaturalArguments = Field(default_factory=NaturalArguments)
    confidence: float = Field(ge=0.0, le=1.0, allow_inf_nan=False)
    missing: list[Literal["task", "date", "exact_time", "scope"]] = Field(default_factory=list, max_length=4)
    evidence: list[Annotated[str, Field(min_length=1, max_length=120)]] = Field(default_factory=list, max_length=6)
