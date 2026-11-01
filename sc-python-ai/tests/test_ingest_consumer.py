import json
from pathlib import Path

import aio_pika
import httpx
import pytest

from app.core.config import load_settings
from app.core.exceptions import AiErrorCode, RetryableAiError
from app.services.ingest.consumer import IngestConsumer
from app.services.ingest.java_client import ParseContextClient
from app.services.ingest.models import ParseContext


def _settings(tmp_path: Path):
    return load_settings({
        "SC_INTERNAL_SERVICE_TOKEN": "test-token",
        "SC_JAVA_BACKEND_BASE_URL": "http://sc-java-backend:8080",
        "SC_STORAGE_RAW_PATH": str(tmp_path / "raw"),
        "SC_INGEST_MAX_PAGES": "300",
    })


def _context(storage_rel_path: str) -> ParseContext:
    return ParseContext(
        allow_proceed=True, abort_reason=None, user_id="10001", course_id="2001",
        doc_id="50001", doc_name="a.txt", file_ext=".txt",
        storage_rel_path=storage_rel_path, parse_version=1, course_index_version=1,
        embedding_provider="OPENAI_COMPATIBLE", embedding_base_url="https://example.com/v1",
        embedding_api_key="hidden", embedding_model="embed", embedding_dim=8,
    )


def _message() -> bytes:
    return json.dumps({
        "ingest_job_id": "job_20261022_120000123_abcdef",
        "job_type": "INITIAL_INGEST",
        "trace_id": "tr_cron_0123456789ab",
        "user_id": "10001", "course_id": "2001", "course_index_version": 1,
        "doc_id": "50001", "parse_version": 1, "doc_name": "a.txt",
        "storage_rel_path": "raw/10001/2001/50001.txt",
        "file_sha256": "abc", "retry_count": 0,
        "enqueued_at": "2026-10-22T12:00:00.000+08:00",
    }).encode()


class _ContextClient:
    def __init__(self, context, error=None) -> None:
        self.context = context
        self.error = error
        self.calls = 0
        self.attempts = 0

    async def fetch(self, **_kwargs):
        self.calls += 1
        if self.error is not None:
            error, self.error = self.error, None
            raise error
        return self.context

    async def record_attempt(self, **_kwargs):
        self.attempts += 1
        return True


class _Indexer:
    def __init__(self) -> None:
        self.calls = 0

    async def index(self, context, result):
        self.calls += 1
        return len(result.children)


class _Sink:
    def __init__(self) -> None:
        self.ready_calls = []
        self.failed_calls = []

    async def ready(self, message, context, result, *, trace_id):
        self.ready_calls.append((message, context, result))
        return True

    async def failed(self, message, context, error_code, error_message, *, trace_id):
        self.failed_calls.append((error_code, error_message))
        return True


@pytest.mark.asyncio
async def test_parse_context_client_maps_envelope(tmp_path: Path) -> None:
    body = {
        "code": "OK", "message": "ok", "trace_id": "tr_cron_0123456789ab",
        "data": {
            "allow_proceed": True, "abort_reason": None,
            "user_id": "10001", "course_id": "2001", "doc_id": "50001",
            "doc_name": "a.txt", "file_ext": ".txt",
            "storage_rel_path": "raw/10001/2001/50001.txt",
            "parse_version": 1, "course_index_version": 1,
            "embedding_config": {"provider": "OPENAI_COMPATIBLE",
                                 "base_url": "https://example.com/v1",
                                 "api_key": "hidden", "model_name": "embed", "dim": 8},
        },
    }

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["parse_version"] == "1"
        assert request.headers["X-Internal-Token"] == "test-token"
        return httpx.Response(200, json=body)

    client = ParseContextClient(_settings(tmp_path), transport=httpx.MockTransport(handler))
    context = await client.fetch(doc_id="50001", parse_version=1, course_index_version=1,
                                 trace_id="tr_cron_0123456789ab", user_id="10001")

    assert context.allow_proceed is True
    assert context.embedding_model == "embed"
    assert context.embedding_api_key == "hidden"


@pytest.mark.asyncio
async def test_consumer_parses_and_delivers(tmp_path: Path) -> None:
    raw = tmp_path / "raw/10001/2001/50001.txt"
    raw.parent.mkdir(parents=True)
    raw.write_text("第一段内容。" * 40, encoding="utf-8")
    sink = _Sink()
    indexer = _Indexer()
    consumer = IngestConsumer(_settings(tmp_path),
                              context_client=_ContextClient(_context("raw/10001/2001/50001.txt")),
                              result_sink=sink, indexer=indexer,
                              retry_delays=(0.0,), sleep=_no_sleep)

    await consumer.handle(_message())

    assert len(sink.ready_calls) == 1
    assert sink.ready_calls[0][2].page_count == 1
    assert indexer.calls == 1


@pytest.mark.asyncio
async def test_consumer_acks_expired_task_without_parsing(tmp_path: Path) -> None:
    expired = _context("raw/10001/2001/50001.txt")
    object.__setattr__(expired, "allow_proceed", False)
    object.__setattr__(expired, "abort_reason", "STALE_PARSE_VERSION")
    sink = _Sink()
    consumer = IngestConsumer(_settings(tmp_path), context_client=_ContextClient(expired),
                              result_sink=sink, indexer=_Indexer(),
                              retry_delays=(0.0,), sleep=_no_sleep)

    await consumer.handle(_message())

    assert sink.ready_calls == []
    assert sink.failed_calls == []


@pytest.mark.asyncio
async def test_consumer_retries_retryable_error(tmp_path: Path) -> None:
    raw = tmp_path / "raw/10001/2001/50001.txt"
    raw.parent.mkdir(parents=True)
    raw.write_text("内容。" * 60, encoding="utf-8")
    client = _ContextClient(_context("raw/10001/2001/50001.txt"),
                            error=RetryableAiError(AiErrorCode.INTERNAL_ERROR, "temporary"))
    sink = _Sink()
    consumer = IngestConsumer(_settings(tmp_path), context_client=client,
                              result_sink=sink, indexer=_Indexer(),
                              retry_delays=(0.0,), sleep=_no_sleep)

    await consumer.handle(_message())

    assert client.calls == 2
    assert client.attempts == 1
    assert len(sink.ready_calls) == 1


async def _no_sleep(_seconds: float) -> None:
    return None


@pytest.mark.asyncio
async def test_start_retries_until_rabbitmq_ready(tmp_path: Path, monkeypatch) -> None:
    """容器比 RabbitMQ 先就绪时，消费者要自己重试，不能直接退出把任务晾在队列里。"""
    attempts = {"count": 0}
    consumed = {"called": False}

    class _Queue:
        async def consume(self, handler):
            consumed["called"] = True

    class _Channel:
        async def set_qos(self, **_kwargs) -> None:
            return None

        async def declare_queue(self, _name, passive: bool = False):
            return _Queue()

    class _Connection:
        async def channel(self):
            return _Channel()

    async def fake_connect_robust(**_kwargs):
        attempts["count"] += 1
        if attempts["count"] < 3:
            raise aio_pika.exceptions.AMQPConnectionError("refused")
        return _Connection()

    monkeypatch.setattr("app.services.ingest.consumer.aio_pika.connect_robust", fake_connect_robust)

    consumer = IngestConsumer(_settings(tmp_path), context_client=_ContextClient(_context("raw/x.txt")),
                              result_sink=_Sink(), indexer=_Indexer(), sleep=_no_sleep)
    await consumer.start()

    assert attempts["count"] == 3
    assert consumed["called"] is True


@pytest.mark.asyncio
async def test_start_gives_up_after_retries(tmp_path: Path, monkeypatch) -> None:
    """重试用尽后仍然抛错，让容器退出交给 restart 策略，而不是假装启动成功。"""
    async def always_fail(**_kwargs):
        raise aio_pika.exceptions.AMQPConnectionError("refused")

    monkeypatch.setattr("app.services.ingest.consumer.aio_pika.connect_robust", always_fail)

    consumer = IngestConsumer(_settings(tmp_path), context_client=_ContextClient(_context("raw/x.txt")),
                              result_sink=_Sink(), indexer=_Indexer(), sleep=_no_sleep)

    with pytest.raises(aio_pika.exceptions.AMQPConnectionError):
        await consumer.start()
