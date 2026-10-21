"""模型探测接口的请求/响应契约（`04` §4.9.1 `API-INT-AI-001`）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, model_validator

CHAT_PROVIDERS = frozenset({"OPENAI_COMPATIBLE", "DEEPSEEK", "ZHIPU", "OLLAMA"})
#: Embedding 不允许 DEEPSEEK（与 Java 保存 DTO、`03` 的 Provider 集合一致）。
EMBEDDING_PROVIDERS = frozenset({"OPENAI_COMPATIBLE", "ZHIPU", "OLLAMA"})


class ModelEndpointPayload(BaseModel):
    """候选模型配置：由 Java 解密后在当次请求体内透传（内存态，不落盘、不写日志）。"""

    #: 固定枚举，严格大小写：不接受 `deepseek` 这类未规范值（与 Java DTO 对齐）。
    provider: Literal["OPENAI_COMPATIBLE", "DEEPSEEK", "ZHIPU", "OLLAMA"]
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
    def _validate_required_configs_and_providers(self) -> "ModelProbeRequest":
        """条件必填与 Provider 规则必须在进入探测前完成，避免任何出站或后台任务。"""
        if self.probe_type in {"CHAT", "BOTH"} and self.chat_config is None:
            raise ValueError("chat_config 为必填项（probe_type 包含 CHAT 时）")
        if self.probe_type in {"EMBEDDING", "BOTH"} and self.embedding_config is None:
            raise ValueError("embedding_config 为必填项（probe_type 包含 EMBEDDING 时）")

        if self.chat_config is not None and self.chat_config.provider not in CHAT_PROVIDERS:
            raise ValueError("chat_config.provider 取值非法")
        if self.embedding_config is not None and self.embedding_config.provider not in EMBEDDING_PROVIDERS:
            raise ValueError("embedding_config.provider 取值非法（不允许 DEEPSEEK）")
        return self


class ModelProbeData(BaseModel):
    chat_reachable: bool
    embedding_reachable: bool
    actual_dim: int | None = None
    latency_ms: int
