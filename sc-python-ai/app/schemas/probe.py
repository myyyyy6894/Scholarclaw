"""模型探测接口的请求/响应契约（`04` §4.9.1 `API-INT-AI-001`）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, model_validator

CHAT_PROVIDERS = frozenset({"OPENAI_COMPATIBLE", "DEEPSEEK", "ZHIPU", "OLLAMA"})
#: Embedding 不允许 DEEPSEEK（与 Java 保存 DTO、`03` 的 Provider 集合一致）。
EMBEDDING_PROVIDERS = frozenset({"OPENAI_COMPATIBLE", "ZHIPU", "OLLAMA"})


class ModelEndpointPayload(BaseModel):
    """候选模型配置：由 Java 解密后在当次请求体内透传（内存态，不落盘、不写日志）。"""

    provider: str = Field(min_length=1, max_length=32)
    base_url: str = Field(min_length=8, max_length=512)
    #: 与 Java DTO 的 @Size(max=512) / ASCII 规则对齐；加密后的列宽校验由 Java 在写库前完成。
    api_key: str | None = Field(default=None, max_length=512, pattern=r"^[\x21-\x7E]+$")
    model_name: str = Field(min_length=1, max_length=128)


class ModelProbeRequest(BaseModel):
    user_id: str = Field(pattern=r"^\d{1,19}$")
    probe_type: Literal["CHAT", "EMBEDDING", "BOTH"]
    chat_config: ModelEndpointPayload | None = None
    embedding_config: ModelEndpointPayload | None = None

    @model_validator(mode="after")
    def _validate_provider_by_probe_type(self) -> "ModelProbeRequest":
        """按 probe_type 校验 Provider，避免“探测允许、保存不允许”的规则漂移。"""
        if self.probe_type in {"CHAT", "BOTH"} and self.chat_config is not None:
            if self.chat_config.provider.upper() not in CHAT_PROVIDERS:
                raise ValueError("chat_config.provider 取值非法")
        if self.probe_type in {"EMBEDDING", "BOTH"} and self.embedding_config is not None:
            if self.embedding_config.provider.upper() not in EMBEDDING_PROVIDERS:
                raise ValueError("embedding_config.provider 取值非法（不允许 DEEPSEEK）")
        return self


class ModelProbeData(BaseModel):
    chat_reachable: bool
    embedding_reachable: bool
    actual_dim: int | None = None
    latency_ms: int
