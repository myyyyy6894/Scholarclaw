"""流式引用真实性校验（TASK-603 / TASK-606 复核修复，`01` FR-RAG-002 / `04` §4.5.1）。

硬性规则：

1. 只允许 `[来源：{doc_name} - 第{page}页]`（含全角方括号 `【…】`）且
   `(doc_name, page)` 必须命中当次权威父块；兼容全角/半角冒号、常见连字符与前后空格。
2. 只要方括号或中文书名括号中的内容以“来源”开头，就必须缓冲到闭合符号再判定；
   不得因为缺少冒号等格式差异而当作普通正文提前放行。
3. 非法页码、未召回文件名、同一文件名对应多个文档且无法唯一归属、格式不规范、
   超过 512 字符仍未闭合、流结束时仍未闭合 → 一律校验失败。
4. 任何非法/超长/畸形/未闭合来源标签都会置 ``failed``，即使同一回答中已有合法引用，
   调用方也必须中止回答，不得当作成功落库。
5. 只有格式完整且唯一归属的引用才计入 ``valid_count``。

本模块只处理文本，不感知 SSE、HTTP 或模型客户端。
"""

from __future__ import annotations

import re
from collections.abc import Iterable

from app.services.rag.parent_chunk_client import AuthoritativeParentChunk

_CITATION_PREFIX = "来源"
_OPEN_TO_CLOSE = {"[": "]", "【": "】"}
_CLOSE_BRACKETS = frozenset(_OPEN_TO_CLOSE.values())
#: 覆盖 `t_knowledge_doc.doc_name` 的 255 字符上限 + 标签固定开销。
_MAX_BUFFER = 512
_CITATION_PATTERN = re.compile(
    r"^\[\s*来源\s*[:：]\s*(?P<doc_name>.+?)\s*[-－—–‑]\s*第\s*(?P<page>\d+)\s*页\s*\]$"
)


class CitationVerifier:
    def __init__(self, parents: Iterable[AuthoritativeParentChunk]) -> None:
        self._entries = [
            (parent.doc_id, parent.doc_name.strip(),
             parent.page_start, parent.page_end)
            for parent in parents
        ]
        self._buffer = ""
        self._discard_until_close = False
        self._valid_count = 0
        self._invalid_count = 0
        self._overlong_count = 0
        self._malformed_count = 0

    @property
    def valid_count(self) -> int:
        return self._valid_count

    @property
    def invalid_count(self) -> int:
        return self._invalid_count

    @property
    def overlong_count(self) -> int:
        return self._overlong_count

    @property
    def malformed_count(self) -> int:
        return self._malformed_count

    @property
    def stripped_count(self) -> int:
        return self._invalid_count + self._overlong_count + self._malformed_count

    @property
    def failed(self) -> bool:
        return self.stripped_count > 0

    def feed(self, delta: str) -> str:
        """喂入一个流式片段，返回已确认可安全下发的文本（可能为空）。

        发现非法/超长/畸形引用时置 ``failed``；正常回答流程必须中止，但安全文本
        （非法标签之前/之后的正文）仍会返回，供拒答补充段等非成功路径使用。
        """
        if not delta:
            return ""
        output: list[str] = []
        for char in delta:
            if self._discard_until_close:
                if char in _CLOSE_BRACKETS:
                    self._discard_until_close = False
                continue
            if self._buffer == "":
                if char in _OPEN_TO_CLOSE:
                    self._buffer = char
                else:
                    output.append(char)
                continue
            self._buffer += char
            if not self._is_possible_citation(self._buffer):
                # 已确认不是来源标签（如 `[注]`），按普通正文下发
                output.append(self._buffer)
                self._buffer = ""
                continue
            if char in _CLOSE_BRACKETS:
                resolved = self._resolve(self._buffer)
                self._buffer = ""
                if resolved is not None:
                    output.append(resolved)
            elif len(self._buffer) >= _MAX_BUFFER:
                # 超过缓冲区仍无法闭合：不得原样下发，并丢弃该标签剩余部分
                self._overlong_count += 1
                self._buffer = ""
                self._discard_until_close = True
        return "".join(output)

    def flush(self) -> str:
        """流结束时处理剩余缓冲；未闭合的可能引用按畸形失败。"""
        remaining = self._buffer
        self._buffer = ""
        if not remaining:
            return ""
        if self._looks_like_citation(remaining):
            self._malformed_count += 1
            return ""
        return remaining

    @staticmethod
    def _is_possible_citation(text: str) -> bool:
        """缓冲区是否仍可能是来源标签；只要以“来源”开头就必须继续缓冲。"""
        body = text[1:].lstrip()
        if body == "":
            return True
        return _CITATION_PREFIX.startswith(body) or body.startswith(_CITATION_PREFIX)

    @staticmethod
    def _looks_like_citation(text: str) -> bool:
        return text[1:].lstrip().startswith(_CITATION_PREFIX)

    def _resolve(self, tag: str) -> str | None:
        normalized = tag.replace("【", "[").replace("】", "]")
        match = _CITATION_PATTERN.fullmatch(normalized)
        if match is None:
            if self._looks_like_citation(tag):
                self._malformed_count += 1
                return None
            # 普通方括号文本（如 [注]）不是引用标签
            return tag
        doc_name = match.group("doc_name").strip()
        page = int(match.group("page"))
        matches = [
            doc_id
            for doc_id, name, start, end in self._entries
            if name == doc_name and start <= page <= end
        ]
        if not matches:
            self._invalid_count += 1
            return None
        if len(set(matches)) > 1:
            # 同名文件对应多个文档，无法唯一归属
            self._invalid_count += 1
            return None
        self._valid_count += 1
        return tag
