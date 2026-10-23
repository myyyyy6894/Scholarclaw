import inspect

import pytest

from app.core.config import load_settings
from app.core.exceptions import NonRetryableAiError
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.ingest.models import ChildVectorMatch
from app.services.rag.dense_retriever import DenseVectorRetriever
from app.services.rag.retriever_base import (
    DEFAULT_SIMILARITY_THRESHOLD,
    DEFAULT_TOP_K,
    DEFAULT_TOP_PARENTS,
    RetrievalConfig,
    RetrievalRequest,
    RetrieverStrategy,
)


class FakeEmbedding:
    def __init__(self, vector=None):
        self.vector = vector or [0.1, 0.2, 0.3]
        self.calls = []

    async def embed_query(self, endpoint, text, *, expected_dim=None):
        self.calls.append((endpoint, text, expected_dim))
        return self.vector


class FakeStore:
    def __init__(self, matches=None):
        self.matches = matches or []
        self.kwargs = None

    def query_children(self, **kwargs):
        self.kwargs = kwargs
        return self.matches


def _endpoint(model_name="embed"):
    return ModelEndpoint("OPENAI_COMPATIBLE", "https://api.example.com/v1", "sk-test", model_name)


def _request(**overrides):
    values = {
        "user_id": "10001",
        "course_id": "2001",
        "course_index_version": 3,
        "valid_doc_ids": ("50001", "50002"),
        "query": "  两阶段锁协议是什么？  ",
        "embedding_endpoint": _endpoint(),
        "embedding_dim": 3,
    }
    values.update(overrides)
    return RetrievalRequest(**values)


def _hit(chunk_id, parent_id, doc_id, similarity, page=1):
    return ChildVectorMatch(chunk_id, parent_id, doc_id, page, "内容", similarity)


def _retriever(matches, embedding=None, config=None):
    embedding = embedding or FakeEmbedding()
    store = FakeStore(matches)
    retriever = DenseVectorRetriever(load_settings({}), embedding_service=embedding,
                                     vector_store=store, config=config)
    return retriever, embedding, store


@pytest.mark.asyncio
async def test_retrieve_embeds_query_with_course_scope_and_maps_top_parents() -> None:
    matches = [
        _hit("pc_1_c1", "pc_1", "50001", 0.91),
        _hit("pc_1_c2", "pc_1", "50001", 0.88),
        _hit("pc_2_c1", "pc_2", "50002", 0.75),
        _hit("pc_3_c1", "pc_3", "50001", 0.70),
        _hit("pc_4_c1", "pc_4", "50002", 0.65),
        _hit("pc_5_c1", "pc_5", "50001", 0.60),
    ]
    retriever, embedding, store = _retriever(matches)

    result = await retriever.retrieve(_request(valid_doc_ids=("50001", "50002", "50001")))

    assert embedding.calls == [(_endpoint(), "两阶段锁协议是什么？", 3)]
    assert store.kwargs["user_id"] == "10001"
    assert store.kwargs["course_id"] == "2001"
    assert store.kwargs["course_index_version"] == 3
    assert store.kwargs["valid_doc_ids"] == ("50001", "50002")
    assert store.kwargs["query_embedding"] == [0.1, 0.2, 0.3]
    assert store.kwargs["top_k"] == DEFAULT_TOP_K
    assert store.kwargs["embedding_model"] == "embed"
    assert result.parent_chunk_ids == ("pc_1", "pc_2", "pc_3", "pc_4")
    assert [parent.similarity for parent in result.parents] == [0.91, 0.75, 0.70, 0.65]
    assert result.top_similarity == 0.91
    assert result.below_threshold is False
    assert result.is_rejected is False


@pytest.mark.asyncio
async def test_top1_below_threshold_triggers_rejection_without_parents() -> None:
    retriever, _, _ = _retriever([_hit("pc_1_c1", "pc_1", "50001", 0.2)])

    result = await retriever.retrieve(_request())

    assert result.top_similarity == 0.2
    assert result.below_threshold is True
    assert result.parents == ()
    assert result.parent_chunk_ids == ()
    assert result.is_rejected is True
    assert len(result.children) == 1


@pytest.mark.asyncio
async def test_empty_recall_is_rejected() -> None:
    retriever, _, _ = _retriever([])

    result = await retriever.retrieve(_request())

    assert result.top_similarity is None
    assert result.below_threshold is False
    assert result.is_rejected is True


@pytest.mark.asyncio
async def test_empty_valid_doc_ids_skips_embedding_and_query() -> None:
    retriever, embedding, store = _retriever([_hit("pc_1_c1", "pc_1", "50001", 0.9)])

    result = await retriever.retrieve(_request(valid_doc_ids=()))

    assert embedding.calls == []
    assert store.kwargs is None
    assert result.is_rejected is True


@pytest.mark.asyncio
async def test_duplicate_parent_hits_keep_best_match_only() -> None:
    retriever, _, _ = _retriever([
        _hit("pc_1_c1", "pc_1", "50001", 0.9),
        _hit("pc_1_c2", "pc_1", "50001", 0.85),
    ])

    result = await retriever.retrieve(_request())

    assert result.parent_chunk_ids == ("pc_1",)
    assert result.parents[0].similarity == 0.9


@pytest.mark.asyncio
async def test_blank_query_is_rejected_before_any_io() -> None:
    retriever, embedding, store = _retriever([])

    with pytest.raises(NonRetryableAiError) as error:
        await retriever.retrieve(_request(query="   "))

    assert error.value.error_code.value == "VALIDATION_ERROR"
    assert embedding.calls == []
    assert store.kwargs is None


@pytest.mark.asyncio
async def test_invalid_doc_id_is_rejected() -> None:
    retriever, embedding, _ = _retriever([])

    with pytest.raises(NonRetryableAiError) as error:
        await retriever.retrieve(_request(valid_doc_ids=("50001", "abc")))

    assert error.value.error_code.value == "VALIDATION_ERROR"
    assert embedding.calls == []


@pytest.mark.asyncio
async def test_query_longer_than_contract_limit_is_rejected() -> None:
    retriever, _, _ = _retriever([])

    with pytest.raises(NonRetryableAiError):
        await retriever.retrieve(_request(query="问" * 2001))


def test_default_config_matches_contract() -> None:
    config = RetrievalConfig()

    assert config.top_k == DEFAULT_TOP_K == 10
    assert config.top_parents == DEFAULT_TOP_PARENTS == 4
    assert config.similarity_threshold == DEFAULT_SIMILARITY_THRESHOLD


def test_invalid_config_is_rejected() -> None:
    with pytest.raises(ValueError):
        RetrievalConfig(top_k=0)
    with pytest.raises(ValueError):
        RetrievalConfig(top_parents=0)
    with pytest.raises(ValueError):
        RetrievalConfig(similarity_threshold=1.5)


def test_dense_retriever_implements_retriever_strategy() -> None:
    assert isinstance(DenseVectorRetriever(load_settings({})), RetrieverStrategy)
    assert inspect.iscoroutinefunction(DenseVectorRetriever.retrieve)
