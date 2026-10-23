import httpx
import pytest

from app.core.config import load_settings
from app.services.ingest.embedding import EmbeddingService
from app.services.ingest.models import ParseContext


def _settings():
    return load_settings({
        "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
        "SC_LOCAL_MODEL_ALLOWED_ORIGINS": "http://127.0.0.1:9000",
    })


def _context():
    return ParseContext(
        allow_proceed=True, abort_reason=None, user_id="10001", course_id="2001",
        doc_id="50001", doc_name="a.pdf", file_ext=".pdf",
        storage_rel_path="raw/10001/2001/50001.pdf", parse_version=1,
        course_index_version=1, embedding_provider="OPENAI_COMPATIBLE",
        embedding_base_url="http://127.0.0.1:9000/v1", embedding_api_key="sk-test",
        embedding_model="embed", embedding_dim=3,
    )


@pytest.mark.asyncio
async def test_embedding_batches_and_validates_dimension() -> None:
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json={"data": [
            {"index": 0, "embedding": [0.1, 0.2, 0.3]},
            {"index": 1, "embedding": [0.4, 0.5, 0.6]},
        ]})

    service = EmbeddingService(_settings(), http_transport=httpx.MockTransport(handler))
    vectors = await service.embed(_context(), ["第一段", "第二段"])

    assert vectors == [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]]
    assert len(calls) == 1
    assert calls[0].headers["Authorization"] == "Bearer sk-test"


@pytest.mark.asyncio
async def test_embedding_batch_size_is_32() -> None:
    batches: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = __import__("json").loads(request.content)
        size = len(payload["input"])
        batches.append(size)
        return httpx.Response(200, json={"data": [
            {"index": index, "embedding": [0.1, 0.2, 0.3]} for index in range(size)
        ]})

    service = EmbeddingService(_settings(), http_transport=httpx.MockTransport(handler))
    vectors = await service.embed(_context(), [f"文本{index}" for index in range(40)])

    assert batches == [32, 8]
    assert len(vectors) == 40


@pytest.mark.asyncio
async def test_embedding_dimension_mismatch_is_rejected() -> None:
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [0.1]}]})

    service = EmbeddingService(_settings(), http_transport=httpx.MockTransport(handler))

    with pytest.raises(Exception) as error:
        await service.embed(_context(), ["x"])
    assert "维度" in str(error.value)
