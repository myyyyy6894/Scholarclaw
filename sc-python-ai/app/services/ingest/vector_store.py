"""ChromaDB 衍生向量索引（TASK-504）。

* Collection 按 `sc_u{uid}_c{cid}_v{course_index_version}` 隔离，避免课程/版本/维度混用；
* 写入前按 `doc_id` 删除旧子块，重复任务不会叠加；
* 旧版本向量只在显式清理接口中删除（TASK-505/901 调用），不会被当作当前索引。
"""

from __future__ import annotations

from urllib.parse import urlparse

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError, RetryableAiError
from app.services.ingest.models import ChildChunk, ParseContext


class ChromaVectorStore:
    def __init__(self, settings: Settings, *, client=None) -> None:
        self._settings = settings
        self._client = client

    def replace_document(self, context: ParseContext, children: list[ChildChunk],
                         embeddings: list[list[float]]) -> None:
        if len(children) != len(embeddings):
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                      "子块数量与向量数量不一致。")
        if not children:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "没有可写入的子块。")
        try:
            collection = self._client_or_create().get_or_create_collection(
                self.collection_name(context.user_id, context.course_id,
                                     context.course_index_version),
                metadata={"hnsw:space": "cosine"})
            collection.delete(where={"doc_id": int(context.doc_id)})
            collection.add(
                ids=[f"{child.parent_chunk_id}_c{child.chunk_seq}" for child in children],
                embeddings=embeddings,
                documents=[child.content for child in children],
                metadatas=[self._metadata(context, child) for child in children],
            )
        except NonRetryableAiError:
            raise
        except Exception as error:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                   "向量索引写入失败。") from error

    def delete_document(self, user_id: str, course_id: str, doc_id: str,
                        *, course_index_version: int | None = None,
                        stale_parse_version: int | None = None) -> None:
        where: dict = {"doc_id": int(doc_id)}
        if stale_parse_version is not None:
            where = {"$and": [{"doc_id": int(doc_id)},
                              {"parse_version": stale_parse_version}]}
        names = ([self.collection_name(user_id, course_id, course_index_version)]
                 if course_index_version is not None
                 else self._list_names(f"sc_u{user_id}_c{course_id}_v"))
        self._delete_where(names, where)

    def delete_course(self, user_id: str, course_id: str,
                      *, course_index_version: int | None = None) -> None:
        if course_index_version is not None:
            self._delete_collections([self.collection_name(
                user_id, course_id, course_index_version)])
        else:
            self._delete_collections(self._list_names(f"sc_u{user_id}_c{course_id}_v"))

    def delete_user(self, user_id: str) -> None:
        self._delete_collections(self._list_names(f"sc_u{user_id}_"))

    def collection_name(self, user_id: str, course_id: str, version: int) -> str:
        return f"sc_u{user_id}_c{course_id}_v{version}"

    def _delete_where(self, names: list[str], where: dict) -> None:
        for name in names:
            try:
                collection = self._client_or_create().get_collection(name)
                collection.delete(where=where)
            except Exception as error:  # 不存在也视为幂等成功
                if not _is_not_found(error):
                    raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                           "向量清理失败。") from error

    def _delete_collections(self, names: list[str]) -> None:
        client = self._client_or_create()
        for name in names:
            try:
                client.delete_collection(name)
            except Exception as error:
                if not _is_not_found(error):
                    raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                           "向量清理失败。") from error

    def _list_names(self, prefix: str) -> list[str]:
        try:
            collections = self._client_or_create().list_collections()
        except Exception as error:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                   "向量索引读取失败。") from error
        names = []
        for collection in collections:
            name = collection if isinstance(collection, str) else getattr(collection, "name", "")
            if name.startswith(prefix):
                names.append(name)
        return names

    def _client_or_create(self):
        if self._client is not None:
            return self._client
        base_url = self._settings.chromadb_base_url
        if not base_url:
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR, "ChromaDB 基址未配置。")
        parsed = urlparse(base_url)
        if not parsed.hostname:
            raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR, "ChromaDB 基址非法。")
        try:
            import chromadb
            from chromadb.config import Settings as ChromaSettings

            self._client = chromadb.HttpClient(
                host=parsed.hostname,
                port=parsed.port or (443 if parsed.scheme == "https" else 8000),
                ssl=parsed.scheme == "https",
                settings=ChromaSettings(anonymized_telemetry=False),
            )
        except Exception as error:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                   "ChromaDB 客户端初始化失败。") from error
        return self._client

    @staticmethod
    def _metadata(context: ParseContext, child: ChildChunk) -> dict:
        return {
            "user_id": int(context.user_id),
            "course_id": int(context.course_id),
            "doc_id": int(context.doc_id),
            "parse_version": context.parse_version,
            "course_index_version": context.course_index_version,
            "parent_chunk_id": child.parent_chunk_id,
            "page_num": child.page_num,
            "embedding_model": context.embedding_model or "",
            "doc_name": context.doc_name or "",
        }


def _is_not_found(error: Exception) -> bool:
    message = str(error).lower()
    return "not exist" in message or "not found" in message or "does not exist" in message
