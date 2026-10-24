#!/usr/bin/env python3
"""RAG Golden Dataset 离线评测（TASK-606）。

评测范围（MVP 单路稠密检索）：

* Hit Rate@4：Top-4 父块是否覆盖标注的 `(doc_name, page)`；
* 负样本拒答：阈值门控下是否返回空父块（LLM 判定需真实模型，本脚本不评分）；
* 引用校验：把“有效 + 越界页码 + 未知文件”三类合成标签喂给 `CitationVerifier`，
  验证非法标签被 100% 剔除；
* 跨课程隔离：返回父块不得属于其他课程；
* 检索耗时：确定性替身 Embedding + 本地 Chroma 的链路耗时。

**重要限制（不得省略）：** 本环境没有真实 Embedding / Chat 供应商 Key。脚本使用
**确定性字符 n-gram 哈希向量**作为检索替身，数字只反映“代码链路 + 该替身”的基线，
**不代表真实供应商的检索质量**。答案采分点覆盖、答案忠实度和 LLM 拒答判断均未评测，
因此本报告不能作为生产质量达标结论；接入真实模型后必须重新运行并替换本报告。

用法（在 `sc-python-ai` 目录下）：
    .venv/bin/python tests/eval_retrieval.py
    .venv/bin/python tests/eval_retrieval.py --threshold 0.35
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
import math
import platform
import statistics
import subprocess
import sys
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from time import perf_counter
from typing import Any

import chromadb
from chromadb.config import Settings as ChromaSettings

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.config import load_settings  # noqa: E402
from app.services.gateway.model_endpoint import ModelEndpoint  # noqa: E402
from app.services.ingest.models import ChildChunk, ParseContext, ParentChunk  # noqa: E402
from app.services.ingest.vector_store import ChromaVectorStore  # noqa: E402
from app.services.rag.citation_verifier import CitationVerifier  # noqa: E402
from app.services.rag.dense_retriever import DenseVectorRetriever  # noqa: E402
from app.services.rag.parent_chunk_client import AuthoritativeParentChunk  # noqa: E402
from app.services.rag.retriever_base import RetrievalConfig, RetrievalRequest  # noqa: E402

DEFAULT_DATASET = ROOT / "tests" / "fixtures" / "rag_golden_dataset.json"
DEFAULT_REPORT_DIR = ROOT / "tests" / "eval_reports"
EMBEDDING_DIM = 256
HASH_MODEL = "deterministic-hash-n-gram-v1"
NEGATIVE_CATEGORIES = {"no_answer", "low_relevance", "cross_course"}
POSITIVE_CATEGORIES = {"answerable"}

# Chroma 在 top_k 大于集合规模时会打印提示，不影响评测结果
logging.getLogger("chromadb").setLevel(logging.CRITICAL)


@dataclass(frozen=True)
class ParentSpan:
    parent_chunk_id: str
    doc_name: str
    page: int
    content: str


@dataclass(frozen=True)
class CourseCorpus:
    key: str
    user_id: str
    course_id: str
    course_name: str
    course_index_version: int
    doc_ids: tuple[str, ...]
    doc_names: frozenset[str]
    parents: tuple[ParentSpan, ...]


class HashEmbedder:
    """确定性字符 n-gram 哈希向量（仅用于离线链路评测，不是真实 Embedding 模型）。"""

    def __init__(self, dim: int = EMBEDDING_DIM) -> None:
        self.dim = dim

    def embed(self, text: str) -> list[float]:
        normalized = "".join(text.lower().split())
        vector = [0.0] * self.dim
        tokens = list(normalized) + [
            normalized[index:index + 2] for index in range(len(normalized) - 1)
        ]
        for token in tokens:
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            vector[int.from_bytes(digest, "big") % self.dim] += 1.0
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0:
            return vector
        return [value / norm for value in vector]


class HashEmbeddingService:
    """与 `EmbeddingService` 同形的最小替身，供 `DenseVectorRetriever` 注入。"""

    def __init__(self, embedder: HashEmbedder) -> None:
        self._embedder = embedder

    async def embed_query(self, endpoint: ModelEndpoint, text: str, *,
                          expected_dim: int | None = None) -> list[float]:
        vector = self._embedder.embed(text)
        if expected_dim and len(vector) != expected_dim:
            raise ValueError("评测替身维度与期望不一致")
        return vector


def load_dataset(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate_dataset(dataset: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    courses = dataset.get("courses")
    questions = dataset.get("questions")
    if not isinstance(courses, list) or not courses:
        return ["courses 必须为非空数组"]
    if not isinstance(questions, list):
        return ["questions 必须为数组"]
    if not 30 <= len(questions) <= 50:
        errors.append(f"题目数量必须在 30~50 之间，当前 {len(questions)}")

    course_map = {course.get("key"): course for course in courses}
    if len(course_map) != len(courses):
        errors.append("课程 key 必须唯一")
    if len(course_map) < 2:
        errors.append("至少需要 2 门课程")

    negative_count = 0
    negative_categories: set[str] = set()
    seen_ids: set[str] = set()
    for index, question in enumerate(questions):
        question_id = question.get("id")
        if not question_id or question_id in seen_ids:
            errors.append(f"第 {index} 题缺少唯一 id")
        seen_ids.add(question_id)
        course_key = question.get("course_key")
        course = course_map.get(course_key)
        if course is None:
            errors.append(f"{question_id}: course_key 不存在 {course_key}")
            continue
        category = question.get("category")
        if category not in POSITIVE_CATEGORIES | NEGATIVE_CATEGORIES:
            errors.append(f"{question_id}: 未知 category {category}")
        query = question.get("query")
        if not isinstance(query, str) or not query.strip():
            errors.append(f"{question_id}: query 不能为空")
        reference = question.get("reference")
        if category in POSITIVE_CATEGORIES:
            if not isinstance(reference, dict):
                errors.append(f"{question_id}: 正样本必须提供 reference")
                continue
            doc = next((item for item in course["documents"]
                        if item["doc_name"] == reference.get("doc_name")), None)
            if doc is None:
                errors.append(f"{question_id}: reference.doc_name 不在课程语料中")
                continue
            pages = {page["page"] for page in doc["pages"]}
            start = reference.get("page_start")
            end = reference.get("page_end")
            if not isinstance(start, int) or not isinstance(end, int) or start > end:
                errors.append(f"{question_id}: reference 页码范围非法")
            elif not {start, end} <= pages:
                errors.append(f"{question_id}: reference 页码不在文档中")
        else:
            negative_count += 1
            negative_categories.add(str(category))
            if reference is not None:
                errors.append(f"{question_id}: 负样本 reference 必须为 null")
            if question.get("expected") != "rejection":
                errors.append(f"{question_id}: 负样本 expected 必须为 rejection")

    ratio = negative_count / len(questions) if questions else 0
    if not 0.15 <= ratio <= 0.25:
        errors.append(f"负样本比例应在 15%~25%，当前 {ratio:.1%}")
    missing = NEGATIVE_CATEGORIES - negative_categories
    if missing:
        errors.append(f"缺少负样本类别: {', '.join(sorted(missing))}")
    return errors


def build_corpora(dataset: dict[str, Any]) -> dict[str, CourseCorpus]:
    corpora: dict[str, CourseCorpus] = {}
    for course in dataset["courses"]:
        parents: list[ParentSpan] = []
        doc_ids: list[str] = []
        doc_names: set[str] = set()
        for document in course["documents"]:
            doc_ids.append(str(document["doc_id"]))
            doc_names.add(document["doc_name"])
            for page in document["pages"]:
                parent_id = (f"pc_{document['doc_id']}_v{document['parse_version']}"
                             f"_{page['page']}")
                parents.append(ParentSpan(parent_id, document["doc_name"],
                                          int(page["page"]), page["text"]))
        corpora[course["key"]] = CourseCorpus(
            key=course["key"], user_id=str(course["user_id"]),
            course_id=str(course["course_id"]), course_name=course["course_name"],
            course_index_version=int(course["course_index_version"]),
            doc_ids=tuple(doc_ids), doc_names=frozenset(doc_names),
            parents=tuple(parents),
        )
    return corpora


def build_context(course: CourseCorpus, document: dict[str, Any]) -> ParseContext:
    return ParseContext(
        allow_proceed=True, abort_reason=None,
        user_id=course.user_id, course_id=course.course_id,
        doc_id=str(document["doc_id"]), doc_name=document["doc_name"],
        file_ext=".md", storage_rel_path=f"raw/{course.user_id}/{course.course_id}/"
                                        f"{document['doc_id']}.md",
        parse_version=int(document["parse_version"]),
        course_index_version=course.course_index_version,
        embedding_provider="OPENAI_COMPATIBLE",
        embedding_base_url="https://eval.invalid/v1",
        embedding_api_key=None, embedding_model=HASH_MODEL,
        embedding_dim=EMBEDDING_DIM,
    )


def build_parent_and_child(document: dict[str, Any], page: dict[str, Any]
                           ) -> tuple[ParentChunk, ChildChunk]:
    parent_id = f"pc_{document['doc_id']}_v{document['parse_version']}_{page['page']}"
    parent = ParentChunk(
        parent_chunk_id=parent_id, chunk_seq=int(page["page"]),
        page_start=int(page["page"]), page_end=int(page["page"]),
        section_title=None, content=page["text"], char_length=len(page["text"]),
    )
    child = ChildChunk(parent_chunk_id=parent_id, chunk_seq=1,
                       page_num=int(page["page"]), content=page["text"])
    return parent, child


def covers(span: ParentSpan, reference: dict[str, Any]) -> bool:
    return (span.doc_name == reference["doc_name"]
            and reference["page_start"] <= span.page <= reference["page_end"])


async def run_evaluation(dataset: dict[str, Any], *, threshold: float,
                         top_k: int, top_parents: int) -> dict[str, Any]:
    corpora = build_corpora(dataset)
    settings = load_settings({"SC_CHROMADB_BASE_URL": "http://eval.invalid:8000"})
    embedder = HashEmbedder()
    client = chromadb.EphemeralClient(
        settings=ChromaSettings(anonymized_telemetry=False))
    store = ChromaVectorStore(settings, client=client)

    for course in dataset["courses"]:
        corpus = corpora[course["key"]]
        for document in course["documents"]:
            context = build_context(corpus, document)
            parents: list[ParentChunk] = []
            children: list[ChildChunk] = []
            for page in document["pages"]:
                parent, child = build_parent_and_child(document, page)
                parents.append(parent)
                children.append(child)
            vectors = [embedder.embed(child.content) for child in children]
            store.replace_document(context, children, vectors)

    retriever = DenseVectorRetriever(
        settings, embedding_service=HashEmbeddingService(embedder),
        vector_store=store,
        config=RetrievalConfig(top_k=top_k, top_parents=top_parents,
                               similarity_threshold=threshold),
    )
    parent_index = {
        span.parent_chunk_id: span
        for corpus in corpora.values()
        for span in corpus.parents
    }

    positives: list[dict[str, Any]] = []
    negatives: list[dict[str, Any]] = []
    cross_course_total = 0
    cross_course_clean = 0
    verifier_checked = 0
    verifier_injected_invalid = 0
    verifier_stripped = 0
    verifier_valid_retained = 0
    latencies: list[float] = []

    for question in dataset["questions"]:
        corpus = corpora[question["course_key"]]
        request = RetrievalRequest(
            user_id=corpus.user_id, course_id=corpus.course_id,
            course_index_version=corpus.course_index_version,
            valid_doc_ids=corpus.doc_ids, query=question["query"],
            embedding_endpoint=ModelEndpoint(
                "OPENAI_COMPATIBLE", "https://eval.invalid/v1", None, HASH_MODEL),
            embedding_dim=EMBEDDING_DIM,
        )
        started = perf_counter()
        result = await retriever.retrieve(request)
        latencies.append((perf_counter() - started) * 1000)

        spans = [parent_index[parent.parent_chunk_id] for parent in result.parents
                 if parent.parent_chunk_id in parent_index]
        raw_parent_ids: list[str] = []
        for child in result.children:
            if child.parent_chunk_id not in raw_parent_ids:
                raw_parent_ids.append(child.parent_chunk_id)
        raw_spans = [parent_index[parent_id] for parent_id in raw_parent_ids[:top_parents]
                     if parent_id in parent_index]
        foreign = [span for span in spans if span.doc_name not in corpus.doc_names]
        cross_course_total += 1
        if not foreign:
            cross_course_clean += 1

        record = {
            "id": question["id"],
            "category": question["category"],
            "query": question["query"],
            "rejected": result.is_rejected,
            "top_similarity": result.top_similarity,
            "returned": [
                {"doc_name": span.doc_name, "page": span.page}
                for span in spans
            ],
            "raw_returned": [
                {"doc_name": span.doc_name, "page": span.page}
                for span in raw_spans
            ],
        }
        if question["category"] in POSITIVE_CATEGORIES:
            record["hit_raw"] = any(covers(span, question["reference"])
                                    for span in raw_spans)
            record["hit_usable"] = any(covers(span, question["reference"])
                                       for span in spans)
            record["reference"] = question["reference"]
            positives.append(record)
            if spans:
                verifier_checked += 1
                authoritative = [
                    AuthoritativeParentChunk(
                        parent_chunk_id=span.parent_chunk_id, doc_id="0",
                        doc_name=span.doc_name, page_start=span.page,
                        page_end=span.page, section_title=None, content=span.content)
                    for span in spans
                ]
                verifier = CitationVerifier(authoritative)
                valid = spans[0]
                synthetic = (
                    f"[来源：{valid.doc_name} - 第{valid.page}页]"
                    f"[来源：{valid.doc_name} - 第999页]"
                    "[来源：完全不存在的课件.pdf - 第1页]"
                )
                safe = verifier.feed(synthetic) + verifier.flush()
                verifier_injected_invalid += 2
                verifier_stripped += verifier.stripped_count
                if f"[来源：{valid.doc_name} - 第{valid.page}页]" in safe:
                    verifier_valid_retained += 1
        else:
            negatives.append(record)

    hit_raw_count = sum(1 for record in positives if record["hit_raw"])
    hit_usable_count = sum(1 for record in positives if record["hit_usable"])
    rejected_count = sum(1 for record in negatives if record["rejected"])
    p50 = statistics.median(latencies) if latencies else 0.0
    p95 = (statistics.quantiles(latencies, n=20)[18]
           if len(latencies) >= 20 else max(latencies, default=0.0))

    def category_stats(records: list[dict[str, Any]], key: str) -> dict[str, Any]:
        total = len(records)
        matched = sum(1 for record in records if record[key])
        return {"total": total, "matched": matched,
                "rate": matched / total if total else 0.0}

    return {
        "dataset_version": dataset.get("version"),
        "mode": "deterministic-hash-embedding (NOT a real provider baseline)",
        "config": {
            "embedding": HASH_MODEL, "dim": EMBEDDING_DIM,
            "threshold": threshold, "top_k": top_k, "top_parents": top_parents,
        },
        "positives": {
            "total": len(positives),
            "hit_raw": hit_raw_count,
            "hit_usable": hit_usable_count,
            "hit_raw_rate": hit_raw_count / len(positives) if positives else 0.0,
            "hit_usable_rate": hit_usable_count / len(positives) if positives else 0.0,
        },
        "negatives": {"total": len(negatives), "rejected": rejected_count,
                      "rate": rejected_count / len(negatives) if negatives else 0.0},
        "by_category": {
            category: {
                **category_stats([record for record in positives
                                  if record["category"] == category], "hit_usable"),
                "hit_raw_rate": category_stats(
                    [record for record in positives if record["category"] == category],
                    "hit_raw")["rate"],
            }
            for category in sorted({record["category"] for record in positives})
        },
        "negative_by_category": {
            category: category_stats([record for record in negatives
                                      if record["category"] == category], "rejected")
            for category in sorted({record["category"] for record in negatives})
        },
        "synthetic_isolation": {
            "total": cross_course_total, "clean": cross_course_clean,
            "rate": cross_course_clean / cross_course_total if cross_course_total else 0.0,
            "note": "仅在各自合成 Collection 内检查文档名，不是跨用户/跨课程授权测试",
        },
        "citation_verifier_contract": {
            "checked": verifier_checked,
            "invalid_injected": verifier_injected_invalid,
            "invalid_stripped": verifier_stripped,
            "valid_retained": verifier_valid_retained,
            "contract_ok": verifier_stripped == verifier_injected_invalid
                           and verifier_valid_retained == verifier_checked,
            "note": "合成标签契约检查，不代表真实模型回答的引用零虚构率",
        },
        "latency_ms": {"p50": round(p50, 2), "p95": round(p95, 2),
                       "max": round(max(latencies, default=0.0), 2)},
        "failures": {
            "positive_miss_raw": [record for record in positives if not record["hit_raw"]],
            "positive_miss_usable": [record for record in positives if not record["hit_usable"]],
            "negative_not_rejected": [record for record in negatives
                                      if not record["rejected"]],
        },
        "environment": _environment(),
        "not_measured": [
            "答案采分点覆盖与忠实度（需要真实 Chat 模型人工/盲评）",
            "LLM 判定“课件无答案”的拒答准确率（本脚本只测阈值门控）",
            "真实 Embedding 供应商的检索质量",
            "v1.1 BM25/RRF/Reranker 指标",
        ],
    }


def _git_commit() -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT.parent,
            capture_output=True, text=True, timeout=5, check=False)
        return result.stdout.strip() if result.returncode == 0 else "UNKNOWN"
    except (OSError, subprocess.SubprocessError):
        return "UNKNOWN"


def _git_worktree_state() -> str:
    """报告必须说明工作区是否包含未提交修复；未提交时 commit 不能代表被测代码。"""
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"], cwd=ROOT.parent,
            capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.SubprocessError):
        return "UNKNOWN"
    if result.returncode != 0:
        return "UNKNOWN"
    return "DIRTY_WORKTREE" if result.stdout.strip() else "CLEAN"


def _source_hashes() -> dict[str, str]:
    files = [
        "app/services/rag/citation_verifier.py",
        "app/services/rag/qa_pipeline.py",
        "app/services/rag/dense_retriever.py",
        "app/services/ingest/vector_store.py",
        "tests/eval_retrieval.py",
    ]
    return {
        relative: hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
        for relative in files if (ROOT / relative).is_file()
    }


def _environment() -> dict[str, object]:
    return {
        "python": platform.python_version(),
        "chromadb": getattr(chromadb, "__version__", "UNKNOWN"),
        "platform": platform.platform(),
        "git_commit": _git_commit(),
        "git_worktree": _git_worktree_state(),
        "source_sha256": _source_hashes(),
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "command": " ".join(sys.argv),
    }


def render_report(summary: dict[str, Any], dataset_path: Path) -> str:
    positives = summary["positives"]
    negatives = summary["negatives"]
    isolation = summary["synthetic_isolation"]
    verifier = summary["citation_verifier_contract"]
    latency = summary["latency_ms"]
    environment = summary["environment"]
    annotation = summary.get("annotation") or {}
    lines = [
        "# RAG 链路冒烟 / 替身基线报告（TASK-606，**未完成质量准出**）",
        "",
        f"- 生成时间：{environment['generated_at']}",
        f"- 数据集：`{dataset_path.name}`（version={summary['dataset_version']}，"
        f"kind={summary.get('dataset_kind')}）",
        f"- 数据集 SHA-256：`{summary.get('dataset_sha256')}`",
        f"- 标注状态：annotator_id={annotation.get('annotator_id')}，"
        f"annotated_at={annotation.get('annotated_at')}，"
        f"reviewed_by={annotation.get('reviewed_by')}，"
        f"review_status={annotation.get('review_status')}",
        f"- 运行环境：Python {environment['python']} / ChromaDB {environment['chromadb']} / "
        f"{environment['platform']}",
        f"- Git commit：`{environment['git_commit']}`",
        f"- Git 工作区：`{environment.get('git_worktree')}`"
        "（`DIRTY_WORKTREE` 表示被测代码含未提交改动，commit 不能单独代表结果）",
        f"- 运行命令：`{environment['command']}`",
        f"- 配置：threshold={summary['config']['threshold']}, "
        f"top_k={summary['config']['top_k']}, top_parents={summary['config']['top_parents']}",
        "",
        "> **结论先行：TASK-606 未完成，P6 不可验收。** 当前数据集是 12 页合成 Markdown 替身，"
        "没有真实课程 PDF、没有人工标注（annotator/reviewer 均为空），不能认定为真实人工标注的 "
        "Golden Dataset。检索使用确定性字符 n-gram 哈希向量替身，**数字不代表真实 Embedding 的"
        "检索质量**；答案采分点、忠实度、引用实质准确率、真实模型拒答与跨用户授权均未评测。",
        "",
        "## 1. 指标（替身链路，仅用于发现代码/流程问题）",
        "",
        "| 指标 | 结果 | 样本数 | 说明 |",
        "|---|---|---|---|",
        f"| 阈值前原始 Hit@4 | {positives['hit_raw_rate']:.1%} ({positives['hit_raw']}/{positives['total']}) | {positives['total']} | 忽略阈值，直接用 Top-4 子块父块命中 |",
        f"| 阈值后可用父块命中率 | {positives['hit_usable_rate']:.1%} ({positives['hit_usable']}/{positives['total']}) | {positives['total']} | 经过 τ_reject 门控后的可用父块 |",
        f"| 阈值拒答率（负样本） | {negatives['rate']:.1%} ({negatives['rejected']}/{negatives['total']}) | {negatives['total']} | 仅阈值门控，不含 LLM 判定 |",
        f"| 合成集合隔离 | {isolation['rate']:.1%} ({isolation['clean']}/{isolation['total']}) | {isolation['total']} | 不是授权测试 |",
        f"| 引用校验契约：非法标签剔除 | {verifier['invalid_stripped']}/{verifier['invalid_injected']} | 合成标签 | 仅验证 `CitationVerifier` |",
        f"| 引用校验契约：有效标签保留 | {verifier['valid_retained']}/{verifier['checked']} | 正样本 | 仅验证 `CitationVerifier` |",
        f"| 检索耗时 P50 / P95 | {latency['p50']} ms / {latency['p95']} ms | {positives['total'] + negatives['total']} | 替身链路，非供应商延迟 |",
        "",
        "### 分类别",
        "",
        "| 类别 | 阈值后命中/拒答 | 阈值前原始命中 | 样本数 |",
        "|---|---|---|---|",
    ]
    for category, stats in summary["by_category"].items():
        lines.append(f"| 正样本 {category} | {stats['rate']:.1%} | "
                     f"{stats['hit_raw_rate']:.1%} | {stats['total']} |")
    for category, stats in summary["negative_by_category"].items():
        lines.append(f"| 负样本 {category} | {stats['rate']:.1%} | — | {stats['total']} |")

    lines.extend([
        "",
        "## 2. 质量指标状态（真实评测必需，当前多未测）",
        "",
        "| 指标 | 状态 | 说明 |",
        "|---|---|---|",
        f"| 原始 Hit Rate@4 | 已测（替身） | {positives['hit_raw_rate']:.1%}；哈希向量，非真实语义召回 |",
        f"| 阈值后可用父块命中率 | 已测（替身） | {positives['hit_usable_rate']:.1%}；依赖占位 τ=0.35 |",
        f"| 负样本正确声明率 | **未测** | 当前只测阈值门控（{negatives['rate']:.1%}），不含 LLM 判定与真实回答声明 |",
        "| 引用零虚构率 | **未测** | 需真实模型端到端统计 token 流与落库内容的虚构引用数 |",
        "| 引用实质准确率 | **未测** | 需人工判定引用是否真正支持对应结论 |",
        "| 人工答案 Pass Rate | **未测** | 需 2 人独立盲评与仲裁 |",
        "| 主要失败类型 | 部分 | 见第 3 节的候选/阈值失败样本，不含真实模型错误归因 |",
        "| 真实 Embedding/Chat 供应商 | **未配置** | 当前为确定性字符 n-gram 哈希替身，指标不代表真实供应商 |",
        "",
        "## 3. 失败样本",
        "",
    ])
    raw_misses = summary["failures"]["positive_miss_raw"]
    usable_misses = summary["failures"]["positive_miss_usable"]
    if raw_misses:
        lines.append("**阈值前未命中：**")
        for record in raw_misses:
            lines.append(f"- **{record['id']}**：`{record['query']}`；"
                         f"期望 {record['reference']}；原始 Top-4 {record['raw_returned'] or '无'}")
    else:
        lines.append("- 阈值前正样本全部进入 Top-4。")
    if usable_misses:
        lines.append("")
        lines.append("**阈值后不可用：**")
        for record in usable_misses:
            lines.append(f"- **{record['id']}**：`{record['query']}`；"
                         f"top_similarity={record['top_similarity']}；"
                         f"可用父块 {record['returned'] or '无'}")
    else:
        lines.append("- 阈值后正样本全部可用。")
    negatives_not_rejected = summary["failures"]["negative_not_rejected"]
    if negatives_not_rejected:
        lines.append("")
        lines.append("**负样本未拒答：**")
        for record in negatives_not_rejected:
            lines.append(f"- **{record['id']}**：`{record['query']}`；"
                         f"top_similarity={record['top_similarity']}；"
                         f"返回 {record['returned']}")
    else:
        lines.append("- 负样本全部触发阈值拒答。")

    lines.extend([
        "",
        "## 4. 被评测源码 SHA-256",
        "",
    ])
    for relative, digest in sorted((environment.get("source_sha256") or {}).items()):
        lines.append(f"- `{relative}`：`{digest}`")
    lines.extend([
        "",
        "## 5. 未评测项（阻断 TASK-606 完成）",        "",
        "1. 真实课程 PDF 与人工标注页码 / `key_points`；缺少 `annotator_id`、`annotated_at`、"
        "`reviewed_by`、`review_status`。",
        "2. 真实 Embedding 供应商的检索质量与 τ_reject 标定。",
        "3. 真实 Chat 模型的引用零虚构率、引用实质准确率、负样本正确声明率。",
        "4. 人工答案 Pass Rate 与错例归因。",
        "5. 真实跨用户 / 跨课程越权请求验证（当前仅检查合成 Collection 文档名）。",
        "6. v1.1 BM25/RRF/Reranker（MVP 未实现，不得计入）。",
        "",
        "## 6. 复现方式",
        "",
        "```bash",
        "cd sc-python-ai",
        ".venv/bin/python tests/eval_retrieval.py",
        "```",
        "",
        "该命令只复现“链路冒烟/替身基线”，**不能**作为 P6 质量准出证据。"
        "获得真实课程 PDF、人工标注与真实模型 Key 后，必须重新生成 Golden Dataset 与报告。",
        "",
    ])
    return "\n".join(lines)


async def _main_async(args: argparse.Namespace) -> int:
    dataset_path = Path(args.dataset)
    dataset = load_dataset(dataset_path)
    errors = validate_dataset(dataset)
    if errors:
        print("数据集校验失败：")
        for error in errors:
            print(f"- {error}")
        return 2

    summary = await run_evaluation(
        dataset, threshold=args.threshold, top_k=args.top_k,
        top_parents=args.top_parents)
    summary["dataset_path"] = str(dataset_path)
    summary["dataset_sha256"] = hashlib.sha256(dataset_path.read_bytes()).hexdigest()
    summary["dataset_kind"] = dataset.get("dataset_kind")
    summary["annotation"] = dataset.get("annotation")
    summary["environment"] = _environment()

    report_path = Path(args.report) if args.report else (
        DEFAULT_REPORT_DIR / f"rag_baseline_{date.today().strftime('%Y%m%d')}.md")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_report(summary, dataset_path), encoding="utf-8")

    if args.json:
        json_path = Path(args.json)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                             encoding="utf-8")
        print(f"JSON: {json_path}")

    print(f"Hit@4 raw: {summary['positives']['hit_raw_rate']:.1%} "
          f"({summary['positives']['hit_raw']}/{summary['positives']['total']})")
    print(f"Hit@4 usable: {summary['positives']['hit_usable_rate']:.1%} "
          f"({summary['positives']['hit_usable']}/{summary['positives']['total']})")
    print(f"Negative rejection: {summary['negatives']['rate']:.1%} "
          f"({summary['negatives']['rejected']}/{summary['negatives']['total']})")
    print(f"Synthetic isolation: {summary['synthetic_isolation']['rate']:.1%}")
    print(f"Report: {report_path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG Golden Dataset 离线评测")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    parser.add_argument("--threshold", type=float, default=0.35)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--top-parents", type=int, default=4)
    parser.add_argument("--report", default="")
    parser.add_argument("--json", default="")
    return asyncio.run(_main_async(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
