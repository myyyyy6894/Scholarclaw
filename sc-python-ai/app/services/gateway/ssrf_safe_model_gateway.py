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

* **IP 钉扎**：连接前解析并校验 DNS，随后直接连接该 IP，保留原主机名做 Host/TLS SNI；
  与禁止重定向、禁止代理共同关闭 DNS rebinding 窗口（P9 复核第 9 项）。
"""

from __future__ import annotations

import asyncio
import codecs
import json
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
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
            allow_local=self._settings.allow_local_model_base_url,
            allowed_origins=self._settings.local_model_allowed_origins,
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
        # 每次调用前都校验：调用方可能在不同请求间切换 Base URL；重试复用同一 URL，不重复解析。
        # 使用异步 DNS 解析，避免同步 getaddrinfo 阻塞事件循环（也保证 BOTH 总预算可被 wait_for 取消）。
        await ssrf_guard.validate_outbound_url_async(
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
                    if status != 200:
                        if status < 300:
                            # 201/202/204/206 等非 200 的 2xx 不是探测契约里的成功响应
                            raise transport_module.non_200_success_error()
                        failure = transport_module.classify_upstream_failure(status)
                        # 只有 429/502/503/504 可重试；500/501/505 等其余 5xx 只请求一次。
                        if (
                            status in transport_module.RETRYABLE_UPSTREAM_STATUS
                            and attempt <= len(policy.delays)
                        ):
                            await sleep(policy.delays[attempt - 1])
                            continue
                        raise failure
                    raw = await self._read_limited(response)
            except httpx.TimeoutException as exc:
                raise UpstreamTimeoutError() from exc
            except httpx.HTTPError as exc:
                raise UpstreamModelError() from exc
            return self._parse_json(raw)

    async def stream_chat(
        self,
        endpoint: ModelEndpoint,
        payload: Mapping[str, Any],
        *,
        path: str = "chat/completions",
        retry: RetryPolicy | None = None,
        sleep: SleepFunction = asyncio.sleep,
    ) -> AsyncIterator[str]:
        """向 OpenAI 兼容接口发起流式 Chat 请求，逐段产出正文增量。

        * 与 :meth:`post_json` 使用同一套 SSRF 校验、重定向拒绝与错误分类；
        * 只有状态码阶段（429/502/503/504）可重试，一旦开始产出不再重试；
        * 原始响应累计上限 2 MiB；上游 `data:` 帧必须是合法 JSON；
        * 只读取 `choices[].delta.content`，忽略心跳/注释与非正文事件。
        """
        if self._client is None:
            raise RuntimeError("SsrfSafeModelGateway 必须在 async with 内使用")

        policy = retry if retry is not None else RetryPolicy.no_retry()
        base_url = endpoint.base_url.rstrip("/")
        url = f"{base_url}/{path.lstrip('/')}"
        await ssrf_guard.validate_outbound_url_async(
            base_url,
            allow_local=self._settings.allow_local_model_base_url,
            allowed_origins=self._settings.local_model_allowed_origins,
        )

        headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
        if endpoint.api_key:
            headers["Authorization"] = f"Bearer {endpoint.api_key}"

        attempt = 0
        while True:
            attempt += 1
            logger.info(
                "模型网关流式出站 url=%s provider=%s model=%s attempt=%s",
                transport_module.safe_url_for_log(url),
                endpoint.provider,
                endpoint.model_name,
                attempt,
            )
            retry_delay: float | None = None
            try:
                async with self._client.stream(
                    "POST", url, json=payload, headers=headers
                ) as response:
                    status = response.status_code
                    if 300 <= status < 400:
                        raise BaseUrlRejectedError()
                    if status != 200:
                        if status < 300:
                            raise transport_module.non_200_success_error()
                        failure = transport_module.classify_upstream_failure(status)
                        if (
                            status in transport_module.RETRYABLE_UPSTREAM_STATUS
                            and attempt <= len(policy.delays)
                        ):
                            retry_delay = policy.delays[attempt - 1]
                        else:
                            raise failure
                    else:
                        async for delta in self._iter_sse_deltas(response):
                            yield delta
                        return
            except httpx.TimeoutException as exc:
                raise UpstreamTimeoutError() from exc
            except httpx.HTTPError as exc:
                raise UpstreamModelError() from exc
            if retry_delay is None:  # 防御：非重试分支必须已抛出
                raise UpstreamModelError()
            await sleep(retry_delay)

    async def _iter_sse_deltas(self, response: httpx.Response) -> AsyncIterator[str]:
        """解析 OpenAI 兼容 SSE；按原始字节计数，超限立即中止。"""
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        buffer = ""
        total = 0
        async for chunk in response.aiter_bytes():
            total += len(chunk)
            if total > self._max_response_bytes:
                raise _response_too_large()
            buffer += decoder.decode(chunk)
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                delta = self._parse_sse_line(line)
                if delta is _STREAM_DONE:
                    return
                if delta is not None:
                    yield delta
        buffer += decoder.decode(b"", final=True)
        if buffer:
            delta = self._parse_sse_line(buffer)
            if delta is not None and delta is not _STREAM_DONE:
                yield delta

    @staticmethod
    def _parse_sse_line(raw_line: str) -> str | None | object:
        line = raw_line.strip()
        if not line or line.startswith(":") or not line.startswith("data:"):
            return None
        data = line[len("data:"):].strip()
        if not data:
            return None
        if data == "[DONE]":
            return _STREAM_DONE
        try:
            event = json.loads(data)
        except ValueError as exc:
            raise NonRetryableAiError(
                AiErrorCode.UPSTREAM_MODEL_ERROR, "上游流式响应不是合法 JSON。"
            ) from exc
        if not isinstance(event, dict):
            return None
        if isinstance(event.get("error"), dict):
            raise UpstreamModelError()
        choices = event.get("choices")
        if not isinstance(choices, list) or not choices:
            return None
        choice = choices[0]
        delta = choice.get("delta") if isinstance(choice, dict) else None
        content = delta.get("content") if isinstance(delta, dict) else None
        return content if isinstance(content, str) and content else None

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


#: SSE 结束标记；与正文 `str` 区分。
_STREAM_DONE = object()


def _response_too_large() -> NonRetryableAiError:
    return NonRetryableAiError(
        AiErrorCode.UPSTREAM_MODEL_ERROR,
        "上游响应超过 2MB 限制，已中止读取。",
    )
