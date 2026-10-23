"""Java 内部接口客户端（TASK-502）：解析上下文核验与结果回调。

Python 不直连 MySQL；所有权威状态都通过 `/internal/api/v1/**` 获取/回写。
"""

from __future__ import annotations

from typing import Any

import httpx

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError, RetryableAiError
from app.core.logging import get_logger
from app.services.ingest.models import ParseContext

logger = get_logger(__name__)

_PARSE_CONTEXT_PATH = "/internal/api/v1/docs/{doc_id}/parse-context"
_RETRY_ATTEMPT_PATH = "/internal/api/v1/docs/{doc_id}/retry-attempt"
_INGEST_RESULT_PATH = "/internal/api/v1/rag/ingest-result"


class ParseContextClient:
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    async def fetch(self, *, doc_id: str, parse_version: int, course_index_version: int,
                    trace_id: str, user_id: str) -> ParseContext:
        if not self._settings.java_backend_base_url:
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR, "Java 内部服务基址未配置。")
        url = self._settings.java_backend_base_url + _PARSE_CONTEXT_PATH.format(doc_id=doc_id)
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(5.0, connect=2.0),
                                         transport=self._transport) as client:
                response = await client.get(
                    url,
                    params={"parse_version": parse_version,
                            "course_index_version": course_index_version},
                    headers=self._headers(trace_id, user_id),
                )
        except httpx.HTTPError as error:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "解析上下文读取失败。") from error

        if response.status_code != 200:
            _raise_for_status(response.status_code)
        payload = _envelope_data(response)
        embedding = payload.get("embedding_config") or {}
        return ParseContext(
            allow_proceed=bool(payload.get("allow_proceed")),
            abort_reason=payload.get("abort_reason"),
            user_id=_string_or_none(payload.get("user_id")),
            course_id=_string_or_none(payload.get("course_id")),
            doc_id=str(payload.get("doc_id") or doc_id),
            doc_name=payload.get("doc_name"),
            file_ext=payload.get("file_ext"),
            storage_rel_path=payload.get("storage_rel_path"),
            parse_version=int(payload.get("parse_version") or parse_version),
            course_index_version=int(
                payload.get("course_index_version") or course_index_version),
            embedding_provider=embedding.get("provider"),
            embedding_base_url=embedding.get("base_url"),
            embedding_api_key=embedding.get("api_key"),
            embedding_model=embedding.get("model_name"),
            embedding_dim=embedding.get("dim"),
        )

    def _headers(self, trace_id: str, user_id: str) -> dict[str, str]:
        return {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Trace-Id": trace_id,
            "X-User-Id": user_id,
        }

    async def record_attempt(self, *, doc_id: str, parse_version: int, trace_id: str,
                             user_id: str) -> bool:
        """上报一次内部重试，保证 `retry_count` 真实反映重试次数；失败不阻断重试。"""
        if not self._settings.java_backend_base_url:
            return False
        url = (self._settings.java_backend_base_url
               + _RETRY_ATTEMPT_PATH.format(doc_id=doc_id))
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(3.0, connect=2.0),
                                         transport=self._transport) as client:
                response = await client.post(
                    url, params={"parse_version": parse_version},
                    headers=self._headers(trace_id, user_id))
            return response.status_code == 200
        except httpx.HTTPError:
            logger.warning("重试计数上报失败 doc_id=%s trace_id=%s", doc_id, trace_id)
            return False


class IngestResultClient:
    """回调 Java 写入父块与最终状态（Java 端属 TASK-503）。"""

    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._transport = transport

    async def post(self, body: dict[str, Any], *, trace_id: str, user_id: str) -> bool:
        if not self._settings.java_backend_base_url:
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR, "Java 内部服务基址未配置。")
        url = self._settings.java_backend_base_url + _INGEST_RESULT_PATH
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0, connect=3.0),
                                         transport=self._transport) as client:
                response = await client.post(
                    url, json=body,
                    headers={
                        "X-Internal-Token": self._settings.internal_service_token,
                        "X-Trace-Id": trace_id,
                        "X-User-Id": user_id,
                        "Content-Type": "application/json",
                    })
        except httpx.HTTPError as error:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "解析结果回调失败。") from error
        if response.status_code != 200:
            _raise_for_status(response.status_code)
        data = _envelope_data(response)
        return bool(data.get("applied", True))


def _envelope_data(response: httpx.Response) -> dict[str, Any]:
    try:
        payload = response.json()
    except ValueError as error:
        raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "内部响应不可解析。") from error
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), dict):
        raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "内部响应结构不符合契约。")
    return payload["data"]


def _raise_for_status(status: int) -> None:
    if status in {401, 403, 404}:
        raise NonRetryableAiError(AiErrorCode.RESOURCE_NOT_FOUND,
                                  "解析任务上下文不存在或已失效。")
    if 400 <= status < 500:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "解析任务参数不被接受。")
    raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "Java 内部服务暂时不可用。")


def _string_or_none(value: Any) -> str | None:
    return None if value is None else str(value)
