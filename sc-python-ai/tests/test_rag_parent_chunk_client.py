import json

import httpx
import pytest

from app.core.config import load_settings
from app.core.exceptions import NonRetryableAiError, ParentChunkFetchFailedError
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk, ParentChunkClient

JAVA_BASE_URL = "http://sc-java-backend:8080"
TRACE_ID = "tr_0123456789ab"


def _settings():
    return load_settings({"SC_JAVA_BACKEND_BASE_URL": JAVA_BASE_URL,
                          "SC_INTERNAL_SERVICE_TOKEN": "test-token"})


def _client(handler) -> ParentChunkClient:
    return ParentChunkClient(_settings(), transport=httpx.MockTransport(handler))


def _envelope(*chunks) -> dict:
    return {
        "code": "OK", "message": "Success",
        "data": {"parent_chunks": list(chunks)},
        "trace_id": TRACE_ID,
        "timestamp": "2026-10-23T11:00:00.000+08:00",
    }


def _chunk(parent_id, doc_id="50001", start=1, end=2, content="权威内容"):
    return {
        "parent_chunk_id": parent_id,
        "doc_id": doc_id,
        "doc_name": "第5章.pdf",
        "page_start": start,
        "page_end": end,
        "section_title": "5.2",
        "content": content,
    }


@pytest.mark.asyncio
async def test_fetch_sends_contract_request_and_orders_by_requested_ids() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=_envelope(
            _chunk("pc_2", start=4, end=5),
            _chunk("pc_1", start=1, end=2),
        ))

    chunks = await _client(handler).fetch(
        user_id="10001", course_id="2001", expected_course_index_version=3,
        parent_chunk_ids=("pc_1", "pc_2"), trace_id=TRACE_ID)

    assert [chunk.parent_chunk_id for chunk in chunks] == ["pc_1", "pc_2"]
    assert chunks[0] == AuthoritativeParentChunk(
        "pc_1", "50001", "第5章.pdf", 1, 2, "5.2", "权威内容")
    assert len(calls) == 1
    request = calls[0]
    assert str(request.url) == JAVA_BASE_URL + "/internal/api/v1/rag/parent-chunks/batch-get"
    assert request.headers["X-Internal-Token"] == "test-token"
    assert request.headers["X-User-Id"] == "10001"
    assert request.headers["X-Trace-Id"] == TRACE_ID
    assert json.loads(request.content) == {
        "user_id": "10001",
        "course_id": "2001",
        "expected_course_index_version": 3,
        "parent_chunk_ids": ["pc_1", "pc_2"],
    }


@pytest.mark.asyncio
async def test_fetch_drops_unknown_duplicate_and_malformed_rows() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_envelope(
            _chunk("pc_1"),
            _chunk("pc_1", content="重复"),
            _chunk("pc_9"),
            _chunk("pc_2", content=" "),
            _chunk("pc_3", start=5, end=4),
            {"parent_chunk_id": "pc_4"},
            "not-an-object",
        ))

    chunks = await _client(handler).fetch(
        user_id="10001", course_id="2001", expected_course_index_version=1,
        parent_chunk_ids=("pc_1", "pc_2", "pc_3", "pc_4"), trace_id=TRACE_ID)

    assert [chunk.parent_chunk_id for chunk in chunks] == ["pc_1"]
    assert chunks[0].content == "权威内容"


@pytest.mark.asyncio
async def test_empty_candidates_do_not_call_java() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=_envelope())

    chunks = await _client(handler).fetch(
        user_id="10001", course_id="2001", expected_course_index_version=1,
        parent_chunk_ids=(), trace_id=TRACE_ID)

    assert chunks == ()
    assert calls == []


@pytest.mark.asyncio
async def test_more_than_four_candidates_is_rejected_before_io() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=_envelope())

    with pytest.raises(NonRetryableAiError) as error:
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("p1", "p2", "p3", "p4", "p5"), trace_id=TRACE_ID)

    assert error.value.error_code.value == "VALIDATION_ERROR"
    assert calls == []


@pytest.mark.asyncio
async def test_invalid_ids_are_rejected_before_io() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=_envelope())

    with pytest.raises(NonRetryableAiError):
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc 1",), trace_id=TRACE_ID)
    with pytest.raises(NonRetryableAiError):
        await _client(handler).fetch(
            user_id="abc", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)
    assert calls == []


@pytest.mark.asyncio
async def test_http_5xx_maps_to_e_rag_03_without_retry() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(503, json={"code": "INTERNAL_ERROR"})

    with pytest.raises(ParentChunkFetchFailedError) as error:
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)

    assert error.value.error_code.value == "E-RAG-03"
    assert error.value.retryable is False
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_read_timeout_maps_to_e_rag_03() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout")

    with pytest.raises(ParentChunkFetchFailedError):
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)


@pytest.mark.asyncio
async def test_connection_refused_is_retried_once() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if len(calls) == 1:
            raise httpx.ConnectError("refused")
        return httpx.Response(200, json=_envelope(_chunk("pc_1")))

    chunks = await _client(handler).fetch(
        user_id="10001", course_id="2001", expected_course_index_version=1,
        parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)

    assert len(calls) == 2
    assert [chunk.parent_chunk_id for chunk in chunks] == ["pc_1"]


@pytest.mark.asyncio
async def test_connection_refused_after_retry_maps_to_e_rag_03() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        raise httpx.ConnectError("refused")

    with pytest.raises(ParentChunkFetchFailedError):
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)

    assert len(calls) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("payload", [
    {"code": "OK"},
    {"code": "OK", "data": []},
    {"code": "OK", "data": {"parent_chunks": "bad"}},
])
async def test_malformed_envelope_maps_to_e_rag_03(payload) -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    with pytest.raises(ParentChunkFetchFailedError):
        await _client(handler).fetch(
            user_id="10001", course_id="2001", expected_course_index_version=1,
            parent_chunk_ids=("pc_1",), trace_id=TRACE_ID)
