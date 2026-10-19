"""模型探测接口的请求/响应契约（`04` §4.9.1 `API-INT-AI-001`）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class ModelEndpointPayload(BaseModel):
    """候选模型配置：由 Java 解密后在当次请求体内透传（内存态，不落盘、不写日志）。"""

    provider: str = Field(min_length=1, max_length=32)
    base_url: str = Field(min_length=8, max_length=512)
    api_key: str | None = None
    model_name: str = Field(min_length=1, max_length=128)


class ModelProbeRequest(BaseModel):
    user_id: str = Field(pattern=r"^\d{1,19}$")
    probe_type: Literal["CHAT", "EMBEDDING", "BOTH"]
    chat_config: ModelEndpointPayload | None = None
    embedding_config: ModelEndpointPayload | None = None


class ModelProbeData(BaseModel):
    chat_reachable: bool
    embedding_reachable: bool
    actual_dim: int | None = None
    latency_ms: int
