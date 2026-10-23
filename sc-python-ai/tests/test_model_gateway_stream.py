"""模型网关流式 Chat（TASK-603）。"""

from __future__ import annotations

import httpx
import pytest

from app.core.config import load_settings
from app.core.exceptions import (
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelError,
    UpstreamModelRateLimitedError,
)
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy, SsrfSafeModelGateway

ENDPOINT = ModelEndpoint(
    provider="OPENAI_COMPATIBLE",
    base_url="http://127.0.0.1:11434/v1",
    api_key="sk-stream-test",
    model_name="test-model",
)


def _settings():
    return load_settings({
        "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
        "SC_LOCAL_MODEL_ALLOWED_ORIGINS": "http://127.0.0.1:11434",
        "SC_MODEL_CONNECT_TIMEOUT_SECONDS": "2",
        "SC_MODEL_READ_TIMEOUT_SECONDS": "2",
    })


def _gateway(handler):
    return SsrfSafeModelGateway(_settings(), http_transport=httpx.MockTransport(handler))


class _SleepRecorder:
    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.delays.append(seconds)


class _ChunkedStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes]) -> None:
        self._chunks = chunks

    async def __aiter__(self):
        for chunk in self._chunks:
            yield chunk


def _sse(*contents: str) -> bytes:
    lines = []
    for content in contents:
        lines.append(
            'data: {"choices":[{"delta":{"content":%s}}]}\n\n'
            % __import__("json").dumps(content, ensure_ascii=False))
    lines.append("data: [DONE]\n\n")
    return "".join(lines).encode()


@pytest.mark.asyncio
async def test_stream_yields_only_delta_content() -> None:
    body = (
        ": heartbeat\n\n"
        "event: message\n\n"
        'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n'
        + _sse("第一段", "第二段").decode()
        + 'data: {"choices":[],"extra":1}\n\n'
        'data: [DONE]\n\n'
        'data: {"choices":[{"delta":{"content":"迟到"}}]}\n\n'
    ).encode()

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=body)

    async with _gateway(handler) as gateway:
        deltas = [delta async for delta in gateway.stream_chat(
            ENDPOINT, {"model": "x", "stream": True})]

    assert deltas == ["第一段", "第二段"]


@pytest.mark.asyncio
async def test_stream_handles_utf8_split_across_chunks() -> None:
    payload = _sse("中文回答")
    split = payload.index("中".encode()) + 1

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, stream=_ChunkedStream([payload[:split], payload[split:]]))

    async with _gateway(handler) as gateway:
        deltas = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]

    assert deltas == ["中文回答"]


@pytest.mark.asyncio
async def test_stream_retries_retryable_status_before_first_token() -> None:
    calls = []

    def handler(_request: httpx.Request) -> httpx.Response:
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(429, json={"error": "rate"})
        return httpx.Response(200, content=_sse("ok"))

    sleep = _SleepRecorder()
    async with _gateway(handler) as gateway:
        deltas = [delta async for delta in gateway.stream_chat(
            ENDPOINT, {}, retry=RetryPolicy.standard(), sleep=sleep)]

    assert deltas == ["ok"]
    assert sleep.delays == [1.0]


@pytest.mark.asyncio
async def test_stream_does_not_retry_non_retryable_5xx() -> None:
    calls = []

    def handler(_request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(500, json={"error": "boom"})

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelError):
            _ = [delta async for delta in gateway.stream_chat(
                ENDPOINT, {}, retry=RetryPolicy.standard())]

    assert len(calls) == 1


@pytest.mark.asyncio
async def test_stream_429_without_policy_raises_rate_limited() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": "rate"})

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelRateLimitedError):
            _ = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]


@pytest.mark.asyncio
async def test_stream_rejects_redirect() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"Location": "http://169.254.169.254/"})

    async with _gateway(handler) as gateway:
        with pytest.raises(BaseUrlRejectedError):
            _ = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]


@pytest.mark.asyncio
async def test_stream_rejects_malformed_json_frame() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"data: {not-json}\n\n")

    async with _gateway(handler) as gateway:
        with pytest.raises(NonRetryableAiError) as error:
            _ = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]

    assert error.value.error_code.value == "UPSTREAM_MODEL_ERROR"


@pytest.mark.asyncio
async def test_stream_enforces_response_size_limit() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=_sse("x" * 100))

    gateway = _gateway(handler)
    gateway._max_response_bytes = 10  # noqa: SLF001 - 测试上限边界
    async with gateway:
        with pytest.raises(NonRetryableAiError) as error:
            _ = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]

    assert error.value.error_code.value == "UPSTREAM_MODEL_ERROR"


@pytest.mark.asyncio
async def test_stream_error_frame_raises_upstream_error() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b'data: {"error":{"message":"quota"}}\n\n')

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelError):
            _ = [delta async for delta in gateway.stream_chat(ENDPOINT, {})]
