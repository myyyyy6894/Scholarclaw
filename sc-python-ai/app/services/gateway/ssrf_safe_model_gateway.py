"""受控模型网关（`06` TASK-301、`02` §10.3、`04` §4.9.1 / §6.2）。

这是 Python 侧**唯一**允许发起外部模型 HTTP 请求的入口。职责：

* **每次调用前校验 URL**：调用 :mod:`app.services.gateway.ssrf_guard` 做 DNS 解析后的
  IP 校验（硬拒绝内部服务名/端口/元数据地址；白名单只豁免本地/私有地址与 http）；
* **固定安全传输**：`follow_redirects=False`、`trust_env=False`、连接/读取超时；
* **响应上限**：流式累计 2 MiB，先拒绝异常大的 `Content-Length`，不把不受信任的响应
  整块读进内存（`NFR-SEC-05`）；
* **错误分类**：按 `04` 第 7 章映射为确定性错误码，绝不把上游响应体原文带出；
* **重试显式化**：默认 **0 次**（探测必须立即反馈配置错误）；业务调用可显式传
  `RetryPolicy.standard()`（429/502/503/504，1s → 3s，最多 2 次，`NFR-REL-01`）；
* **日志脱敏**：只记录去掉查询串的 URL、provider/model 与尝试次数；**绝不记录**
  API Key、Authorization 头或请求/响应体。

**残余风险（不得宣称已完全防住 DNS rebinding）：** 校验在出站前解析一次 DNS，httpx 建立
连接时会再次解析；两次解析之间域名仍可能被切换为内网地址。当前未实现 IP 钉扎。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import Settings
from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelError,
    UpstreamTimeoutError,
)
from app.core.logging import get_logger
from app.services.gateway import ssrf_guard
from app.services.gateway import transport as transport_module
from app.services.gateway.model_endpoint import ModelEndpoint

logger = get_logger(__name__)

SleepFunction = Callable[[float], Awaitable[None]]


@dataclass(frozen=True)
class RetryPolicy:
    """退避重试策略。空元组表示不重试（探测路径的默认值）。

    与 `04` §6.2 对齐：只有 429/502/503/504 可重试；超时、鉴权失败、重定向与
    响应格式错误一律不重试。
    """

    delays: tuple[float, ...] = ()

    @classmethod
    def no_retry(cls) -> "RetryPolicy":
        return cls(())

    @classmethod
    def standard(cls) -> "RetryPolicy":
        """`NFR-REL-01` / `04` §6.2：1s → 3s，最多 2 次重试。"""
        return cls(transport_module.BACKOFF_SECONDS)


class SsrfSafeModelGateway:
    """受控模型网关；必须作为异步上下文管理器使用。"""

    def __init__(
        self,
        settings: Settings,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        read_timeout: float | None = None,
    ) -> None:
        self._settings = settings
        self._http_transport = http_transport
        self._read_timeout = read_timeout
        self._client: httpx.AsyncClient | None = None
        self._max_response_bytes = ssrf_guard.MAX_RESPONSE_BYTES

    async def __aenter__(self) -> "SsrfSafeModelGateway":
        self._client = transport_module.build_async_client(
            connect_timeout=self._settings.model_connect_timeout_seconds,
            read_timeout=(
                self._read_timeout
                if self._read_timeout is not None
                else self._settings.model_read_timeout_seconds
            ),
            transport=self._http_transport,
        )
        return self

    async def __aexit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def post_json(
        self,
        endpoint: ModelEndpoint,
        path: str,
        payload: Mapping[str, Any],
        *,
        retry: RetryPolicy | None = None,
        sleep: SleepFunction = asyncio.sleep,
    ) -> dict[str, Any]:
        """向模型服务发送 JSON POST 并返回解析后的对象。

        :param endpoint: 当次调用所需的配置（明文 Key 只用于构造 Authorization 头）。
        :param path: 相对 `base_url` 的固定路径（由调用方代码决定，不来自用户输入）。
        :param retry: 默认 `no_retry`；业务调用可显式传 `RetryPolicy.standard()`。
        :raises BaseUrlRejectedError: URL 未通过 SSRF 校验，或上游返回 3xx。
        :raises UpstreamModelAuthError: 上游 401/403/404。
        :raises UpstreamModelRateLimitedError: 429 且重试耗尽。
        :raises UpstreamModelError: 5xx、连接失败、响应过大或 JSON 不可解析。
        :raises UpstreamTimeoutError: 连接/读取超时。
        """
        if self._client is None:
            raise RuntimeError("SsrfSafeModelGateway 必须在 async with 内使用")

        policy = retry if retry is not None else RetryPolicy.no_retry()
        base_url = endpoint.base_url.rstrip("/")
        url = f"{base_url}/{path.lstrip('/')}"
        # 每次调用前都校验：调用方可能在不同请求间切换 Base URL；重试复用同一 URL，不重复解析
        ssrf_guard.validate_outbound_url(
            base_url,
            allow_local=self._settings.allow_local_model_base_url,
            allowed_origins=self._settings.local_model_allowed_origins,
        )

        headers = {"Content-Type": "application/json"}
        if endpoint.api_key:
            headers["Authorization"] = f"Bearer {endpoint.api_key}"

        attempt = 0
        while True:
            attempt += 1
            logger.info(
                "模型网关出站 url=%s provider=%s model=%s attempt=%s",
                transport_module.safe_url_for_log(url),
                endpoint.provider,
                endpoint.model_name,
                attempt,
            )
            try:
                async with self._client.stream(
                    "POST", url, json=payload, headers=headers
                ) as response:
                    status = response.status_code
                    if 300 <= status < 400:
                        # 重定向视为拒绝：既防 SSRF 绕行，也避免把 302 当成功
                        raise BaseUrlRejectedError()
                    if status >= 400:
                        failure = transport_module.classify_upstream_failure(status)
                        if failure.retryable and attempt <= len(policy.delays):
                            await sleep(policy.delays[attempt - 1])
                            continue
                        raise failure
                    raw = await self._read_limited(response)
            except httpx.TimeoutException as exc:
                raise UpstreamTimeoutError() from exc
            except httpx.HTTPError as exc:
                raise UpstreamModelError() from exc
            return self._parse_json(raw)

    async def _read_limited(self, response: httpx.Response) -> bytes:
        declared = response.headers.get("content-length")
        if declared is not None:
            try:
                declared_bytes = int(declared)
            except ValueError:
                raise NonRetryableAiError(
                    AiErrorCode.UPSTREAM_MODEL_ERROR,
                    "上游返回了非法的 Content-Length。",
                ) from None
            if declared_bytes > self._max_response_bytes:
                raise _response_too_large()

        chunks: list[bytes] = []
        total = 0
        async for chunk in response.aiter_bytes():
            total += len(chunk)
            if total > self._max_response_bytes:
                raise _response_too_large()
            chunks.append(chunk)
        return b"".join(chunks)

    @staticmethod
    def _parse_json(raw: bytes) -> dict[str, Any]:
        try:
            payload = json.loads(raw)
        except ValueError as exc:
            raise NonRetryableAiError(
                AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回的不是合法 JSON。"
            ) from exc
        if not isinstance(payload, dict):
            raise NonRetryableAiError(
                AiErrorCode.UPSTREAM_MODEL_ERROR, "上游返回结构不可识别。"
            )
        return payload


def _response_too_large() -> NonRetryableAiError:
    return NonRetryableAiError(
        AiErrorCode.UPSTREAM_MODEL_ERROR,
        "上游响应超过 2MB 限制，已中止读取。",
    )
