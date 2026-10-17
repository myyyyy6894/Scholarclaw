"""健康检查接口契约（`04` §4.9.8 / `API-CONF-03`）。"""

from __future__ import annotations

from pathlib import Path

import pytest
from starlette.testclient import TestClient

from tests.conftest import LOOPBACK_HOST, REMOTE_HOST, TEST_INTERNAL_TOKEN

HEALTH_PATH = "/internal/ai/v1/health"


def test_loopback_probe_needs_no_token(loopback_client: TestClient) -> None:
    """容器本机环回探针免令牌，便于 Compose healthcheck 无需转义令牌。"""
    response = loopback_client.get(HEALTH_PATH)

    assert response.status_code == 200
    assert response.json()["code"] == "OK"


def test_non_loopback_probe_requires_token(remote_client: TestClient) -> None:
    """跨容器来源不适用环回例外，缺少令牌一律 403。"""
    response = remote_client.get(HEALTH_PATH)

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_authorized_remote_probe_succeeds(authorized_remote_client: TestClient) -> None:
    response = authorized_remote_client.get(HEALTH_PATH)

    assert response.status_code == 200
    assert response.json()["code"] == "OK"
    assert response.json()["message"] == "Healthy"


def test_health_payload_reports_service_and_dependencies(loopback_client: TestClient) -> None:
    payload = loopback_client.get(HEALTH_PATH).json()

    assert payload["data"]["service"] == "sc-python-ai"
    assert payload["data"]["status"] == "UP"
    assert payload["data"]["dependencies"] == {"shared_storage_ro": "UP"}


def test_health_payload_has_full_envelope(loopback_client: TestClient) -> None:
    payload = loopback_client.get(HEALTH_PATH).json()

    assert set(payload) == {"code", "message", "data", "trace_id", "timestamp"}
    assert payload["trace_id"].startswith("tr_")
    assert payload["timestamp"].endswith("+08:00")


def test_storage_probe_reports_down_when_mount_missing(
    client_factory, tmp_path: Path
) -> None:
    """共享卷未挂载时如实报 DOWN，不做假报 UP。"""
    missing = tmp_path / "not-mounted"

    with client_factory(LOOPBACK_HOST, storage_raw_path=missing) as client:
        payload = client.get(HEALTH_PATH).json()

    assert payload["data"]["status"] == "DOWN"
    assert payload["data"]["dependencies"]["shared_storage_ro"] == "DOWN"


def test_health_payload_does_not_leak_paths(loopback_client: TestClient, storage_raw_dir: Path) -> None:
    """响应体不得出现挂载路径等内部信息（`API-CONF-03` 数据最小化要求）。"""
    body = loopback_client.get(HEALTH_PATH).text

    assert str(storage_raw_dir) not in body
    assert "/data/scholarclaw" not in body


def test_trace_id_is_returned_in_header(loopback_client: TestClient) -> None:
    response = loopback_client.get(HEALTH_PATH)

    assert response.headers["X-Trace-Id"].startswith("tr_")


@pytest.mark.parametrize("inbound", ["tr_0123456789ab", "tr_cron_0123456789ab", "garbage"])
def test_inbound_trace_id_is_propagated_only_when_well_formed(
    loopback_client: TestClient, inbound: str
) -> None:
    response = loopback_client.get(HEALTH_PATH, headers={"X-Trace-Id": inbound})
    trace_id = response.json()["trace_id"]

    if inbound == "tr_0123456789ab":
        assert trace_id == inbound
    else:
        assert trace_id != inbound
        assert trace_id.startswith("tr_")


def test_remote_health_works_with_token(client_factory) -> None:
    with client_factory(REMOTE_HOST) as client:
        client.headers.update({"X-Internal-Token": TEST_INTERNAL_TOKEN})
        assert client.get(HEALTH_PATH).status_code == 200
