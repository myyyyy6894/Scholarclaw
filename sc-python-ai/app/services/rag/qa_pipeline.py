"""RAG 生成管线（TASK-603，`01` FR-RAG-002 / `02` §5.2.2 / `04` §4.5.1）。

职责：

1. 调用 :class:`DenseVectorRetriever` 完成课程范围内的稠密检索；
2. 仅在未触发阈值拒答时，调用 :class:`ParentChunkClient` 回查 MySQL 权威父块；
3. 用权威父块组装防幻觉 Prompt，调用受控模型网关做流式生成；
4. 用 :class:`CitationVerifier` 对 `[来源：doc - 第X页]` 做前瞻缓冲校验，实时剔除
   虚构文件名或越界页码；
5. 召回为空、低于阈值、权威父块为空或模型明确判定“课件无答案”时，走无资料拒答分支；
   按 `PO-04` 默认附加显式标注的通用知识补充。

本模块只产出内部事件对象，不处理 SSE/HTTP；事件到 `citation/token/rejection_notice/
done/error` 帧的映射与 `user_message_id` 补全属 TASK-604。
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from time import perf_counter
from typing import Any

from app.core.config import Settings
from app.core.exceptions import (
    AiErrorCode,
    CitationVerificationFailedError,
    NonRetryableAiError,
)
from app.core.logging import get_logger
from app.services.gateway.model_endpoint import ModelEndpoint
from app.services.gateway.ssrf_safe_model_gateway import RetryPolicy, SsrfSafeModelGateway
from app.services.rag.citation_verifier import CitationVerifier
from app.services.rag.dense_retriever import DenseVectorRetriever
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk, ParentChunkClient
from app.services.rag.retriever_base import RetrievalRequest, RetrievalResult

logger = get_logger(__name__)

#: 模型明确表示“召回父块均不含答案”的固定哨兵；不得出现在最终回答中。
NO_ANSWER_SENTINEL = "NO_ANSWER_IN_COURSE"
GENERAL_SUPPLEMENT_PREFIX = (
    "💡 以下为基于通用专业知识的补充参考（非本校课件原文，请注意甄别）：\n"
)
#: `PO-04` 默认方案：拒答后附带显式标注的通用知识补充。
DEFAULT_GENERAL_SUPPLEMENT = True
_MAX_HISTORY_TURNS = 10

_COURSE_SYSTEM_PROMPT = (
    "你是 ScholarClaw 课程助教。你只能依据用户消息中给出的【课件片段】回答问题，"
    "不得使用课件片段之外的知识，也不得猜测。"
    "每个事实性结论后必须标注来源，格式严格为：[来源：{文件名} - 第{页码}页]。"
    "文件名必须与片段中的文件名完全一致，页码必须落在该片段给出的页码范围闭区间内；"
    "严禁编造文件名、页码或片段中不存在的内容。"
    f"如果所有课件片段都无法回答该问题，只输出 {NO_ANSWER_SENTINEL}，不要输出任何其他内容。"
    "回答使用简体中文，条理清晰，不要输出与问题无关的客套话。"
)

_SUPPLEMENT_SYSTEM_PROMPT = (
    "你是学习助手。当前课程已就绪的课件中没有与用户问题直接相关的内容。"
    "请基于通用专业知识给出参考解答，并明确这是非课件原文的补充。"
    "严禁标注任何课件文件名或页码，严禁声称回答来自课程课件。"
    "回答使用简体中文，条理清晰。"
)


@dataclass(frozen=True)
class ChatTurn:
    role: str
    content: str


@dataclass(frozen=True)
class RagQaRequest:
    user_id: str
    course_id: str
    course_name: str
    course_index_version: int
    valid_doc_ids: tuple[str, ...]
    query: str
    embedding_endpoint: ModelEndpoint
    embedding_dim: int | None
    chat_endpoint: ModelEndpoint
    trace_id: str
    chat_history: tuple[ChatTurn, ...] = ()


@dataclass(frozen=True)
class CitationItem:
    parent_chunk_id: str
    doc_id: str
    doc_name: str
    page_start: int
    page_end: int
    section_title: str | None
    content: str
    similarity_score: float


@dataclass(frozen=True)
class CitationEvent:
    citations: tuple[CitationItem, ...]


@dataclass(frozen=True)
class RejectionNoticeEvent:
    notice_text: str
    has_general_supplement: bool


@dataclass(frozen=True)
class TokenEvent:
    delta: str
    is_general_supplement: bool


@dataclass(frozen=True)
class DoneEvent:
    is_rejected: bool
    retrieval_cost_ms: int
    llm_first_token_ms: int
    stripped_citation_count: int


RagQaEvent = CitationEvent | RejectionNoticeEvent | TokenEvent | DoneEvent
GatewayFactory = Callable[[], Any]


class RagQaPipeline:
    def __init__(self, settings: Settings, *,
                 retriever: DenseVectorRetriever | None = None,
                 parent_client: ParentChunkClient | None = None,
                 gateway_factory: GatewayFactory | None = None,
                 general_supplement: bool = DEFAULT_GENERAL_SUPPLEMENT) -> None:
        self._settings = settings
        self._retriever = retriever or DenseVectorRetriever(settings)
        self._parent_client = parent_client or ParentChunkClient(settings)
        self._gateway_factory = gateway_factory or (
            lambda: SsrfSafeModelGateway(settings))
        self._general_supplement = general_supplement

    async def stream(self, request: RagQaRequest) -> AsyncIterator[RagQaEvent]:
        _validate_request(request)
        started = perf_counter()
        retrieval = await self._retriever.retrieve(RetrievalRequest(
            user_id=request.user_id,
            course_id=request.course_id,
            course_index_version=request.course_index_version,
            valid_doc_ids=request.valid_doc_ids,
            query=request.query,
            embedding_endpoint=request.embedding_endpoint,
            embedding_dim=request.embedding_dim,
        ))
        if retrieval.is_rejected:
            async for event in self._rejection_stream(request, _elapsed_ms(started)):
                yield event
            return

        parents = await self._parent_client.fetch(
            user_id=request.user_id,
            course_id=request.course_id,
            expected_course_index_version=request.course_index_version,
            parent_chunk_ids=retrieval.parent_chunk_ids,
            trace_id=request.trace_id,
        )
        if not parents:
            async for event in self._rejection_stream(request, _elapsed_ms(started)):
                yield event
            return

        retrieval_cost_ms = _elapsed_ms(started)
        citations = _build_citations(retrieval, parents)
        verifier = CitationVerifier(parents)
        llm_started = perf_counter()
        first_token_ms: int | None = None
        decided = False
        rejected_by_llm = False
        citation_sent = False
        emitted_any = False
        sentinel_buffer = ""
        pending_safe = ""

        async with self._gateway_factory() as gateway:
            async for delta in gateway.stream_chat(
                    request.chat_endpoint, _course_payload(request, parents)):
                if first_token_ms is None:
                    first_token_ms = _elapsed_ms(llm_started)
                if not decided:
                    sentinel_buffer += delta
                    if _is_no_answer(sentinel_buffer):
                        rejected_by_llm = True
                        break
                    if _could_be_no_answer_prefix(sentinel_buffer):
                        continue
                    decided = True
                    pending_safe += verifier.feed(sentinel_buffer)
                    if verifier.failed:
                        raise _citation_failure(verifier, parents)
                    if pending_safe.strip():
                        if not citation_sent:
                            citation_sent = True
                            yield CitationEvent(citations)
                        emitted_any = True
                        yield TokenEvent(pending_safe, False)
                        pending_safe = ""
                    continue
                pending_safe += verifier.feed(delta)
                if verifier.failed:
                    raise _citation_failure(verifier, parents)
                if pending_safe.strip():
                    if not citation_sent:
                        citation_sent = True
                        yield CitationEvent(citations)
                    emitted_any = True
                    yield TokenEvent(pending_safe, False)
                    pending_safe = ""

        if not rejected_by_llm and not decided:
            if _is_no_answer(sentinel_buffer):
                rejected_by_llm = True
            else:
                pending_safe += verifier.feed(sentinel_buffer)
                if verifier.failed:
                    raise _citation_failure(verifier, parents)
                pending_safe += verifier.flush()
                if verifier.failed:
                    raise _citation_failure(verifier, parents)
        elif not rejected_by_llm:
            pending_safe += verifier.flush()
            if verifier.failed:
                raise _citation_failure(verifier, parents)

        if not rejected_by_llm:
            if pending_safe.strip():
                if not citation_sent:
                    citation_sent = True
                    yield CitationEvent(citations)
                emitted_any = True
                yield TokenEvent(pending_safe, False)
                pending_safe = ""
            elif emitted_any and pending_safe:
                yield TokenEvent(pending_safe, False)
                pending_safe = ""
            if not emitted_any:
                rejected_by_llm = True
            elif verifier.valid_count == 0:
                # 有正文但没有任何可验证的课件引用：不得当作成功回答
                raise _citation_failure(verifier, parents, no_valid=True)

        if rejected_by_llm:
            async for event in self._rejection_stream(request, retrieval_cost_ms):
                yield event
            return

        yield DoneEvent(False, retrieval_cost_ms, first_token_ms or 0,
                        verifier.stripped_count)

    async def _rejection_stream(self, request: RagQaRequest,
                                retrieval_cost_ms: int) -> AsyncIterator[RagQaEvent]:
        has_supplement = self._general_supplement
        yield RejectionNoticeEvent(_rejection_notice(request), has_supplement)
        if not has_supplement:
            yield DoneEvent(True, retrieval_cost_ms, 0, 0)
            return

        yield TokenEvent(GENERAL_SUPPLEMENT_PREFIX, True)
        verifier = CitationVerifier(())
        llm_started = perf_counter()
        first_token_ms: int | None = None
        async with self._gateway_factory() as gateway:
            async for delta in gateway.stream_chat(
                    request.chat_endpoint, _supplement_payload(request)):
                if first_token_ms is None:
                    first_token_ms = _elapsed_ms(llm_started)
                safe = verifier.feed(delta)
                if safe:
                    yield TokenEvent(safe, True)
        tail = verifier.flush()
        if tail:
            yield TokenEvent(tail, True)
        if verifier.failed:
            logger.warning("通用补充中出现非法引用，已剔除 valid=%s invalid=%s overlong=%s malformed=%s",
                           verifier.valid_count, verifier.invalid_count,
                           verifier.overlong_count, verifier.malformed_count)
        yield DoneEvent(True, retrieval_cost_ms, first_token_ms or 0,
                        verifier.stripped_count)


def _citation_failure(verifier: CitationVerifier,
                      parents: tuple[AuthoritativeParentChunk, ...],
                      *, no_valid: bool = False) -> CitationVerificationFailedError:
    slices = "、".join(
        f"《{parent.doc_name}》第{parent.page_start}-{parent.page_end}页"
        for parent in parents
    ) or "无"
    reason = "未检测到有效引用" if no_valid else "存在非法引用"
    message = (
        f"引用校验未通过（{reason}；有效引用 {verifier.valid_count}，"
        f"非法引用 {verifier.invalid_count}，超长标签 {verifier.overlong_count}，"
        f"畸形标签 {verifier.malformed_count}）；本次实际召回切片为：{slices}。"
        "请重试或换一种问法。"
    )
    logger.warning("引用校验失败 valid=%s invalid=%s overlong=%s malformed=%s",
                   verifier.valid_count, verifier.invalid_count,
                   verifier.overlong_count, verifier.malformed_count)
    return CitationVerificationFailedError(message)


def _validate_request(request: RagQaRequest) -> None:
    if not request.course_name or not request.course_name.strip():
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "课程名称不能为空。")
    endpoint = request.chat_endpoint
    if not endpoint.provider or not endpoint.base_url or not endpoint.model_name:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "Chat 模型配置不完整。")


def _build_citations(retrieval: RetrievalResult,
                     parents: tuple[AuthoritativeParentChunk, ...]) -> tuple[CitationItem, ...]:
    scores = {parent.parent_chunk_id: parent.similarity for parent in retrieval.parents}
    return tuple(CitationItem(
        parent_chunk_id=parent.parent_chunk_id,
        doc_id=parent.doc_id,
        doc_name=parent.doc_name,
        page_start=parent.page_start,
        page_end=parent.page_end,
        section_title=parent.section_title,
        content=parent.content,
        similarity_score=scores.get(parent.parent_chunk_id, 0.0),
    ) for parent in parents)


def _rejection_notice(request: RagQaRequest) -> str:
    return (f"📌 检索说明：当前【{request.course_name}】课程已就绪的课件中，"
            f"未找到与“{request.query}”直接相关的内容（因此不标注课件页码）。")


def _course_payload(request: RagQaRequest,
                    parents: tuple[AuthoritativeParentChunk, ...]) -> dict[str, Any]:
    context = "\n\n".join(
        f"[片段{index}] 文件名：{parent.doc_name} "
        f"页码：{parent.page_start}-{parent.page_end}\n{parent.content}"
        for index, parent in enumerate(parents, start=1)
    )
    messages: list[dict[str, str]] = [
        {"role": "system", "content": _COURSE_SYSTEM_PROMPT}]
    for turn in request.chat_history[-_MAX_HISTORY_TURNS:]:
        role = _history_role(turn.role)
        content = turn.content.strip()
        if role and content:
            messages.append({"role": role, "content": content})
    messages.append({"role": "user",
                     "content": f"课件片段：\n\n{context}\n\n用户问题：{request.query}"})
    return _chat_payload(request.chat_endpoint, messages)


def _supplement_payload(request: RagQaRequest) -> dict[str, Any]:
    messages = [
        {"role": "system", "content": _SUPPLEMENT_SYSTEM_PROMPT},
        {"role": "user", "content": request.query},
    ]
    return _chat_payload(request.chat_endpoint, messages)


def _chat_payload(endpoint: ModelEndpoint,
                  messages: list[dict[str, str]]) -> dict[str, Any]:
    return {
        "model": endpoint.model_name,
        "messages": messages,
        "stream": True,
        "temperature": 0.2,
    }


def _history_role(raw: str) -> str | None:
    normalized = (raw or "").strip().upper()
    if normalized == "USER":
        return "user"
    if normalized == "ASSISTANT":
        return "assistant"
    return None


def _normalized_prefix(text: str) -> str:
    return text.lstrip().lstrip("\"'*` \t\r\n")


def _is_no_answer(text: str) -> bool:
    normalized = _normalized_prefix(text)
    if not normalized:
        return False
    return normalized.startswith(NO_ANSWER_SENTINEL)


def _could_be_no_answer_prefix(text: str) -> bool:
    normalized = _normalized_prefix(text)
    if not normalized:
        return True
    if normalized.startswith(NO_ANSWER_SENTINEL):
        return False
    return NO_ANSWER_SENTINEL.startswith(normalized)


def _elapsed_ms(started: float) -> int:
    return max(0, int((perf_counter() - started) * 1000))
