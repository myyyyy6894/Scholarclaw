"""受控模型网关（`06` TASK-301、`04` §4.9.1 / §6.2、`NFR-SEC-05` / `NFR-REL-01`）。

覆盖网关必须守住的边界：
URL 校验先于请求、重定向不跟随、429/5xx 的显式重试与退避、鉴权失败不重试、
超时/连接失败分类、2 MiB 响应上限、非法 JSON、日志不含 Key。
"""

from __future__ import annotations

import logging

import httpx
import pytest

from app.core.config import load_settings
from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelAuthError,
    UpstreamModelError,
    UpstreamModelRateLimitedError,
    UpstreamTimeoutError,
)
from app.services.gateway import ssrf_guard
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy, SsrfSafeModelGateway

ENDPOINT = ModelEndpoint(
    provider="OPENAI_COMPATIBLE",
    base_url="http://127.0.0.1:11434/v1",
    api_key="sk-gateway-test-secret",
    model_name="test-model",
)


def _settings():
    return load_settings({
        "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
        "SC_LOCAL_MODEL_ALLOWED_ORIGINS": "http://127.0.0.1:11434",
        "SC_MODEL_CONNECT_TIMEOUT_SECONDS": "2",
        "SC_MODEL_READ_TIMEOUT_SECONDS": "2",
    })


def _gateway(handler):  # noqa: ANN001 - httpx handler
    return SsrfSafeModelGateway(_settings(), http_transport=httpx.MockTransport(handler))


class _SleepRecorder:
    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.delays.append(seconds)


async def test_success_returns_json_and_sends_per_call_config() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

    async with _gateway(handler) as gateway:
        payload = await gateway.post_json(ENDPOINT, "/chat/completions", {"model": "x"})

    assert payload["choices"][0]["message"]["content"] == "ok"
    assert len(seen) == 1
    assert seen[0].url.path == "/v1/chat/completions"
    assert seen[0].headers["Authorization"] == "Bearer sk-gateway-test-secret"


@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
async def test_redirect_is_rejected_without_following(status: int) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(status, headers={"Location": "http://169.254.169.254/"})

    async with _gateway(handler) as gateway:
        with pytest.raises(BaseUrlRejectedError) as exc_info:
            await gateway.post_json(ENDPOINT, "/chat/completions", {})

    assert calls == 1, "网关不得跟随重定向"
    assert exc_info.value.error_code is AiErrorCode.E_SYS_02
    assert "169.254" not in str(exc_info.value)


@pytest.mark.parametrize("status", [401, 403, 404])
async def test_auth_failures_are_not_retried(status: int) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(status, json={"error": "sensitive upstream body"})

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelAuthError) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard())

    assert calls == 1
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_AUTH_ERROR
    assert "sensitive upstream body" not in str(exc_info.value)


async def test_rate_limit_retries_with_documented_backoff_then_succeeds() -> None:
    statuses = [429, 429, 200]
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(statuses[calls - 1], json={"ok": calls == 3})

    sleep = _SleepRecorder()
    async with _gateway(handler) as gateway:
        payload = await gateway.post_json(
            ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)

    assert calls == 3
    assert sleep.delays == [1.0, 3.0]
    assert payload["ok"] is True


async def test_server_error_exhausts_retries_and_is_redacted() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503, text="upstream exploded with sk-secret")

    sleep = _SleepRecorder()
    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelError) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)

    assert calls == 3, "首次 + 2 次重试"
    assert sleep.delays == [1.0, 3.0]
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert "upstream exploded" not in str(exc_info.value)


@pytest.mark.parametrize("status", [429, 502, 503, 504])
async def test_documented_transient_statuses_retry_at_most_twice(status: int) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(status, text=f"upstream {status}")

    sleep = _SleepRecorder()
    async with _gateway(handler) as gateway:
        expected = (
            UpstreamModelRateLimitedError if status == 429 else UpstreamModelError
        )
        with pytest.raises(expected) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)

    assert calls == 3, f"{status} 应为首试 + 最多 2 次重试"
    assert sleep.delays == [1.0, 3.0]
    assert exc_info.value.error_code in {
        AiErrorCode.UPSTREAM_MODEL_RATE_LIMITED,
        AiErrorCode.UPSTREAM_MODEL_ERROR,
    }


@pytest.mark.parametrize("status", [500, 501, 505, 507, 599])
async def test_other_5xx_are_requested_only_once(status: int) -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(status, text="upstream exploded with sk-secret")

    sleep = _SleepRecorder()
    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelError) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)

    assert calls == 1, f"{status} 不在 NFR-REL-01 的可重试集合内"
    assert sleep.delays == []
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert exc_info.value.retryable is False
    assert "upstream exploded" not in str(exc_info.value)


async def test_connect_timeout_maps_to_upstream_timeout_without_retry() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ConnectTimeout("connect timed out", request=request)

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamTimeoutError) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard())

    assert calls == 1
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_TIMEOUT


async def test_probe_default_is_no_retry() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(429, json={})

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamModelRateLimitedError):
            await gateway.post_json(ENDPOINT, "/chat/completions", {})

    assert calls == 1, "探测路径默认 0 次重试"


async def test_timeout_maps_to_upstream_timeout_without_retry() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ReadTimeout("timed out", request=request)

    async with _gateway(handler) as gateway:
        with pytest.raises(UpstreamTimeoutError) as exc_info:
            await gateway.post_json(
                ENDPOINT, "/chat/completions", {}, retry=RetryPolicy.standard())

    assert calls == 1
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_TIMEOUT


async def test_oversized_content_length_is_rejected_before_reading() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            200,
            headers={"Content-Length": str(ssrf_guard.MAX_RESPONSE_BYTES + 1)},
            content=b"{}",
        )

    async with _gateway(handler) as gateway:
        with pytest.raises(NonRetryableAiError) as exc_info:
            await gateway.post_json(ENDPOINT, "/embeddings", {})

    assert calls == 1
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR


async def test_invalid_json_is_rejected_without_echoing_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>not json sk-secret</html>")

    async with _gateway(handler) as gateway:
        with pytest.raises(NonRetryableAiError) as exc_info:
            await gateway.post_json(ENDPOINT, "/chat/completions", {})

    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert "sk-secret" not in str(exc_info.value)


async def test_url_is_validated_before_any_request() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={})

    bad = ModelEndpoint(
        provider="DEEPSEEK",
        base_url="http://sc-mysql:3306/v1",
        api_key="sk-x",
        model_name="x",
    )
    async with _gateway(handler) as gateway:
        with pytest.raises(BaseUrlRejectedError):
            await gateway.post_json(bad, "/chat/completions", {})

    assert calls == 0, "URL 未通过校验时不得发出任何请求"


async def test_logs_never_contain_api_key(caplog: pytest.LogCaptureFixture) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"ok": True})

    # 其他测试可能已把 `sc.*` 设为 WARNING；这里显式把网关 logger 提到 INFO 再断言
    with caplog.at_level(
        logging.INFO, logger="sc.app.services.gateway.ssrf_safe_model_gateway"
    ):
        async with _gateway(handler) as gateway:
            await gateway.post_json(ENDPOINT, "/chat/completions", {"secret": "sk-body-secret"})

    assert "sk-gateway-test-secret" not in caplog.text
    assert "sk-body-secret" not in caplog.text
    assert "model=test-model" in caplog.text
