"""内部令牌鉴权（`NFR-SEC-04`、`04` §2.7.1 / §6.1 / `API-CONF-03`）。"""

from __future__ import annotations

import pytest
from starlette.requests import Request
from starlette.testclient import TestClient

from tests.conftest import LOOPBACK_HOST, REMOTE_HOST, TEST_INTERNAL_TOKEN

HEALTH_PATH = "/internal/ai/v1/health"


def test_missing_token_is_rejected_for_non_loopback(remote_client: TestClient) -> None:
    response = remote_client.get(HEALTH_PATH)

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_wrong_token_is_rejected(remote_client: TestClient) -> None:
    response = remote_client.get(HEALTH_PATH, headers={"X-Internal-Token": f"{TEST_INTERNAL_TOKEN}x"})

    assert response.status_code == 403


def test_correct_token_is_accepted(remote_client: TestClient) -> None:
    response = remote_client.get(HEALTH_PATH, headers={"X-Internal-Token": TEST_INTERNAL_TOKEN})

    assert response.status_code == 200


def test_token_not_configured_fails_closed(client_factory) -> None:
    """令牌未注入时必须拒绝所有跨容器调用，而不是跳过校验。"""
    with client_factory(REMOTE_HOST, token=None) as client:
        response = client.get(HEALTH_PATH, headers={"X-Internal-Token": TEST_INTERNAL_TOKEN})

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_rejection_response_never_echoes_token(remote_client: TestClient) -> None:
    attempted = "attempted-secret-value"
    body = remote_client.get(HEALTH_PATH, headers={"X-Internal-Token": attempted}).text

    assert attempted not in body
    assert TEST_INTERNAL_TOKEN not in body


def test_loopback_exemption_applies_to_health_only(client_factory) -> None:
    """`API-CONF-03` 的免令牌例外只覆盖健康检查路径，不得扩大到业务接口。"""
    from app.main import create_app

    app = create_app()
    exempt: list[str] = []
    guarded: list[str] = []

    for route in app.routes:
        path = getattr(route, "path", "")
        methods = getattr(route, "methods", None) or set()
        if not path.startswith("/internal/") or not methods - {"HEAD", "OPTIONS"}:
            continue
        with client_factory(LOOPBACK_HOST) as client:
            status = client.request("GET", path).status_code
        (exempt if status == 200 else guarded).append(path)

    assert exempt == [HEALTH_PATH], "环回免令牌路径集合必须恰好是健康检查"
    # 后续任务每新增一个内部业务接口，都会落进 guarded 并在上面被验证为需要令牌；
    # 当前骨架只有健康检查，因此 guarded 为空是预期结果。
    assert all(path not in guarded for path in exempt)


def test_registered_internal_routes_are_exactly_the_expected_set() -> None:
    """记录当前内部路由规模：新增内部接口时必须同步确认鉴权依赖已加。

    TASK-302 新增 `POST /model/probe`；TASK-504 新增 `DELETE /rag/vectors`。
    两者均为跨容器调用，必须携带 `X-Internal-Token`。
    """
    from app.main import create_app

    paths = {
        getattr(route, "path", "")
        for route in create_app().routes
        if getattr(route, "path", "").startswith("/internal/")
    }

    assert paths == {HEALTH_PATH, "/internal/ai/v1/model/probe",
                     "/internal/ai/v1/rag/vectors"}


def test_user_id_header_is_ignored_when_token_invalid(remote_client: TestClient) -> None:
    """未通过令牌校验时不得信任 X-User-Id（`04` §6.1.2）。"""
    from app.core import tracing

    response = remote_client.get(HEALTH_PATH, headers={"X-User-Id": "10001"})

    assert response.status_code == 403
    assert tracing.current_user_id() is None


# ----------------------------------------------------------------------
# 身份绑定时机与信任边界（04 §6.1.2、TASK-105 复核）
#
# 直接对鉴权依赖下断言：身份只在"令牌校验通过 + 取值合法"时才被绑定，
# 且日志字段 user_id 由该绑定驱动（见 app.core.logging.TraceContextFilter）。
# ----------------------------------------------------------------------


def _internal_request(headers: dict[str, str]) -> Request:
    """构造一个不经网络往返的内部请求，用于直接驱动鉴权依赖。"""
    return Request(
        {
            "type": "http",
            "http_version": "1.1",
            "method": "GET",
            "scheme": "http",
            "path": HEALTH_PATH,
            "raw_path": HEALTH_PATH.encode(),
            "query_string": b"",
            "headers": [(name.lower().encode(), value.encode()) for name, value in headers.items()],
            "client": (REMOTE_HOST, 51234),
            "server": ("sc-python-ai", 8000),
        }
    )


def test_authenticated_request_binds_real_user_id_for_logging() -> None:
    """令牌校验通过后，真实 user_id 必须进入日志上下文（而不是一直显示 -）。"""
    from app.core import tracing
    from app.core.security import verify_internal_token

    tracing.clear()
    try:
        verify_internal_token(
            _internal_request(
                {"X-Internal-Token": TEST_INTERNAL_TOKEN, "X-User-Id": "10001"}
            )
        )

        assert tracing.current_user_id() == "10001"
    finally:
        tracing.clear()


def test_forged_user_id_is_dropped_even_with_valid_token() -> None:
    """令牌合法但 X-User-Id 非十进制字符串时一律丢弃，避免脏值污染日志。"""
    from app.core import tracing
    from app.core.security import verify_internal_token

    for forged in ("10001 OR 1=1", "abc", "10001abc", "-10001", "1e5", "  "):
        tracing.clear()
        try:
            verify_internal_token(
                _internal_request(
                    {"X-Internal-Token": TEST_INTERNAL_TOKEN, "X-User-Id": forged}
                )
            )

            assert tracing.current_user_id() is None, f"取值 {forged!r} 不应被绑定"
        finally:
            tracing.clear()


def test_user_id_is_not_bound_when_token_check_fails() -> None:
    """令牌缺失/错误时不得绑定身份（顺序必须是先鉴权再信任）。"""
    from app.core import tracing
    from app.core.security import verify_internal_token
    from app.core.exceptions import InternalTokenInvalidError

    for headers in (
        {"X-User-Id": "10001"},
        {"X-Internal-Token": "wrong-token", "X-User-Id": "10001"},
    ):
        tracing.clear()
        try:
            with pytest.raises(InternalTokenInvalidError):
                verify_internal_token(_internal_request(headers))

            assert tracing.current_user_id() is None
        finally:
            tracing.clear()


def test_loopback_health_probe_carries_no_identity() -> None:
    """环回健康探针免令牌放行，但**不**因此获得任何用户身份。"""
    from app.core import tracing
    from app.core.security import verify_internal_token

    tracing.clear()
    try:
        request = _internal_request({"X-User-Id": "10001"})
        request.scope["client"] = (LOOPBACK_HOST, 51234)

        verify_internal_token(request, allow_loopback=True)

        assert tracing.current_user_id() is None
    finally:
        tracing.clear()
