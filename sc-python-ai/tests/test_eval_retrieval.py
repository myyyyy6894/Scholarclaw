"""Golden Dataset 与离线评测脚本的契约测试（TASK-606）。"""

from __future__ import annotations

import asyncio

import pytest

from tests.eval_retrieval import (
    DEFAULT_DATASET,
    HashEmbedder,
    build_corpora,
    covers,
    load_dataset,
    run_evaluation,
    validate_dataset,
)


def _dataset():
    return load_dataset(DEFAULT_DATASET)


def test_golden_dataset_is_valid_and_balanced() -> None:
    dataset = _dataset()

    assert validate_dataset(dataset) == []
    assert len(dataset["questions"]) == 40
    assert len(dataset["courses"]) == 2
    negatives = [q for q in dataset["questions"] if q["reference"] is None]
    assert len(negatives) == 8
    assert {q["category"] for q in negatives} == {
        "no_answer", "low_relevance", "cross_course"}


def test_corpus_builds_one_parent_per_page() -> None:
    corpora = build_corpora(_dataset())

    assert set(corpora) == {"db", "os"}
    assert len(corpora["db"].parents) == 6
    assert len(corpora["os"].parents) == 6
    parent_ids = [parent.parent_chunk_id for parent in corpora["db"].parents]
    assert len(parent_ids) == len(set(parent_ids))


def test_hash_embedder_reflects_lexical_overlap() -> None:
    embedder = HashEmbedder()

    def cosine(left: str, right: str) -> float:
        a = embedder.embed(left)
        b = embedder.embed(right)
        return sum(x * y for x, y in zip(a, b))

    relevant = cosine("两阶段锁协议扩展阶段和收缩阶段", "两阶段锁协议把加锁和解锁分为两个阶段")
    unrelated = cosine("两阶段锁协议扩展阶段和收缩阶段", "TLB 是页表的高速缓存")

    assert relevant > unrelated


def test_covers_requires_doc_and_page_range() -> None:
    span = build_corpora(_dataset())["db"].parents[0]
    reference = {"doc_name": "第5章_事务处理.md", "page_start": 1, "page_end": 1}

    assert covers(span, reference)
    assert not covers(span, {**reference, "page_start": 2, "page_end": 2})
    assert not covers(span, {**reference, "doc_name": "第4章_内存管理.md"})


def test_harness_reports_contract_metrics() -> None:
    dataset = _dataset()

    summary = asyncio.run(run_evaluation(
        dataset, threshold=0.35, top_k=10, top_parents=4))

    assert summary["positives"]["total"] == 32
    assert summary["negatives"]["total"] == 8
    assert summary["synthetic_isolation"]["rate"] == 1.0
    assert summary["citation_verifier_contract"]["contract_ok"] is True
    assert summary["citation_verifier_contract"]["invalid_stripped"] == \
        summary["citation_verifier_contract"]["invalid_injected"]
    assert summary["positives"]["hit_raw_rate"] >= summary["positives"]["hit_usable_rate"]
    assert "NOT a real provider baseline" in summary["mode"]


@pytest.mark.parametrize("field", ["positives", "negatives", "latency_ms", "failures", "environment"])
def test_harness_summary_has_required_sections(field: str) -> None:
    dataset = _dataset()
    summary = asyncio.run(run_evaluation(
        dataset, threshold=0.35, top_k=10, top_parents=4))

    assert field in summary
