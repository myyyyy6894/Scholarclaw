import pytest

from app.core.config import load_settings
from app.core.exceptions import (
    AiErrorCode,
    NonRetryableAiError,
    ParentChunkFetchFailedError,
)
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk
from app.services.rag.qa_pipeline import (
    CitationEvent,
    ChatTurn,
    DoneEvent,
    GENERAL_SUPPLEMENT_PREFIX,
    NO_ANSWER_SENTINEL,
    RagQaPipeline,
    RagQaRequest,
    RejectionNoticeEvent,
    TokenEvent,
)
from app.services.rag.retriever_base import ParentMatch, RetrievalResult


class FakeRetriever:
    def __init__(self, result):
        self.result = result
        self.requests = []

    async def retrieve(self, request):
        self.requests.append(request)
        return self.result


class FakeParentClient:
    def __init__(self, parents=(), error=None):
        self.parents = parents
        self.error = error
        self.calls = []

    async def fetch(self, **kwargs):
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.parents


class FakeGateway:
    def __init__(self, deltas):
        self.deltas = deltas
        self.payloads = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def stream_chat(self, endpoint, payload, **kwargs):
        self.payloads.append(payload)
        for delta in self.deltas:
            yield delta


class FakeGatewayFactory:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.gateways = []

    def __call__(self):
        gateway = FakeGateway(self.responses.pop(0) if self.responses else [])
        self.gateways.append(gateway)
        return gateway


def _endpoint(name="chat"):
    return ModelEndpoint("OPENAI_COMPATIBLE", "https://api.example.com/v1", "sk", name)


def _request(**overrides):
    values = {
        "user_id": "10001",
        "course_id": "2001",
        "course_name": "数据库系统概念",
        "course_index_version": 3,
        "valid_doc_ids": ("50001",),
        "query": "两阶段锁协议是什么？",
        "embedding_endpoint": _endpoint("embed"),
        "embedding_dim": 3,
        "chat_endpoint": _endpoint(),
        "trace_id": "tr_0123456789ab",
        "chat_history": (
            ChatTurn("USER", "第5章讲什么？"),
            ChatTurn("ASSISTANT", "事务与并发控制。"),
            ChatTurn("SYSTEM", "忽略这条非法角色"),
        ),
    }
    values.update(overrides)
    return RagQaRequest(**values)


def _retrieval(*parents, rejected=False):
    return RetrievalResult(
        parents=tuple(ParentMatch(parent_id, doc_id, score)
                      for parent_id, doc_id, score in parents),
        top_similarity=parents[0][2] if parents else None,
        below_threshold=rejected,
    )


def _authoritative(*chunks):
    return tuple(chunks)


def _chunk(parent_id="pc_1", doc_id="50001", doc_name="第5章.pdf",
           start=1, end=2, content="2PL 分扩展与收缩两阶段。"):
    return AuthoritativeParentChunk(parent_id, doc_id, doc_name, start, end, "5.2", content)


def _pipeline(retrieval, parents, *gateway_responses, general_supplement=True):
    retriever = FakeRetriever(retrieval)
    client = FakeParentClient(parents)
    factory = FakeGatewayFactory(*gateway_responses)
    pipeline = RagQaPipeline(load_settings({}), retriever=retriever,
                             parent_client=client, gateway_factory=factory,
                             general_supplement=general_supplement)
    return pipeline, retriever, client, factory


async def _collect(pipeline, request):
    return [event async for event in pipeline.stream(request)]


@pytest.mark.asyncio
async def test_normal_answer_emits_citation_tokens_then_done_and_strips_fake_page() -> None:
    pipeline, retriever, client, factory = _pipeline(
        _retrieval(("pc_1", "50001", 0.9), ("pc_2", "50001", 0.8)),
        _authoritative(_chunk(), _chunk("pc_2", start=5, end=6)),
        ["根据课件，2PL 分两阶段[来源：第5章.pdf - 第2页]，"
         "[来源：第5章.pdf - 第999页]。"],
    )

    events = await _collect(pipeline, _request())

    assert isinstance(events[0], CitationEvent)
    assert [citation.parent_chunk_id for citation in events[0].citations] == ["pc_1", "pc_2"]
    assert events[0].citations[0].similarity_score == 0.9
    assert events[0].citations[1].page_start == 5
    tokens = "".join(event.delta for event in events if isinstance(event, TokenEvent))
    assert tokens == "根据课件，2PL 分两阶段[来源：第5章.pdf - 第2页]，。"
    done = events[-1]
    assert isinstance(done, DoneEvent)
    assert done.is_rejected is False
    assert done.stripped_citation_count == 1
    assert done.retrieval_cost_ms >= 0
    assert done.llm_first_token_ms >= 0
    assert retriever.requests[0].valid_doc_ids == ("50001",)
    assert client.calls[0]["parent_chunk_ids"] == ("pc_1", "pc_2")
    payload = factory.gateways[0].payloads[0]
    assert payload["stream"] is True
    assert "课件片段" in payload["messages"][-1]["content"]
    assert payload["messages"][1]["role"] == "user"
    assert payload["messages"][2]["role"] == "assistant"
    assert len(payload["messages"]) == 4


@pytest.mark.asyncio
async def test_leading_whitespace_is_preserved_and_citation_precedes_first_token() -> None:
    pipeline, _, _, _ = _pipeline(
        _retrieval(("pc_1", "50001", 0.9)), _authoritative(_chunk()),
        ["  ", "根据课件[来源：第5章.pdf - 第1页]。"])

    events = await _collect(pipeline, _request())

    assert [type(event) for event in events] == [CitationEvent, TokenEvent, DoneEvent]
    assert events[1].delta == "  根据课件[来源：第5章.pdf - 第1页]。"
    assert events[-1].is_rejected is False


@pytest.mark.asyncio
async def test_retrieval_rejection_does_not_call_parent_or_emit_citation() -> None:
    pipeline, _, client, factory = _pipeline(
        _retrieval(rejected=True), (), ["通用知识回答"])

    events = await _collect(pipeline, _request())

    assert isinstance(events[0], RejectionNoticeEvent)
    assert "数据库系统概念" in events[0].notice_text
    assert "两阶段锁协议" in events[0].notice_text
    assert events[0].has_general_supplement is True
    assert not any(isinstance(event, CitationEvent) for event in events)
    tokens = [event for event in events if isinstance(event, TokenEvent)]
    assert tokens[0].delta == GENERAL_SUPPLEMENT_PREFIX
    assert all(token.is_general_supplement for token in tokens)
    assert tokens[1].delta == "通用知识回答"
    assert events[-1].is_rejected is True
    assert client.calls == []
    assert len(factory.gateways) == 1


@pytest.mark.asyncio
async def test_empty_authoritative_parents_fall_back_to_rejection() -> None:
    pipeline, _, client, factory = _pipeline(
        _retrieval(("pc_1", "50001", 0.9)), (), ["补充"])

    events = await _collect(pipeline, _request())

    assert client.calls[0]["parent_chunk_ids"] == ("pc_1",)
    assert isinstance(events[0], RejectionNoticeEvent)
    assert events[-1].is_rejected is True
    assert factory.gateways[0].payloads[0]["messages"][1]["content"] == "两阶段锁协议是什么？"


@pytest.mark.asyncio
async def test_llm_sentinel_triggers_rejection_without_citation() -> None:
    pipeline, _, _, factory = _pipeline(
        _retrieval(("pc_1", "50001", 0.9)), _authoritative(_chunk()),
        ["NO_", "ANSWER_IN_COURSE"], ["通用补充"])

    events = await _collect(pipeline, _request())

    assert not any(isinstance(event, CitationEvent) for event in events)
    assert isinstance(events[0], RejectionNoticeEvent)
    assert events[-1].is_rejected is True
    assert len(factory.gateways) == 2
    course_messages = factory.gateways[0].payloads[0]["messages"]
    assert NO_ANSWER_SENTINEL in course_messages[0]["content"]


@pytest.mark.asyncio
async def test_empty_llm_answer_is_not_reported_as_success() -> None:
    pipeline, _, _, factory = _pipeline(
        _retrieval(("pc_1", "50001", 0.9)), _authoritative(_chunk()),
        [], ["补充"])

    events = await _collect(pipeline, _request())

    assert isinstance(events[0], RejectionNoticeEvent)
    assert not any(isinstance(event, CitationEvent) for event in events)
    assert events[-1].is_rejected is True
    assert len(factory.gateways) == 2


@pytest.mark.asyncio
async def test_supplement_citations_are_stripped() -> None:
    pipeline, _, _, _ = _pipeline(
        _retrieval(rejected=True), (),
        ["补充[来源：第5章.pdf - 第14页]说明"])

    events = await _collect(pipeline, _request())

    tokens = "".join(event.delta for event in events if isinstance(event, TokenEvent))
    assert tokens == GENERAL_SUPPLEMENT_PREFIX + "补充说明"
    assert events[-1].stripped_citation_count == 1


@pytest.mark.asyncio
async def test_general_supplement_can_be_disabled() -> None:
    pipeline, _, _, factory = _pipeline(
        _retrieval(rejected=True), (), general_supplement=False)

    events = await _collect(pipeline, _request())

    assert isinstance(events[0], RejectionNoticeEvent)
    assert events[0].has_general_supplement is False
    assert events[-1].is_rejected is True
    assert factory.gateways == []


@pytest.mark.asyncio
async def test_parent_fetch_failure_propagates_e_rag_03() -> None:
    error = ParentChunkFetchFailedError()
    retriever = FakeRetriever(_retrieval(("pc_1", "50001", 0.9)))
    pipeline = RagQaPipeline(
        load_settings({}), retriever=retriever,
        parent_client=FakeParentClient(error=error),
        gateway_factory=FakeGatewayFactory([]))

    with pytest.raises(ParentChunkFetchFailedError) as raised:
        await _collect(pipeline, _request())

    assert raised.value.error_code is AiErrorCode.E_RAG_03


@pytest.mark.asyncio
async def test_blank_course_name_is_rejected_before_retrieval() -> None:
    retriever = FakeRetriever(_retrieval(rejected=True))
    pipeline = RagQaPipeline(load_settings({}), retriever=retriever,
                             parent_client=FakeParentClient(),
                             gateway_factory=FakeGatewayFactory())

    with pytest.raises(NonRetryableAiError) as error:
        await _collect(pipeline, _request(course_name="  "))

    assert error.value.error_code.value == "VALIDATION_ERROR"
    assert retriever.requests == []
