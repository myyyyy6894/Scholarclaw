from app.services.ingest.chunker import chunk_document
from app.services.ingest.models import PageText


def test_parent_and_child_chunks_keep_ids_and_pages() -> None:
    long_page = "事务是访问并更新数据项的程序执行单元。" * 60
    pages = [PageText(1, long_page), PageText(2, "第二章 并发控制\n\n锁与隔离级别。" * 40)]

    result = chunk_document(pages, doc_id="50001", parse_version=2)

    assert result.page_count == 2
    assert result.parents
    assert result.children
    assert result.parents[0].parent_chunk_id == "pc_50001_v2_1"
    assert [parent.chunk_seq for parent in result.parents] == list(
        range(1, len(result.parents) + 1))
    assert [child.chunk_seq for child in result.children] == list(
        range(1, len(result.children) + 1))
    assert all(child.parent_chunk_id.startswith("pc_50001_v2_") for child in result.children)
    assert {child.page_num for child in result.children} <= {1, 2}
    assert all(parent.page_start <= parent.page_end for parent in result.parents)
    assert all(1 <= len(child.content) <= 350 for child in result.children)
    assert result.chunk_count == len(result.children)


def test_paragraphs_do_not_cross_page_for_children() -> None:
    pages = [PageText(1, "第一页内容。" * 50), PageText(2, "第二页内容。" * 50)]

    result = chunk_document(pages, doc_id="50001", parse_version=1)

    for child in result.children:
        assert child.page_num in {1, 2}
    parent_pages = {(parent.page_start, parent.page_end) for parent in result.parents}
    assert (1, 1) in parent_pages or (1, 2) in parent_pages


def test_storage_path_strips_raw_prefix(tmp_path) -> None:
    from app.services.ingest.pipeline import resolve_storage_path

    path = resolve_storage_path(str(tmp_path / "raw"), "raw/10001/2001/50001.pdf")
    assert path == (tmp_path / "raw/10001/2001/50001.pdf").resolve()
