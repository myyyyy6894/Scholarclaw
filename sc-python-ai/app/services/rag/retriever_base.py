"""检索策略抽象与契约（TASK-601，`02` §5.2.1 / `01` FR-RAG-002）。

MVP 只有 :class:`~app.services.rag.dense_retriever.DenseVectorRetriever`。
v1.1 的 BM25/RRF 需要实现同一 :class:`RetrieverStrategy`，但本阶段不引入任何
关键词检索、融合或重排实现。

阈值 `similarity_threshold` 的默认值只是工程占位：PRD 明确 $\tau_{\text{reject}}$
为「建议方案-待验证」，须由 TASK-606 的 Golden Dataset 标定后再固化，不得把当前
默认值宣称为已标定结果。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.ingest.models import ChildVectorMatch

#: `01` FR-RAG-002 / `02` §5.2.2：ChromaDB 召回 Top-10 子块。
DEFAULT_TOP_K = 10
#: 子块按相似度映射回父块后取 Top-4。
DEFAULT_TOP_PARENTS = 4
#: 余弦相似度拒答阈值占位值；TASK-606 标定后替换。
DEFAULT_SIMILARITY_THRESHOLD = 0.35


@dataclass(frozen=True)
class RetrievalConfig:
    """检索参数。允许运行时覆盖，便于 Golden Dataset 标定。"""

    top_k: int = DEFAULT_TOP_K
    top_parents: int = DEFAULT_TOP_PARENTS
    similarity_threshold: float = DEFAULT_SIMILARITY_THRESHOLD

    def __post_init__(self) -> None:
        if self.top_k < 1:
            raise ValueError("top_k 必须为正整数")
        if self.top_parents < 1:
            raise ValueError("top_parents 必须为正整数")
        if not -1.0 <= self.similarity_threshold <= 1.0:
            raise ValueError("similarity_threshold 必须落在 [-1, 1] 区间")


@dataclass(frozen=True)
class RetrievalRequest:
    """一次课程内检索所需的全部输入。

    `valid_doc_ids` 由 Java 依据 MySQL 权威状态计算（`status = READY` 且
    `course_index_version` 匹配），Python 不得自行推断或扩大范围。
    """

    user_id: str
    course_id: str
    course_index_version: int
    valid_doc_ids: tuple[str, ...]
    query: str
    embedding_endpoint: ModelEndpoint
    embedding_dim: int | None = None


@dataclass(frozen=True)
class ParentMatch:
    """去重后的父块候选；`similarity` 取该父块下最相关子块的相似度。"""

    parent_chunk_id: str
    doc_id: str
    similarity: float


@dataclass(frozen=True)
class RetrievalResult:
    """检索结果与阈值门控结论。

    * `children`：ChromaDB 实际命中的子块（已按相似度降序）；
    * `parents`：按子块相似度去重后的 Top-4 父块候选，供 TASK-602 权威回查；
    * `below_threshold`：召回非空但 Top-1 相似度低于 $\tau_{\text{reject}}$；
    * `is_rejected`：无命中或低于阈值，调用方必须走无资料拒答分支，不得调用 LLM
      基于课件编造答案。
    """

    children: tuple[ChildVectorMatch, ...] = ()
    parents: tuple[ParentMatch, ...] = ()
    top_similarity: float | None = None
    threshold: float = DEFAULT_SIMILARITY_THRESHOLD
    below_threshold: bool = False

    @property
    def parent_chunk_ids(self) -> tuple[str, ...]:
        return tuple(parent.parent_chunk_id for parent in self.parents)

    @property
    def is_rejected(self) -> bool:
        return not self.parents


@runtime_checkable
class RetrieverStrategy(Protocol):
    """v1.1 混合检索的扩展点；MVP 只有稠密向量实现。"""

    async def retrieve(self, request: RetrievalRequest) -> RetrievalResult:
        ...
