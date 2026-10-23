"""文档解析（TASK-502）：文字版 PDF / MD / TXT，只读共享卷。

规则（`02` §5.2.1、`04` §4.4.2）：
- 单 PDF > 300 页：拒绝解析，错误码 `E-RAG-02`；
- PDF 全文平均每页清洗后字符密度 < 20：视为扫描件，错误码 `E-RAG-01`
  （允许文字版 PDF 含少量扉页/空白页）；
- MD/TXT 必须 UTF-8 无损解码；空文本/损坏文件归类为 `VALIDATION_ERROR`；
- PDF 在独立子进程逐页解析，单页超过 5s 直接终止并视为需重试的瞬态超时。
"""

from __future__ import annotations

import multiprocessing
import re
from pathlib import Path
from queue import Empty as QueueEmpty
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
MAX_EXTRACTED_CHARS = 2_000_000
_CHILD_MEMORY_LIMIT_BYTES = 512 * 1024 * 1024

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
              reader_factory: Callable[..., object] | None = None,
              page_timeout: float = PAGE_PARSE_TIMEOUT_SECONDS,
              worker_target: Callable[..., None] | None = None
              ) -> list[PageText]:
    """解析 PDF。

    真实文件在**独立子进程**中解析：单页超过 `page_timeout` 时直接终止子进程，
    而不是等 `extract_text()` 返回后再比较耗时——否则损坏/恶意 PDF 可以无限阻塞消费者。
    注入 `reader_factory` 的单元测试路径保持同步执行，只为验证提取与分类逻辑。
    """
    if reader_factory is not None:
        return _parse_pdf_inline(path, max_pages, reader_factory)
    return _parse_pdf_isolated(str(path), max_pages, page_timeout,
                               worker_target or _pdf_worker)


def _parse_pdf_inline(path: Path, max_pages: int,
                      factory: Callable[..., object]) -> list[PageText]:
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
        try:
            text = clean_text(page.extract_text() or "")
        except Exception as error:
            raise CorruptedDocumentError() from error
        result.append(PageText(page_number=index, text=text))
    return _validated_pages(result)


def _parse_pdf_isolated(path: str, max_pages: int, page_timeout: float,
                        worker_target: Callable[..., None]) -> list[PageText]:
    # 生产使用 spawn，避免从 asyncio 工作线程 fork 出多线程进程；测试注入 target 时用
    # fork 便于直接引用测试函数（单线程测试环境）。
    method = "fork" if worker_target is not _pdf_worker else "spawn"
    context = multiprocessing.get_context(method)
    progress: multiprocessing.Queue = context.Queue()
    process = context.Process(target=worker_target,
                              args=(path, max_pages, progress), daemon=True)
    process.start()
    pages: list[PageText] = []
    total_chars = 0
    try:
        while True:
            try:
                item = progress.get(timeout=page_timeout)
            except QueueEmpty:
                if not process.is_alive() and pages:
                    raise CorruptedDocumentError("PDF 解析进程异常退出。") from None
                raise RetryableAiError(
                    AiErrorCode.INTERNAL_ERROR,
                    f"PDF 单页解析超过 {page_timeout:.0f} 秒，已终止。") from None
            kind = item[0]
            if kind == "done":
                break
            if kind == "error":
                raise _worker_error(item[1], item[2])
            page_number, text = item[1], item[2]
            total_chars += len(text)
            if total_chars > MAX_EXTRACTED_CHARS:
                raise NonRetryableAiError(
                    AiErrorCode.VALIDATION_ERROR, "PDF 提取文本量超出安全上限。")
            pages.append(PageText(page_number=page_number, text=text))
    finally:
        if process.is_alive():
            process.terminate()
        process.join(timeout=2.0)
        progress.close()
        progress.join_thread()
    return _validated_pages(pages)


def _pdf_worker(path: str, max_pages: int, progress) -> None:  # noqa: ANN001
    """子进程入口：逐页上报文本，父进程据此执行硬超时。"""
    try:
        import resource

        resource.setrlimit(resource.RLIMIT_AS,
                           (_CHILD_MEMORY_LIMIT_BYTES, _CHILD_MEMORY_LIMIT_BYTES))
    except Exception:  # 非 Linux 或受限环境不阻断解析
        pass
    try:
        from pypdf import PdfReader

        reader = PdfReader(path, strict=False)
        page_count = len(reader.pages)
        if page_count <= 0:
            progress.put(("error", "EMPTY", "PDF 没有可解析的页面。"))
            return
        if page_count > max_pages:
            progress.put(("error", "OVER_LIMIT",
                          f"PDF 共 {page_count} 页，超过单文档 {max_pages} 页上限。"))
            return
        for index, page in enumerate(reader.pages, start=1):
            text = clean_text(page.extract_text() or "")
            progress.put(("page", index, text))
        progress.put(("done",))
    except Exception as error:  # 子进程内异常统一回传给父进程分类
        progress.put(("error", "CORRUPTED", str(error)))


def _worker_error(kind: str, message: str) -> Exception:
    if kind == "EMPTY":
        return EmptyDocumentError(message)
    if kind == "OVER_LIMIT":
        return IngestRetryExhaustedError(message)
    return CorruptedDocumentError()


def _validated_pages(pages: list[PageText]) -> list[PageText]:
    if not pages:
        raise EmptyDocumentError("PDF 没有可解析的页面。")
    total = sum(len(page.text) for page in pages)
    if total == 0:
        # 有页面但完全没有文字层：按扫描件处理（OCR 在 v1.1 开放），而不是“空文件”
        raise ScanPdfOcrNotEnabledError()
    # 按全文平均字符密度判定扫描件：允许文字版 PDF 含少量扉页/空白页
    if total / len(pages) < MIN_CHARS_PER_PAGE:
        raise ScanPdfOcrNotEnabledError()
    return pages


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
