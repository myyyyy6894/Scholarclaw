"""ChromaDB 衍生向量索引（TASK-504 / TASK-601）。

* Collection 按 `sc_u{uid}_c{cid}_v{course_index_version}` 隔离，避免课程/版本/维度混用；
* 写入前按 `doc_id` 删除旧子块，重复任务不会叠加；
* 旧版本向量只在显式清理接口中删除（TASK-505/901 调用），不会被当作当前索引；
* 检索（TASK-601）只读当前版本 Collection，并强制 `user_id` / `course_id` /
  `doc_id IN valid_doc_ids` / `embedding_model` 同时匹配。
"""

from __future__ import annotations

import math
import re
from urllib.parse import urlparse

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError, RetryableAiError
from app.core.logging import get_logger
from app.services.ingest.models import ChildChunk, ChildVectorMatch, ParseContext

logger = get_logger(__name__)

_DECIMAL_ID = re.compile(r"[0-9]+")
#: ChromaDB 的余弦距离 = 1 - 余弦相似度；相似度可能因浮点误差略越界。
_MIN_SIMILARITY = -1.0
_MAX_SIMILARITY = 1.0
_COSINE_SPACE = "cosine"


class ChromaVectorStore:
    def __init__(self, settings: Settings, *, client=None) -> None:
        self._settings = settings
        self._client = client

    def replace_document(self, context: ParseContext, children: list[ChildChunk],
                         embeddings: list[list[float]]) -> None:
        if not context.embedding_model or not context.embedding_model.strip():
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                      "写入向量时 Embedding 模型不能为空。")
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

    def query_children(self, *, user_id: str, course_id: str, course_index_version: int,
                       valid_doc_ids: tuple[str, ...] | list[str],
                       query_embedding: list[float], top_k: int,
                       embedding_model: str) -> list[ChildVectorMatch]:
        """在当前版本 Collection 中执行课程范围内的子块向量检索。

        * 只读：不创建 Collection；集合不存在视为"尚无索引"，返回空列表；
        * `valid_doc_ids` 必须全部是十进制正数，且非空；调用方负责保证它来自
          Java 的权威 READY 文档集合；
        * 返回的 `similarity` 为余弦相似度（ChromaDB 返回的是余弦距离）。
        """
        if top_k < 1:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "检索 Top-K 必须为正整数。")
        if not embedding_model or not embedding_model.strip():
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                      "检索时 Embedding 模型不能为空。")
        if not isinstance(course_index_version, int) or course_index_version < 1:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                      "课程索引版本必须为正整数。")
        user = _decimal_id(user_id, "user_id")
        course = _decimal_id(course_id, "course_id")
        doc_ids = _decimal_ids(valid_doc_ids)
        if not doc_ids:
            return []
        vector = _validated_embedding(query_embedding)
        collection = self._read_collection(
            self.collection_name(user_id, course_id, course_index_version))
        if collection is None:
            return []

        where: dict = {"$and": [
            {"user_id": user},
            {"course_id": course},
            {"doc_id": {"$in": doc_ids}},
        ]}
        try:
            _guard_cosine_space(collection)
            raw = collection.query(query_embeddings=[vector], n_results=top_k,
                                   where=where,
                                   include=["metadatas", "documents", "distances"])
        except NonRetryableAiError:
            raise
        except Exception as error:
            if _is_dimension_error(error):
                raise NonRetryableAiError(
                    AiErrorCode.UPSTREAM_MODEL_ERROR,
                    "Embedding 维度与课程索引不一致，请重新探测模型或重建索引。",
                ) from error
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                   "向量检索失败。") from error
        return _to_matches(raw, allowed_doc_ids=set(doc_ids),
                           expected_model=embedding_model,
                           expected_version=course_index_version)

    def collection_name(self, user_id: str, course_id: str, version: int) -> str:
        return f"sc_u{user_id}_c{course_id}_v{version}"

    def list_collection_names(self) -> list[str]:
        """列出本应用管理的全部 Collection 名称（只读，供 Java 侧孤儿对账）。

        只返回 `sc_u` 前缀，避免误把同机 Chroma 中其他应用的集合纳入清理范围。
        """
        return sorted(self._list_names("sc_u"))

    def _read_collection(self, name: str):
        """只读打开 Collection；不存在返回 None，不隐式创建空集合。"""
        try:
            return self._client_or_create().get_collection(name)
        except Exception as error:
            if _is_not_found(error):
                return None
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                   "向量索引读取失败。") from error

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


def _decimal_id(value: str, field: str) -> int:
    text = "" if value is None else str(value).strip()
    if not _DECIMAL_ID.fullmatch(text) or int(text) <= 0:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  f"{field} 必须为十进制正整数。")
    return int(text)


def _decimal_ids(values: tuple[str, ...] | list[str]) -> list[int]:
    return [_decimal_id(value, "doc_id") for value in values]


def _validated_embedding(vector: list[float]) -> list[float]:
    if not vector:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "查询向量不能为空。")
    try:
        converted = [float(value) for value in vector]
    except (TypeError, ValueError) as error:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  "查询向量包含非数值元素。") from error
    if not all(math.isfinite(value) for value in converted):
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR,
                                  "查询向量包含非有限数值。")
    return converted


def _guard_cosine_space(collection) -> None:
    """相似度换算依赖余弦距离；集合度量方式不一致时拒绝返回误导性分数。"""
    metadata = getattr(collection, "metadata", None)
    space = metadata.get("hnsw:space") if isinstance(metadata, dict) else None
    if space is not None and space != _COSINE_SPACE:
        raise NonRetryableAiError(AiErrorCode.INTERNAL_ERROR,
                                  "向量集合度量方式不是余弦距离，无法计算相似度。")


def _is_dimension_error(error: Exception) -> bool:
    return "dimension" in str(error).lower()


def _first(value) -> list:
    if not isinstance(value, list) or not value:
        return []
    first = value[0]
    return first if isinstance(first, list) else []


def _to_matches(raw, *, allowed_doc_ids: set[int],
                expected_model: str | None,
                expected_version: int | None = None) -> list[ChildVectorMatch]:
    ids = _first((raw or {}).get("ids"))
    metadatas = _first((raw or {}).get("metadatas"))
    documents = _first((raw or {}).get("documents"))
    distances = _first((raw or {}).get("distances"))
    matches: list[ChildVectorMatch] = []
    for chunk_id, metadata, content, distance in zip(ids, metadatas, documents, distances):
        match = _to_match(chunk_id, metadata, content, distance,
                          allowed_doc_ids=allowed_doc_ids,
                          expected_model=expected_model,
                          expected_version=expected_version)
        if match is not None:
            matches.append(match)
    matches.sort(key=lambda item: item.similarity, reverse=True)
    return matches


def _to_match(chunk_id, metadata, content, distance, *, allowed_doc_ids: set[int],
              expected_model: str | None,
              expected_version: int | None = None) -> ChildVectorMatch | None:
    if not isinstance(metadata, dict) or not metadata.get("parent_chunk_id"):
        logger.warning("跳过缺少 parent_chunk_id 的向量记录 chunk_id=%s", chunk_id)
        return None
    try:
        doc_id = int(metadata.get("doc_id"))
        similarity = 1.0 - float(distance)
    except (TypeError, ValueError):
        logger.warning("跳过元数据或距离非法的向量记录 chunk_id=%s", chunk_id)
        return None
    if not math.isfinite(similarity):
        logger.warning("跳过距离非有限的向量记录 chunk_id=%s", chunk_id)
        return None
    if doc_id not in allowed_doc_ids:
        logger.warning("丢弃不在 valid_doc_ids 内的向量记录 chunk_id=%s doc_id=%s",
                       chunk_id, doc_id)
        return None
    if metadata.get("embedding_model") != expected_model:
        logger.warning("丢弃 Embedding 模型缺失或不一致的向量记录 chunk_id=%s", chunk_id)
        return None
    if expected_version is not None:
        raw_version = metadata.get("course_index_version")
        if raw_version is not None and _optional_int(raw_version) != expected_version:
            logger.warning("丢弃索引版本陈旧的向量记录 chunk_id=%s", chunk_id)
            return None
    return ChildVectorMatch(
        chunk_id=str(chunk_id),
        parent_chunk_id=str(metadata["parent_chunk_id"]),
        doc_id=str(doc_id),
        page_num=_optional_int(metadata.get("page_num")),
        content=content if isinstance(content, str) else "",
        similarity=min(_MAX_SIMILARITY, max(_MIN_SIMILARITY, similarity)),
    )


def _optional_int(value) -> int | None:
    try:
        return None if value is None else int(value)
    except (TypeError, ValueError):
        return None


def _is_not_found(error: Exception) -> bool:
    message = str(error).lower()
    return "not exist" in message or "not found" in message or "does not exist" in message
