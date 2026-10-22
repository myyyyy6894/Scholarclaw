"""TASK-502 文档解析领域模型（无数据库、无 MQ 依赖，便于单测）。"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class PageText:
    page_number: int
    text: str


@dataclass(frozen=True)
class ParentChunk:
    parent_chunk_id: str
    chunk_seq: int
    page_start: int
    page_end: int
    section_title: str | None
    content: str
    char_length: int


@dataclass(frozen=True)
class ChildChunk:
    parent_chunk_id: str
    chunk_seq: int
    page_num: int
    content: str


@dataclass(frozen=True)
class ParseResult:
    page_count: int
    parents: list[ParentChunk] = field(default_factory=list)
    children: list[ChildChunk] = field(default_factory=list)

    @property
    def chunk_count(self) -> int:
        return len(self.children)


@dataclass(frozen=True)
class ParseContext:
    """Java `/parse-context` 返回的权威任务上下文。"""

    allow_proceed: bool
    abort_reason: str | None
    user_id: str | None
    course_id: str | None
    doc_id: str
    doc_name: str | None
    file_ext: str | None
    storage_rel_path: str | None
    parse_version: int
    course_index_version: int
    embedding_provider: str | None
    embedding_base_url: str | None
    embedding_api_key: str | None
    embedding_model: str | None
    embedding_dim: int | None
