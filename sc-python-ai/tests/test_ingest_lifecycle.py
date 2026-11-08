"""消费者启动/恢复与健康检查，所有 broker 均为进程内替身。"""

import asyncio

import aio_pika
import httpx
import pytest

from app.core.config import get_settings, reset_settings_cache
from app.services.ingest.consumer import IngestConsumer


@pytest.mark.asyncio
async def test_exhausted_startup_retry_recovers_and_health_tracks_consumer(monkeypatch):
    monkeypatch.setenv("SC_INGEST_CONSUMER_ENABLED", "true")
    reset_settings_cache()
    import app.main as main

    attempts = 0
    ready_for_broker = asyncio.Event()

    class Channel:
        is_closed = False

        async def set_qos(self, **kwargs):
            pass

        async def declare_queue(self, *args, **kwargs):
            return self

        async def consume(self, handler):
            pass

        async def close(self):
            self.is_closed = True

    class Connection:
        is_closed = False

        def __init__(self):
            self.connected = asyncio.Event()
            self.connected.set()
            self.channel_instance = Channel()

        async def channel(self):
            return self.channel_instance

        async def close(self):
            self.is_closed = True
            self.connected.clear()

    connection = Connection()

    async def connect(**kwargs):
        nonlocal attempts
        attempts += 1
        if not ready_for_broker.is_set():
            raise aio_pika.exceptions.AMQPConnectionError("broker unavailable")
        return connection

    async def no_sleep(_delay):
        await asyncio.sleep(0)

    monkeypatch.setattr("app.services.ingest.consumer.aio_pika.connect_robust", connect)
    monkeypatch.setattr(main, "build_ingest_consumer", lambda settings: IngestConsumer(
        settings, context_client=None, result_sink=None, indexer=None, sleep=no_sleep
    ))
    monkeypatch.setattr("app.services.ingest.supervisor.RETRY_SECONDS", 0.01)
    monkeypatch.setattr("app.services.ingest.supervisor.MONITOR_SECONDS", 0.01)
    app = main.create_app()
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app, client=("127.0.0.1", 1234))
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            async with asyncio.timeout(2):
                while attempts < 6:
                    await asyncio.sleep(0.005)
            failed = (await client.get("/internal/ai/v1/health")).json()["data"]
            assert failed["status"] == "DOWN"
            assert failed["dependencies"]["ingest_consumer"] == "DOWN"

            ready_for_broker.set()
            async with asyncio.timeout(2):
                while (await client.get("/internal/ai/v1/health")).json()["data"]["status"] != "UP":
                    await asyncio.sleep(0.01)
            assert attempts > 6
            assert (await client.get("/internal/ai/v1/health")).json()["data"]["dependencies"]["ingest_consumer"] == "UP"

            connection.connected.clear()
            disconnected = (await client.get("/internal/ai/v1/health")).json()["data"]
            assert disconnected["dependencies"]["ingest_consumer"] == "DOWN"
            connection.connected.set()
    assert connection.is_closed


@pytest.mark.asyncio
async def test_supervisor_rebuilds_stalled_subscription_and_closes_on_shutdown(monkeypatch):
    from app.services.ingest import supervisor as module

    monkeypatch.setattr(module, "MONITOR_SECONDS", 0.005)
    monkeypatch.setattr(module, "RETRY_SECONDS", 0.005)
    monkeypatch.setattr(module, "UNHEALTHY_GRACE_SECONDS", 0.01)

    class Consumer:
        starts = 0
        closes = 0
        ready = False

        async def start(self):
            self.starts += 1
            self.ready = True

        async def close(self):
            self.closes += 1
            self.ready = False

        def is_ready(self):
            return self.ready

    consumer = Consumer()
    supervisor = module.IngestSupervisor(consumer)
    supervisor.start()
    async with asyncio.timeout(2):
        while not supervisor.is_ready():
            await asyncio.sleep(0.005)
        consumer.ready = False
        while consumer.starts < 2:
            await asyncio.sleep(0.005)
    assert supervisor.is_ready()
    assert consumer.closes == 1
    await supervisor.close()
    assert consumer.closes == 2
    assert not supervisor.is_ready()


@pytest.mark.asyncio
async def test_incomplete_subscription_is_closed_before_retry(monkeypatch):
    from app.services.ingest import supervisor as module

    monkeypatch.setattr(module, "RETRY_SECONDS", 0.01)
    closed = asyncio.Event()

    class Channel:
        async def set_qos(self, **kwargs):
            raise RuntimeError("setup failed")

        async def close(self):
            pass

    class Connection:
        async def channel(self):
            return Channel()

        async def close(self):
            closed.set()

    async def connect(**kwargs):
        return Connection()

    monkeypatch.setattr("app.services.ingest.consumer.aio_pika.connect_robust", connect)
    consumer = IngestConsumer(get_settings(), context_client=None, result_sink=None, indexer=None)
    supervisor = module.IngestSupervisor(consumer)
    supervisor.start()
    await asyncio.wait_for(closed.wait(), timeout=2)
    assert not supervisor.is_ready()
    await supervisor.close()
