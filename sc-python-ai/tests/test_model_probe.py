"""模型探测（`04` §4.9.1 `API-INT-AI-001`）。

用 stdlib 起一个假 OpenAI 兼容服务，验证"真的发出最小请求、真的取回向量长度"，
以及 401/302/非 JSON/缺字段这些失败分支全部映射为文档规定的错误码。
"""

from __future__ import annotations

import asyncio
import json
import threading
import time
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from app.core.config import load_settings
from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelAuthError,
    UpstreamTimeoutError,
)
from app.services.gateway import model_probe as model_probe_module
from app.services.gateway import ssrf_guard
from app.services.gateway.model_probe import ModelEndpointConfig, probe_models

DIM = 8


class _StubHandler(BaseHTTPRequestHandler):
    status_code = 200
    body: dict | None = None
    raw_body: bytes | None = None
    content_length_override: int | None = None
    omit_content_length = False
    delay_seconds = 0.0
    chunk_delay_seconds = 0.0
    received_headers: list[dict[str, str]] = []
    received_paths: list[str] = []

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 接口
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        type(self).received_headers.append(dict(self.headers))
        type(self).received_paths.append(self.path)
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        payload = self.body
        if payload is None:
            payload = ({"choices": [{"message": {"role": "assistant", "content": "p"}}]}
                       if self.path.endswith("/chat/completions")
                       else {"data": [{"embedding": [0.01] * DIM}]})
        raw = self.raw_body if self.raw_body is not None else json.dumps(payload).encode()
        self.send_response(self.status_code)
        if 300 <= self.status_code < 400:
            self.send_header("Location", "https://evil.example.com/")
        self.send_header("Content-Type", "application/json")
        if not self.omit_content_length:
            declared = self.content_length_override
            self.send_header("Content-Length", str(declared if declared is not None else len(raw)))
        self.end_headers()
        if self.chunk_delay_seconds:
            # 先发一半并 flush，再停住：httpx 读取剩余字节时触发读超时。
            half = max(1, len(raw) // 2)
            self.wfile.write(raw[:half])
            self.wfile.flush()
            time.sleep(self.chunk_delay_seconds)
            self.wfile.write(raw[half:])
            self.wfile.flush()
            return
        self.wfile.write(raw)

    def log_message(self, *args) -> None:  # noqa: ANN002 - 静音
        return


@pytest.fixture
def stub_model_server() -> Iterator[str]:
    server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    _StubHandler.status_code = 200
    _StubHandler.body = None
    _StubHandler.raw_body = None
    _StubHandler.content_length_override = None
    _StubHandler.omit_content_length = False
    _StubHandler.delay_seconds = 0.0
    _StubHandler.chunk_delay_seconds = 0.0
    _StubHandler.received_headers = []
    _StubHandler.received_paths = []
    try:
        yield f"http://127.0.0.1:{server.server_port}/v1"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def _settings(base_url: str, *, read_timeout: str = "2"):
    return load_settings({
        "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
        "SC_LOCAL_MODEL_ALLOWED_ORIGINS": base_url.rsplit("/v1", 1)[0],
        "SC_MODEL_CONNECT_TIMEOUT_SECONDS": "2",
        "SC_MODEL_READ_TIMEOUT_SECONDS": read_timeout,
    })


def _config(base_url: str, *, api_key: str | None = "sk-test-key") -> ModelEndpointConfig:
    return ModelEndpointConfig(provider="OPENAI_COMPATIBLE", base_url=base_url,
                               api_key=api_key, model_name="test-model")


@pytest.mark.asyncio
async def test_embedding_probe_returns_real_dimension(stub_model_server: str) -> None:
    outcome = await probe_models(
        _settings(stub_model_server), "EMBEDDING", None, _config(stub_model_server))

    assert outcome.actual_dim == DIM
    assert outcome.embedding_reachable is True
    assert outcome.chat_reachable is False
    assert _StubHandler.received_paths == ["/v1/embeddings"]
    assert _StubHandler.received_headers[0]["Authorization"] == "Bearer sk-test-key"


@pytest.mark.asyncio
async def test_chat_probe_sends_minimal_request(stub_model_server: str) -> None:
    outcome = await probe_models(
        _settings(stub_model_server), "CHAT", _config(stub_model_server), None)

    assert outcome.chat_reachable is True
    assert outcome.actual_dim is None
    assert _StubHandler.received_paths == ["/v1/chat/completions"]


@pytest.mark.asyncio
async def test_both_probe_runs_both_calls(stub_model_server: str) -> None:
    outcome = await probe_models(
        _settings(stub_model_server), "BOTH", _config(stub_model_server), _config(stub_model_server))

    assert outcome.chat_reachable is True
    assert outcome.embedding_reachable is True
    assert outcome.actual_dim == DIM
    assert sorted(_StubHandler.received_paths) == sorted(
        ["/v1/chat/completions", "/v1/embeddings"]), "BOTH 并行执行，请求顺序不固定"


@pytest.mark.asyncio
async def test_upstream_401_maps_to_auth_error(stub_model_server: str) -> None:
    _StubHandler.status_code = 401

    with pytest.raises(UpstreamModelAuthError):
        await probe_models(_settings(stub_model_server), "CHAT", _config(stub_model_server), None)


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [301, 302, 303, 307, 308])
async def test_redirect_is_rejected_as_ssrf(stub_model_server: str, status: int) -> None:
    """所有重定向状态码都必须按 `E-SYS-02` 拒绝，不能跟随到未校验地址。"""
    _StubHandler.status_code = status

    with pytest.raises(NonRetryableAiError) as excinfo:
        await probe_models(_settings(stub_model_server), "CHAT", _config(stub_model_server), None)

    assert excinfo.value.error_code == AiErrorCode.E_SYS_02
    assert _StubHandler.received_paths == ["/v1/chat/completions"]


@pytest.mark.asyncio
async def test_both_probes_run_in_parallel(stub_model_server: str) -> None:
    """BOTH 必须并行：串行最坏 2×5s 会撞上 Java 10s 读取超时（04 §6.2）。"""
    _StubHandler.delay_seconds = 0.3

    started = time.monotonic()
    outcome = await probe_models(
        _settings(stub_model_server), "BOTH",
        _config(stub_model_server), _config(stub_model_server))
    elapsed = time.monotonic() - started

    assert outcome.chat_reachable is True
    assert outcome.embedding_reachable is True
    assert elapsed < 0.55, f"BOTH 疑似串行执行（耗时 {elapsed:.2f}s）"


@pytest.mark.asyncio
async def test_both_total_budget_times_out_and_cancels_subtasks(monkeypatch: pytest.MonkeyPatch) -> None:
    """DNS/连接/读取任一环节变慢时，总预算必须中止整个 BOTH 探测并取消两个子任务。"""
    started: list[str] = []
    cancelled: list[str] = []

    async def slow_probe(_gateway, _config):  # noqa: ANN001, ANN202
        started.append("started")
        try:
            await asyncio.sleep(5)
        except asyncio.CancelledError:
            cancelled.append("cancelled")
            raise
        return True if len(started) == 1 else 8

    monkeypatch.setattr(model_probe_module, "_probe_chat", slow_probe)
    monkeypatch.setattr(model_probe_module, "_probe_embedding", slow_probe)
    monkeypatch.setattr(model_probe_module, "BOTH_TOTAL_TIMEOUT_SECONDS", 0.05)

    base = "https://api.example.com/v1"
    with pytest.raises(UpstreamTimeoutError) as excinfo:
        await probe_models(_settings(base), "BOTH", _config(base), _config(base))

    assert excinfo.value.error_code == AiErrorCode.UPSTREAM_TIMEOUT
    assert len(started) == 2
    assert len(cancelled) == 2, "总预算触发后两个子任务都必须被取消"


@pytest.mark.asyncio
async def test_slow_dns_is_covered_by_both_total_budget(monkeypatch: pytest.MonkeyPatch) -> None:
    """同步 getaddrinfo 不再阻塞事件循环；慢 DNS 由总预算统一中止为 504。"""

    async def slow_resolve(_host: str):  # noqa: ANN202
        await asyncio.sleep(5)
        return ()

    monkeypatch.setattr(ssrf_guard, "_resolve_async", slow_resolve)
    monkeypatch.setattr(model_probe_module, "BOTH_TOTAL_TIMEOUT_SECONDS", 0.05)

    base = "https://api.example.com/v1"
    with pytest.raises(UpstreamTimeoutError):
        await probe_models(_settings(base), "BOTH", _config(base), _config(base))


@pytest.mark.asyncio
async def test_slow_chunked_response_hits_read_timeout(stub_model_server: str) -> None:
    """上游先返回一半响应再停住：读超时必须映射为 504，而不是无限等待。"""
    _StubHandler.chunk_delay_seconds = 0.5

    with pytest.raises(UpstreamTimeoutError) as excinfo:
        await probe_models(
            _settings(stub_model_server, read_timeout="0.2"),
            "CHAT",
            _config(stub_model_server),
            None,
        )

    assert excinfo.value.error_code == AiErrorCode.UPSTREAM_TIMEOUT


@pytest.mark.asyncio
async def test_oversized_content_length_is_rejected_before_reading(stub_model_server: str) -> None:
    """声明长度超过 2MB 时立即中止，不把不受信任的响应读进内存。"""
    _StubHandler.content_length_override = ssrf_guard.MAX_RESPONSE_BYTES + 1
    _StubHandler.raw_body = b"{}"

    with pytest.raises(NonRetryableAiError) as excinfo:
        await probe_models(_settings(stub_model_server), "CHAT", _config(stub_model_server), None)

    assert excinfo.value.error_code == AiErrorCode.UPSTREAM_MODEL_ERROR


@pytest.mark.asyncio
async def test_oversized_stream_without_content_length_is_rejected(stub_model_server: str) -> None:
    """没有 Content-Length 的超大流也必须按累计字节数中止。"""
    _StubHandler.omit_content_length = True
    _StubHandler.raw_body = b"{" + b"x" * (ssrf_guard.MAX_RESPONSE_BYTES + 1) + b"}"

    with pytest.raises(NonRetryableAiError) as excinfo:
        await probe_models(_settings(stub_model_server), "CHAT", _config(stub_model_server), None)

    assert excinfo.value.error_code == AiErrorCode.UPSTREAM_MODEL_ERROR


@pytest.mark.asyncio
async def test_non_json_and_bad_shape_map_to_upstream_error(stub_model_server: str) -> None:
    _StubHandler.body = {"unexpected": "shape"}

    with pytest.raises(NonRetryableAiError) as excinfo:
        await probe_models(_settings(stub_model_server), "EMBEDDING", None, _config(stub_model_server))

    assert excinfo.value.error_code == AiErrorCode.UPSTREAM_MODEL_ERROR


@pytest.mark.asyncio
async def test_missing_config_is_validation_error(stub_model_server: str) -> None:
    with pytest.raises(NonRetryableAiError) as excinfo:
        await probe_models(_settings(stub_model_server), "BOTH", _config(stub_model_server), None)

    assert excinfo.value.error_code == AiErrorCode.VALIDATION_ERROR


@pytest.mark.asyncio
async def test_url_without_whitelist_is_rejected_before_any_call(stub_model_server: str) -> None:
    settings = load_settings({"SC_ALLOW_LOCAL_MODEL_BASE_URL": "false"})

    with pytest.raises(BaseUrlRejectedError):
        await probe_models(settings, "CHAT", _config(stub_model_server), None)

    assert _StubHandler.received_paths == []
