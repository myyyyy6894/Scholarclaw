"""模型连通性探测与真实向量维度获取（`04` §4.9.1 `API-INT-AI-001`、`02` §15.1 阶段 3）。

设计要点：

* **不重试**：探测的目的是"立刻反馈配置错误"，重试只会让用户多等（`04` §6.2 明确 0 次重试）；
  网关默认就是 `RetryPolicy.no_retry()`，这里不需要特殊处理；
* **不吃透响应体**：只从成功响应里取"是否可达"与 `data[0].embedding` 的长度，
  上游返回的错误正文一律不向外透传（可能回显凭据）；
* **所有出站都经 `SsrfSafeModelGateway`**：SSRF 校验、禁重定向、2 MiB 上限、错误分类与
  脱敏日志统一收敛在网关上（TASK-301）；
* **CHAT 只发一条 1 token 的最小请求**：够证明鉴权、模型名、连通性，不浪费用户的额度。
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.services.gateway import ssrf_guard
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import SsrfSafeModelGateway

#: 兼容既有调用方（内部接口与测试）的旧名字。
ModelEndpointConfig = ModelEndpoint

PROBE_CHAT_PATH = "/chat/completions"
PROBE_EMBEDDING_PATH = "/embeddings"
CHAT_PING_MESSAGE = "ping"
EMBEDDING_PING_INPUT = "ping"


@dataclass(frozen=True)
class ProbeOutcome:
    chat_reachable: bool
    embedding_reachable: bool
    actual_dim: int | None
    latency_ms: int


async def probe_models(
    settings: Settings,
    probe_type: str,
    chat_config: ModelEndpointConfig | None,
    embedding_config: ModelEndpointConfig | None,
) -> ProbeOutcome:
    """执行 CHAT / EMBEDDING / BOTH 探测。

    :raises BaseUrlRejectedError: 出站地址未通过 SSRF 校验，或上游返回 3xx（`E-SYS-02`）
    :raises UpstreamModelAuthError: 上游 401/403/404
    :raises UpstreamModelError: 上游 5xx / 连接失败 / 响应结构不可解析
    :raises UpstreamTimeoutError: 连接或读取超时
    """
    probe_read_timeout = min(
        settings.model_read_timeout_seconds,
        ssrf_guard.DEFAULT_PROBE_READ_TIMEOUT_SECONDS,
    )
    started = time.monotonic()
    chat_reachable = False
    embedding_reachable = False
    actual_dim: int | None = None

    async with SsrfSafeModelGateway(settings, read_timeout=probe_read_timeout) as gateway:
        if probe_type in {"CHAT", "BOTH"}:
            chat_reachable = await _probe_chat(gateway, _require(chat_config, "chat_config"))
        if probe_type in {"EMBEDDING", "BOTH"}:
            actual_dim = await _probe_embedding(
                gateway, _require(embedding_config, "embedding_config"))
            embedding_reachable = actual_dim > 0

    return ProbeOutcome(
        chat_reachable=chat_reachable,
        embedding_reachable=embedding_reachable,
        actual_dim=actual_dim,
        latency_ms=int((time.monotonic() - started) * 1000),
    )


__all__ = [
    "ModelEndpointConfig",
    "ProbeOutcome",
    "probe_models",
]


def _require(config: ModelEndpointConfig | None, field_name: str) -> ModelEndpointConfig:
    if config is None:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR, f"{field_name} 为必填项（probe_type 包含对应类型时）。")
    return config


async def _probe_chat(gateway: SsrfSafeModelGateway, config: ModelEndpointConfig) -> bool:
    body = {
        "model": config.model_name,
        "messages": [{"role": "user", "content": CHAT_PING_MESSAGE}],
        "max_tokens": 1,
        "stream": False,
    }
    payload = await gateway.post_json(config, PROBE_CHAT_PATH, body)
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不符合 OpenAI 兼容对话接口约定。")
    return True


async def _probe_embedding(gateway: SsrfSafeModelGateway, config: ModelEndpointConfig) -> int:
    body = {"model": config.model_name, "input": EMBEDDING_PING_INPUT}
    payload = await gateway.post_json(config, PROBE_EMBEDDING_PATH, body)
    vectors = payload.get("data")
    if not isinstance(vectors, list) or not vectors:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不符合 OpenAI 兼容向量接口约定。")
    embedding = vectors[0].get("embedding") if isinstance(vectors[0], dict) else None
    if not isinstance(embedding, list) or not embedding:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游向量接口未返回有效向量。")
    return len(embedding)
