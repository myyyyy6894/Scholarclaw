"""监管文档消费者：启动失败持续重试，异常断连后恢复，停机时释放资源。"""

from __future__ import annotations

import asyncio
from contextlib import suppress

from app.core.logging import get_logger
from app.services.ingest.consumer import IngestConsumer

logger = get_logger(__name__)
RETRY_SECONDS = 15.0
MONITOR_SECONDS = 5.0
# 给 aio-pika 的 robust connection 足够时间自动重连及恢复订阅。
UNHEALTHY_GRACE_SECONDS = 30.0


class IngestSupervisor:
    def __init__(self, consumer: IngestConsumer) -> None:
        self._consumer = consumer
        self._task: asyncio.Task[None] | None = None

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run(), name="ingest-consumer-supervisor")

    def is_ready(self) -> bool:
        return bool(self._task and not self._task.done() and self._consumer.is_ready())

    async def _close_consumer(self) -> None:
        try:
            await self._consumer.close()
        except Exception as exc:  # 关闭失败也不能终止后续重试；不要记录连接参数。
            logger.warning("关闭文档消费者失败 error=%s", type(exc).__name__)

    async def _run(self) -> None:
        while True:
            try:
                await self._consumer.start()
                unhealthy_since: float | None = None
                while True:
                    await asyncio.sleep(MONITOR_SECONDS)
                    if self._consumer.is_ready():
                        unhealthy_since = None
                    elif unhealthy_since is None:
                        unhealthy_since = asyncio.get_running_loop().time()
                    elif asyncio.get_running_loop().time() - unhealthy_since >= UNHEALTHY_GRACE_SECONDS:
                        logger.warning("文档消费者持续不可用，重建订阅")
                        break
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                # 只记录类型，避免 broker 异常携带认证信息。
                logger.warning("文档消费者启动或监管失败，将继续重试 error=%s", type(exc).__name__)
            finally:
                await self._close_consumer()
            await asyncio.sleep(RETRY_SECONDS)

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
            self._task = None
