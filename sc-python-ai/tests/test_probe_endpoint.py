"""`API-INT-AI-001` 端点契约（`04` §4.9.1、`06` TASK-302）。

覆盖端点层而不是探测内部实现：
* 成功响应结构与 `04` 一致（`chat_reachable`/`embedding_reachable`/`actual_dim`/`latency_ms`）；
* `probe_type` / `user_id` / 条件必填配置的 Schema 校验；
* 上游失败按 `04` 第 7 章映射为明确的 HTTP 状态与错误码，且消息脱敏；
* 内部令牌仍是硬门槛（错误路径也不得绕过）。
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError
from starlette.testclient import TestClient

from app.api.internal_v1 import endpoints_probe
from app.core.exceptions import (
    AiErrorCode,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelAuthError,
    UpstreamModelRateLimitedError,
    UpstreamTimeoutError,
)
from app.schemas.probe import ModelProbeRequest
from app.services.gateway.model_probe import ProbeOutcome

PROBE_PATH = "/internal/ai/v1/model/probe"


def _endpoint_config(model: str) -> dict[str, str]:
    return {
        "provider": "OPENAI_COMPATIBLE",
        "base_url": "https://api.example.com/v1",
        "api_key": "sk-endpoint-test-secret",
        "model_name": model,
    }


def _body(*, probe_type: str = "BOTH", chat: bool = True, embedding: bool = True) -> dict:
    body: dict = {"user_id": "10001", "probe_type": probe_type}
    if chat:
        body["chat_config"] = _endpoint_config("deepseek-chat")
    if embedding:
        body["embedding_config"] = _endpoint_config("BAAI/bge-m3")
    return body


def test_success_shape_matches_internal_contract(
    authorized_remote_client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    async def fake_probe(*_args, **_kwargs) -> ProbeOutcome:
        return ProbeOutcome(chat_reachable=True, embedding_reachable=True,
                            actual_dim=1024, latency_ms=410)

    monkeypatch.setattr(endpoints_probe, "probe_models", fake_probe)

    response = authorized_remote_client.post(PROBE_PATH, json=_body())
    payload = response.json()

    assert response.status_code == 200
    assert payload["code"] == "OK"
    assert payload["message"] == "Probe succeeded"
    assert payload["data"] == {
        "chat_reachable": True,
        "embedding_reachable": True,
        "actual_dim": 1024,
        "latency_ms": 410,
    }
    assert response.headers["X-Trace-Id"] == payload["trace_id"]


@pytest.mark.parametrize("body", [
    {"user_id": "10001", "probe_type": "INVALID"},
    {"user_id": "not-a-number", "probe_type": "CHAT", "chat_config": _endpoint_config("x")},
    {"user_id": "10001", "probe_type": "CHAT"},              # 缺 chat_config
    {"user_id": "10001", "probe_type": "EMBEDDING"},         # 缺 embedding_config
])
def test_schema_or_required_config_errors_are_validation_error(
    authorized_remote_client: TestClient, body: dict
) -> None:
    response = authorized_remote_client.post(PROBE_PATH, json=body)
    payload = response.json()

    assert response.status_code == 400
    assert payload["code"] == "VALIDATION_ERROR"


@pytest.mark.parametrize(("error", "status", "code"), [
    (BaseUrlRejectedError(), 400, "E-SYS-02"),
    (UpstreamModelAuthError(), 502, "UPSTREAM_MODEL_AUTH_ERROR"),
    (UpstreamModelRateLimitedError(), 502, "UPSTREAM_MODEL_RATE_LIMITED"),
    (UpstreamTimeoutError(), 504, "UPSTREAM_TIMEOUT"),
    (NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR,
                         "上游响应超过 2MB 限制，已中止读取。"), 502, "UPSTREAM_MODEL_ERROR"),
])
def test_upstream_errors_map_to_documented_status_and_code(
    authorized_remote_client: TestClient, monkeypatch: pytest.MonkeyPatch,
    error: Exception, status: int, code: str,
) -> None:
    async def failing_probe(*_args, **_kwargs) -> ProbeOutcome:
        raise error

    monkeypatch.setattr(endpoints_probe, "probe_models", failing_probe)

    response = authorized_remote_client.post(PROBE_PATH, json=_body())
    payload = response.json()

    assert response.status_code == status
    assert payload["code"] == code
    assert payload["message"]
    assert "sk-endpoint-test-secret" not in payload["message"]


def test_missing_internal_token_is_rejected_even_for_valid_body(
    remote_client: TestClient,
) -> None:
    response = remote_client.post(PROBE_PATH, json=_body())

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"
    assert "sk-endpoint-test-secret" not in response.text


# ----------------------------------------------------------------------
# 内部 Schema：Provider 集合与 API Key 长度（与 Java 保存 DTO / 03 对齐）
# ----------------------------------------------------------------------


def _config(provider: str) -> dict[str, str]:
    return {
        "provider": provider,
        "base_url": "https://api.example.com/v1",
        "api_key": "sk-test",
        "model_name": "m",
    }


def test_schema_rejects_deepseek_for_embedding_probe() -> None:
    with pytest.raises(ValidationError):
        ModelProbeRequest(
            user_id="10001", probe_type="EMBEDDING",
            embedding_config=_config("DEEPSEEK"),
        )


def test_schema_rejects_deepseek_for_both_embedding_config() -> None:
    with pytest.raises(ValidationError):
        ModelProbeRequest(
            user_id="10001", probe_type="BOTH",
            chat_config=_config("DEEPSEEK"),
            embedding_config=_config("DEEPSEEK"),
        )


def test_schema_allows_deepseek_for_chat_probe() -> None:
    request = ModelProbeRequest(
        user_id="10001", probe_type="CHAT",
        chat_config=_config("DEEPSEEK"),
    )

    assert request.chat_config is not None


def test_schema_rejects_non_ascii_api_key() -> None:
    with pytest.raises(ValidationError):
        ModelProbeRequest(
            user_id="10001", probe_type="CHAT",
            chat_config={**_config("DEEPSEEK"), "api_key": "密" * 10},
        )


def test_schema_enforces_api_key_max_length() -> None:
    with pytest.raises(ValidationError):
        ModelProbeRequest(
            user_id="10001", probe_type="CHAT",
            chat_config={**_config("DEEPSEEK"), "api_key": "k" * 513},
        )

    # 512 字符应当通过 Schema 校验（加密后的列宽校验由 Java 负责）
    request = ModelProbeRequest(
        user_id="10001", probe_type="CHAT",
        chat_config={**_config("DEEPSEEK"), "api_key": "k" * 512},
    )
    assert request.chat_config is not None
