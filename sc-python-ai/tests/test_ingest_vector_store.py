from app.core.config import load_settings
from app.services.ingest.models import ChildChunk, ParseContext
from app.services.ingest.vector_store import ChromaVectorStore


class FakeCollection:
    def __init__(self, name):
        self.name = name
        self.deleted = []
        self.added = {}

    def delete(self, where):
        self.deleted.append(where)

    def add(self, **kwargs):
        self.added = kwargs


class FakeClient:
    def __init__(self):
        self.collections = {}
        self.deleted = []

    def get_or_create_collection(self, name, metadata=None):
        return self.collections.setdefault(name, FakeCollection(name))

    def get_collection(self, name):
        if name not in self.collections:
            raise ValueError("Collection does not exist")
        return self.collections[name]

    def list_collections(self):
        return list(self.collections.values())

    def delete_collection(self, name):
        if name not in self.collections:
            raise ValueError("Collection does not exist")
        self.deleted.append(name)
        self.collections.pop(name)


def _settings():
    return load_settings({"SC_CHROMADB_BASE_URL": "http://sc-chromadb:8000"})


def _context():
    return ParseContext(
        True, None, "10001", "2001", "50001", "a.pdf", ".pdf",
        "raw/10001/2001/50001.pdf", 2, 3, "OPENAI_COMPATIBLE",
        "https://example.com/v1", "secret", "embed", 3,
    )


def test_replace_document_deletes_old_vectors_and_writes_metadata() -> None:
    client = FakeClient()
    store = ChromaVectorStore(_settings(), client=client)
    children = [ChildChunk("pc_50001_v2_1", 1, 4, "内容")]

    store.replace_document(_context(), children, [[0.1, 0.2, 0.3]])

    collection = client.collections["sc_u10001_c2001_v3"]
    assert collection.deleted == [{"doc_id": 50001}]
    assert collection.added["ids"] == ["pc_50001_v2_1_c1"]
    assert collection.added["metadatas"][0]["parse_version"] == 2
    assert collection.added["metadatas"][0]["course_index_version"] == 3
    assert collection.added["metadatas"][0]["page_num"] == 4


def test_cleanup_is_idempotent_for_missing_collections() -> None:
    store = ChromaVectorStore(_settings(), client=FakeClient())

    store.delete_document("10001", "2001", "50001", course_index_version=3)
    store.delete_course("10001", "2001")
    store.delete_user("10001")
