"""解析结果索引编排（TASK-504）：生成子块向量并写入 ChromaDB。"""

from __future__ import annotations

from app.core.config import Settings
from app.services.ingest.embedding import EmbeddingService
from app.services.ingest.models import ParseContext, ParseResult
from app.services.ingest.vector_store import ChromaVectorStore


class DocumentIndexer:
    def __init__(self, settings: Settings, *, embedding_service: EmbeddingService | None = None,
                 vector_store: ChromaVectorStore | None = None) -> None:
        self._settings = settings
        self._embedding = embedding_service or EmbeddingService(settings)
        self._store = vector_store or ChromaVectorStore(settings)

    async def index(self, context: ParseContext, result: ParseResult) -> int:
        texts = [child.content for child in result.children]
        embeddings = await self._embedding.embed(context, texts)
        self._store.replace_document(context, result.children, embeddings)
        return len(embeddings)
