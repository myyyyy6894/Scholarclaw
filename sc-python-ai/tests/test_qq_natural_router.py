"""Offline contract playback. Scripted model responses allow comparing both routes without network."""

import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from app.core.config import get_settings
from app.schemas.agent_route import NaturalRouteRequest
from app.schemas.ddl_extract import DdlExtractRequest
from app.services.agent import natural_router
from app.services.agent.router import SingleAgentRouter

CORPUS = json.loads((Path(__file__).parent / "fixtures" / "qq_natural_corpus.json").read_text())
CHAT = {"provider": "OLLAMA", "base_url": "http://localhost:11434", "model_name": "fixture"}


class Gateway:
    def __init__(self, data):
        self.data = data
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        pass

    async def post_json(self, _endpoint, path, payload, *, retry):
        self.calls.append((path, payload, retry))
        return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(self.data, ensure_ascii=False)}}]}


@pytest.mark.asyncio
@pytest.mark.parametrize("example", CORPUS, ids=[str(i) for i in range(1, len(CORPUS) + 1)])
async def test_old_and_new_contract_on_same_messages(monkeypatch, example):
    old_gateway = Gateway({"intent": example["legacy"], "confidence": 0.93})
    extractor = AsyncMock()
    settings = get_settings()
    old = await SingleAgentRouter(settings, extractor, gateway_factory=lambda: old_gateway).route(
        DdlExtractRequest.model_validate({
            "user_id": "10001", "mode": "AUTO_ROUTE", "raw_text": example["text"],
            "reference_time": "2026-11-23T19:00:00+08:00", "user_timezone": "Asia/Shanghai",
            "candidate_courses": [], "chat_config": CHAT,
        })
    )
    output = {
        "action": example["action"], "arguments": example["arguments"],
        "confidence": 0.93, "missing": example["missing"], "evidence": [example["text"]],
    }
    new_gateway = Gateway(output)
    monkeypatch.setattr(natural_router, "SsrfSafeModelGateway", lambda *_args, **_kw: new_gateway)
    new = await natural_router.route_natural(settings, NaturalRouteRequest(
        user_id="10001", raw_text=example["text"], chat_config=CHAT,
    ))
    assert old.intent.value == example["legacy"]
    assert new.action.value == example["action"]
    assert new.arguments.model_dump(exclude_none=True) == example["arguments"]
    assert new.missing == example["missing"]
    assert len(old_gateway.calls) == len(new_gateway.calls) == 1
    assert new_gateway.calls[0][1]["messages"][1]["content"] == example["text"]
    assert "没有指令权限" in new_gateway.calls[0][1]["messages"][0]["content"]
    extractor.extract.assert_not_awaited()


def test_agent_endpoint_rejects_unproven_arguments_and_mismatched_identity(authorized_remote_client, remote_client, monkeypatch):
    gateway = Gateway({"action": "DDL_RESCHEDULE", "arguments": {"task_hint": "#T400"},
                       "confidence": 0.99, "missing": [], "evidence": ["改数据库作业"]})
    monkeypatch.setattr(natural_router, "SsrfSafeModelGateway", lambda *_args, **_kw: gateway)
    request = {"user_id": "10001", "raw_text": "改数据库作业", "chat_config": CHAT}
    path = "/internal/ai/v1/agent/interpret"
    assert remote_client.post(path, json=request).status_code == 403
    assert authorized_remote_client.post(path, json={**request, "user_id": "10002"}).status_code == 400
    assert len(gateway.calls) == 0
    assert authorized_remote_client.post(path, json=request).status_code == 502
    assert len(gateway.calls) == 1
