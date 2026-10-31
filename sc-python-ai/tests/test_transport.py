"""出站传输基础：退避重试、失败分类、重定向拒绝（`NFR-REL-01`、`NFR-SEC-05`、`04` §6.2）。"""

from __future__ import annotations

import httpx
import pytest

from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelAuthError,
    UpstreamModelError,
    UpstreamModelRateLimitedError,
    UpstreamTimeoutError,
)
from app.services.gateway import transport


def _client(handler) -> httpx.AsyncClient:  # noqa: ANN001 - httpx handler
    return httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)


class _SleepRecorder:
    """记录退避等待时长，避免测试真的等 4 秒。"""

    def __init__(self) -> None:
        self.delays: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.delays.append(seconds)


async def test_successful_response_is_returned_immediately() -> None:
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(200, json={"ok": True})

    sleep = _SleepRecorder()
    async with _client(handler) as client:
        response = await transport.request_with_retry(
            client, "POST", "https://api.example.com/v1/chat", sleep=sleep
        )

    assert response.status_code == 200
    assert calls == [1]
    assert sleep.delays == []


async def test_rate_limit_is_retried_with_documented_backoff_then_succeeds() -> None:
    statuses = [429, 429, 200]
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(statuses[len(attempts) - 1], json={})

    sleep = _SleepRecorder()
    async with _client(handler) as client:
        response = await transport.request_with_retry(
            client, "POST", "https://api.example.com/v1/chat", sleep=sleep
        )

    assert response.status_code == 200
    assert len(attempts) == 3
    assert sleep.delays == [1.0, 3.0], "退避序列必须是 1s -> 3s（最多 2 次重试）"


async def test_server_error_exhausts_retries_and_raises_upstream_error() -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(503, text="upstream exploded")

    sleep = _SleepRecorder()
    async with _client(handler) as client:
        with pytest.raises(UpstreamModelError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=sleep
            )

    assert len(attempts) == 3, "首次 + 2 次重试"
    assert sleep.delays == [1.0, 3.0]
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert exc_info.value.retryable is True
    assert "upstream exploded" not in str(exc_info.value), "不得把上游响应体原文当异常消息"


@pytest.mark.parametrize("status", [429, 502, 503, 504])
async def test_documented_transient_statuses_are_retried_at_most_twice(status: int) -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(status, text="upstream transient")

    sleep = _SleepRecorder()
    async with _client(handler) as client:
        expected = (
            UpstreamModelRateLimitedError if status == 429 else UpstreamModelError
        )
        with pytest.raises(expected):
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=sleep
            )

    assert len(attempts) == 3, f"{status} 应为首试 + 最多 2 次重试"
    assert sleep.delays == [1.0, 3.0]


@pytest.mark.parametrize("status", [500, 501, 505, 507, 599])
async def test_other_5xx_are_requested_only_once(status: int) -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(status, text="upstream exploded")

    sleep = _SleepRecorder()
    async with _client(handler) as client:
        with pytest.raises(UpstreamModelError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=sleep
            )

    assert attempts == [1], f"{status} 不在 NFR-REL-01 的可重试集合内"
    assert sleep.delays == []
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert exc_info.value.retryable is False
    assert "upstream exploded" not in str(exc_info.value)


def test_retryable_flag_matches_retryable_status_set() -> None:
    assert transport.classify_upstream_failure(502).retryable is True
    assert transport.classify_upstream_failure(503).retryable is True
    assert transport.classify_upstream_failure(504).retryable is True
    assert transport.classify_upstream_failure(429).retryable is True
    assert transport.classify_upstream_failure(500).retryable is False
    assert transport.classify_upstream_failure(501).retryable is False
    assert transport.classify_upstream_failure(505).retryable is False


async def test_connect_timeout_maps_to_upstream_timeout_without_retry() -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        raise httpx.ConnectTimeout("connect timed out", request=request)

    async with _client(handler) as client:
        with pytest.raises(UpstreamTimeoutError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert attempts == [1]
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_TIMEOUT


@pytest.mark.parametrize("status", [201, 202, 204, 206])
async def test_non_200_2xx_is_not_success(status: int) -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(status, content=b"{}")

    async with _client(handler) as client:
        with pytest.raises(UpstreamModelError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert attempts == [1]
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR
    assert exc_info.value.retryable is False


async def test_rate_limited_after_retries_keeps_specific_error_code() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={})

    async with _client(handler) as client:
        with pytest.raises(UpstreamModelRateLimitedError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_RATE_LIMITED


@pytest.mark.parametrize("status", [401, 403, 404])
async def test_auth_failures_are_not_retried(status: int) -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(status, json={})

    async with _client(handler) as client:
        with pytest.raises(UpstreamModelAuthError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert attempts == [1], "鉴权失败重试没有意义，不得重试"
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_AUTH_ERROR


async def test_unexpected_client_error_is_not_retried() -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        return httpx.Response(400, json={})

    async with _client(handler) as client:
        with pytest.raises(NonRetryableAiError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert attempts == [1]
    assert exc_info.value.retryable is False
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR


@pytest.mark.parametrize("status", [301, 302, 307, 308])
async def test_redirect_is_rejected_not_followed(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, headers={"Location": "http://169.254.169.254/latest/meta-data/"})

    async with _client(handler) as client:
        with pytest.raises(BaseUrlRejectedError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert exc_info.value.error_code is AiErrorCode.E_SYS_02
    assert "169.254.169.254" not in str(exc_info.value), "拒绝消息不得回显目标地址"


async def test_timeout_maps_to_upstream_timeout_without_retry() -> None:
    attempts: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        raise httpx.ReadTimeout("timed out", request=request)

    async with _client(handler) as client:
        with pytest.raises(UpstreamTimeoutError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert attempts == [1]
    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_TIMEOUT
    assert exc_info.value.retryable is False


async def test_connection_failure_maps_to_upstream_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    async with _client(handler) as client:
        with pytest.raises(UpstreamModelError) as exc_info:
            await transport.request_with_retry(
                client, "POST", "https://api.example.com/v1/chat", sleep=_SleepRecorder()
            )

    assert exc_info.value.error_code is AiErrorCode.UPSTREAM_MODEL_ERROR


async def test_build_async_client_disables_redirects_and_env_proxies() -> None:
    async with transport.build_async_client(connect_timeout=3.0, read_timeout=9.0) as client:
        assert client.follow_redirects is False
        assert client.trust_env is False, "不得让 HTTP_PROXY 等环境变量把出站流量带离受控路径"
        assert client.timeout.connect == 3.0
        assert client.timeout.read == 9.0


def test_safe_url_for_log_strips_query_and_fragment() -> None:
    assert (
        transport.safe_url_for_log("https://api.example.com/v1/chat?key=sk-secret#frag")
        == "https://api.example.com/v1/chat"
    )
    assert transport.safe_url_for_log("http://127.0.0.1:11434/api/tags") == "http://127.0.0.1:11434/api/tags"


def test_retryable_status_set_matches_document() -> None:
    assert transport.RETRYABLE_UPSTREAM_STATUS == frozenset({429, 502, 503, 504})
    assert len(transport.BACKOFF_SECONDS) == 2, "NFR-REL-01：最多 2 次重试"


async def test_pinned_transport_connects_to_validated_ip_and_keeps_host(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """P9 复核第 9 项：连接前解析一次并钉扎 IP，Host/SNI 仍保留原主机名。"""
    import ipaddress

    from app.services.gateway import ssrf_guard

    async def fake_resolve(host, port, **kwargs):  # noqa: ANN001
        assert host == "api.example.com"
        return (ipaddress.ip_address("93.184.216.34"),)

    monkeypatch.setattr(ssrf_guard, "resolve_and_validate_host_async", fake_resolve)
    captured: dict[str, httpx.Request] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["request"] = request
        return httpx.Response(200, json={"ok": True})

    pinned = transport.PinnedAsyncTransport(allow_local=False, allowed_origins=())
    await pinned._inner.aclose()
    pinned._inner = httpx.MockTransport(handler)

    request = httpx.Request("POST", "https://api.example.com/v1/chat",
                            json={"model": "m"})
    response = await pinned.handle_async_request(request)

    assert response.status_code == 200
    sent = captured["request"]
    assert sent.url.host == "93.184.216.34"
    assert sent.headers["host"] == "api.example.com"
    assert sent.extensions["sni_hostname"] == "api.example.com"
    await pinned.aclose()


async def test_pinned_transport_rejects_when_host_validation_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.services.gateway import ssrf_guard

    async def reject(host, port, **kwargs):  # noqa: ANN001
        raise BaseUrlRejectedError()

    monkeypatch.setattr(ssrf_guard, "resolve_and_validate_host_async", reject)
    pinned = transport.PinnedAsyncTransport(allow_local=False, allowed_origins=())
    request = httpx.Request("GET", "https://metadata.google.internal/")
    with pytest.raises(BaseUrlRejectedError):
        await pinned.handle_async_request(request)
    await pinned.aclose()


@pytest.mark.asyncio
async def test_pinned_transport_real_https_uses_original_sni_and_host(
    tmp_path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """真实 HTTPS：连接钉扎到 IP，但证书校验与 SNI 仍使用原主机名。

    使用本地自签名 CA（不是公网供应商），验证 `sni_hostname` 扩展在真实 TLS 握手中生效；
    若 httpcore 忽略该扩展，证书主机名校验会因 IP 与 SAN 不匹配而失败。
    """
    import http.server
    import ipaddress
    import ssl
    import subprocess
    import threading

    from app.services.gateway import ssrf_guard

    cert = tmp_path / "cert.pem"
    key = tmp_path / "key.pem"
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(key), "-out", str(cert), "-days", "1",
         "-subj", "/CN=pinned.test",
         "-addext", "subjectAltName=DNS:pinned.test"],
        check=True, capture_output=True)

    sni_names: list[str | None] = []
    hosts: list[str | None] = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            hosts.append(self.headers.get("Host"))
            body = b'{"ok":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args) -> None:  # noqa: ANN002
            return

    server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_ctx.load_cert_chain(str(cert), str(key))
    server_ctx.set_servername_callback(
        lambda _sslobj, server_name, _ctx: sni_names.append(server_name))
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.socket = server_ctx.wrap_socket(httpd.socket, server_side=True)
    port = httpd.server_address[1]
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()

    async def fake_resolve(host, port, **kwargs):  # noqa: ANN001
        assert host == "pinned.test"
        return (ipaddress.ip_address("127.0.0.1"),)

    monkeypatch.setattr(ssrf_guard, "resolve_and_validate_host_async", fake_resolve)
    client_ctx = ssl.create_default_context(cafile=str(cert))
    pinned = transport.PinnedAsyncTransport(
        allow_local=True, allowed_origins=(f"https://pinned.test:{port}",),
        ssl_context=client_ctx)
    try:
        async with httpx.AsyncClient(transport=pinned, trust_env=False,
                                     follow_redirects=False) as client:
            response = await client.get(f"https://pinned.test:{port}/probe")
        assert response.status_code == 200
        assert sni_names == ["pinned.test"]
        assert hosts == [f"pinned.test:{port}"]
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)
