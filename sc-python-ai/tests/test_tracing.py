"""追踪上下文与中间件（`NFR-REL-02`、`02` §11、`04` §2.7.1）。"""

from __future__ import annotations

import re

import pytest

from app.core import tracing
from app.core.tracing import TraceIdMiddleware

TRACE_ID_PATTERN = re.compile(r"^tr_[0-9a-f]{12}$")
CRON_TRACE_ID_PATTERN = re.compile(r"^tr_cron_[0-9a-f]{12}$")


def test_generate_trace_id_matches_documented_format() -> None:
    assert TRACE_ID_PATTERN.match(tracing.generate_trace_id())


def test_generate_cron_trace_id_uses_cron_prefix() -> None:
    assert CRON_TRACE_ID_PATTERN.match(tracing.generate_cron_trace_id())


def test_inbound_trace_id_acceptance() -> None:
    assert tracing.is_acceptable_inbound_trace_id("tr_0123456789ab") is True
    assert tracing.is_acceptable_inbound_trace_id("tr_ZZZZZZZZZZZZ") is False
    assert tracing.is_acceptable_inbound_trace_id("tr_0123456789") is False
    assert tracing.is_acceptable_inbound_trace_id("tr_cron_0123456789ab") is False
    assert tracing.is_acceptable_inbound_trace_id(None) is False
    assert tracing.is_acceptable_inbound_trace_id("") is False


def test_current_or_generated_falls_back_to_cron_prefix() -> None:
    tracing.clear()

    assert CRON_TRACE_ID_PATTERN.match(tracing.current_or_generated_trace_id())


def _http_scope(headers: list[tuple[bytes, bytes]] | None = None) -> dict:
    return {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.1"},
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/internal/ai/v1/health",
        "raw_path": b"/internal/ai/v1/health",
        "query_string": b"",
        "root_path": "",
        "headers": headers or [],
        "client": ("10.0.0.7", 51234),
        "server": ("sc-python-ai", 8000),
    }


def _collector(sink: list[dict]):
    """返回把 ASGI 消息收集到 `sink` 的 send 可调用对象。"""

    async def _send(message: dict) -> None:
        sink.append(message)

    return _send


async def _empty_receive() -> dict:
    return {"type": "http.request", "body": b"", "more_body": False}


async def test_middleware_generates_trace_id_and_adds_response_header() -> None:
    seen: dict[str, str | None] = {}

    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        seen["trace_id"] = tracing.current_trace_id()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    messages: list[dict] = []
    middleware = TraceIdMiddleware(app)
    await middleware(_http_scope(), _empty_receive, _collector(messages))

    assert seen["trace_id"] is not None
    assert TRACE_ID_PATTERN.match(seen["trace_id"] or "")
    response_headers = dict(messages[0]["headers"])
    assert response_headers[b"x-trace-id"].decode() == seen["trace_id"]
    # 请求结束后上下文必须清理，避免 ASGI worker 复用协程时串线
    assert tracing.current_trace_id() is None


async def test_middleware_propagates_well_formed_inbound_trace_id() -> None:
    seen: dict[str, str | None] = {}

    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        seen["trace_id"] = tracing.current_trace_id()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = TraceIdMiddleware(app)
    scope = _http_scope([(b"x-trace-id", b"tr_0123456789ab")])
    await middleware(scope, _empty_receive, _collector([]))

    assert seen["trace_id"] == "tr_0123456789ab"


async def test_middleware_regenerates_malformed_inbound_trace_id() -> None:
    seen: dict[str, str | None] = {}

    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        seen["trace_id"] = tracing.current_trace_id()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = TraceIdMiddleware(app)
    scope = _http_scope([(b"x-trace-id", b"not-a-trace-id")])
    await middleware(scope, _empty_receive, _collector([]))

    assert seen["trace_id"] != "not-a-trace-id"
    assert TRACE_ID_PATTERN.match(seen["trace_id"] or "")


async def test_middleware_clears_context_even_when_app_raises() -> None:
    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        raise RuntimeError("boom")

    middleware = TraceIdMiddleware(app)

    with pytest.raises(RuntimeError):
        await middleware(_http_scope(), _empty_receive, _collector([]))

    assert tracing.current_trace_id() is None


async def test_middleware_passes_through_non_http_scopes() -> None:
    called: dict[str, bool] = {}

    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        called["lifespan"] = True

    middleware = TraceIdMiddleware(app)
    await middleware({"type": "lifespan"}, _empty_receive, _collector([]))

    assert called["lifespan"] is True
    assert tracing.current_trace_id() is None


async def test_middleware_clears_user_identity_after_request() -> None:
    """请求结束后必须清空用户身份（`04` §6.1.2）：ASGI worker 复用任务时不得串用户。"""

    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        tracing.bind_user_id("10001")
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    middleware = TraceIdMiddleware(app)
    await middleware(_http_scope(), _empty_receive, _collector([]))

    assert tracing.current_user_id() is None


async def test_middleware_clears_user_identity_even_when_app_raises() -> None:
    async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
        tracing.bind_user_id("10001")
        raise RuntimeError("boom")

    middleware = TraceIdMiddleware(app)

    with pytest.raises(RuntimeError):
        await middleware(_http_scope(), _empty_receive, _collector([]))

    assert tracing.current_user_id() is None


async def test_user_identity_does_not_leak_into_next_request() -> None:
    """连续两次请求：第一次带用户身份，第二次必须是"无身份"，不得沿用上一个用户。"""
    seen: list[str | None] = []

    def _app(user_id: str | None):
        async def app(scope, receive, send):  # noqa: ANN001 - ASGI 协议
            if user_id is not None:
                tracing.bind_user_id(user_id)
            seen.append(tracing.current_user_id())
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body", "body": b""})

        return app

    middleware_with_identity = TraceIdMiddleware(_app("10001"))
    await middleware_with_identity(_http_scope(), _empty_receive, _collector([]))

    middleware_without_identity = TraceIdMiddleware(_app(None))
    await middleware_without_identity(_http_scope(), _empty_receive, _collector([]))

    assert seen == ["10001", None]
