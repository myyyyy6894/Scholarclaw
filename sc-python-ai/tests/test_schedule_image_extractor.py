"""Synthetic files and a fake gateway verify safety/contract, not recognition quality."""

from __future__ import annotations

import base64
import json
import os
import traceback
from pathlib import Path
from typing import Any

import pytest
from starlette.testclient import TestClient

from app.core.config import get_settings
from app.core.exceptions import NonRetryableAiError, UpstreamModelError
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy
from app.services.schedule import image_extractor

PATH = "/internal/ai/v1/schedule/extract-image"
NAME = "a" * 32 + ".png"
# Only the header is needed here because Java is responsible for full image decoding.
# This is deliberately synthetic and is never sent to an external service.
IMAGE = b"\x89PNG\r\n\x1a\nsynthetic-test-bytes"
TEST_KEY = "sk-unit-test-not-real"


def _row(**changes: Any) -> dict[str, Any]:
    result: dict[str, Any] = {
        "source_text": "数学 周一 1-2节", "title": "数学", "teacher": None, "room": None,
        "weekday": 1, "start_period": 1, "end_period": 2, "start_time": None,
        "end_time": None, "weeks": [1, 2, 3], "issues": [],
    }
    result.update(changes)
    return result


def _response(rows: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(
        {"rows": rows if rows is not None else [_row()]}, ensure_ascii=False)}}]}


def _body(**changes: Any) -> dict[str, Any]:
    result: dict[str, Any] = {
        "user_id": "10001", "source_name": NAME, "week_count": 16,
        "chat_config": {"provider": "DEEPSEEK", "base_url": "https://example.com/v1",
                        "api_key": TEST_KEY, "model_name": "synthetic-vision-model"},
    }
    result.update(changes)
    return result


def _file(user_id: str = "10001", name: str = NAME, content: bytes = IMAGE) -> Path:
    path = Path(get_settings().storage_raw_path) / "schedule-import" / user_id / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


class FakeGateway:
    def __init__(self, response: Any) -> None:
        self.response = response
        self.calls: list[tuple[Any, str, dict[str, Any], Any]] = []

    async def __aenter__(self) -> "FakeGateway":
        return self

    async def __aexit__(self, *_: object) -> None:
        return None

    async def post_json(self, endpoint: Any, path: str, payload: dict[str, Any], *, retry: Any) -> Any:
        self.calls.append((endpoint, path, payload, retry))
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def test_auth_identity_and_contract_use_single_fake_call(
    remote_client: TestClient, authorized_remote_client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _file()
    gateway = FakeGateway(_response())
    monkeypatch.setattr(image_extractor, "SsrfSafeModelGateway", lambda _settings: gateway)

    assert remote_client.post(PATH, json=_body()).status_code == 403
    assert authorized_remote_client.post(PATH, json=_body(user_id="10002")).status_code == 400
    assert not gateway.calls
    response = authorized_remote_client.post(PATH, json=_body())
    assert response.status_code == 200
    assert response.json()["data"]["rows"] == [_row()]
    assert len(gateway.calls) == 1
    endpoint, path, payload, retry = gateway.calls[0]
    assert endpoint.api_key == TEST_KEY and path == "chat/completions"
    assert retry == RetryPolicy.no_retry()
    image_url = payload["messages"][1]["content"][1]["image_url"]["url"]
    assert image_url == "data:image/png;base64," + base64.b64encode(IMAGE).decode()
    assert TEST_KEY not in response.text and image_url not in response.text


@pytest.mark.parametrize("source_name", ["../x.png", "https://example.com/a.png", "/tmp/a.png", "a" * 32 + ".svg"])
def test_endpoint_rejects_arbitrary_sources(authorized_remote_client: TestClient, source_name: str) -> None:
    assert authorized_remote_client.post(PATH, json=_body(source_name=source_name)).status_code == 400


def test_source_owner_symlinks_and_limits_are_enforced(tmp_path: Path) -> None:
    path = _file()
    root = get_settings().storage_raw_path
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(root, "10002", NAME)
    path.unlink()
    target = tmp_path / "outside.png"
    target.write_bytes(IMAGE)
    path.symlink_to(target)
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(root, "10001", NAME)
    path.unlink()
    path.write_bytes(b"wrong signature")
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(root, "10001", NAME)
    path.write_bytes(IMAGE + b"x" * image_extractor.MAX_IMAGE_BYTES)
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(root, "10001", NAME)
    path.unlink()
    os.mkfifo(path)
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(root, "10001", NAME)


def test_parent_directory_symlink_is_rejected(tmp_path: Path) -> None:
    root = Path(get_settings().storage_raw_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / NAME).write_bytes(IMAGE)
    schedule_dir = root / "schedule-import"
    schedule_dir.mkdir()
    (schedule_dir / "10001").symlink_to(outside, target_is_directory=True)
    with pytest.raises(NonRetryableAiError):
        image_extractor._read_image(str(root), "10001", NAME)


@pytest.mark.parametrize("row", [
    _row(weekday=True), _row(weekday=8), _row(weeks=[2, 1]), _row(weeks=[1, 1]),
    _row(weeks=[17]), _row(start_period=3, end_period=2), _row(end_period=None),
    _row(start_time="09:00:00", end_time=None), _row(start_time="09:00:00", end_time="08:00:00"),
    _row(start_time="25:00:00", end_time="26:00:00"), _row(extra="not allowed"),
])
def test_untrusted_model_fields_are_rejected(row: dict[str, Any]) -> None:
    with pytest.raises(UpstreamModelError):
        image_extractor._parse_suggestions(_response([row]), 16)


@pytest.mark.parametrize("content", [
    '{"rows":[],"rows":[]}', '```json\n{"rows":[]}\n```', '{"rows":[],"extra":1}',
    "x" * (image_extractor.MAX_MODEL_CONTENT + 1),
])
def test_malformed_or_duplicate_json_rejected_without_echo(content: str) -> None:
    with pytest.raises(UpstreamModelError) as error:
        image_extractor._parse_suggestions({"choices": [{"message": {"content": content}}]}, 16)
    assert content not in str(error.value)


def test_truncated_or_failed_model_is_never_retried(
    authorized_remote_client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _file()
    truncated = _response()
    truncated["choices"][0]["finish_reason"] = "length"
    gateway = FakeGateway(truncated)
    monkeypatch.setattr(image_extractor, "SsrfSafeModelGateway", lambda _settings: gateway)
    result = authorized_remote_client.post(PATH, json=_body())
    assert result.status_code == 502
    assert len(gateway.calls) == 1
    assert TEST_KEY not in result.text


def test_validation_traceback_does_not_leak_model_output() -> None:
    sensitive = "private-model-output-" + "x" * 200
    with pytest.raises(UpstreamModelError) as error:
        image_extractor._parse_suggestions(_response([_row(title=sensitive)]), 16)
    assert sensitive not in "".join(traceback.format_exception(error.value))


def test_unexpected_gateway_error_is_generic_and_not_retried(
    authorized_remote_client: TestClient, monkeypatch: pytest.MonkeyPatch,
) -> None:
    _file()
    gateway = FakeGateway(RuntimeError("transport-internal-secret-data"))
    monkeypatch.setattr(image_extractor, "SsrfSafeModelGateway", lambda _settings: gateway)
    response = authorized_remote_client.post(PATH, json=_body())
    assert response.status_code == 502
    assert len(gateway.calls) == 1
    assert "transport-internal-secret-data" not in response.text
