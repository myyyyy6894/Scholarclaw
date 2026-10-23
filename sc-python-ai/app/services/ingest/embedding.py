"""Embedding 生成（TASK-504 / TASK-601）：复用受控模型网关。

* 文档子块批量向量化（TASK-504）；
* RAG 查询向量化（TASK-601）：使用课程绑定的 Embedding 模型，与写入索引时保持同一模型。

API Key 只来自 `/parse-context` 的内存配置或本次内部请求体，不出现在日志、异常或持久化中。
"""

from __future__ import annotations

from app.core.config import Settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy, SsrfSafeModelGateway
from app.services.ingest.models import ParseContext

BATCH_SIZE = 32


class EmbeddingService:
    def __init__(self, settings: Settings, *, http_transport=None) -> None:
        self._settings = settings
        self._http_transport = http_transport

    async def embed(self, context: ParseContext, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if not context.embedding_provider or not context.embedding_base_url \
                or not context.embedding_model:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "Embedding 配置不完整。")
        endpoint = ModelEndpoint(context.embedding_provider, context.embedding_base_url,
                                 context.embedding_api_key, context.embedding_model)
        return await self.embed_texts(endpoint, texts,
                                      expected_dim=context.embedding_dim or 0)

    async def embed_query(self, endpoint: ModelEndpoint, text: str, *,
                          expected_dim: int | None = None) -> list[float]:
        """把用户问题编码为查询向量；模型配置必须与课程索引一致。"""
        if not endpoint.provider or not endpoint.base_url or not endpoint.model_name:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "Embedding 配置不完整。")
        if not text.strip():
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "检索问题不能为空。")
        vectors = await self.embed_texts(endpoint, [text], expected_dim=expected_dim or 0)
        return vectors[0]

    async def embed_texts(self, endpoint: ModelEndpoint, texts: list[str], *,
                          expected_dim: int = 0) -> list[list[float]]:
        if not texts:
            return []
        if not endpoint.provider or not endpoint.base_url or not endpoint.model_name:
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "Embedding 配置不完整。")
        vectors: list[list[float]] = []
        async with SsrfSafeModelGateway(
                self._settings, http_transport=self._http_transport) as gateway:
            for start in range(0, len(texts), BATCH_SIZE):
                batch = texts[start:start + BATCH_SIZE]
                payload = await gateway.post_json(
                    endpoint, "embeddings", {"model": endpoint.model_name, "input": batch},
                    retry=RetryPolicy.standard())
                vectors.extend(_parse_embeddings(payload, len(batch), expected_dim))
        return vectors


def _parse_embeddings(payload: dict, expected_count: int, expected_dim: int) -> list[list[float]]:
    data = payload.get("data")
    if not isinstance(data, list) or len(data) != expected_count:
        raise NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR,
                                  "Embedding 响应数量与输入不一致。")
    ordered = sorted(
        (item for item in data if isinstance(item, dict)),
        key=lambda item: item.get("index", 0) if isinstance(item.get("index"), int) else 0,
    )
    vectors: list[list[float]] = []
    for item in ordered:
        embedding = item.get("embedding")
        if not isinstance(embedding, list) or not embedding:
            raise NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR,
                                      "Embedding 响应缺少向量。")
        if expected_dim and len(embedding) != expected_dim:
            raise NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR,
                                      "Embedding 维度与课程配置不一致。")
        if not all(isinstance(value, (int, float)) for value in embedding):
            raise NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR,
                                      "Embedding 向量包含非数值元素。")
        vectors.append([float(value) for value in embedding])
    return vectors
