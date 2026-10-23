from app.services.rag.citation_verifier import CitationVerifier
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk


def _parent(doc_name="第5章.pdf", start=14, end=16):
    return AuthoritativeParentChunk("pc_1", "50001", doc_name, start, end, "5.2", "内容")


def _verifier(*parents):
    return CitationVerifier(parents or (_parent(),))


def test_valid_citation_is_kept() -> None:
    verifier = _verifier()

    output = verifier.feed("结论[来源：第5章.pdf - 第14页]。")

    assert output == "结论[来源：第5章.pdf - 第14页]。"
    assert verifier.stripped_count == 0


def test_out_of_range_and_unknown_doc_citations_are_stripped() -> None:
    verifier = _verifier()

    output = verifier.feed(
        "甲[来源：第5章.pdf - 第999页]乙[来源：不存在.pdf - 第14页]丙"
        "[来源：第5章.pdf - 第16页]")

    assert output == "甲乙丙[来源：第5章.pdf - 第16页]"
    assert verifier.stripped_count == 2


def test_citation_split_across_deltas_is_validated_as_one_tag() -> None:
    verifier = _verifier()

    assert verifier.feed("结论[来源：第5章.pdf") == "结论"
    assert verifier.feed(" - 第999页]") == ""
    assert verifier.feed("。") == "。"
    assert verifier.stripped_count == 1


def test_non_citation_brackets_pass_through_immediately() -> None:
    verifier = _verifier()

    assert verifier.feed("[1] [注] [链接](x)") == "[1] [注] [链接](x)"
    assert verifier.stripped_count == 0


def test_incomplete_long_buffer_is_flushed_as_plain_text() -> None:
    verifier = _verifier()
    text = "[来源：" + "很长的文件名" * 20

    output = verifier.feed(text)

    assert output == text
    assert verifier.flush() == ""


def test_flush_returns_unclosed_buffer() -> None:
    verifier = _verifier()

    assert verifier.feed("回答[来源：第5章.pdf") == "回答"
    assert verifier.flush() == "[来源：第5章.pdf"


def test_page_boundaries_are_inclusive() -> None:
    verifier = _verifier(_parent(start=14, end=16))

    output = verifier.feed(
        "[来源：第5章.pdf - 第14页][来源：第5章.pdf - 第16页][来源：第5章.pdf - 第13页]")

    assert output == "[来源：第5章.pdf - 第14页][来源：第5章.pdf - 第16页]"
    assert verifier.stripped_count == 1


def test_same_doc_multiple_page_ranges_are_all_valid() -> None:
    verifier = CitationVerifier([
        _parent(start=1, end=2),
        _parent(start=8, end=9),
    ])

    output = verifier.feed("[来源：第5章.pdf - 第2页][来源：第5章.pdf - 第8页]"
                           "[来源：第5章.pdf - 第5页]")

    assert output == "[来源：第5章.pdf - 第2页][来源：第5章.pdf - 第8页]"
    assert verifier.stripped_count == 1


def test_empty_scope_strips_every_well_formed_citation() -> None:
    verifier = CitationVerifier([])

    output = verifier.feed("补充[来源：第5章.pdf - 第14页]")

    assert output == "补充"
    assert verifier.stripped_count == 1
