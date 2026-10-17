"""内部令牌鉴权（`NFR-SEC-04`、`04` §2.7.1 / §6.1 / `API-CONF-03`）。"""

from __future__ import annotations

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


def test_health_is_the_only_registered_internal_route_for_now() -> None:
    """记录当前路由规模：新增内部接口时必须同步确认鉴权依赖已加。"""
    from app.main import create_app

    paths = {
        getattr(route, "path", "")
        for route in create_app().routes
        if getattr(route, "path", "").startswith("/internal/")
    }

    assert paths == {HEALTH_PATH}


def test_user_id_header_is_ignored_when_token_invalid(remote_client: TestClient) -> None:
    """未通过令牌校验时不得信任 X-User-Id（`04` §6.1.2）。"""
    from app.core import tracing

    response = remote_client.get(HEALTH_PATH, headers={"X-User-Id": "10001"})

    assert response.status_code == 403
    assert tracing.current_user_id() is None
