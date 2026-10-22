from pathlib import Path

import pytest

from app.core.exceptions import IngestRetryExhaustedError, ScanPdfOcrNotEnabledError
from app.services.ingest.parser import clean_text, parse_document, parse_plain_text


class _Page:
    def __init__(self, text: str) -> None:
        self._text = text

    def extract_text(self) -> str:
        return self._text


class _Reader:
    def __init__(self, pages) -> None:
        self.pages = pages


def _factory(texts):
    def factory(_path: str) -> _Reader:
        return _Reader([_Page(text) for text in texts])
    return factory


def test_pdf_extracts_pages_and_cleans_text(tmp_path: Path) -> None:
    pdf = tmp_path / "a.pdf"
    pdf.write_bytes(b"%PDF-1.7")
    pages = parse_document(pdf, ".pdf", pdf_reader_factory=_factory([
        "第一章  事务处理\n\n事务是访问并更新数据项的程序执行单元。",
        "第二章 并发控制\n\n锁、事务隔离级别与多版本并发控制的基本概念。",
    ]))

    assert [page.page_number for page in pages] == [1, 2]
    assert "事务是访问并更新数据项" in pages[0].text


def test_pdf_over_max_pages_is_rejected(tmp_path: Path) -> None:
    pdf = tmp_path / "big.pdf"
    pdf.write_bytes(b"%PDF-1.7")

    with pytest.raises(IngestRetryExhaustedError):
        parse_document(pdf, ".pdf", max_pages=300,
                       pdf_reader_factory=_factory(["a" * 40] * 301))


def test_scanned_pdf_page_is_rejected(tmp_path: Path) -> None:
    pdf = tmp_path / "scan.pdf"
    pdf.write_bytes(b"%PDF-1.7")

    with pytest.raises(ScanPdfOcrNotEnabledError):
        parse_document(pdf, ".pdf", pdf_reader_factory=_factory(["short"]))


def test_markdown_and_text_are_single_page(tmp_path: Path) -> None:
    markdown = tmp_path / "notes.md"
    markdown.write_text("# 标题\n\n内容。" * 5, encoding="utf-8")

    pages = parse_plain_text(markdown)

    assert len(pages) == 1
    assert pages[0].page_number == 1
    assert "标题" in pages[0].text


def test_invalid_utf8_is_rejected(tmp_path: Path) -> None:
    text = tmp_path / "bad.txt"
    text.write_bytes(b"\xff\xfe\x00")

    with pytest.raises(Exception) as error:
        parse_plain_text(text)
    assert "编码" in str(error.value) or "文本" in str(error.value)


def test_clean_text_removes_control_chars_and_compresses_blank_lines() -> None:
    assert clean_text("a\x00b\r\n\r\n\r\nc  d") == "ab\n\nc d"
