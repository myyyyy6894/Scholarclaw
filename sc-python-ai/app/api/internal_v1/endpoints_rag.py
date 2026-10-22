"""内部向量清理 `DELETE /internal/ai/v1/rag/vectors`（TASK-504，`04` §4.9.4）。"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.security import require_internal_identity
from app.schemas.common import InternalResponse
from app.schemas.rag_vectors import VectorCleanupData, VectorCleanupRequest
from app.services.ingest.vector_store import ChromaVectorStore

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


def _settings():
    from app.core.config import get_settings

    return get_settings()
