"""Java 父块权威回查客户端（TASK-602，`04` §4.9.6 / `02` §5.2.2）。

调用流程：

1. `DenseVectorRetriever` 返回去重后的 Top-4 `parent_chunk_ids`；
2. 仅在未拒答时调用本客户端，回调 Java `POST /internal/api/v1/rag/parent-chunks/batch-get`；
3. Java 依据 MySQL 三表关联做四重权威校验，返回有效父块全文与真实页码。

契约边界：

* ChromaDB 元数据只用于召回，**不得**替代 Java 返回的权威内容与页码；
* 允许部分命中（1~4 个），缺失/失效候选由 Java 过滤，本客户端只按请求顺序保留命中项；
* 超时或 5xx 统一抛 :class:`ParentChunkFetchFailedError`（`E-RAG-03`），
  调用方不得降级用子块拼答案；
* 连接被拒绝允许在网络层重试 1 次；读取超时与 5xx 不重试。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import Settings
from app.core.exceptions import (
    AiErrorCode,
    NonRetryableAiError,
    ParentChunkFetchFailedError,
)
from app.core.logging import get_logger

logger = get_logger(__name__)

_PARENT_CHUNKS_PATH = "/internal/api/v1/rag/parent-chunks/batch-get"
_MAX_PARENT_CHUNKS = 4
_ID_PATTERN = re.compile(r"[A-Za-z0-9_]{1,64}")
_DECIMAL_ID = re.compile(r"[0-9]+")


@dataclass(frozen=True)
class AuthoritativeParentChunk:
    """MySQL 权威父块；字段与 `API-INT-JAVA-002` 响应一一对应。"""

    parent_chunk_id: str
    doc_id: str
    doc_name: str
    page_start: int
    page_end: int
    section_title: str | None
    content: str


class ParentChunkClient:
    def __init__(self, settings: Settings, *,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    async def fetch(self, *, user_id: str, course_id: str,
                    expected_course_index_version: int,
                    parent_chunk_ids: tuple[str, ...] | list[str],
                    trace_id: str) -> tuple[AuthoritativeParentChunk, ...]:
        ids = _validated_ids(parent_chunk_ids)
        if not ids:
            return ()
        _require_positive_id(user_id, "user_id")
        _require_positive_id(course_id, "course_id")
        if not isinstance(expected_course_index_version, int) \
                or expected_course_index_version < 1:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                      "课程索引版本必须为正整数。")
        if not self._settings.java_backend_base_url:
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                      "Java 内部服务基址未配置。")

        body = {
            "user_id": user_id,
            "course_id": course_id,
            "expected_course_index_version": expected_course_index_version,
            "parent_chunk_ids": list(ids),
        }
        response = await self._post(body, trace_id=trace_id, user_id=user_id)
        if response.status_code != 200:
            logger.warning("父块回查失败 status=%s trace_id=%s", response.status_code, trace_id)
            raise ParentChunkFetchFailedError()
        return _parse_parent_chunks(response, requested=ids, trace_id=trace_id)

    async def _post(self, body: dict[str, Any], *, trace_id: str,
                    user_id: str) -> httpx.Response:
        url = self._settings.java_backend_base_url + _PARENT_CHUNKS_PATH
        headers = {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Trace-Id": trace_id,
            "X-User-Id": user_id,
            "Content-Type": "application/json",
        }
        timeout = httpx.Timeout(5.0, connect=2.0)
        async with httpx.AsyncClient(timeout=timeout,
                                     transport=self._transport) as client:
            for attempt in range(2):
                try:
                    return await client.post(url, json=body, headers=headers)
                except httpx.ConnectError as error:
                    if attempt == 1:
                        raise ParentChunkFetchFailedError() from error
                except httpx.HTTPError as error:
                    raise ParentChunkFetchFailedError() from error
        raise ParentChunkFetchFailedError()  # pragma: no cover - 循环必然返回或抛出


def _validated_ids(raw_ids: tuple[str, ...] | list[str]) -> tuple[str, ...]:
    if raw_ids is None:
        return ()
    if len(raw_ids) > _MAX_PARENT_CHUNKS:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  "父块候选最多允许 4 个。")
    normalized: list[str] = []
    seen: set[str] = set()
    for raw in raw_ids:
        value = "" if raw is None else str(raw).strip()
        if not _ID_PATTERN.fullmatch(value):
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                      "parent_chunk_id 格式非法。")
        if value not in seen:
            seen.add(value)
            normalized.append(value)
    return tuple(normalized)


def _require_positive_id(value: str, field: str) -> None:
    text = "" if value is None else str(value).strip()
    if not _DECIMAL_ID.fullmatch(text) or int(text) <= 0:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  f"{field} 必须为十进制正整数。")


def _parse_parent_chunks(response: httpx.Response, *, requested: tuple[str, ...],
                         trace_id: str) -> tuple[AuthoritativeParentChunk, ...]:
    try:
        payload = response.json()
    except ValueError as error:
        raise ParentChunkFetchFailedError() from error
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), dict):
        raise ParentChunkFetchFailedError()
    items = payload["data"].get("parent_chunks")
    if not isinstance(items, list):
        raise ParentChunkFetchFailedError()

    requested_set = set(requested)
    found: dict[str, AuthoritativeParentChunk] = {}
    for item in items:
        chunk = _to_chunk(item, trace_id=trace_id)
        if chunk is None:
            continue
        if chunk.parent_chunk_id not in requested_set:
            logger.warning("丢弃未请求或越权的父块 parent_chunk_id=%s trace_id=%s",
                           chunk.parent_chunk_id, trace_id)
            continue
        found.setdefault(chunk.parent_chunk_id, chunk)
    return tuple(found[parent_id] for parent_id in requested if parent_id in found)


def _to_chunk(item: Any, *, trace_id: str) -> AuthoritativeParentChunk | None:
    if not isinstance(item, dict):
        logger.warning("丢弃非对象父块响应 trace_id=%s", trace_id)
        return None
    parent_chunk_id = item.get("parent_chunk_id")
    doc_id = item.get("doc_id")
    doc_name = item.get("doc_name")
    page_start = item.get("page_start")
    page_end = item.get("page_end")
    section_title = item.get("section_title")
    content = item.get("content")

    if not isinstance(parent_chunk_id, str) or not _ID_PATTERN.fullmatch(parent_chunk_id):
        logger.warning("丢弃 parent_chunk_id 非法的父块 trace_id=%s", trace_id)
        return None
    if not isinstance(doc_id, (str, int)) or isinstance(doc_id, bool) \
            or not _DECIMAL_ID.fullmatch(str(doc_id)) or int(doc_id) <= 0:
        logger.warning("丢弃 doc_id 非法的父块 parent_chunk_id=%s trace_id=%s",
                       parent_chunk_id, trace_id)
        return None
    if not isinstance(doc_name, str) or not doc_name.strip():
        logger.warning("丢弃 doc_name 为空的父块 parent_chunk_id=%s trace_id=%s",
                       parent_chunk_id, trace_id)
        return None
    if not _valid_page(page_start, page_end):
        logger.warning("丢弃页码非法的父块 parent_chunk_id=%s trace_id=%s",
                       parent_chunk_id, trace_id)
        return None
    if section_title is not None and not isinstance(section_title, str):
        logger.warning("丢弃 section_title 非法的父块 parent_chunk_id=%s trace_id=%s",
                       parent_chunk_id, trace_id)
        return None
    if not isinstance(content, str) or not content.strip():
        logger.warning("丢弃内容为空的父块 parent_chunk_id=%s trace_id=%s",
                       parent_chunk_id, trace_id)
        return None
    return AuthoritativeParentChunk(
        parent_chunk_id=parent_chunk_id,
        doc_id=str(doc_id),
        doc_name=doc_name,
        page_start=page_start,
        page_end=page_end,
        section_title=section_title,
        content=content,
    )


def _valid_page(page_start: Any, page_end: Any) -> bool:
    if not isinstance(page_start, int) or isinstance(page_start, bool):
        return False
    if not isinstance(page_end, int) or isinstance(page_end, bool):
        return False
    return page_start >= 1 and page_end >= page_start
