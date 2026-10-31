"""`eval_quality.py` 的契约测试：真实数据集门禁与指标计算（不连接真实供应商）。"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

from tests import eval_quality
from tests.eval_quality import (
    RealEvaluationUnavailable,
    citation_metrics,
    evaluate,
    parse_citations,
    parse_judge,
    validate_real_dataset,
)


def _real_dataset(questions: int = 30) -> dict:
    categories = ["answerable"] * (questions - 6) + [
        "unanswerable_in_corpus", "unanswerable_in_corpus",
        "low_relevance", "low_relevance",
        "cross_course", "cross_course"]
    items = []
    for index, category in enumerate(categories, start=1):
        positive = category == "answerable"
        items.append({
            "id": f"Q{index:02d}",
            "course_key": "db",
            "category": category,
            "query": f"问题 {index}",
            "reference": ({"doc_name": "第5章_事务处理.pdf", "page_start": 1,
                           "page_end": 1} if positive else None),
            "key_points": ["采分点"] if positive else [],
            "expected": None if positive else "rejection",
            "annotation": {
                "annotator_id": "a", "annotated_at": "2026-10-31T10:00:00+08:00",
                "reviewed_by": "b", "review_status": "APPROVED",
                "arbitration_note": None,
            },
        })
    return {
        "version": "2.0", "dataset_kind": "real-annotated",
        "annotation": {"annotated_at": "2026-10-31T10:00:00+08:00",
                       "reviewed_by": "b", "review_status": "APPROVED"},
        "courses": [
            {"key": "db", "course_name": "数据库", "documents": [
                {"key": "db1", "doc_name": "第5章_事务处理.pdf",
                 "pdf_path": "eval/corpus/db1.pdf", "sha256": "a" * 64},
                {"key": "db2", "doc_name": "第6章_索引.pdf",
                 "pdf_path": "eval/corpus/db2.pdf", "sha256": "b" * 64},
            ]},
            {"key": "os", "course_name": "操作系统", "documents": [
                {"key": "os1", "doc_name": "第3章_调度.pdf",
                 "pdf_path": "eval/corpus/os1.pdf", "sha256": "c" * 64},
            ]},
        ],
        "questions": items,
    }


def test_synthetic_dataset_is_rejected() -> None:
    dataset = _real_dataset()
    dataset["dataset_kind"] = "synthetic-link-smoke"
    errors = validate_real_dataset(dataset, corpus_root=Path("/"))
    assert errors and "real-annotated" in errors[0]


def test_template_is_rejected() -> None:
    template = json.loads((Path(__file__).parent / "fixtures"
                           / "rag_golden_dataset.real.template.json").read_text(encoding="utf-8"))
    errors = validate_real_dataset(template, corpus_root=Path("/"), check_files=False)
    assert errors


def test_complete_real_dataset_passes_structure_validation() -> None:
    dataset = _real_dataset()
    errors = validate_real_dataset(dataset, corpus_root=Path("/"), check_files=False)
    assert errors == []


def test_citation_metrics_detect_fabrication_and_precision() -> None:
    answer = ("结论 A [来源：第5章_事务处理.pdf - 第2页] "
              "结论 B [来源：伪造课件.pdf - 第99页]")
    metrics = citation_metrics(
        answer, allowed={("第5章_事务处理.pdf", 2)},
        reference_pages={("第5章_事务处理.pdf", 2)})
    assert metrics["total"] == 2
    assert metrics["fabricated"] == [("伪造课件.pdf", 99)]
    assert metrics["zero_hallucination"] is False
    assert metrics["precision"] == 0.5


def test_parse_citations_accepts_chinese_brackets() -> None:
    assert parse_citations("【来源：第6章_索引.pdf - 第12页】") == [
        ("第6章_索引.pdf", 12)]


def test_judge_requires_exact_fields_and_booleans() -> None:
    assert parse_judge('{"key_points_covered":[],"faithful":true,"pass":true,'
                       '"reason":"ok"}') is not None
    assert parse_judge('{"faithful":true,"pass":true}') is None
    assert parse_judge('{"key_points_covered":[],"faithful":"yes","pass":true,'
                       '"reason":"ok"}') is None
    assert parse_judge("not json") is None


@pytest.mark.parametrize("error", [
    FileNotFoundError("git"),
    OSError("git unavailable"),
    subprocess.SubprocessError("git failed"),
])
def test_git_helpers_return_unknown_when_git_is_unavailable(monkeypatch, error) -> None:
    """测试镜像没有 git：评测不得因环境信息采集失败而中断。"""

    def boom(*args, **kwargs):  # noqa: ANN002, ANN003
        raise error

    monkeypatch.setattr(subprocess, "run", boom)
    assert eval_quality._git_commit() == "UNKNOWN"
    assert eval_quality._git_worktree() == "UNKNOWN"


class _FakeProvider:
    _embedding_model = "fake-embedding"
    _chat_model = "fake-chat"
    _judge_model = "fake-judge"

    def __init__(self) -> None:
        self.answer_prompts: list[str] = []

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [[1.0, 0.0] for _ in texts]

    async def chat(self, prompt: str, *, model=None, temperature=0.0,
                   max_tokens=1600) -> str:
        if prompt.startswith("你是严格的评测员"):
            return ('{"key_points_covered":["采分点"],"faithful":true,'
                    '"pass":true,"reason":"ok"}')
        self.answer_prompts.append(prompt)
        return "回答 [来源：第5章_事务处理.pdf - 第1页]"


@pytest.mark.asyncio
async def test_evaluate_computes_real_quality_metrics() -> None:
    dataset = {
        "version": "2.0", "dataset_kind": "real-annotated",
        "courses": [{"key": "db", "course_name": "数据库", "documents": [
            {"key": "db1", "doc_name": "第5章_事务处理.pdf",
             "pdf_path": "eval/corpus/db1.pdf", "sha256": "a" * 64}]}],
        "questions": [
            {"id": "Q1", "course_key": "db", "category": "answerable",
             "query": "两阶段锁？",
             "reference": {"doc_name": "第5章_事务处理.pdf",
                            "page_start": 1, "page_end": 1},
             "key_points": ["采分点"],
             "annotation": {"annotator_id": "a", "annotated_at": "x",
                            "reviewed_by": "b", "review_status": "APPROVED"}},
            {"id": "N1", "course_key": "db", "category": "unanswerable_in_corpus",
             "query": "课外问题？", "reference": None, "key_points": [],
             "expected": "rejection",
             "annotation": {"annotator_id": "a", "annotated_at": "x",
                            "reviewed_by": "b", "review_status": "APPROVED"}},
        ],
    }
    pages = [SimpleNamespace(page_number=1, text="两阶段锁要求持有排他锁直到提交。")]

    summary = await evaluate(
        dataset, _FakeProvider(), threshold=0.35,
        parse_pages=lambda path, ext: pages)

    assert summary["positives"]["total"] == 1
    assert summary["positives"]["hit_at_4_rate"] == 1.0
    assert summary["citation"]["fabricated"] == 0
    assert summary["citation"]["precision"] == 1.0
    assert summary["negatives"]["rejection_accuracy"] == 1.0
    assert summary["positives"]["faithfulness_rate"] == 1.0
