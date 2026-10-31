#!/usr/bin/env python3
"""真实 RAG 质量评测（P9 复核第 1 项）。**未取得真实 PDF/标注/Key 时不得运行成功。**

与 `eval_retrieval.py` 的分工：

* `eval_retrieval.py`：合成语料 + 确定性哈希向量，只验证检索/引用/拒答**代码路径**，
  报告明确标注为“链路冒烟”，不得作为 TASK-905 或 P9 的质量准出证据；
* 本脚本：只接受 `dataset_kind = "real-annotated"` 的真实数据集，校验 PDF 路径与 SHA-256、
  人工标注字段（标注人/复核人/时间/仲裁/review_status），调用**真实 Embedding/Chat 供应商**，
  输出 Hit@4、引用零虚构率、Citation Precision、答案 Pass Rate/忠实度、Negative Rejection
  Accuracy，并保留失败样本。

真实数据集要求（详见 `tests/fixtures/rag_golden_dataset.real.template.json`）：

* 2 门课程、3~5 份文字版 PDF；
* 30~50 道题，含正样本、库外负样本、低相关样本、跨课程样本；
* 每题标注文档名、页码、采分点、标注人、复核人、标注时间与 `review_status`；
* 每份 PDF 记录相对路径与 SHA-256。

用法示例：

    sc-python-ai/.venv/bin/python tests/eval_quality.py \
      --dataset tests/fixtures/rag_golden_dataset.real.json \
      --embedding-base-url https://api.example.com/v1 --embedding-model text-embedding-3-large \
      --embedding-dim 3072 \
      --chat-base-url https://api.example.com/v1 --chat-model deepseek-chat \
      --api-key-env SC_EVAL_API_KEY \
      --threshold 0.35 --report tests/eval_reports/rag_quality_real.md

退出码：0 = 完成真实评测；2 = 数据集/凭据不满足真实评测条件（**不得伪造指标**）。
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import platform
import re
import statistics
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Awaitable, Callable

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parent
DEFAULT_DATASET = ROOT / "tests" / "fixtures" / "rag_golden_dataset.real.json"
DEFAULT_REPORT = ROOT / "tests" / "eval_reports" / "rag_quality_real.md"

POSITIVE_CATEGORIES = {"answerable", "cross_page", "cross_doc"}
NEGATIVE_CATEGORIES = {"unanswerable_in_corpus", "low_relevance", "cross_course"}
REQUIRED_CATEGORIES = POSITIVE_CATEGORIES | NEGATIVE_CATEGORIES
REVIEW_STATUSES = {"APPROVED", "ARBITRATED"}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CITATION_RE = re.compile(
    r"(?:\[|【)来源[：:]\s*(?P<doc>[^\-\]】]+?)\s*-\s*第\s*(?P<page>\d+)\s*页\s*(?:\]|】)")
JUDGE_KEYS = {"key_points_covered", "faithful", "pass", "reason"}


class RealEvaluationUnavailable(RuntimeError):
    """数据集或凭据不满足真实评测条件；调用方必须退出 2，不得降级为替身。"""


@dataclass(frozen=True)
class Page:
    doc_name: str
    page: int
    text: str


@dataclass(frozen=True)
class Question:
    id: str
    course_key: str
    category: str
    query: str
    reference: dict[str, Any] | None
    key_points: tuple[str, ...]
    annotation: dict[str, Any]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_annotation(document: dict[str, Any], where: str, errors: list[str]) -> None:
    for field in ("annotator_id", "annotated_at", "reviewed_by", "review_status"):
        if not document.get(field):
            errors.append(f"{where}: 缺少人工标注字段 {field}")
    if document.get("review_status") not in REVIEW_STATUSES:
        errors.append(f"{where}: review_status 必须为 {sorted(REVIEW_STATUSES)}")


def validate_real_dataset(dataset: dict[str, Any], *, corpus_root: Path,
                          check_files: bool = True) -> list[str]:
    """严格校验真实数据集；任何缺项都返回错误，不做“尽量跑”的降级。"""
    errors: list[str] = []
    if dataset.get("dataset_kind") != "real-annotated":
        return ["dataset_kind 必须为 'real-annotated'；合成数据只能用于链路冒烟"]

    annotation = dataset.get("annotation") or {}
    if not annotation.get("annotated_at"):
        errors.append("annotation.annotated_at 缺失")
    if annotation.get("review_status") not in REVIEW_STATUSES:
        errors.append("annotation.review_status 必须为 APPROVED/ARBITRATED")
    if not annotation.get("reviewed_by"):
        errors.append("annotation.reviewed_by 缺失")

    courses = dataset.get("courses")
    questions = dataset.get("questions")
    if not isinstance(courses, list) or len(courses) < 2:
        return errors + ["至少需要 2 门课程"]
    if not isinstance(questions, list) or not 30 <= len(questions) <= 50:
        errors.append(f"题目数量必须在 30~50 之间，当前 {len(questions or [])}")

    course_map = {course.get("key"): course for course in courses}
    if len(course_map) != len(courses):
        errors.append("课程 key 必须唯一")

    pdf_total = 0
    for course in courses:
        documents = course.get("documents")
        if not isinstance(documents, list) or not documents:
            errors.append(f"课程 {course.get('key')} 缺少文档")
            continue
        for document in documents:
            pdf_total += 1
            where = f"课程 {course.get('key')} / 文档 {document.get('key')}"
            pdf_path = document.get("pdf_path")
            digest = document.get("sha256")
            if not pdf_path or not str(pdf_path).lower().endswith(".pdf"):
                errors.append(f"{where}: pdf_path 必须是 PDF 相对路径")
                continue
            if not isinstance(digest, str) or not SHA256_RE.match(digest):
                errors.append(f"{where}: sha256 必须是 64 位小写十六进制")
            if check_files:
                actual_path = (corpus_root / pdf_path).resolve()
                if not actual_path.is_file():
                    errors.append(f"{where}: PDF 不存在 {actual_path}")
                elif sha256_file(actual_path) != digest:
                    errors.append(f"{where}: PDF SHA-256 与标注不一致")
    if not 3 <= pdf_total <= 5:
        errors.append(f"真实语料必须为 3~5 份 PDF，当前 {pdf_total}")

    negative_count = 0
    seen_ids: set[str] = set()
    for index, question in enumerate(questions or []):
        question_id = question.get("id")
        if not question_id or question_id in seen_ids:
            errors.append(f"第 {index} 题缺少唯一 id")
            continue
        seen_ids.add(question_id)
        course = course_map.get(question.get("course_key"))
        if course is None:
            errors.append(f"{question_id}: course_key 不存在")
            continue
        category = question.get("category")
        if category not in REQUIRED_CATEGORIES:
            errors.append(f"{question_id}: 未知 category {category}")
        if not isinstance(question.get("query"), str) or not question["query"].strip():
            errors.append(f"{question_id}: query 不能为空")
        _require_annotation(question.get("annotation") or {}, question_id, errors)
        key_points = question.get("key_points")
        if category in POSITIVE_CATEGORIES:
            if not isinstance(key_points, list) or not key_points:
                errors.append(f"{question_id}: 正样本必须提供非空 key_points")
            reference = question.get("reference")
            if not isinstance(reference, dict):
                errors.append(f"{question_id}: 正样本必须提供 reference")
                continue
            doc = next((item for item in course.get("documents", [])
                        if item.get("doc_name") == reference.get("doc_name")), None)
            if doc is None:
                errors.append(f"{question_id}: reference.doc_name 不在课程语料中")
            start = reference.get("page_start")
            end = reference.get("page_end")
            if not isinstance(start, int) or not isinstance(end, int) or start > end:
                errors.append(f"{question_id}: reference 页码范围非法")
        else:
            negative_count += 1
            if question.get("reference") is not None:
                errors.append(f"{question_id}: 负样本 reference 必须为 null")
            if question.get("expected") != "rejection":
                errors.append(f"{question_id}: 负样本 expected 必须为 rejection")
    ratio = negative_count / len(questions) if questions else 0
    if not 0.15 <= ratio <= 0.25:
        errors.append(f"负样本比例应在 15%~25%，当前 {ratio:.1%}")
    missing = NEGATIVE_CATEGORIES - {
        str(question.get("category")) for question in questions or []
        if question.get("category") in NEGATIVE_CATEGORIES
    }
    if missing:
        errors.append(f"缺少负样本类别: {', '.join(sorted(missing))}")
    return errors


def load_questions(dataset: dict[str, Any]) -> list[Question]:
    result = []
    for item in dataset["questions"]:
        result.append(Question(
            id=item["id"], course_key=item["course_key"],
            category=item["category"], query=item["query"],
            reference=item.get("reference"),
            key_points=tuple(item.get("key_points") or ()),
            annotation=item.get("annotation") or {},
        ))
    return result


def parse_citations(answer: str) -> list[tuple[str, int]]:
    """提取回答中的来源标签；只认完整 `[来源：doc - 第N页]` 形式。"""
    return [(match.group("doc").strip(), int(match.group("page")))
            for match in CITATION_RE.finditer(answer)]


def citation_metrics(answer: str, allowed: set[tuple[str, int]],
                     reference_pages: set[tuple[str, int]] | None) -> dict[str, Any]:
    citations = parse_citations(answer)
    total = len(citations)
    fabricated = [citation for citation in citations if citation not in allowed]
    if reference_pages is None:
        precise = None
        precision = None
    else:
        precise = sum(1 for citation in citations if citation in reference_pages)
        precision = precise / total if total else 0.0
    return {
        "citations": citations,
        "total": total,
        "fabricated": fabricated,
        "zero_hallucination": total > 0 and not fabricated,
        "precision": precision,
    }


def cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right))
    norm_left = math.sqrt(sum(a * a for a in left))
    norm_right = math.sqrt(sum(b * b for b in right))
    if norm_left == 0 or norm_right == 0:
        return 0.0
    return dot / (norm_left * norm_right)


ANSWER_PROMPT = """你是课程助教。只允许依据下面给出的课件片段回答问题。
每个结论后必须附来源标签，格式严格为：[来源：文件名 - 第N页]。
如果片段不足以回答，必须明确回复“课件中没有足够信息”，不得猜测。
不要输出与问题无关的内容。

课件片段：
{context}

问题：{question}
"""

JUDGE_PROMPT = """你是严格的评测员。请判断候选回答是否满足要求，只输出 JSON，不要输出其他文字。
JSON 字段：key_points_covered（数组，元素来自采分点原文）、faithful（布尔，回答是否只依据给定片段）、
pass（布尔，采分点全部覆盖且无相反/虚构内容）、reason（一句话原因）。
若候选回答对负样本问题仍给出实质答案（未拒答），pass=false，faithful=false。

问题：{question}
采分点：{key_points}
给定片段：
{context}
候选回答：
{answer}
"""


class RealProvider:
    """真实供应商调用（OpenAI 兼容）。所有请求经 `SsrfSafeModelGateway`。"""

    def __init__(self, *, settings, embedding_base_url: str, embedding_model: str,
                 chat_base_url: str, chat_model: str, api_key: str,
                 embedding_dim: int | None = None,
                 judge_model: str | None = None) -> None:
        self._settings = settings
        self._embedding_base_url = embedding_base_url
        self._embedding_model = embedding_model
        self._chat_base_url = chat_base_url
        self._chat_model = chat_model
        self._judge_model = judge_model or chat_model
        self._api_key = api_key
        self._embedding_dim = embedding_dim

    async def embed(self, texts: list[str]) -> list[list[float]]:
        from app.services.gateway.model_endpoint import ModelEndpoint
        from app.services.gateway.ssrf_safe_model_gateway import (
            RetryPolicy,
            SsrfSafeModelGateway,
        )

        endpoint = ModelEndpoint("OPENAI_COMPATIBLE", self._embedding_base_url,
                                 self._api_key, self._embedding_model)
        vectors: list[list[float]] = []
        async with SsrfSafeModelGateway(self._settings) as gateway:
            for start in range(0, len(texts), 32):
                payload = await gateway.post_json(
                    endpoint, "embeddings",
                    {"model": self._embedding_model, "input": texts[start:start + 32]},
                    retry=RetryPolicy.standard())
                data = payload.get("data")
                if not isinstance(data, list) or len(data) != len(texts[start:start + 32]):
                    raise RuntimeError("Embedding 响应数量与输入不一致")
                ordered = sorted(data, key=lambda item: item.get("index", 0))
                for item in ordered:
                    vector = [float(value) for value in item["embedding"]]
                    if self._embedding_dim and len(vector) != self._embedding_dim:
                        raise RuntimeError("Embedding 维度与 --embedding-dim 不一致")
                    vectors.append(vector)
        return vectors

    async def chat(self, prompt: str, *, model: str | None = None,
                   temperature: float = 0.0, max_tokens: int = 1600) -> str:
        from app.services.gateway.model_endpoint import ModelEndpoint
        from app.services.gateway.ssrf_safe_model_gateway import (
            RetryPolicy,
            SsrfSafeModelGateway,
        )

        endpoint = ModelEndpoint("OPENAI_COMPATIBLE", self._chat_base_url,
                                 self._api_key, model or self._chat_model)
        async with SsrfSafeModelGateway(self._settings) as gateway:
            payload = await gateway.post_json(
                endpoint, "chat/completions", {
                    "model": model or self._chat_model,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "messages": [{"role": "user", "content": prompt}],
                }, retry=RetryPolicy.standard())
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices:
            raise RuntimeError("Chat 响应缺少 choices")
        content = (choices[0].get("message") or {}).get("content")
        if not isinstance(content, str):
            raise RuntimeError("Chat 响应缺少正文")
        return content


def parse_judge(raw: str) -> dict[str, Any] | None:
    stripped = raw.strip()
    if stripped.startswith("```"):
        stripped = re.sub(r"^```[a-zA-Z]*\s*", "", stripped)
        stripped = re.sub(r"\s*```$", "", stripped)
    try:
        payload = json.loads(stripped)
    except ValueError:
        return None
    if not isinstance(payload, dict) or not JUDGE_KEYS <= set(payload):
        return None
    if not isinstance(payload.get("faithful"), bool) or not isinstance(payload.get("pass"), bool):
        return None
    return payload


async def evaluate(dataset: dict[str, Any], provider: RealProvider, *,
                   threshold: float, top_k: int = 4,
                   parse_pages: Callable[[Path, str], list] | None = None,
                   ) -> dict[str, Any]:
    """执行真实评测。`parse_pages` 仅用于测试注入。"""
    if parse_pages is None:
        from app.services.ingest.parser import parse_document

        def parse_pages(path: Path, ext: str):  # noqa: ANN001
            return parse_document(path, ext)

    corpus_root = REPO_ROOT
    pages_by_course: dict[str, list[Page]] = {}
    for course in dataset["courses"]:
        pages: list[Page] = []
        for document in course["documents"]:
            parsed = parse_pages(corpus_root / document["pdf_path"], ".pdf")
            for page in parsed:
                text = getattr(page, "text", None)
                number = getattr(page, "page_number", None)
                if not isinstance(text, str) or not isinstance(number, int):
                    raise RealEvaluationUnavailable("PDF 解析结果缺少 page_number/text")
                pages.append(Page(document["doc_name"], number, text))
        if not pages:
            raise RealEvaluationUnavailable(f"课程 {course['key']} 未解析到任何页面")
        pages_by_course[course["key"]] = pages

    all_texts = [page.text for pages in pages_by_course.values() for page in pages]
    page_vectors = await provider.embed(all_texts)
    vector_index: dict[tuple[str, str, int], list[float]] = {}
    offset = 0
    for course_key, pages in pages_by_course.items():
        for page in pages:
            vector_index[(course_key, page.doc_name, page.page)] = page_vectors[offset]
            offset += 1

    questions = load_questions(dataset)
    results: list[dict[str, Any]] = []
    for question in questions:
        pages = pages_by_course[question.course_key]
        query_vector = (await provider.embed([question.query]))[0]
        ranked = sorted(
            ((cosine(query_vector, vector_index[(question.course_key, page.doc_name,
                                                  page.page)]), page)
             for page in pages),
            key=lambda item: item[0], reverse=True)
        top = ranked[:top_k]
        top_similarity = top[0][0] if top else 0.0
        allowed = {(page.doc_name, page.page) for _, page in top}
        context = "\n\n".join(
            f"[{page.doc_name} - 第{page.page}页]\n{page.text}" for _, page in top)
        result: dict[str, Any] = {
            "id": question.id, "category": question.category,
            "query": question.query, "top_similarity": round(top_similarity, 4),
            "retrieved": [{"doc_name": page.doc_name, "page": page.page,
                           "score": round(score, 4)} for score, page in top],
        }
        reference_pages = None
        if question.reference is not None:
            reference_pages = {
                (question.reference["doc_name"], page)
                for page in range(question.reference["page_start"],
                                  question.reference["page_end"] + 1)
            }
            result["hit_at_4"] = bool(
                reference_pages & {(page.doc_name, page.page) for _, page in top})
            result["reference"] = question.reference
        else:
            result["hit_at_4"] = None

        if top_similarity < threshold and question.category in NEGATIVE_CATEGORIES:
            result.update({"answer": "", "rejected_by_threshold": True,
                           "citation": citation_metrics("", allowed, None),
                           "judge": {"pass": True, "faithful": True,
                                     "key_points_covered": [],
                                     "reason": "阈值门控拒答"},
                           "refusal": True})
            results.append(result)
            continue

        answer = await provider.chat(ANSWER_PROMPT.format(context=context,
                                                          question=question.query))
        citation = citation_metrics(answer, allowed, reference_pages)
        judge_raw = await provider.chat(JUDGE_PROMPT.format(
            question=question.query,
            key_points=json.dumps(list(question.key_points), ensure_ascii=False),
            context=context, answer=answer), model=provider._judge_model)
        judge = parse_judge(judge_raw)
        if judge is None:
            judge = {"key_points_covered": [], "faithful": False, "pass": False,
                     "reason": "judge 输出不是合法 JSON，按失败处理"}
            result["judge_parse_error"] = judge_raw[:500]
        result.update({
            "answer": answer,
            "rejected_by_threshold": False,
            "citation": citation,
            "judge": judge,
            "refusal": bool(judge.get("pass")) and question.category in NEGATIVE_CATEGORIES,
        })
        results.append(result)

    positives = [item for item in results if item["category"] in POSITIVE_CATEGORIES]
    negatives = [item for item in results if item["category"] in NEGATIVE_CATEGORIES]
    total_citations = sum(item["citation"]["total"] for item in positives)
    fabricated = sum(len(item["citation"]["fabricated"]) for item in positives)
    precise = sum(
        (item["citation"]["precision"] or 0.0) * item["citation"]["total"]
        for item in positives)
    evaluated_precision = sum(item["citation"]["total"] for item in positives
                              if item["citation"]["precision"] is not None)
    pass_count = sum(1 for item in positives if item["judge"].get("pass"))
    faithful_count = sum(1 for item in positives if item["judge"].get("faithful"))
    negative_rejected = sum(1 for item in negatives if item["judge"].get("pass"))
    hit_count = sum(1 for item in positives if item["hit_at_4"])
    latencies = [item["top_similarity"] for item in results]

    return {
        "dataset_version": dataset.get("version"),
        "dataset_kind": dataset.get("dataset_kind"),
        "config": {"threshold": threshold, "top_k": top_k},
        "environment": _environment(),
        "models": {
            "embedding": provider._embedding_model,
            "chat": provider._chat_model,
            "judge": provider._judge_model,
        },
        "positives": {
            "total": len(positives),
            "hit_at_4": hit_count,
            "hit_at_4_rate": hit_count / len(positives) if positives else 0.0,
            "pass": pass_count,
            "pass_rate": pass_count / len(positives) if positives else 0.0,
            "faithful": faithful_count,
            "faithfulness_rate": faithful_count / len(positives) if positives else 0.0,
        },
        "citation": {
            "total": total_citations,
            "fabricated": fabricated,
            "zero_hallucination_rate": (
                (total_citations - fabricated) / total_citations
                if total_citations else 0.0),
            "answers_with_zero_fabrication": (
                sum(1 for item in positives if item["citation"]["zero_hallucination"])
                / len(positives) if positives else 0.0),
            "precision": precise / evaluated_precision if evaluated_precision else 0.0,
            "evaluated_citations": evaluated_precision,
        },
        "negatives": {
            "total": len(negatives),
            "rejected": negative_rejected,
            "rejection_accuracy": (
                negative_rejected / len(negatives) if negatives else 0.0),
        },
        "by_category": {
            category: {
                "total": sum(1 for item in results if item["category"] == category),
                "hit_at_4_rate": _rate(
                    [item for item in results if item["category"] == category], "hit_at_4"),
                "pass_rate": _rate(
                    [item for item in results if item["category"] == category], "judge"),
            }
            for category in sorted({item["category"] for item in results})
        },
        "failures": {
            "miss": [item for item in positives if not item["hit_at_4"]],
            "unfaithful": [item for item in positives if not item["judge"].get("faithful")],
            "not_passed": [item for item in positives if not item["judge"].get("pass")],
            "negative_not_rejected": [
                item for item in negatives if not item["judge"].get("pass")],
            "fabricated_citations": [
                {"id": item["id"], "fabricated": item["citation"]["fabricated"]}
                for item in positives if item["citation"]["fabricated"]],
        },
        "latency_proxy": {"top_similarity_min": min(latencies) if latencies else 0.0},
    }


def _rate(records: list[dict[str, Any]], key: str) -> float:
    if not records:
        return 0.0
    if key == "judge":
        return sum(1 for item in records if item["judge"].get("pass")) / len(records)
    return sum(1 for item in records if item[key]) / len(records)


def _run_git(*args: str) -> subprocess.CompletedProcess[str] | None:
    """执行只读 git 命令；测试镜像未安装 git 时返回 None，不阻断评测。"""
    try:
        return subprocess.run(["git", *args], cwd=REPO_ROOT, capture_output=True,
                              text=True, check=False)
    except (FileNotFoundError, OSError, subprocess.SubprocessError):
        return None


def _git_commit() -> str:
    result = _run_git("rev-parse", "HEAD")
    return result.stdout.strip() if result is not None and result.returncode == 0 else "UNKNOWN"


def _git_worktree() -> str:
    result = _run_git("status", "--porcelain")
    if result is None or result.returncode != 0:
        return "UNKNOWN"
    return "DIRTY_WORKTREE" if result.stdout.strip() else "CLEAN"


def _environment() -> dict[str, Any]:
    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "git_commit": _git_commit(),
        "git_worktree": _git_worktree(),
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "command": " ".join(sys.argv),
    }


def render_report(summary: dict[str, Any], dataset_path: Path) -> str:
    pos = summary["positives"]
    neg = summary["negatives"]
    citation = summary["citation"]
    env = summary["environment"]
    models = summary["models"]
    lines = [
        "# 真实 RAG 质量评测报告（TASK-905 / P9 复核第 1 项）",
        "",
        f"- 数据集：`{dataset_path}`（version={summary['dataset_version']}，"
        f"kind={summary['dataset_kind']}）",
        f"- 生成时间：{env['generated_at']}",
        f"- 运行环境：Python {env['python']} / {env['platform']}",
        f"- Git commit：`{env['git_commit']}`；工作区：`{env['git_worktree']}`",
        f"- 模型：Embedding `{models['embedding']}`，Chat `{models['chat']}`，"
        f"Judge `{models['judge']}`",
        f"- 配置：threshold={summary['config']['threshold']}，top_k={summary['config']['top_k']}",
        f"- 命令：`{env['command']}`",
        "",
        "> 本报告只使用真实课程 PDF、人工标注与真实供应商；合成语料/哈希向量结果"
        "（`eval_retrieval.py`）只用于链路冒烟，不得与本报告混用。",
        "",
        "## 1. 指标",
        "",
        "| 指标 | 结果 | 样本数 |",
        "|---|---|---|",
        f"| 检索 Hit@4 | {pos['hit_at_4_rate']:.1%} ({pos['hit_at_4']}/{pos['total']}) | {pos['total']} |",
        f"| 引用零虚构率（逐条引用） | {citation['zero_hallucination_rate']:.1%} "
        f"({citation['total'] - citation['fabricated']}/{citation['total']}) | {citation['total']} |",
        f"| 回答零虚构率（逐题） | {citation['answers_with_zero_fabrication']:.1%} | {pos['total']} |",
        f"| 引用实质准确率 Citation Precision | {citation['precision']:.1%} | "
        f"{citation['evaluated_citations']} 条引用 |",
        f"| 答案 Pass Rate | {pos['pass_rate']:.1%} ({pos['pass']}/{pos['total']}) | {pos['total']} |",
        f"| 忠实度 | {pos['faithfulness_rate']:.1%} ({pos['faithful']}/{pos['total']}) | {pos['total']} |",
        f"| 负样本拒绝率 | {neg['rejection_accuracy']:.1%} "
        f"({neg['rejected']}/{neg['total']}) | {neg['total']} |",
        "",
        "## 2. 分类别",
        "",
        "| 类别 | Hit@4 | Pass Rate | 样本数 |",
        "|---|---|---|---|",
    ]
    for category, stats in summary["by_category"].items():
        lines.append(f"| {category} | {stats['hit_at_4_rate']:.1%} | "
                     f"{stats['pass_rate']:.1%} | {stats['total']} |")
    lines.extend(["", "## 3. 失败样本", ""])
    failures = summary["failures"]
    lines.append(f"- 检索未命中：{len(failures['miss'])}")
    for item in failures["miss"]:
        lines.append(f"  - **{item['id']}** `{item['query']}`；"
                     f"期望 {item['reference']}；检索 {item['retrieved']}")
    lines.append(f"- 不忠实：{len(failures['unfaithful'])}")
    for item in failures["unfaithful"]:
        lines.append(f"  - **{item['id']}**：{item['judge'].get('reason')}")
    lines.append(f"- 未通过：{len(failures['not_passed'])}")
    for item in failures["not_passed"]:
        lines.append(f"  - **{item['id']}**：{item['judge'].get('reason')}")
    lines.append(f"- 负样本未拒绝：{len(failures['negative_not_rejected'])}")
    for item in failures["negative_not_rejected"]:
        lines.append(f"  - **{item['id']}**：{item['judge'].get('reason')}")
    lines.append(f"- 虚构引用：{len(failures['fabricated_citations'])}")
    for item in failures["fabricated_citations"]:
        lines.append(f"  - **{item['id']}**：{item['fabricated']}")
    lines.extend([
        "", "## 4. 方法与边界", "",
        "1. PDF 由 `app.services.ingest.parser.parse_document` 解析，逐页作为检索单元；",
        "2. Embedding 与 Chat/Judge 均为 OpenAI 兼容真实接口，经 `SsrfSafeModelGateway` 出站；",
        "3. 引用零虚构率 = 回答中的来源标签都能在本次检索上下文找到；",
        "4. Citation Precision = 来源标签命中人工标注页码范围的比例；",
        "5. Pass/忠实度由 Judge 模型按采分点判定，Judge 输出非法 JSON 时按失败处理，不静默通过；",
        "6. 负样本先经过检索阈值门控，阈值以上仍交给模型并判定是否拒答；",
        "7. 本报告不覆盖 v1.1 BM25/RRF/Reranker，也不覆盖跨用户授权。",
        "",
    ])
    return "\n".join(lines)


async def _main_async(args: argparse.Namespace) -> int:
    dataset_path = Path(args.dataset)
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    errors = validate_real_dataset(dataset, corpus_root=REPO_ROOT)
    if errors:
        print("真实数据集校验失败，拒绝生成质量指标：", file=sys.stderr)
        for error in errors:
            print("  -", error, file=sys.stderr)
        return 2

    api_key = os.environ.get(args.api_key_env)
    if not api_key:
        print(f"缺少真实供应商 Key：请设置环境变量 {args.api_key_env}；"
              "不得用替身或空 Key 生成质量结论。", file=sys.stderr)
        return 2

    from app.core.config import Settings

    settings = Settings()
    provider = RealProvider(
        settings=settings,
        embedding_base_url=args.embedding_base_url,
        embedding_model=args.embedding_model,
        chat_base_url=args.chat_base_url,
        chat_model=args.chat_model,
        api_key=api_key,
        embedding_dim=args.embedding_dim,
        judge_model=args.judge_model,
    )
    summary = await evaluate(dataset, provider, threshold=args.threshold,
                             top_k=args.top_k)
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(render_report(summary, dataset_path), encoding="utf-8")
    print(json.dumps(summary["positives"], ensure_ascii=False))
    print(json.dumps(summary["citation"], ensure_ascii=False))
    print(json.dumps(summary["negatives"], ensure_ascii=False))
    print(f"Report: {report_path}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="真实 RAG 质量评测（仅接受 real-annotated 数据集）")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    parser.add_argument("--report", default=str(DEFAULT_REPORT))
    parser.add_argument("--embedding-base-url", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--embedding-dim", type=int, default=None)
    parser.add_argument("--chat-base-url", required=True)
    parser.add_argument("--chat-model", required=True)
    parser.add_argument("--judge-model", default=None)
    parser.add_argument("--api-key-env", default="SC_EVAL_API_KEY")
    parser.add_argument("--threshold", type=float, default=0.35)
    parser.add_argument("--top-k", type=int, default=4)
    args = parser.parse_args()
    try:
        return asyncio.run(_main_async(args))
    except RealEvaluationUnavailable as error:
        print(f"真实评测不可用：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
