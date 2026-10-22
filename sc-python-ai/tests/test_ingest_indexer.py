import pytest

from app.core.config import load_settings
from app.services.ingest.indexer import DocumentIndexer
from app.services.ingest.models import ChildChunk, ParseContext, ParseResult


class FakeEmbedding:
    def __init__(self):
        self.texts = []

    async def embed(self, context, texts):
        self.texts = texts
        return [[0.1, 0.2] for _ in texts]


class FakeStore:
    def __init__(self):
        self.calls = []

    def replace_document(self, context, children, embeddings):
        self.calls.append((context.doc_id, len(children), len(embeddings)))


@pytest.mark.asyncio
async def test_indexer_embeds_and_writes_all_children() -> None:
    embedding = FakeEmbedding()
    store = FakeStore()
    indexer = DocumentIndexer(load_settings({}), embedding_service=embedding, vector_store=store)
    context = ParseContext(True, None, "10001", "2001", "50001", "a", ".txt",
                           "raw/10001/2001/50001.txt", 1, 1, "OPENAI_COMPATIBLE",
                           "https://example.com/v1", "secret", "embed", 2)
    result = ParseResult(page_count=1, parents=[], children=[
        ChildChunk("pc_50001_v1_1", 1, 1, "a"),
        ChildChunk("pc_50001_v1_2", 2, 1, "b"),
    ])

    count = await indexer.index(context, result)

    assert count == 2
    assert embedding.texts == ["a", "b"]
    assert store.calls == [("50001", 2, 2)]
