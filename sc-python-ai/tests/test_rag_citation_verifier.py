from app.services.rag.citation_verifier import CitationVerifier
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk


def _parent(doc_name="第5章.pdf", start=14, end=16, doc_id="50001"):
    return AuthoritativeParentChunk("pc_1", doc_id, doc_name, start, end, "5.2", "内容")


def _verifier(*parents):
    return CitationVerifier(parents or (_parent(),))


def test_valid_citation_is_kept_and_counted() -> None:
    verifier = _verifier()

    output = verifier.feed("结论[来源：第5章.pdf - 第14页]。")

    assert output == "结论[来源：第5章.pdf - 第14页]。"
    assert verifier.valid_count == 1
    assert verifier.failed is False


def test_full_width_colon_hyphen_and_spaces_are_accepted() -> None:
    verifier = _verifier()

    output = verifier.feed("结论[ 来源 ： 第5章.pdf — 第 15 页 ]")

    assert output == "结论[ 来源 ： 第5章.pdf — 第 15 页 ]"
    assert verifier.valid_count == 1


def test_out_of_range_citation_fails_and_is_not_emitted() -> None:
    verifier = _verifier()

    output = verifier.feed("结论[来源：第5章.pdf - 第999页]")

    assert output == "结论"
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_unknown_doc_citation_fails() -> None:
    verifier = _verifier()

    output = verifier.feed("模型编造的内容。[来源：不存在.pdf - 第999页]")

    assert output == "模型编造的内容。"
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_citation_split_across_deltas_is_validated_as_one_tag() -> None:
    verifier = _verifier()

    assert verifier.feed("结论[来源：第5章.pdf") == "结论"
    assert verifier.feed(" - 第999页]") == ""
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_overlong_possible_citation_is_not_emitted() -> None:
    verifier = _verifier()
    text = "[来源：" + "很长的文件名" * 120

    output = verifier.feed("前缀" + text)

    assert output == "前缀"
    assert verifier.overlong_count == 1
    assert verifier.failed is True


def test_unclosed_citation_at_flush_is_malformed() -> None:
    verifier = _verifier()

    assert verifier.feed("回答[来源：第5章.pdf") == "回答"
    assert verifier.flush() == ""
    assert verifier.malformed_count == 1
    assert verifier.failed is True


def test_malformed_source_tag_fails() -> None:
    verifier = _verifier()

    output = verifier.feed("回答[来源：第5章.pdf]")

    assert output == "回答"
    assert verifier.malformed_count == 1
    assert verifier.failed is True


def test_non_citation_brackets_pass_through() -> None:
    verifier = _verifier()

    assert verifier.feed("[1] [注] [来]") == "[1] [注] [来]"
    assert verifier.failed is False


def test_page_boundaries_are_inclusive() -> None:
    verifier = _verifier(_parent(start=14, end=16))

    first = verifier.feed("[来源：第5章.pdf - 第14页]")
    second = verifier.feed("[来源：第5章.pdf - 第16页]")

    assert first == "[来源：第5章.pdf - 第14页]"
    assert second == "[来源：第5章.pdf - 第16页]"
    assert verifier.valid_count == 2


def test_same_doc_multiple_page_ranges_are_valid() -> None:
    verifier = CitationVerifier([
        _parent(start=1, end=2, doc_id="50001"),
        _parent(start=8, end=9, doc_id="50001"),
    ])

    output = verifier.feed("[来源：第5章.pdf - 第2页]"
                           "[来源：第5章.pdf - 第8页]")

    assert output == "[来源：第5章.pdf - 第2页][来源：第5章.pdf - 第8页]"
    assert verifier.valid_count == 2


def test_duplicate_doc_name_with_ambiguous_page_fails() -> None:
    verifier = CitationVerifier([
        _parent(doc_name="同名.pdf", start=1, end=5, doc_id="50001"),
        _parent(doc_name="同名.pdf", start=3, end=8, doc_id="50002"),
    ])

    output = verifier.feed("结论[来源：同名.pdf - 第4页]")

    assert output == "结论"
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_duplicate_doc_name_with_unique_page_is_valid() -> None:
    verifier = CitationVerifier([
        _parent(doc_name="同名.pdf", start=1, end=2, doc_id="50001"),
        _parent(doc_name="同名.pdf", start=8, end=9, doc_id="50002"),
    ])

    output = verifier.feed("结论[来源：同名.pdf - 第2页]")

    assert output == "结论[来源：同名.pdf - 第2页]"
    assert verifier.valid_count == 1


def test_valid_citation_plus_missing_colon_tag_fails() -> None:
    verifier = _verifier()

    output = verifier.feed(
        "真实结论[来源：第5章.pdf - 第14页]；伪来源[来源不存在.pdf - 第999页]")

    assert "[来源不存在.pdf - 第999页]" not in output
    assert verifier.valid_count == 1
    assert verifier.malformed_count == 1
    assert verifier.failed is True


def test_valid_citation_plus_chinese_bracket_tag_fails() -> None:
    verifier = _verifier()

    output = verifier.feed(
        "真实结论[来源：第5章.pdf - 第14页]；伪来源【来源：不存在.pdf - 第999页】")

    assert "【来源：不存在.pdf - 第999页】" not in output
    assert verifier.valid_count == 1
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_chinese_bracket_missing_colon_tag_is_malformed() -> None:
    verifier = _verifier()

    output = verifier.feed("真实结论[来源：第5章.pdf - 第14页]【来源不存在.pdf - 第999页】")

    assert "【来源不存在.pdf - 第999页】" not in output
    assert verifier.valid_count == 1
    assert verifier.malformed_count == 1
    assert verifier.failed is True


def test_valid_citation_plus_out_of_range_tag_fails() -> None:
    verifier = _verifier()

    output = verifier.feed(
        "真实结论[来源：第5章.pdf - 第14页]又一段[来源：第5章.pdf - 第999页]")

    assert "[来源：第5章.pdf - 第999页]" not in output
    assert verifier.valid_count == 1
    assert verifier.invalid_count == 1
    assert verifier.failed is True


def test_valid_citation_plus_overlong_tag_fails() -> None:
    verifier = _verifier()
    overlong = "[来源：" + "不存在" * 200

    output = verifier.feed(
        "真实结论[来源：第5章.pdf - 第14页]" + overlong)

    assert overlong not in output
    assert verifier.valid_count == 1
    assert verifier.overlong_count == 1
    assert verifier.failed is True


def test_source_word_outside_brackets_is_not_blocked() -> None:
    verifier = _verifier()

    output = verifier.feed("本次回答的来源是第5章，来源页码见引用。")

    assert output == "本次回答的来源是第5章，来源页码见引用。"
    assert verifier.stripped_count == 0
    assert verifier.failed is False


def test_chinese_bracket_citation_is_accepted() -> None:
    verifier = _verifier()

    output = verifier.feed("结论【来源：第5章.pdf - 第14页】")

    assert output == "结论【来源：第5章.pdf - 第14页】"
    assert verifier.valid_count == 1
    assert verifier.failed is False


def test_long_but_valid_255_char_filename_is_accepted() -> None:
    doc_name = "长" * 251 + ".pdf"
    assert len(doc_name) == 255
    verifier = CitationVerifier([_parent(doc_name=doc_name)])

    tag = f"[来源：{doc_name} - 第14页]"
    output = verifier.feed(tag)

    assert output == tag
    assert verifier.valid_count == 1
    assert verifier.stripped_count == 0
