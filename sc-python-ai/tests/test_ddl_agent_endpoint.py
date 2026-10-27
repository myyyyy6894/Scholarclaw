"""内部 DDL 提取接口测试（TASK-701，`04` §4.9.2 `API-INT-AI-002`）。

覆盖鉴权、身份一致性、请求校验、FORCE/AUTO_ROUTE 行为、统一响应信封、
错误脱敏，以及“不触碰持久化/MQ”的结构性断言。
"""

from __future__ import annotations

import ast
from datetime import date, time, datetime
from pathlib import Path
from typing import Any

import pytest

from app.api.internal_v1.endpoints_agent import get_agent_router, get_ddl_extractor
from app.core.exceptions import UpstreamModelError
from app.schemas.agent_route import AgentIntent
from app.schemas.ddl_extract import (
    DdlExtractRequest,
    DdlPayload,
    ExtractionMode,
    TimePrecision,
)
from app.services.agent.router import RouteResult, RouteSource
from app.services.ddl.extractor import DdlExtractionRequest, DdlExtractionResult
from app.services.ddl.time_parser import DdlInitialStatus, TimeResolution
from tests.conftest import REMOTE_HOST, TEST_INTERNAL_TOKEN

API_KEY = "sk-endpoint-secret-key"
BODY_USER_ID = "10001"


class FakeRouter:
    def __init__(
        self,
        result: RouteResult | None = None,
        error: Exception | None = None,
    ) -> None:
        self.result = result
        self.error = error
        self.requests: list[DdlExtractRequest] = []

    async def route(self, request: DdlExtractRequest) -> RouteResult:
        self.requests.append(request)
        if self.error is not None:
            raise self.error
        assert self.result is not None
        return self.result


class FakeExtractor:
    def __init__(
        self,
        result: DdlExtractionResult | None = None,
        error: Exception | None = None,
    ) -> None:
        self.result = result
        self.error = error
        self.requests: list[DdlExtractionRequest] = []

    async def extract(self, request: DdlExtractionRequest) -> DdlExtractionResult:
        self.requests.append(request)
        if self.error is not None:
            raise self.error
        assert self.result is not None
        return self.result


def _result() -> DdlExtractionResult:
    return DdlExtractionResult(
        intent_confidence=0.98,
        payload=DdlPayload(
            course_name="操作系统原理",
            task_title="提交实验二报告与源码",
            raw_time_text="10月25日 22:00 前",
            time_precision=TimePrecision.EXACT_DATETIME,
            is_relative_time=False,
            parsed_due_date=date(2026, 10, 25),
            parsed_due_time=time(22, 0, 0),
            deliverable_format="学号_姓名.zip",
            submission_channel="学习通作业区",
            confidence=0.96,
            has_ambiguity=False,
            ambiguity_reason=None,
        ),
        time_resolution=TimeResolution(
            time_precision=TimePrecision.EXACT_DATETIME,
            due_date=date(2026, 10, 25),
            due_time=time(22, 0, 0),
            due_at=datetime.fromisoformat("2026-10-25T22:00:00+08:00"),
            time_inferred=False,
            is_relative_time=False,
            has_ambiguity=False,
            ambiguity_reason=None,
            initial_status=DdlInitialStatus.ACTIVE,
        ),
    )


def _body(**overrides: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "user_id": BODY_USER_ID,
        "mode": "FORCE_DDL_EXTRACT",
        "raw_text": "各位同学，10月25日 22:00 前请将实验二报告交到学习通作业区。",
        "reference_time": "2026-10-15T11:40:00+08:00",
        "user_timezone": "Asia/Shanghai",
        "candidate_courses": ["操作系统原理", "数据库系统概念"],
        "chat_config": {
            "provider": "OPENAI_COMPATIBLE",
            "base_url": "https://api.example.com/v1",
            "api_key": API_KEY,
            "model_name": "chat-model",
        },
    }
    body.update(overrides)
    return body


@pytest.fixture
def agent_client(client_factory):
    with client_factory(REMOTE_HOST, SC_INGEST_CONSUMER_ENABLED="false") as client:
        client.headers.update(
            {
                "X-Internal-Token": TEST_INTERNAL_TOKEN,
                "X-User-Id": BODY_USER_ID,
            }
        )
        yield client


@pytest.fixture
def anon_agent_client(client_factory):
    with client_factory(REMOTE_HOST, SC_INGEST_CONSUMER_ENABLED="false") as client:
        yield client


def _fastapi(client):
    app = client.app
    return getattr(app, "app", app)


def _override(client, extractor: FakeExtractor) -> None:
    _fastapi(client).dependency_overrides[get_ddl_extractor] = lambda: extractor


def _override_router(client, agent_router: FakeRouter) -> None:
    _fastapi(client).dependency_overrides[get_agent_router] = lambda: agent_router


def test_force_ddl_extract_returns_contract_payload(agent_client) -> None:
    extractor = FakeExtractor(result=_result())
    _override(agent_client, extractor)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body()
    )

    assert response.status_code == 200
    envelope = response.json()
    assert set(envelope) == {"code", "message", "data", "trace_id", "timestamp"}
    assert envelope["code"] == "OK"
    assert envelope["message"] == "Extraction completed"
    assert response.headers["X-Trace-Id"] == envelope["trace_id"]

    data = envelope["data"]
    assert data["intent"] == "DDL_EXTRACT"
    assert data["intent_confidence"] == pytest.approx(0.98)
    payload = data["ddl_payload"]
    assert set(payload) == {
        "course_name",
        "task_title",
        "raw_time_text",
        "time_precision",
        "is_relative_time",
        "parsed_due_date",
        "parsed_due_time",
        "deliverable_format",
        "submission_channel",
        "confidence",
        "has_ambiguity",
        "ambiguity_reason",
    }
    assert payload["parsed_due_date"] == "2026-10-25"
    assert payload["parsed_due_time"] == "22:00:00"
    assert "due_at" not in payload
    assert "status" not in payload
    assert "time_inferred" not in payload
    assert "follow_up_at" not in payload
    assert "due_at" not in response.text
    assert "time_inferred" not in response.text
    assert "time_resolution" not in response.text
    assert API_KEY not in response.text

    captured = extractor.requests[0]
    assert captured.user_id == BODY_USER_ID
    assert captured.user_timezone == "Asia/Shanghai"
    assert captured.candidate_courses == ("操作系统原理", "数据库系统概念")
    assert captured.reference_time.utcoffset() is not None
    assert captured.chat_endpoint.model_name == "chat-model"
    assert captured.chat_endpoint.api_key == API_KEY


def test_force_mode_does_not_invoke_router(agent_client) -> None:
    extractor = FakeExtractor(result=_result())
    agent_router = FakeRouter()
    _override(agent_client, extractor)
    _override_router(agent_client, agent_router)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body()
    )

    assert response.status_code == 200
    assert extractor.requests
    assert agent_router.requests == []


def test_auto_route_returns_ddl_payload_from_router(agent_client) -> None:
    payload = _result().payload
    agent_router = FakeRouter(
        result=RouteResult(
            intent=AgentIntent.DDL_EXTRACT,
            intent_confidence=0.92,
            ddl_payload=payload,
            source=RouteSource.MODEL,
        )
    )
    extractor = FakeExtractor()
    _override(agent_client, extractor)
    _override_router(agent_client, agent_router)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(mode="AUTO_ROUTE"),
    )

    assert response.status_code == 200
    envelope = response.json()
    assert envelope["code"] == "OK"
    assert envelope["message"] == "Routing completed"
    data = envelope["data"]
    assert data["intent"] == "DDL_EXTRACT"
    assert data["intent_confidence"] == pytest.approx(0.92)
    assert data["ddl_payload"]["parsed_due_date"] == "2026-10-25"
    assert "due_at" not in response.text
    assert "time_inferred" not in response.text
    assert API_KEY not in response.text

    captured = agent_router.requests[0]
    assert captured.mode is ExtractionMode.AUTO_ROUTE
    assert captured.candidate_courses == ["操作系统原理", "数据库系统概念"]
    # AUTO_ROUTE 的提取由路由器内部完成，端点不得再调用一次提取器
    assert extractor.requests == []


def test_auto_route_low_confidence_has_null_payload(agent_client) -> None:
    agent_router = FakeRouter(
        result=RouteResult(
            intent=AgentIntent.CHITCHAT_HELP,
            intent_confidence=0.4,
            ddl_payload=None,
            source=RouteSource.MODEL,
        )
    )
    extractor = FakeExtractor()
    _override(agent_client, extractor)
    _override_router(agent_client, agent_router)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(mode="AUTO_ROUTE"),
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["intent"] == "CHITCHAT_HELP"
    assert data["intent_confidence"] == pytest.approx(0.4)
    assert data["ddl_payload"] is None
    assert extractor.requests == []


def test_auto_route_router_error_maps_to_envelope(agent_client) -> None:
    agent_router = FakeRouter(
        error=UpstreamModelError("模型未能返回符合契约的意图分类结果，请稍后重试。", retryable=False)
    )
    _override(agent_client, FakeExtractor())
    _override_router(agent_client, agent_router)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(mode="AUTO_ROUTE"),
    )

    assert response.status_code == 502
    envelope = response.json()
    assert envelope["code"] == "UPSTREAM_MODEL_ERROR"
    assert envelope["data"] is None
    assert API_KEY not in response.text
    assert '"intent"' not in response.text
    assert '"ddl_payload"' not in response.text


def test_missing_internal_token_is_rejected(anon_agent_client) -> None:
    response = anon_agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body()
    )

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_wrong_internal_token_is_rejected(anon_agent_client) -> None:
    response = anon_agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(),
        headers={"X-Internal-Token": "wrong-token", "X-User-Id": BODY_USER_ID},
    )

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_missing_user_header_is_rejected(anon_agent_client) -> None:
    response = anon_agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(),
        headers={"X-Internal-Token": TEST_INTERNAL_TOKEN},
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_body_user_mismatch_is_rejected(agent_client) -> None:
    extractor = FakeExtractor(result=_result())
    _override(agent_client, extractor)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(user_id="10002"),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert "不一致" in response.json()["message"]
    assert extractor.requests == []


@pytest.mark.parametrize("raw_text", ["", "   "])
def test_blank_raw_text_is_rejected(agent_client, raw_text: str) -> None:
    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body(raw_text=raw_text)
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_overlong_raw_text_is_rejected(agent_client) -> None:
    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body(raw_text="a" * 4001)
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_naive_reference_time_is_rejected(agent_client) -> None:
    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(reference_time="2026-10-15T11:40:00"),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_invalid_timezone_is_rejected(agent_client) -> None:
    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(user_timezone="Mars/Base"),
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_unknown_mode_is_rejected(agent_client) -> None:
    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body(mode="SOMETHING_ELSE")
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_missing_user_timezone_is_rejected(agent_client) -> None:
    """docs/04 将 user_timezone 标为必填；缺失不得静默回退 Asia/Shanghai。"""
    body = _body()
    body.pop("user_timezone")

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=body
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_missing_chat_config_is_rejected(agent_client) -> None:
    body = _body()
    body.pop("chat_config")

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=body
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_invalid_api_key_is_rejected_without_echoing_it(agent_client) -> None:
    bad_key = "sk with spaces"
    body = _body()
    body["chat_config"]["api_key"] = bad_key

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=body
    )

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert bad_key not in response.text


def test_reference_time_with_utc_offset_is_accepted(agent_client) -> None:
    extractor = FakeExtractor(result=_result())
    _override(agent_client, extractor)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract",
        json=_body(reference_time="2026-10-15T03:40:00+00:00"),
    )

    assert response.status_code == 200
    assert extractor.requests[0].reference_time == datetime.fromisoformat(
        "2026-10-15T03:40:00+00:00"
    )


def test_extractor_error_maps_to_envelope_without_leaking_details(agent_client) -> None:
    extractor = FakeExtractor(
        error=UpstreamModelError("上游模型服务暂时异常，请稍后重试。", retryable=False)
    )
    _override(agent_client, extractor)

    response = agent_client.post(
        "/internal/ai/v1/agent/route-and-extract", json=_body()
    )

    assert response.status_code == 502
    envelope = response.json()
    assert envelope["code"] == "UPSTREAM_MODEL_ERROR"
    assert set(envelope) == {"code", "message", "data", "trace_id", "timestamp"}
    # 已处理异常会同时经异常处理器与追踪中间件写头，值必须一致。
    assert response.headers.get_list("X-Trace-Id")
    assert all(
        header == envelope["trace_id"]
        for header in response.headers.get_list("X-Trace-Id")
    )
    assert API_KEY not in response.text
    assert "10月25日" not in response.text
    assert '"intent"' not in response.text
    assert '"ddl_payload"' not in response.text


_FORBIDDEN_IMPORT_PREFIXES = (
    "aio_pika",
    "pika",
    "chromadb",
    "sqlalchemy",
    "aiomysql",
    "pymysql",
    "mysql",
    "redis",
)


def test_ddl_extraction_has_no_persistence_or_mq_dependency() -> None:
    """结构性断言：DDL 提取代码不导入数据库、Redis、ChromaDB 或 RabbitMQ 客户端。

    TASK-701 只做无状态提取；`t_ddl_task` 写入、Redis 草案与 MQ 发布属 TASK-703/704。
    """
    repo_root = Path(__file__).resolve().parents[1]
    files = [
        repo_root / "app" / "services" / "ddl" / "extractor.py",
        repo_root / "app" / "services" / "agent" / "router.py",
        repo_root / "app" / "services" / "agent" / "intents.py",
        repo_root / "app" / "api" / "internal_v1" / "endpoints_agent.py",
    ]
    for path in files:
        tree = ast.parse(path.read_text(encoding="utf-8"))
        imported: list[str] = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.extend(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.append(node.module)
        for module in imported:
            assert not module.startswith(_FORBIDDEN_IMPORT_PREFIXES), (
                f"{path.name} 不应导入 {module}"
            )
        source = path.read_text(encoding="utf-8")
        assert "t_ddl_task" not in source
        assert "basic_publish" not in source
