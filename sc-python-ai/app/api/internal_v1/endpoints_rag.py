"""内部 RAG 向量清理（TASK-504）与流式问答（TASK-604）。

* `DELETE /internal/ai/v1/rag/vectors`：`04` §4.9.4；
* `POST /internal/ai/v1/rag/qa-stream`：`04` §4.9.3，事件帧供 Java 补全会话 ID 后转发。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from dataclasses import asdict
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse

from app.core import tracing
from app.core.exceptions import AiEngineError, AiErrorCode, NonRetryableAiError
from app.core.logging import get_logger
from app.core.security import require_internal_identity, require_internal_token
from app.schemas.common import InternalResponse
from app.schemas.rag_qa import RagQaStreamRequest
from app.schemas.rag_vectors import (
    VectorCleanupData,
    VectorCleanupRequest,
    VectorCollectionData,
)
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.ingest.vector_store import ChromaVectorStore
from app.services.rag.qa_pipeline import (
    ChatTurn,
    CitationEvent,
    DoneEvent,
    RagQaEvent,
    RagQaPipeline,
    RagQaRequest,
    RejectionNoticeEvent,
    TokenEvent,
)

logger = get_logger(__name__)

router = APIRouter(tags=["internal-rag"])


@router.delete(
    "/rag/vectors",
    response_model=InternalResponse[VectorCleanupData],
    summary="幂等清理 ChromaDB 衍生向量",
)
async def cleanup_vectors(
    body: VectorCleanupRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[VectorCleanupData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR,
            "请求体 user_id 与 X-User-Id 不一致，已拒绝向量清理。",
        )
    store = ChromaVectorStore(_settings())
    if body.scope == "DOCUMENT":
        store.delete_document(body.user_id, body.course_id or "", body.doc_id or "",
                              course_index_version=body.course_index_version,
                              stale_parse_version=body.stale_parse_version)
    elif body.scope == "COURSE_COLLECTION":
        store.delete_course(body.user_id, body.course_id or "",
                            course_index_version=body.course_index_version)
    else:
        store.delete_user(body.user_id)
    return InternalResponse.ok(VectorCleanupData(scope=body.scope, cleaned=True),
                               message="Vector cleanup completed idempotently")


@router.get(
    "/rag/collections",
    response_model=InternalResponse[VectorCollectionData],
    summary="列出本应用管理的 ChromaDB Collection（孤儿对账）",
)
async def list_collections(
    _: Annotated[None, Depends(require_internal_token)],
) -> InternalResponse[VectorCollectionData]:
    store = ChromaVectorStore(_settings())
    names = store.list_collection_names()
    return InternalResponse.ok(VectorCollectionData(names=names, count=len(names)),
                               message="Collection list completed")


def get_rag_qa_pipeline() -> RagQaPipeline:
    """FastAPI 依赖：测试可覆盖此函数注入 FakeGateway/Retriever。"""
    return RagQaPipeline(_settings())


@router.post("/rag/qa-stream", summary="内部课程 RAG 流式问答（SSE）")
async def qa_stream(
    body: RagQaStreamRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
    pipeline: Annotated[RagQaPipeline, Depends(get_rag_qa_pipeline)],
) -> StreamingResponse:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR,
            "请求体 user_id 与 X-User-Id 不一致，已拒绝问答。",
        )
    request = _to_pipeline_request(body)
    return StreamingResponse(
        _event_stream(pipeline, request),
        media_type="text/event-stream; charset=utf-8",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


async def _event_stream(pipeline: RagQaPipeline,
                        request: RagQaRequest) -> AsyncIterator[str]:
    trace_id = request.trace_id
    try:
        async for event in pipeline.stream(request):
            frame = _to_frame(event)
            if frame:
                yield frame
    except asyncio.CancelledError:
        # 客户端断开：立即停止上游模型请求，不追加 error 帧
        raise
    except AiEngineError as error:
        logger.warning("RAG 流式问答失败 code=%s trace_id=%s",
                       error.error_code.value, trace_id)
        yield _frame("error", {"code": error.error_code.value,
                               "message": error.message, "trace_id": trace_id})
    except Exception:
        logger.exception("RAG 流式问答未捕获异常 trace_id=%s", trace_id)
        yield _frame("error", {"code": AiErrorCode.INTERNAL_ERROR.value,
                               "message": "系统繁忙，请稍后重试。",
                               "trace_id": trace_id})


def _to_frame(event: RagQaEvent) -> str | None:
    if isinstance(event, CitationEvent):
        return _frame("citation", {
            "citations": [asdict(citation) for citation in event.citations]})
    if isinstance(event, RejectionNoticeEvent):
        return _frame("rejection_notice", {
            "notice_text": event.notice_text,
            "has_general_supplement": event.has_general_supplement,
        })
    if isinstance(event, TokenEvent):
        return _frame("token", {
            "delta": event.delta,
            "is_general_supplement": event.is_general_supplement,
        })
    if isinstance(event, DoneEvent):
        return _frame("done", {
            "is_rejected": event.is_rejected,
            "retrieval_cost_ms": event.retrieval_cost_ms,
            "llm_first_token_ms": event.llm_first_token_ms,
            "stripped_citation_count": event.stripped_citation_count,
        })
    return None


def _frame(event: str, data: dict) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {payload}\n\n"


def _to_pipeline_request(body: RagQaStreamRequest) -> RagQaRequest:
    return RagQaRequest(
        user_id=body.user_id,
        course_id=body.course_id,
        course_name=body.course_name.strip(),
        course_index_version=body.course_index_version,
        valid_doc_ids=tuple(body.valid_doc_ids),
        query=body.query,
        embedding_endpoint=ModelEndpoint(
            body.embedding_config.provider, body.embedding_config.base_url,
            body.embedding_config.api_key, body.embedding_config.model_name),
        embedding_dim=body.embedding_config.dim,
        chat_endpoint=ModelEndpoint(
            body.chat_config.provider, body.chat_config.base_url,
            body.chat_config.api_key, body.chat_config.model_name),
        trace_id=tracing.current_or_generated_trace_id(),
        chat_history=tuple(ChatTurn(turn.role, turn.content)
                           for turn in body.chat_history_window),
    )


def _settings():
    from app.core.config import get_settings

    return get_settings()
