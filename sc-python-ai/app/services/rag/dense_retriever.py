"""单路稠密向量检索器（TASK-601，`01` FR-RAG-002 / `02` §5.2.2）。

流程与契约完全对齐：

1. 用课程绑定的 Embedding 模型把 `query` 编码为向量；
2. 在 `sc_u{user_id}_c{course_id}_v{course_index_version}` 中限定
   `user_id` / `course_id` / `doc_id IN valid_doc_ids` / `embedding_model` 检索 Top-10 子块；
3. 按余弦相似度降序映射回 `parent_chunk_id` 并去重，取 Top-4 父块候选；
4. Top-1 相似度低于 $\tau_{\text{reject}}$ 或召回为空时置 `is_rejected`，由上层走拒答分支。

本模块**不**直连 MySQL、**不**调用 Java 回查父块（属 TASK-602），也**不**引入
BM25/RRF/Reranker（v1.1）。
"""

from __future__ import annotations

import asyncio
import re

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.services.ingest.embedding import EmbeddingService
from app.services.ingest.models import ChildVectorMatch
from app.services.ingest.vector_store import ChromaVectorStore
from app.services.rag.retriever_base import (
    ParentMatch,
    RetrievalConfig,
    RetrievalRequest,
    RetrievalResult,
)

_DECIMAL_ID = re.compile(r"[0-9]+")
#: `04` §4.5.1：query 自动 trim 后长度 1 ~ 2000。
_MAX_QUERY_LENGTH = 2000


class DenseVectorRetriever:
    def __init__(self, settings: Settings, *, embedding_service: EmbeddingService | None = None,
                 vector_store: ChromaVectorStore | None = None,
                 config: RetrievalConfig | None = None) -> None:
        self._settings = settings
        self._embedding = embedding_service or EmbeddingService(settings)
        self._store = vector_store or ChromaVectorStore(settings)
        self._config = config or RetrievalConfig()

    async def retrieve(self, request: RetrievalRequest) -> RetrievalResult:
        query = _validated_query(request.query)
        _validate_scope(request)
        if not request.valid_doc_ids:
            return RetrievalResult(threshold=self._config.similarity_threshold)

        query_vector = await self._embedding.embed_query(
            request.embedding_endpoint, query,
            expected_dim=request.embedding_dim or 0)
        matches = await asyncio.to_thread(
            self._store.query_children,
            user_id=request.user_id,
            course_id=request.course_id,
            course_index_version=request.course_index_version,
            valid_doc_ids=_unique_ids(request.valid_doc_ids),
            query_embedding=query_vector,
            top_k=self._config.top_k,
            embedding_model=request.embedding_endpoint.model_name,
        )
        children = tuple(sorted(matches, key=lambda item: item.similarity, reverse=True))
        top_similarity = children[0].similarity if children else None
        threshold = self._config.similarity_threshold
        below_threshold = top_similarity is not None and top_similarity < threshold
        if below_threshold:
            return RetrievalResult(children=children, top_similarity=top_similarity,
                                   threshold=threshold, below_threshold=True)
        return RetrievalResult(
            children=children,
            parents=_map_parents(children, self._config.top_parents),
            top_similarity=top_similarity,
            threshold=threshold,
        )


def _map_parents(children: tuple[ChildVectorMatch, ...],
                 top_parents: int) -> tuple[ParentMatch, ...]:
    """按相似度降序对父块去重，保留每个父块的最佳相似度。"""
    seen: set[str] = set()
    parents: list[ParentMatch] = []
    for child in children:
        if child.parent_chunk_id in seen:
            continue
        seen.add(child.parent_chunk_id)
        parents.append(ParentMatch(child.parent_chunk_id, child.doc_id, child.similarity))
        if len(parents) >= top_parents:
            break
    return tuple(parents)


def _validated_query(raw: str) -> str:
    query = (raw or "").strip()
    if not query:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "检索问题不能为空。")
    if len(query) > _MAX_QUERY_LENGTH:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  f"检索问题长度不得超过 {_MAX_QUERY_LENGTH} 字符。")
    return query


def _validate_scope(request: RetrievalRequest) -> None:
    _positive_id(request.user_id, "user_id")
    _positive_id(request.course_id, "course_id")
    if not isinstance(request.course_index_version, int) or request.course_index_version < 1:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  "课程索引版本必须为正整数。")
    if request.embedding_dim is not None and (
            not isinstance(request.embedding_dim, int) or request.embedding_dim < 1):
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  "Embedding 维度必须为正整数。")
    for doc_id in request.valid_doc_ids or ():
        _positive_id(doc_id, "doc_id")


def _unique_ids(ids: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(ids))


def _positive_id(value: str, field: str) -> None:
    text = "" if value is None else str(value).strip()
    if not _DECIMAL_ID.fullmatch(text) or int(text) <= 0:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  f"{field} 必须为十进制正整数。")
