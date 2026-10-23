"""流式引用真实性校验（TASK-603，`01` FR-RAG-002 / `04` §4.5.1）。

模型输出中的 `[来源：{doc_name} - 第{page_num}页]` 必须严格落在当次 Java 权威回查
返回的父块页码闭区间内。校验器采用**前瞻缓冲区**，解决标签被拆到多个流式片段的问题：

* 遇到 `[` 开始缓冲，最多 80 字符；
* 一旦能确定不是引用标签（例如 `[1]`、`[注]`），立即原样放行；
* 命中引用标签格式时校验 `(doc_name, page_num)`；越界或未召回的标签整段剔除，
  并递增 ``stripped_count``；
* 缓冲区超长仍未闭合时按普通文本放行，避免吞掉正文。

本模块只处理文本，不感知 SSE、HTTP 或模型客户端。
"""

from __future__ import annotations

import re
from collections.abc import Iterable

from app.services.rag.parent_chunk_client import AuthoritativeParentChunk

_CITATION_PREFIX = "[来源："
_CITATION_PATTERN = re.compile(r"^\[来源：(?P<doc_name>.+) - 第(?P<page>\d+)页\]$")
_MAX_BUFFER = 80


class CitationVerifier:
    def __init__(self, parents: Iterable[AuthoritativeParentChunk]) -> None:
        ranges: dict[str, list[tuple[int, int]]] = {}
        for parent in parents:
            ranges.setdefault(parent.doc_name, []).append(
                (parent.page_start, parent.page_end))
        self._ranges = ranges
        self._buffer = ""
        self._stripped_count = 0

    @property
    def stripped_count(self) -> int:
        return self._stripped_count

    def feed(self, delta: str) -> str:
        """喂入一个流式片段，返回已确认可安全下发的文本（可能为空）。"""
        if not delta:
            return ""
        output: list[str] = []
        for char in delta:
            self._buffer += char
            if not self._buffer.startswith("["):
                output.append(self._buffer)
                self._buffer = ""
                continue
            if self._is_possible_citation(self._buffer):
                if self._buffer.endswith("]"):
                    output.append(self._resolve(self._buffer))
                    self._buffer = ""
                elif len(self._buffer) >= _MAX_BUFFER:
                    output.append(self._buffer)
                    self._buffer = ""
                continue
            output.append(self._buffer)
            self._buffer = ""
        return "".join(output)

    def flush(self) -> str:
        """流结束时下发剩余缓冲（未闭合的普通文本或残缺标签）。"""
        remaining = self._buffer
        self._buffer = ""
        return remaining

    @staticmethod
    def _is_possible_citation(text: str) -> bool:
        if text == "[":
            return True
        if text.startswith(_CITATION_PREFIX):
            return True
        return _CITATION_PREFIX.startswith(text)

    def _resolve(self, tag: str) -> str:
        match = _CITATION_PATTERN.fullmatch(tag)
        if match is None:
            return tag
        doc_name = match.group("doc_name")
        page = int(match.group("page"))
        if any(start <= page <= end for start, end in self._ranges.get(doc_name, ())):
            return tag
        self._stripped_count += 1
        return ""
