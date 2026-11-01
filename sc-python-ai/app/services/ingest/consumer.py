"""RabbitMQ 文档解析消费者（TASK-502）。

消息契约见 `04` §4.9.9。消费者先调 Java `/parse-context` 核验版本/状态：
`allow_proceed=false` 直接 ack 丢弃过期或重复消息；可重试错误按 `5s → 15s → 45s`
重试 3 次后 `nack(requeue=false)` 进入 DLQ（DLQ 业务处理属 TASK-505）。

**默认开启**（`SC_INGEST_CONSUMER_ENABLED=true`，TASK-506 完成联调）：
TASK-503 回调与 TASK-504 向量写入均已接入；关闭该开关可临时停用解析消费。
"""

from __future__ import annotations

import asyncio
import json
from typing import Any, Protocol

import aio_pika

from app.core import tracing
from app.core.config import Settings
from app.core.exceptions import AiEngineError, AiErrorCode, NonRetryableAiError
from app.core.logging import get_logger
from app.services.ingest.java_client import IngestResultClient, ParseContextClient
from app.services.ingest.models import ParseContext, ParseResult
from app.services.ingest.indexer import DocumentIndexer
from app.services.ingest.pipeline import parse_task

logger = get_logger(__name__)

RETRY_DELAYS_SECONDS = (5.0, 15.0, 45.0)

# 启动期连接重试间隔（秒）：0 表示立即首试，之后按此序列退避，总计约 30 秒
STARTUP_RETRY_DELAYS_SECONDS = (1.0, 2.0, 4.0, 8.0, 15.0)
_ALLOWED_JOB_TYPES = frozenset({"INITIAL_INGEST", "RETRY_FAILED", "REINDEX"})


class IngestResultSink(Protocol):
    """解析结果落库接口；由 TASK-503/504 的编排层实现。"""

    async def ready(self, message: dict[str, Any], context: ParseContext,
                    result: ParseResult, *, trace_id: str) -> bool: ...

    async def failed(self, message: dict[str, Any], context: ParseContext | None,
                     error_code: str, error_message: str, *, trace_id: str) -> bool: ...


class JavaIngestResultSink:
    """把结果回调给 Java；READY 需要 TASK-504 先完成向量写入。"""

    def __init__(self, client: IngestResultClient) -> None:
        self._client = client

    async def ready(self, message: dict[str, Any], context: ParseContext,
                    result: ParseResult, *, trace_id: str) -> bool:
        body = {
            "ingest_job_id": message["ingest_job_id"],
            "user_id": message["user_id"],
            "course_id": message["course_id"],
            "doc_id": message["doc_id"],
            "parse_version": message["parse_version"],
            "course_index_version": message["course_index_version"],
            "status": "READY",
            "indexed_embedding_provider": context.embedding_provider,
            "indexed_embedding_model": context.embedding_model,
            "indexed_embedding_dim": context.embedding_dim,
            "page_count": result.page_count,
            "chunk_count": result.chunk_count,
            "parent_chunks": [
                {
                    "parent_chunk_id": parent.parent_chunk_id,
                    "chunk_seq": parent.chunk_seq,
                    "page_start": parent.page_start,
                    "page_end": parent.page_end,
                    "section_title": parent.section_title,
                    "content": parent.content,
                    "char_length": parent.char_length,
                }
                for parent in result.parents
            ],
            "error_code": None,
            "error_msg": None,
        }
        return await self._client.post(body, trace_id=trace_id, user_id=message["user_id"])

    async def failed(self, message: dict[str, Any], context: ParseContext | None,
                     error_code: str, error_message: str, *, trace_id: str) -> bool:
        body = {
            "ingest_job_id": message["ingest_job_id"],
            "user_id": message["user_id"],
            "course_id": message["course_id"],
            "doc_id": message["doc_id"],
            "parse_version": message["parse_version"],
            "course_index_version": message["course_index_version"],
            "status": "FAILED",
            "indexed_embedding_provider": None,
            "indexed_embedding_model": None,
            "indexed_embedding_dim": None,
            "page_count": 0,
            "chunk_count": 0,
            "parent_chunks": [],
            "error_code": error_code,
            "error_msg": error_message,
        }
        return await self._client.post(body, trace_id=trace_id, user_id=message["user_id"])


class IngestConsumer:
    def __init__(self, settings: Settings, *, context_client: ParseContextClient,
                 result_sink: IngestResultSink, indexer: DocumentIndexer,
                 retry_delays: tuple[float, ...] = RETRY_DELAYS_SECONDS,
                 sleep=asyncio.sleep) -> None:
        self._settings = settings
        self._context_client = context_client
        self._result_sink = result_sink
        self._indexer = indexer
        self._retry_delays = retry_delays
        self._sleep = sleep
        self._connection: aio_pika.abc.AbstractRobustConnection | None = None
        self._channel: aio_pika.abc.AbstractRobustChannel | None = None

    async def start(self) -> None:
        """建立连接并开始消费。

        **启动期必须重试**：容器可能比 RabbitMQ 先就绪（整机重启、冷启动），
        而 `connect_robust` 只在"连接建立之后"自动重连，首次连不上会直接抛错，
        进程就此退出——队列里的解析任务会一直堆着没人消费（实测踩过：
        重启后 8 个任务排队 10 分钟无消费者）。
        """
        last_error: Exception | None = None
        for attempt, delay in enumerate((0, *STARTUP_RETRY_DELAYS_SECONDS), start=1):
            if delay:
                await self._sleep(delay)
            try:
                self._connection = await aio_pika.connect_robust(
                    host=self._settings.rabbitmq_host,
                    port=self._settings.rabbitmq_port,
                    login=self._settings.rabbitmq_user,
                    password=self._settings.rabbitmq_password,
                )
                break
            except (aio_pika.exceptions.AMQPConnectionError, OSError) as error:
                last_error = error
                logger.warning("RabbitMQ 连接失败（第 %d 次），稍后重试", attempt)
        else:
            # 重试用尽：抛出去让容器退出，由 restart 策略再起一轮
            logger.error("RabbitMQ 多次连接失败，放弃启动消费者")
            if last_error is not None:
                raise last_error
            raise RuntimeError("RabbitMQ 连接失败")

        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=1)
        queue = await self._channel.declare_queue(self._settings.mq_ingest_queue, passive=True)
        await queue.consume(self._on_message)
        logger.info("文档解析消费者已启动 queue=%s", self._settings.mq_ingest_queue)

    async def close(self) -> None:
        if self._channel is not None:
            await self._channel.close()
        if self._connection is not None:
            await self._connection.close()
        self._channel = None
        self._connection = None

    async def _on_message(self, message: aio_pika.abc.AbstractIncomingMessage) -> None:
        async with message.process(requeue=False, ignore_processed=True):
            await self.handle(message.body)

    async def handle(self, raw_body: bytes) -> None:
        """处理一条消息；异常由外层 `message.process` 转为 nack 进 DLQ。"""
        payload = self._decode(raw_body)
        trace_id = payload.get("trace_id")
        if not tracing.is_acceptable_inbound_trace_id(trace_id):
            trace_id = tracing.generate_cron_trace_id()
        trace_token = tracing.bind_trace_id(trace_id)
        user_token = tracing.bind_user_id(payload["user_id"])
        try:
            await self._handle_with_retry(payload, trace_id)
        finally:
            tracing.reset_user_id(user_token)
            tracing.reset(trace_token)

    async def _handle_with_retry(self, payload: dict[str, Any], trace_id: str) -> None:
        attempts = len(self._retry_delays) + 1
        for attempt in range(attempts):
            try:
                await self._process(payload, trace_id)
                return
            except AiEngineError as error:
                last_attempt = attempt == attempts - 1
                if not error.retryable or last_attempt:
                    if error.error_code in {AiErrorCode.E_RAG_01, AiErrorCode.E_RAG_02,
                                            AiErrorCode.VALIDATION_ERROR}:
                        await self._result_sink.failed(
                            payload, None, error.error_code.value, error.message, trace_id=trace_id)
                        return
                    raise
                delay = self._retry_delays[attempt]
                logger.warning("解析任务可重试失败 code=%s retry_in=%.0fs trace_id=%s",
                               error.error_code.value, delay, trace_id)
                await self._context_client.record_attempt(
                    doc_id=payload["doc_id"], parse_version=payload["parse_version"],
                    trace_id=trace_id, user_id=payload["user_id"])
                await self._sleep(delay)

    async def _process(self, payload: dict[str, Any], trace_id: str) -> None:
        context = await self._context_client.fetch(
            doc_id=payload["doc_id"],
            parse_version=payload["parse_version"],
            course_index_version=payload["course_index_version"],
            trace_id=trace_id,
            user_id=payload["user_id"],
        )
        if not context.allow_proceed:
            logger.info("解析任务已过期，ack 丢弃 doc_id=%s reason=%s",
                        payload["doc_id"], context.abort_reason)
            return
        result = await asyncio.to_thread(
            parse_task, self._settings.storage_raw_path, context,
            max_pages=self._settings.ingest_max_pages)
        await self._indexer.index(context, result)
        delivered = await self._result_sink.ready(payload, context, result, trace_id=trace_id)
        if not delivered:
            logger.warning("解析结果未被 Java 接受 doc_id=%s trace_id=%s",
                           payload["doc_id"], trace_id)

    @staticmethod
    def _decode(raw_body: bytes) -> dict[str, Any]:
        try:
            payload = json.loads(raw_body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as error:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "消息体不是合法 JSON。") from error
        if not isinstance(payload, dict):
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "消息体结构不符合契约。")
        if payload.get("job_type") not in _ALLOWED_JOB_TYPES:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "未知的 job_type。")
        for key in ("ingest_job_id", "trace_id", "user_id", "course_id", "doc_id"):
            value = payload.get(key)
            if not isinstance(value, str) or not value:
                raise NonRetryableAiError(
                    AiErrorCode.VALIDATION_ERROR, f"消息字段 {key} 非法。")
            if key in {"user_id", "course_id", "doc_id"} and not value.isdigit():
                raise NonRetryableAiError(
                    AiErrorCode.VALIDATION_ERROR, f"消息字段 {key} 必须为十进制字符串。")
        for key in ("parse_version", "course_index_version"):
            if not isinstance(payload.get(key), int) or payload[key] < 1:
                raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, f"消息字段 {key} 非法。")
        return payload


def build_ingest_consumer(settings: Settings) -> IngestConsumer:
    return IngestConsumer(
        settings,
        context_client=ParseContextClient(settings),
        result_sink=JavaIngestResultSink(IngestResultClient(settings)),
        indexer=DocumentIndexer(settings),
    )
