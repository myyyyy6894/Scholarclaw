"""父子分块（TASK-502、`02` §5.2.4）。

- 父块 800~1200 字，主键 `pc_{doc_id}_v{parse_version}_{seq}`，跨页时记录真实页码范围；
- 子块 200~350 字，相邻子块保留 40 字重叠，供 ChromaDB 检索与引用校验；
- 不跨页生成子块，避免引用页码失真；超长段落按句子/硬切分处理。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.services.ingest.models import ChildChunk, PageText, ParentChunk, ParseResult
from app.services.ingest.parser import iter_paragraphs

PARENT_MIN_CHARS = 800
PARENT_MAX_CHARS = 1200
CHILD_MIN_CHARS = 200
CHILD_MAX_CHARS = 350
CHILD_OVERLAP_CHARS = 40
CHILD_TARGET_CHARS = CHILD_MAX_CHARS - CHILD_OVERLAP_CHARS
_SENTENCE_BOUNDARY = re.compile(r"(?<=[。！？!?；;\.])\s*")
_TITLE_MAX = 80
_TITLE_ENDINGS = tuple("。！？!?；;，,.")


@dataclass(frozen=True)
class _Block:
    page: int
    text: str


def chunk_document(pages: list[PageText], *, doc_id: str, parse_version: int) -> ParseResult:
    blocks = _split_blocks(pages)
    if not blocks:
        return ParseResult(page_count=len(pages))

    parents: list[ParentChunk] = []
    children: list[ChildChunk] = []
    parent_seq = 0
    child_seq = 0
    buffer: list[_Block] = []
    buffer_length = 0

    def flush() -> None:
        nonlocal parent_seq, child_seq, buffer, buffer_length
        if not buffer:
            return
        parent_seq += 1
        parent_id = f"pc_{doc_id}_v{parse_version}_{parent_seq}"
        content = "\n\n".join(block.text for block in buffer)
        parents.append(ParentChunk(
            parent_chunk_id=parent_id,
            chunk_seq=parent_seq,
            page_start=buffer[0].page,
            page_end=buffer[-1].page,
            section_title=_section_title(buffer[0].text),
            content=content,
            char_length=len(content),
        ))
        pending: list[tuple[int, str]] = []
        for block in buffer:
            pending.extend((block.page, piece) for piece in _split_child_text(block.text))
        # 同一页内相邻子块保留 40 字重叠；跨页不重叠，避免子块页码失真
        for index in range(1, len(pending)):
            page, text = pending[index]
            previous_page, previous_text = pending[index - 1]
            if page == previous_page:
                pending[index] = (page, previous_text[-CHILD_OVERLAP_CHARS:] + text)
        for page, text in pending:
            child_seq += 1
            children.append(ChildChunk(
                parent_chunk_id=parent_id,
                chunk_seq=child_seq,
                page_num=page,
                content=text,
            ))
        buffer = []
        buffer_length = 0

    for block in blocks:
        projected = buffer_length + (2 if buffer else 0) + len(block.text)
        if buffer and projected > PARENT_MAX_CHARS:
            flush()
        buffer.append(block)
        buffer_length = len(block.text) if len(buffer) == 1 else buffer_length + 2 + len(block.text)
        if buffer_length >= PARENT_MIN_CHARS:
            flush()
    flush()

    # 父块边界处同样补重叠（同一页内），保证任意相邻子块共享 40 字上下文
    for index in range(1, len(children)):
        current = children[index]
        previous = children[index - 1]
        if (current.page_num == previous.page_num
                and current.parent_chunk_id != previous.parent_chunk_id):
            children[index] = ChildChunk(
                parent_chunk_id=current.parent_chunk_id,
                chunk_seq=current.chunk_seq,
                page_num=current.page_num,
                content=previous.content[-CHILD_OVERLAP_CHARS:] + current.content,
            )

    return ParseResult(page_count=len(pages), parents=parents, children=children)


def _split_blocks(pages: list[PageText]) -> list[_Block]:
    blocks: list[_Block] = []
    for page, paragraph in iter_paragraphs(pages):
        for piece in _hard_split(paragraph, PARENT_MAX_CHARS):
            blocks.append(_Block(page=page, text=piece))
    return blocks


def _split_child_text(text: str) -> list[str]:
    pieces: list[str] = []
    current = ""
    for sentence in _SENTENCE_BOUNDARY.split(text):
        if not sentence:
            continue
        for part in _hard_split(sentence, CHILD_TARGET_CHARS):
            candidate = f"{current} {part}".strip() if current else part
            if current and len(candidate) > CHILD_TARGET_CHARS:
                pieces.append(current)
                current = part
            else:
                current = candidate
            if len(current) >= CHILD_MIN_CHARS:
                pieces.append(current)
                current = ""
    if current:
        pieces.append(current)
    return pieces


def _hard_split(text: str, limit: int) -> list[str]:
    if len(text) <= limit:
        return [text]
    return [text[start:start + limit] for start in range(0, len(text), limit)]


def _section_title(text: str) -> str | None:
    first_line = text.strip().splitlines()[0].strip() if text.strip() else ""
    if not first_line or len(first_line) > _TITLE_MAX:
        return None
    if first_line.endswith(_TITLE_ENDINGS):
        return None
    return first_line
