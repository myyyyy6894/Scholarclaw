from fastapi.testclient import TestClient

from app.api.internal_v1 import endpoints_rag


class FakeStore:
    calls = []

    def __init__(self, _settings):
        pass

    def delete_document(self, user, course, doc, *, course_index_version=None,
                        stale_parse_version=None):
        self.calls.append(("doc", user, course, doc, course_index_version, stale_parse_version))

    def delete_course(self, user, course, *, course_index_version=None):
        self.calls.append(("course", user, course, course_index_version))

    def delete_user(self, user):
        self.calls.append(("user", user))


def test_cleanup_document_is_idempotent_and_identity_checked(
        authorized_remote_client: TestClient, monkeypatch) -> None:
    FakeStore.calls = []
    monkeypatch.setattr(endpoints_rag, "ChromaVectorStore", FakeStore)

    response = authorized_remote_client.request(
        "DELETE", "/internal/ai/v1/rag/vectors",
        json={"scope": "DOCUMENT", "user_id": "10001", "course_id": "2001",
              "course_index_version": 1, "doc_id": "50001", "stale_parse_version": 1})

    assert response.status_code == 200
    assert response.json()["data"] == {"scope": "DOCUMENT", "cleaned": True}
    assert FakeStore.calls == [("doc", "10001", "2001", "50001", 1, 1)]


def test_cleanup_rejects_cross_user_body(authorized_remote_client: TestClient) -> None:
    response = authorized_remote_client.request(
        "DELETE", "/internal/ai/v1/rag/vectors",
        json={"scope": "USER_ALL", "user_id": "20002"})

    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
