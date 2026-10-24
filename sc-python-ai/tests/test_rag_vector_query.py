import pytest

from app.core.config import load_settings
from app.core.exceptions import NonRetryableAiError
from app.services.ingest.vector_store import ChromaVectorStore


class FakeCollection:
    def __init__(self, raw, metadata=None):
        self.raw = raw
        self.metadata = metadata if metadata is not None else {"hnsw:space": "cosine"}
        self.query_kwargs = None

    def query(self, **kwargs):
        self.query_kwargs = kwargs
        if isinstance(self.raw, Exception):
            raise self.raw
        return self.raw


class FakeClient:
    def __init__(self, collection=None, *, missing=False):
        self.collection = collection
        self.missing = missing
        self.lookups = []

    def get_collection(self, name):
        self.lookups.append(name)
        if self.missing:
            raise ValueError("Collection does not exist")
        return self.collection


def _settings():
    return load_settings({"SC_CHROMADB_BASE_URL": "http://sc-chromadb:8000"})


def _store(client):
    return ChromaVectorStore(_settings(), client=client)


def _raw(*rows):
    if not rows:
        return {"ids": [[]], "metadatas": [[]], "documents": [[]], "distances": [[]]}
    ids, metadatas, documents, distances = zip(*rows)
    return {
        "ids": [list(ids)],
        "metadatas": [list(metadatas)],
        "documents": [list(documents)],
        "distances": [list(distances)],
    }


def _metadata(parent_id, doc_id, page=1, model="embed"):
    return {
        "user_id": 10001, "course_id": 2001, "doc_id": doc_id,
        "parse_version": 1, "course_index_version": 3,
        "parent_chunk_id": parent_id, "page_num": page,
        "embedding_model": model, "doc_name": "a.pdf",
    }


def test_query_children_builds_scoped_where_and_converts_cosine_distance() -> None:
    collection = FakeCollection(_raw(
        ("pc_1_c1", _metadata("pc_1", 50001, page=4), "内容一", 0.2),
        ("pc_2_c1", _metadata("pc_2", 50002, page=9), "内容二", 0.5),
    ))
    store = _store(FakeClient(collection))

    matches = store.query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=["50001", "50002"], query_embedding=[1.0, 0.0],
        top_k=10, embedding_model="embed")

    assert collection.query_kwargs["n_results"] == 10
    assert collection.query_kwargs["query_embeddings"] == [[1.0, 0.0]]
    assert collection.query_kwargs["include"] == ["metadatas", "documents", "distances"]
    assert collection.query_kwargs["where"] == {"$and": [
        {"user_id": 10001},
        {"course_id": 2001},
        {"doc_id": {"$in": [50001, 50002]}},
    ]}
    assert [match.chunk_id for match in matches] == ["pc_1_c1", "pc_2_c1"]
    assert [match.similarity for match in matches] == pytest.approx([0.8, 0.5])
    assert matches[0].parent_chunk_id == "pc_1"
    assert matches[0].doc_id == "50001"
    assert matches[0].page_num == 4
    assert matches[0].content == "内容一"


def test_query_children_is_read_only_and_returns_empty_for_missing_collection() -> None:
    client = FakeClient(missing=True)

    matches = _store(client).query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
            embedding_model="embed")

    assert matches == []
    assert client.lookups == ["sc_u10001_c2001_v3"]


def test_query_children_short_circuits_on_empty_valid_doc_ids() -> None:
    client = FakeClient(collection=None)

    matches = _store(client).query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=[], query_embedding=[1.0], top_k=10,
            embedding_model="embed")

    assert matches == []
    assert client.lookups == []


def test_query_children_rejects_blank_embedding_model() -> None:
    store = _store(FakeClient(FakeCollection(_raw())))

    with pytest.raises(NonRetryableAiError) as error:
        store.query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
            embedding_model="")

    assert error.value.error_code.value == "VALIDATION_ERROR"


def test_query_children_rejects_non_decimal_ids() -> None:
    store = _store(FakeClient(FakeCollection(_raw())))

    with pytest.raises(NonRetryableAiError) as error:
        store.query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001x"], query_embedding=[1.0], top_k=10,
            embedding_model="embed")

    assert error.value.error_code.value == "VALIDATION_ERROR"


def test_query_children_rejects_empty_or_non_finite_query_vector() -> None:
    store = _store(FakeClient(FakeCollection(_raw())))

    with pytest.raises(NonRetryableAiError):
        store.query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001"], query_embedding=[], top_k=10,
            embedding_model="embed")
    with pytest.raises(NonRetryableAiError):
        store.query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001"], query_embedding=[float("nan")], top_k=10,
            embedding_model="embed")


def test_query_children_drops_docs_outside_valid_set_and_model_mismatch() -> None:
    collection = FakeCollection(_raw(
        ("pc_1_c1", _metadata("pc_1", 50001), "ok", 0.1),
        ("pc_2_c1", _metadata("pc_2", 50002), "not allowed", 0.2),
        ("pc_3_c1", _metadata("pc_3", 50001, model="other"), "other model", 0.3),
        ("pc_4_c1", {"doc_id": 50001}, "no parent", 0.4),
        ("pc_5_c1", _metadata("pc_5", 50001), "nan distance", float("nan")),
        ("pc_6_c1", _metadata("pc_6", 50001, model=""), "empty model", 0.1),
    ))

    matches = _store(FakeClient(collection)).query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
        embedding_model="embed")

    assert [match.chunk_id for match in matches] == ["pc_1_c1"]


def test_query_children_maps_dimension_mismatch_to_non_retryable_error() -> None:
    collection = FakeCollection(Exception("Embedding dimension 2 does not match"))

    with pytest.raises(NonRetryableAiError) as error:
        _store(FakeClient(collection)).query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
            embedding_model="embed")

    assert error.value.error_code.value == "UPSTREAM_MODEL_ERROR"
    assert error.value.retryable is False


def test_query_children_rejects_non_cosine_collection() -> None:
    collection = FakeCollection(_raw(), metadata={"hnsw:space": "l2"})

    with pytest.raises(NonRetryableAiError) as error:
        _store(FakeClient(collection)).query_children(
            user_id="10001", course_id="2001", course_index_version=3,
            valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
            embedding_model="embed")

    assert error.value.error_code.value == "INTERNAL_ERROR"
    assert collection.query_kwargs is None


def test_stale_vectors_cannot_bypass_ready_doc_or_index_version() -> None:
    """陈旧向量只能占用候选位，不能越过 READY/版本/父块校验。"""
    stale_version = {**_metadata("pc_stale_version", 50001),
                     "course_index_version": 2}
    stale_doc = _metadata("pc_stale_doc", 59999)
    collection = FakeCollection(_raw(
        ("pc_stale_v", stale_version, "旧索引版本", 0.0),
        ("pc_stale_d", stale_doc, "非 READY 文档", 0.1),
        ("pc_ok", _metadata("pc_ok", 50001), "有效", 0.2),
    ))

    matches = _store(FakeClient(collection)).query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=["50001"], query_embedding=[1.0], top_k=10,
        embedding_model="embed")

    assert [match.parent_chunk_id for match in matches] == ["pc_ok"]


def test_query_children_with_real_chroma_uses_course_filter() -> None:
    import chromadb
    from chromadb.config import Settings as ChromaSettings

    client = chromadb.EphemeralClient(
        settings=ChromaSettings(anonymized_telemetry=False))
    collection = client.get_or_create_collection(
        "sc_u10001_c2001_v3", metadata={"hnsw:space": "cosine"})
    collection.add(
        ids=["pc_1_c1", "pc_2_c1", "pc_3_c1"],
        embeddings=[[1.0, 0.0], [0.0, 1.0], [1.0, 0.0]],
        documents=["一", "二", "三"],
        metadatas=[
            _metadata("pc_1", 50001, page=2),
            _metadata("pc_2", 50002, page=5),
            _metadata("pc_3", 50003, page=8),
        ],
    )
    store = _store(client)

    matches = store.query_children(
        user_id="10001", course_id="2001", course_index_version=3,
        valid_doc_ids=["50001", "50002"], query_embedding=[1.0, 0.0],
        top_k=10, embedding_model="embed")

    assert [match.parent_chunk_id for match in matches] == ["pc_1", "pc_2"]
    assert matches[0].similarity == pytest.approx(1.0, abs=1e-5)
    assert matches[1].similarity == pytest.approx(0.0, abs=1e-5)
    assert all(match.doc_id != "50003" for match in matches)
