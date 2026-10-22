"""文档解析（TASK-502）：文字版 PDF / MD / TXT，只读共享卷。

规则（`02` §5.2.1、`04` §4.4.2）：
- 单 PDF > 300 页：拒绝解析，错误码 `E-RAG-02`；
- PDF 任一页清洗后字符密度 < 20：视为扫描件，错误码 `E-RAG-01`；
- MD/TXT 必须 UTF-8 无损解码；空文本/损坏文件归类为 `VALIDATION_ERROR`；
- 单页解析耗时 > 5s 视为需重试的瞬态超时。
"""

from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Callable, Iterable

from app.core.exceptions import (
    AiErrorCode,
    IngestRetryExhaustedError,
    NonRetryableAiError,
    RetryableAiError,
    ScanPdfOcrNotEnabledError,
)
from app.services.ingest.models import PageText

MAX_PAGES_DEFAULT = 300
MIN_CHARS_PER_PAGE = 20
PAGE_PARSE_TIMEOUT_SECONDS = 5.0

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_MULTI_BLANK = re.compile(r"\n{3,}")
_INLINE_SPACE = re.compile(r"[ \t　]+")


class CorruptedDocumentError(NonRetryableAiError):
    def __init__(self, message: str = "文件内容损坏或编码非法，无法解析。") -> None:
        super().__init__(AiErrorCode.VALIDATION_ERROR, message)


class EmptyDocumentError(NonRetryableAiError):
    def __init__(self, message: str = "文档未提取到可解析文本。") -> None:
        super().__init__(AiErrorCode.VALIDATION_ERROR, message)


def clean_text(raw: str) -> str:
    """统一换行、移除控制字符、压缩空白；不做语义改写。"""
    if not raw:
        return ""
    normalized = raw.replace("\r\n", "\n").replace("\r", "\n").replace("﻿", "")
    normalized = normalized.replace("\x00", "")
    normalized = _CONTROL_CHARS.sub("", normalized)
    lines = [_INLINE_SPACE.sub(" ", line).strip() for line in normalized.split("\n")]
    text = "\n".join(lines)
    return _MULTI_BLANK.sub("\n\n", text).strip()


def parse_document(path: Path, extension: str, *, max_pages: int = MAX_PAGES_DEFAULT,
                   pdf_reader_factory: Callable[..., object] | None = None) -> list[PageText]:
    normalized_ext = extension.lower()
    if normalized_ext == ".pdf":
        return parse_pdf(path, max_pages=max_pages, reader_factory=pdf_reader_factory)
    if normalized_ext in {".md", ".txt"}:
        return parse_plain_text(path)
    raise CorruptedDocumentError("仅支持 .pdf、.md、.txt 文件。")


def parse_pdf(path: Path, *, max_pages: int = MAX_PAGES_DEFAULT,
              reader_factory: Callable[..., object] | None = None) -> list[PageText]:
    factory = reader_factory or _default_pdf_reader
    try:
        reader = factory(str(path))
        pages = reader.pages
        page_count = len(pages)
    except Exception as error:  # pypdf 的异常族较宽，统一归类为损坏文件
        raise CorruptedDocumentError() from error

    if page_count <= 0:
        raise EmptyDocumentError("PDF 没有可解析的页面。")
    if page_count > max_pages:
        raise IngestRetryExhaustedError(
            f"PDF 共 {page_count} 页，超过单文档 {max_pages} 页上限。")

    result: list[PageText] = []
    for index, page in enumerate(pages, start=1):
        started = time.monotonic()
        try:
            text = clean_text(page.extract_text() or "")
        except Exception as error:
            raise CorruptedDocumentError() from error
        if time.monotonic() - started > PAGE_PARSE_TIMEOUT_SECONDS:
            raise RetryableAiError(AiErrorCode.INTERNAL_ERROR, "单页解析超时，请稍后重试。")
        if len(text) < MIN_CHARS_PER_PAGE:
            raise ScanPdfOcrNotEnabledError()
        result.append(PageText(page_number=index, text=text))
    return result


def parse_plain_text(path: Path) -> list[PageText]:
    try:
        raw = path.read_bytes()
    except FileNotFoundError as error:
        raise NonRetryableAiError(AiErrorCode.RESOURCE_NOT_FOUND, "源文件不存在。") from error
    except OSError as error:
        raise CorruptedDocumentError() from error
    try:
        decoded = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise CorruptedDocumentError("文本文件不是合法 UTF-8 编码。") from error
    text = clean_text(decoded)
    if not text:
        raise EmptyDocumentError()
    return [PageText(page_number=1, text=text)]


def iter_paragraphs(pages: Iterable[PageText]) -> Iterable[tuple[int, str]]:
    """按空行切段并保留页码；超长段落原样返回，由分块器继续切分。"""
    for page in pages:
        for block in re.split(r"\n\s*\n", page.text):
            paragraph = block.strip("\n").strip()
            if paragraph:
                yield page.page_number, paragraph


def _default_pdf_reader(path: str) -> object:
    from pypdf import PdfReader

    return PdfReader(path, strict=False)
