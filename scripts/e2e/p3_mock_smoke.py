#!/usr/bin/env python3
"""P3 mock 安全验证（在 `sc-python-ai` 容器内运行）。

覆盖负责人要求的 mock 项：

* `500/501/505` 及其他 5xx 只请求 1 次；
* `429/502/503/504` 使用 `RetryPolicy.standard()` 时最多请求 3 次（首试 + 2 次重试），退避 `1s→3s`；
* `BOTH` 总预算触发后返回 `504 UPSTREAM_TIMEOUT` 并在预算内返回；
* SSRF 拒绝 C0/C1/DEL 控制字符与 `%2e%2e` 等编码穿越，合法单点不误拦；
* 日志与异常消息不含 API Key；
* `tr_cron_*` 被追踪模块接受。

运行（容器内，工作目录 `/app`）：

    docker compose exec -T sc-python-ai python3 - < scripts/e2e/p3_mock_smoke.py
"""

from __future__ import annotations

import asyncio
import io
import json
import logging
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from app.core import tracing
from app.core.config import load_settings
from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    UpstreamModelError,
    UpstreamModelRateLimitedError,
    UpstreamTimeoutError,
)
from app.services.gateway import model_probe as model_probe_module
from app.services.gateway import ssrf_guard
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy, SsrfSafeModelGateway

SECRET = "sk-p3-mock-secret"
RESULTS: list[tuple[str, bool, str]] = []


class _MockHandler(BaseHTTPRequestHandler):
    status = 200
    delay = 0.0
    calls = 0

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 接口
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        type(self).calls += 1
        if self.delay:
            time.sleep(self.delay)
        body = json.dumps({"choices": [{"message": {"content": "ok"}}]}).encode()
        self.send_response(self.status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args) -> None:  # noqa: ANN002 - 静音
        return


def _check(name: str, condition: bool, detail: str = "") -> None:
    RESULTS.append((name, condition, detail))
    print(f"[{'PASS' if condition else 'FAIL'}] {name}" + ("" if condition else f"（{detail}）"))


class _SleepRecorder:
    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.delays.append(seconds)


@contextmanager
def _mock_server() -> Iterator[tuple[str, type[_MockHandler]]]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _MockHandler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", _MockHandler
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _settings(origin: str):
    return load_settings({
        "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
        "SC_LOCAL_MODEL_ALLOWED_ORIGINS": origin,
        "SC_MODEL_CONNECT_TIMEOUT_SECONDS": "5",
        "SC_MODEL_READ_TIMEOUT_SECONDS": "5",
    })


async def _verify_retry_boundaries(base_url: str, handler: type[_MockHandler]) -> None:
    settings = _settings(base_url)
    endpoint = ModelEndpoint("OLLAMA", f"{base_url}/v1", SECRET, "mock-model")

    for status in (500, 501, 505, 507, 599):
        handler.status = status
        handler.calls = 0
        sleep = _SleepRecorder()
        async with SsrfSafeModelGateway(settings) as gateway:
            try:
                await gateway.post_json(
                    endpoint, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)
            except UpstreamModelError as exc:
                _check(f"5xx {status} 不重试", handler.calls == 1 and not sleep.delays
                       and exc.retryable is False and exc.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR,
                       f"calls={handler.calls} delays={sleep.delays} retryable={exc.retryable}")
                _check(f"5xx {status} 消息不含 Key", SECRET not in str(exc))
            else:
                _check(f"5xx {status} 应失败", False, "未抛异常")

    for status in (429, 502, 503, 504):
        handler.status = status
        handler.calls = 0
        sleep = _SleepRecorder()
        expected = UpstreamModelRateLimitedError if status == 429 else UpstreamModelError
        async with SsrfSafeModelGateway(settings) as gateway:
            try:
                await gateway.post_json(
                    endpoint, "/chat/completions", {}, retry=RetryPolicy.standard(), sleep=sleep)
            except expected as exc:
                _check(f"可重试 {status} 最多 3 次", handler.calls == 3 and sleep.delays == [1.0, 3.0],
                       f"calls={handler.calls} delays={sleep.delays}")
                _check(f"可重试 {status} 消息不含 Key", SECRET not in str(exc))
            else:
                _check(f"可重试 {status} 应失败", False, "未抛异常")


async def _verify_both_budget(base_url: str, handler: type[_MockHandler]) -> None:
    settings = _settings(base_url)
    handler.status = 200
    handler.delay = 20.0
    handler.calls = 0
    original = model_probe_module.BOTH_TOTAL_TIMEOUT_SECONDS
    model_probe_module.BOTH_TOTAL_TIMEOUT_SECONDS = 1.0
    try:
        endpoint = ModelEndpoint("OLLAMA", f"{base_url}/v1", SECRET, "mock-model")
        started = time.monotonic()
        try:
            await model_probe_module.probe_models(settings, "BOTH", endpoint, endpoint)
        except UpstreamTimeoutError as exc:
            elapsed = time.monotonic() - started
            _check("BOTH 总预算返回 504", exc.error_code is AiErrorCode.UPSTREAM_TIMEOUT)
            _check("BOTH 总预算在预算内返回", elapsed < 3.0, f"elapsed={elapsed:.2f}s")
            _check("BOTH 两侧都已发起", handler.calls == 2, f"calls={handler.calls}")
        else:
            _check("BOTH 慢响应应超时", False, "未抛异常")
    finally:
        model_probe_module.BOTH_TOTAL_TIMEOUT_SECONDS = original
        handler.delay = 0.0


async def _verify_ssrf(base_url: str) -> None:
    for url in (
        "https://api.example.com/v1/%2e%2e/admin",
        "https://api.example.com/v1/%252e%252e/admin",
        "https://api.example.com/v1/%5cadmin",
        "https://api.example.com/v1/\x00admin",
        "https://api.example.com/v1/\x7fadmin",
    ):
        try:
            await ssrf_guard.validate_outbound_url_async(url, allow_local=False, allowed_origins=())
        except BaseUrlRejectedError:
            _check(f"SSRF 拒绝 {url!r}", True)
        else:
            _check(f"SSRF 拒绝 {url!r}", False, "未拒绝")

    try:
        await ssrf_guard.validate_outbound_url_async(
            f"{base_url}/v1.0/models", allow_local=True,
            allowed_origins=(base_url,))
        _check("SSRF 合法单点不误拦", True)
    except BaseUrlRejectedError as exc:
        _check("SSRF 合法单点不误拦", False, str(exc))


def _verify_trace_and_logs(log_text: str) -> None:
    _check("追踪接受 tr_cron_*", tracing.is_acceptable_inbound_trace_id("tr_cron_0123456789ab"))
    _check("追踪仍拒绝非法值", not tracing.is_acceptable_inbound_trace_id("tr_cron_xyz"))
    _check("网关日志不含 Key", SECRET not in log_text)


async def main_async() -> int:
    log_stream = io.StringIO()
    log_handler = logging.StreamHandler(log_stream)
    gateway_logger = logging.getLogger("sc.app.services.gateway.ssrf_safe_model_gateway")
    gateway_logger.addHandler(log_handler)
    gateway_logger.setLevel(logging.INFO)

    with _mock_server() as (base_url, handler):
        await _verify_retry_boundaries(base_url, handler)
        await _verify_both_budget(base_url, handler)
        await _verify_ssrf(base_url)

    gateway_logger.removeHandler(log_handler)
    _verify_trace_and_logs(log_stream.getvalue())

    failed = [name for name, ok, _ in RESULTS if not ok]
    print(f"p3 mock 安全验证：{len(RESULTS) - len(failed)}/{len(RESULTS)} 项通过")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main_async()))
