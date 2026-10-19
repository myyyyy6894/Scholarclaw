"""模型连通性探测与真实向量维度获取（`04` §4.9.1 `API-INT-AI-001`、`02` §15.1 阶段 3）。

设计要点：

* **不重试**：探测的目的是"立刻反馈配置错误"，重试只会让用户多等（`04` §6.2 明确 0 次重试）；
* **不吃透响应体**：只从成功响应里取"是否可达"与 `data[0].embedding` 的长度，
  上游返回的错误正文一律不向外透传（可能回显凭据）；
* **出站前二次 SSRF 校验**（:mod:`app.services.gateway.ssrf_guard`），并在客户端上
  `follow_redirects=False` + `trust_env=False`（复用 `transport.build_async_client`）；
* **CHAT 只发一条 1 token 的最小请求**：够证明鉴权、模型名、连通性，不浪费用户的额度。
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import httpx

from app.core.config import Settings
from app.core.exceptions import (
    AiEngineError,
    AiErrorCode,
    NonRetryableAiError,
)
from app.core.logging import get_logger
from app.services.gateway import ssrf_guard
from app.services.gateway.transport import (
    build_async_client,
    classify_upstream_failure,
    safe_url_for_log,
)

logger = get_logger(__name__)

PROBE_CHAT_PATH = "/chat/completions"
PROBE_EMBEDDING_PATH = "/embeddings"
CHAT_PING_MESSAGE = "ping"
EMBEDDING_PING_INPUT = "ping"


@dataclass(frozen=True)
class ModelEndpointConfig:
    """候选模型配置（Java 解密后经内部请求体透传的那一份，内存态、不落盘）。"""

    provider: str
    base_url: str
    api_key: str | None
    model_name: str


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

    :raises BaseUrlRejectedError: 出站地址未通过 SSRF 校验（`E-SYS-02`）
    :raises UpstreamModelAuthError: 上游 401/403/404
    :raises UpstreamModelError: 上游 5xx / 连接失败 / 响应结构不可解析
    :raises UpstreamTimeoutError: 连接或读取超时
    """
    read_timeout = min(settings.model_read_timeout_seconds, ssrf_guard.DEFAULT_PROBE_READ_TIMEOUT_SECONDS)
    started = time.monotonic()
    chat_reachable = False
    embedding_reachable = False
    actual_dim: int | None = None

    async with build_async_client(
        connect_timeout=settings.model_connect_timeout_seconds,
        read_timeout=read_timeout,
    ) as client:
        if probe_type in {"CHAT", "BOTH"}:
            chat_reachable = await _probe_chat(settings, client, _require(chat_config, "chat_config"))
        if probe_type in {"EMBEDDING", "BOTH"}:
            actual_dim = await _probe_embedding(
                settings, client, _require(embedding_config, "embedding_config"))
            embedding_reachable = actual_dim > 0

    return ProbeOutcome(
        chat_reachable=chat_reachable,
        embedding_reachable=embedding_reachable,
        actual_dim=actual_dim,
        latency_ms=int((time.monotonic() - started) * 1000),
    )


def _require(config: ModelEndpointConfig | None, field_name: str) -> ModelEndpointConfig:
    if config is None:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR, f"{field_name} 为必填项（probe_type 包含对应类型时）。")
    return config


async def _probe_chat(settings: Settings, client: httpx.AsyncClient, config: ModelEndpointConfig) -> bool:
    body = {
        "model": config.model_name,
        "messages": [{"role": "user", "content": CHAT_PING_MESSAGE}],
        "max_tokens": 1,
        "stream": False,
    }
    response = await _post(settings, client, config, PROBE_CHAT_PATH, body)
    payload = _json(response)
    choices = payload.get("choices")
    if not isinstance(choices, list) or not choices:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不符合 OpenAI 兼容对话接口约定。")
    return True


async def _probe_embedding(settings: Settings, client: httpx.AsyncClient,
                           config: ModelEndpointConfig) -> int:
    body = {"model": config.model_name, "input": EMBEDDING_PING_INPUT}
    response = await _post(settings, client, config, PROBE_EMBEDDING_PATH, body)
    payload = _json(response)
    vectors = payload.get("data")
    if not isinstance(vectors, list) or not vectors:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不符合 OpenAI 兼容向量接口约定。")
    embedding = vectors[0].get("embedding") if isinstance(vectors[0], dict) else None
    if not isinstance(embedding, list) or not embedding:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游向量接口未返回有效向量。")
    return len(embedding)


async def _post(settings: Settings, client: httpx.AsyncClient, config: ModelEndpointConfig,
                path: str, body: dict) -> httpx.Response:
    ssrf_guard.validate_outbound_url(
        config.base_url,
        allow_local=settings.allow_local_model_base_url,
        allowed_origins=settings.local_model_allowed_origins,
    )
    url = config.base_url.rstrip("/") + path
    headers = {"Content-Type": "application/json"}
    if config.api_key:
        headers["Authorization"] = f"Bearer {config.api_key}"
    logger.info("模型探测出站 url=%s provider=%s model=%s",
                safe_url_for_log(url), config.provider, config.model_name)
    try:
        response = await client.post(url, json=body, headers=headers)
    except httpx.TimeoutException as exc:
        raise _timeout() from exc
    except httpx.HTTPError as exc:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "无法连接上游模型服务，请检查 Base URL 与网络。") from exc
    if 300 <= response.status_code < 400:
        # 重定向视为拒绝：既防 SSRF 绕行，也避免把 302 当成功
        raise NonRetryableAiError(AiErrorCode.E_SYS_02, "上游返回重定向，已按安全策略拒绝。")
    if response.status_code >= 400:
        raise classify_upstream_failure(response.status_code)
    return response


def _timeout() -> AiEngineError:
    from app.core.exceptions import UpstreamTimeoutError
    return UpstreamTimeoutError()


def _json(response: httpx.Response) -> dict:
    try:
        payload = response.json()
    except ValueError as exc:
        raise NonRetryableAiError(
            AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回的不是合法 JSON。") from exc
    if not isinstance(payload, dict):
        raise NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不可识别。")
    return payload
