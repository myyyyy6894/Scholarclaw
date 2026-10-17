"""日志脱敏与追踪字段（`NFR-SEC-02`、`02` §11）。"""

from __future__ import annotations

import logging

from app.core.logging import (
    SensitiveDataMaskingFilter,
    TraceContextFilter,
    mask_sensitive,
)
from app.core import tracing


def test_masks_prefixed_model_api_key() -> None:
    masked = mask_sensitive("请求头携带 sk-liveabcdef1234567890 已失效")

    assert "sk-liveabcdef1234567890" not in masked
    assert "sk-****" in masked


def test_masks_json_secret_fields() -> None:
    masked = mask_sensitive('{"api_key":"sk-abcdef123456","model":"gpt-4o","token":"my secret value"}')

    assert "sk-abcdef123456" not in masked
    assert '"api_key":"****"' in masked
    assert '"token":"****"' in masked
    assert '"model":"gpt-4o"' in masked


def test_masks_key_value_secrets() -> None:
    masked = mask_sensitive("api_key=abc123def456 password=hunter2 model=gpt-4o")

    assert "abc123def456" not in masked
    assert "hunter2" not in masked
    assert "model=gpt-4o" in masked


def test_masks_jwt_and_bearer_header() -> None:
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAwMSJ9.c2lnbmF0dXJl"
    masked = mask_sensitive(f"Authorization: Bearer {jwt}")

    assert jwt not in masked
    assert "c2lnbmF0dXJl" not in masked
    assert "Authorization" in masked


def test_masks_encrypted_ciphertext() -> None:
    ciphertext = "v1:Q2F1dGhTZWNyZXRJdg==:c2NfY2lwaGVydGV4dF90YWdfdmFsdWU="
    masked = mask_sensitive(f"保存密文 {ciphertext}")

    assert "Q2F1dGhTZWNyZXRJdg==" not in masked
    assert "v1:****:****" in masked


def test_keeps_ordinary_business_logs_intact() -> None:
    message = "任务创建成功 task_id=102 course_id=2001 parse_version=3"

    assert mask_sensitive(message) == message


def test_handles_none_and_empty() -> None:
    assert mask_sensitive(None) is None
    assert mask_sensitive("") == ""


def test_filter_rewrites_record_message() -> None:
    record = logging.LogRecord(
        name="sc.test",
        level=logging.WARNING,
        pathname=__file__,
        lineno=1,
        msg="上传失败 api_key=%s",
        args=("sk-liveabcdef1234567890",),
        exc_info=None,
    )

    assert SensitiveDataMaskingFilter().filter(record) is True
    assert "sk-liveabcdef1234567890" not in record.getMessage()


def test_trace_filter_injects_context_fields() -> None:
    token = tracing.bind_trace_id("tr_0123456789ab")
    user_token = tracing.bind_user_id("10001")
    try:
        record = logging.LogRecord("sc.test", logging.INFO, __file__, 1, "hello", (), None)
        assert TraceContextFilter().filter(record) is True
        assert record.trace_id == "tr_0123456789ab"
        assert record.user_id == "10001"
    finally:
        tracing.reset(token)
        tracing.reset_user_id(user_token)


def test_trace_filter_falls_back_to_dash_outside_request() -> None:
    tracing.clear()
    record = logging.LogRecord("sc.test", logging.INFO, __file__, 1, "hello", (), None)

    TraceContextFilter().filter(record)

    assert record.trace_id == "-"
    assert record.user_id == "-"


def test_configure_logging_routes_uvicorn_loggers_through_our_formatter() -> None:
    """`NFR-REL-02` 要求两侧日志统一带 `[trace_id, user_id]`，uvicorn 的日志也必须收敛进来。"""
    from app.core.config import load_settings
    from app.core.logging import configure_logging

    configure_logging(load_settings({"SC_LOG_LEVEL": "INFO"}))

    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logger = logging.getLogger(name)
        assert logger.handlers == [], f"{name} 不应保留自带的 handler"
        assert logger.propagate is True, f"{name} 必须向上传播到统一 handler"

    root_handlers = logging.getLogger().handlers
    assert root_handlers
    pattern = root_handlers[0].formatter._fmt
    assert "%(trace_id)s" in pattern
    assert "%(user_id)s" in pattern
