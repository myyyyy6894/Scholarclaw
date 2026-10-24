import asyncio

import pytest

from app.api.internal_v1.endpoints_rag import _event_stream, get_rag_qa_pipeline
from app.core.exceptions import CitationVerificationFailedError, ParentChunkFetchFailedError
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.rag.qa_pipeline import (
    ChatTurn,
    CitationEvent,
    CitationItem,
    DoneEvent,
    RagQaRequest,
    RejectionNoticeEvent,
    TokenEvent,
)
from tests.conftest import REMOTE_HOST, TEST_INTERNAL_TOKEN


@pytest.fixture
def stream_client(client_factory):
    """带内部身份头的非环回客户端；关闭 MQ 消费者避免测试环境连接 broker。"""
    with client_factory(REMOTE_HOST, SC_INGEST_CONSUMER_ENABLED="false") as client:
        client.headers.update({
            "X-Internal-Token": TEST_INTERNAL_TOKEN,
            "X-User-Id": "10001",
        })
        yield client


@pytest.fixture
def anon_stream_client(client_factory):
    with client_factory(REMOTE_HOST, SC_INGEST_CONSUMER_ENABLED="false") as client:
        yield client


def _fastapi(client):
    app = client.app
    return getattr(app, "app", app)


class FakePipeline:
    def __init__(self, events=None, error=None):
        self.events = events or []
        self.error = error
        self.requests = []

    async def stream(self, request):
        self.requests.append(request)
        for event in self.events:
            yield event
        if self.error is not None:
            raise self.error


def _body(**overrides):
    body = {
        "user_id": "10001",
        "course_id": "2001",
        "course_name": "数据库系统概念",
        "course_index_version": 3,
        "valid_doc_ids": ["50001", "50002", "50001"],
        "query": "  两阶段锁协议？  ",
        "chat_history_window": [
            {"role": "USER", "content": "上一问"},
            {"role": "ASSISTANT", "content": "上一答"},
        ],
        "chat_config": {
            "provider": "OPENAI_COMPATIBLE",
            "base_url": "https://api.example.com/v1",
            "api_key": "sk-chat",
            "model_name": "chat",
        },
        "embedding_config": {
            "provider": "OPENAI_COMPATIBLE",
            "base_url": "https://api.example.com/v1",
            "api_key": "sk-embed",
            "model_name": "embed",
            "dim": 3,
        },
    }
    body.update(overrides)
    return body


def _citation():
    return CitationItem("pc_1", "50001", "第5章.pdf", 1, 2, "5.2",
                        "两阶段锁协议", 0.91)


def _request():
    return RagQaRequest(
        user_id="10001", course_id="2001", course_name="数据库系统概念",
        course_index_version=3, valid_doc_ids=("50001",),
        query="问题", embedding_endpoint=ModelEndpoint(
            "OPENAI_COMPATIBLE", "https://x/v1", "sk", "embed"), embedding_dim=3,
        chat_endpoint=ModelEndpoint(
            "OPENAI_COMPATIBLE", "https://x/v1", "sk", "chat"),
        trace_id="tr_0123456789ab",
        chat_history=(ChatTurn("USER", "上一问"),))


def test_qa_stream_emits_contract_frames_in_order(stream_client) -> None:
    pipeline = FakePipeline([
        CitationEvent((_citation(),)),
        TokenEvent("答案[来源：第5章.pdf - 第1页]", False),
        DoneEvent(False, 185, 1120, 0),
    ])
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: pipeline

    response = stream_client.post("/internal/ai/v1/rag/qa-stream", json=_body())

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    body = response.text
    assert body.index("event: citation") < body.index("event: token") < body.index("event: done")
    assert '"parent_chunk_id":"pc_1"' in body
    assert '"delta":"答案[来源：第5章.pdf - 第1页]"' in body
    assert '"is_rejected":false' in body
    assert '"retrieval_cost_ms":185' in body
    request = pipeline.requests[0]
    assert request.user_id == "10001"
    assert request.course_index_version == 3
    assert request.valid_doc_ids == ("50001", "50002")
    assert request.query == "两阶段锁协议？"
    assert request.embedding_dim == 3
    assert request.chat_endpoint.model_name == "chat"
    assert request.chat_history[1].content == "上一答"


def test_qa_stream_maps_pipeline_error_to_error_frame(stream_client) -> None:
    pipeline = FakePipeline(error=ParentChunkFetchFailedError())
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: pipeline

    response = stream_client.post("/internal/ai/v1/rag/qa-stream", json=_body())

    assert response.status_code == 200
    assert "event: error" in response.text
    assert '"code":"E-RAG-03"' in response.text
    assert "event: done" not in response.text


def test_qa_stream_maps_citation_failure_message(stream_client) -> None:
    pipeline = FakePipeline(error=CitationVerificationFailedError(
        "引用校验未通过（存在非法引用）；本次实际召回切片为：《第5章.pdf》第1-2页。"))
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: pipeline

    response = stream_client.post("/internal/ai/v1/rag/qa-stream", json=_body())

    assert response.status_code == 200
    assert "event: error" in response.text
    assert '"code":"E-RAG-03"' in response.text
    assert "引用校验未通过" in response.text
    assert "第1-2页" in response.text


def test_qa_stream_rejects_body_and_header_identity_mismatch(stream_client) -> None:
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: FakePipeline()

    response = stream_client.post(
        "/internal/ai/v1/rag/qa-stream", json=_body(user_id="10002"))

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_qa_stream_requires_internal_token(anon_stream_client) -> None:
    response = anon_stream_client.post("/internal/ai/v1/rag/qa-stream", json=_body())

    assert response.status_code == 403
    assert response.json()["code"] == "AUTH_FORBIDDEN"


def test_qa_stream_rejects_blank_query(stream_client) -> None:
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: FakePipeline()

    response = stream_client.post(
        "/internal/ai/v1/rag/qa-stream", json=_body(query="   "))

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_qa_stream_rejects_non_decimal_doc_ids(stream_client) -> None:
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: FakePipeline()

    response = stream_client.post(
        "/internal/ai/v1/rag/qa-stream", json=_body(valid_doc_ids=["abc"]))

    assert response.status_code == 400


def test_qa_stream_supports_rejection_notice(stream_client) -> None:
    pipeline = FakePipeline([
        RejectionNoticeEvent("📌 未找到相关内容", True),
        TokenEvent("💡 补充", True),
        DoneEvent(True, 110, 980, 0),
    ])
    _fastapi(stream_client).dependency_overrides[get_rag_qa_pipeline] = lambda: pipeline

    response = stream_client.post("/internal/ai/v1/rag/qa-stream", json=_body())

    assert "event: rejection_notice" in response.text
    assert '"has_general_supplement":true' in response.text
    assert '"is_general_supplement":true' in response.text
    assert '"is_rejected":true' in response.text


@pytest.mark.asyncio
async def test_cancelled_stream_does_not_emit_error_frame() -> None:
    generator = _event_stream(FakePipeline(error=asyncio.CancelledError()), _request())

    with pytest.raises(asyncio.CancelledError):
        await anext(generator)


@pytest.mark.asyncio
async def test_real_pipeline_malformed_citation_emits_error_without_done() -> None:
    from app.core.config import load_settings
    from app.services.rag.parent_chunk_client import AuthoritativeParentChunk
    from app.services.rag.qa_pipeline import RagQaPipeline
    from app.services.rag.retriever_base import ParentMatch, RetrievalResult

    class Retriever:
        async def retrieve(self, request):
            return RetrievalResult(
                parents=(ParentMatch("pc_50001_v1_1", "50001", 0.9),),
                top_similarity=0.9, below_threshold=False)

    class ParentClient:
        async def fetch(self, **kwargs):
            return (AuthoritativeParentChunk(
                "pc_50001_v1_1", "50001", "第5章.pdf", 1, 2, "5.2", "内容"),)

    class Gateway:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def stream_chat(self, endpoint, payload, **kwargs):
            yield "真实结论[来源：第5章.pdf - 第1页]"
            yield "；伪来源[来源不存在.pdf - 第999页]"

    pipeline = RagQaPipeline(
        load_settings({}), retriever=Retriever(), parent_client=ParentClient(),
        gateway_factory=lambda: Gateway())

    frames = [chunk async for chunk in _event_stream(pipeline, _request())]
    body = "".join(frames)

    assert "event: error" in body
    assert "E-RAG-03" in body
    assert "畸形标签 1" in body
    assert "event: done" not in body
    assert "来源不存在.pdf" not in body
