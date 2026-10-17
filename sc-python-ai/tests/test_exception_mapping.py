"""统一异常映射（`04` §2.3.2 / 第 7 章）。

最关键的一条：未捕获异常必须返回 500 + 脱敏文案，绝不把异常原文（可能含密钥、
内部主机名或连接串）回传调用方。
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from app.core.exceptions import (
    AiEngineError,
    AiErrorCode,
    InternalTokenInvalidError,
    ParentChunkFetchFailedError,
    UpstreamModelError,
    register_exception_handlers,
)
from app.core.tracing import TraceIdMiddleware


def _build_probe_app() -> FastAPI:
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(TraceIdMiddleware)
    register_exception_handlers(app)

    @app.get("/probe/upstream")
    async def _upstream() -> None:
        raise UpstreamModelError()

    @app.get("/probe/no-http-mapping")
    async def _no_http_mapping() -> None:
        raise ParentChunkFetchFailedError()

    @app.get("/probe/forbidden")
    async def _forbidden() -> None:
        raise InternalTokenInvalidError()

    @app.get("/probe/unexpected")
    async def _unexpected() -> None:
        raise RuntimeError("内部细节 api_key=sk-liveabcdef123456 host=sc-java-backend")

    @app.get("/probe/validation")
    async def _validation(count: int) -> dict[str, int]:
        return {"count": count}

    return app


@pytest.fixture
def probe_client() -> Iterator[TestClient]:
    with TestClient(_build_probe_app(), raise_server_exceptions=False) as client:
        yield client


def test_upstream_error_maps_to_documented_status_and_code(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/upstream")

    assert response.status_code == 502
    assert response.json()["code"] == AiErrorCode.UPSTREAM_MODEL_ERROR.value
    assert response.json()["data"] is None


def test_error_code_without_http_mapping_falls_back_to_500(probe_client: TestClient) -> None:
    """`E-RAG-03` 按 `04` 第 7 章是 SSE 错误帧、无 HTTP 映射；若意外经 REST 上抛则按 500 兜底。"""
    response = probe_client.get("/probe/no-http-mapping")

    assert response.status_code == 500
    assert response.json()["code"] == AiErrorCode.E_RAG_03.value


def test_forbidden_maps_to_403(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/forbidden")

    assert response.status_code == 403
    assert response.json()["code"] == AiErrorCode.AUTH_FORBIDDEN.value


def test_unexpected_error_is_sanitised(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/unexpected")
    body = response.text

    assert response.status_code == 500
    assert response.json()["code"] == AiErrorCode.INTERNAL_ERROR.value
    assert "sk-liveabcdef123456" not in body
    assert "sc-java-backend" not in body
    assert "RuntimeError" not in body
    assert "内部细节" not in body


def test_validation_error_uses_envelope(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/validation", params={"count": "abc"})

    assert response.status_code == 400
    assert response.json()["code"] == AiErrorCode.VALIDATION_ERROR.value
    assert "detail" not in response.json()


def test_unknown_path_uses_envelope(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/does-not-exist")

    assert response.status_code == 404
    assert response.json()["code"] == AiErrorCode.RESOURCE_NOT_FOUND.value


def test_every_error_response_carries_trace_id(probe_client: TestClient) -> None:
    response = probe_client.get("/probe/unexpected")
    payload = response.json()

    assert payload["trace_id"].startswith("tr_")
    assert response.headers["X-Trace-Id"] == payload["trace_id"]


def test_error_envelope_has_exactly_five_fields(probe_client: TestClient) -> None:
    payload = probe_client.get("/probe/forbidden").json()

    assert set(payload) == {"code", "message", "data", "trace_id", "timestamp"}


def test_engine_error_carries_retry_semantics() -> None:
    """重试语义由异常类型承载，供上层决定是否退避重试（`02` §5.3）。"""
    assert UpstreamModelError().retryable is True
    assert ParentChunkFetchFailedError().retryable is False
    assert isinstance(UpstreamModelError(), AiEngineError)
