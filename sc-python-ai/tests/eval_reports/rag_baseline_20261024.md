# RAG 链路冒烟 / 替身基线报告（TASK-606，**未完成质量准出**）

- 生成时间：2026-10-24T13:24:22
- 数据集：`rag_golden_dataset.json`（version=1.0，kind=synthetic-link-smoke）
- 数据集 SHA-256：`c0a12a546a3963c44b53849171e2e1a85f44fad3a340333191e96a0b618006da`
- 标注状态：annotator_id=None，annotated_at=None，reviewed_by=None，review_status=NOT_ANNOTATED
- 运行环境：Python 3.13.13 / ChromaDB 0.5.23 / Linux-7.0.0-38-generic-x86_64-with-glibc2.39
- Git commit：`b435be5be8656811f2891fda09ac4ad8109e3f5e`
- Git 工作区：`DIRTY_WORKTREE`（`DIRTY_WORKTREE` 表示被测代码含未提交改动，commit 不能单独代表结果）
- 运行命令：`tests/eval_retrieval.py --report tests/eval_reports/rag_baseline_20261024.md --json tests/eval_reports/rag_baseline_latest.json`
- 配置：threshold=0.35, top_k=10, top_parents=4

> **结论先行：TASK-606 未完成，P6 不可验收。** 当前数据集是 12 页合成 Markdown 替身，没有真实课程 PDF、没有人工标注（annotator/reviewer 均为空），不能认定为真实人工标注的 Golden Dataset。检索使用确定性字符 n-gram 哈希向量替身，**数字不代表真实 Embedding 的检索质量**；答案采分点、忠实度、引用实质准确率、真实模型拒答与跨用户授权均未评测。

## 1. 指标（替身链路，仅用于发现代码/流程问题）

| 指标 | 结果 | 样本数 | 说明 |
|---|---|---|---|
| 阈值前原始 Hit@4 | 100.0% (32/32) | 32 | 忽略阈值，直接用 Top-4 子块父块命中 |
| 阈值后可用父块命中率 | 81.2% (26/32) | 32 | 经过 τ_reject 门控后的可用父块 |
| 阈值拒答率（负样本） | 87.5% (7/8) | 8 | 仅阈值门控，不含 LLM 判定 |
| 合成集合隔离 | 100.0% (40/40) | 40 | 不是授权测试 |
| 引用校验契约：非法标签剔除 | 52/52 | 合成标签 | 仅验证 `CitationVerifier` |
| 引用校验契约：有效标签保留 | 26/26 | 正样本 | 仅验证 `CitationVerifier` |
| 检索耗时 P50 / P95 | 2.3 ms / 2.51 ms | 40 | 替身链路，非供应商延迟 |

### 分类别

| 类别 | 阈值后命中/拒答 | 阈值前原始命中 | 样本数 |
|---|---|---|---|
| 正样本 answerable | 81.2% | 100.0% | 32 |
| 负样本 cross_course | 100.0% | — | 2 |
| 负样本 low_relevance | 100.0% | — | 2 |
| 负样本 no_answer | 75.0% | — | 4 |

## 2. 质量指标状态（真实评测必需，当前多未测）

| 指标 | 状态 | 说明 |
|---|---|---|
| 原始 Hit Rate@4 | 已测（替身） | 100.0%；哈希向量，非真实语义召回 |
| 阈值后可用父块命中率 | 已测（替身） | 81.2%；依赖占位 τ=0.35 |
| 负样本正确声明率 | **未测** | 当前只测阈值门控（87.5%），不含 LLM 判定与真实回答声明 |
| 引用零虚构率 | **未测** | 需真实模型端到端统计 token 流与落库内容的虚构引用数 |
| 引用实质准确率 | **未测** | 需人工判定引用是否真正支持对应结论 |
| 人工答案 Pass Rate | **未测** | 需 2 人独立盲评与仲裁 |
| 主要失败类型 | 部分 | 见第 3 节的候选/阈值失败样本，不含真实模型错误归因 |
| 真实 Embedding/Chat 供应商 | **未配置** | 当前为确定性字符 n-gram 哈希替身，指标不代表真实供应商 |

## 3. 失败样本

- 阈值前正样本全部进入 Top-4。

**阈值后不可用：**
- **D03**：`事务持久性在系统故障时意味着什么？`；top_similarity=0.296062171459198；可用父块 无
- **D09**：`SQL 标准定义了哪四种隔离级别？`；top_similarity=0.2786295413970947；可用父块 无
- **O07**：`什么是抢占式调度？`；top_similarity=0.26540231704711914；可用父块 无
- **O09**：`页表的作用是什么？`；top_similarity=0.31282275915145874；可用父块 无
- **O10**：`TLB 命中有什么好处？`；top_similarity=0.25997745990753174；可用父块 无
- **O16**：`什么是 Belady 异常？`；top_similarity=0.3103426694869995；可用父块 无

**负样本未拒答：**
- **N04**：`Transformer 自注意力的计算复杂度是多少？`；top_similarity=0.4083511233329773；返回 [{'doc_name': '第4章_内存管理.md', 'page': 3}, {'doc_name': '第4章_内存管理.md', 'page': 2}, {'doc_name': '第3章_进程与线程.md', 'page': 3}, {'doc_name': '第4章_内存管理.md', 'page': 1}]

## 4. 被评测源码 SHA-256

- `app/services/ingest/vector_store.py`：`81faff5a014c0e6231849e432bc3b6c375d4763a5b017e04799da9aa6eb1b729`
- `app/services/rag/citation_verifier.py`：`d75ebe0289c5bd1c7fff91cbdb1fbd0150c060c6afe79d3637c0bb0d6e16cd79`
- `app/services/rag/dense_retriever.py`：`01d634b2e6e27a86d770b68a3df468b5a6218d04b0a798b107faa7ec16857679`
- `app/services/rag/qa_pipeline.py`：`6edd24d625a92b30e6f19517ba948abd9a1de43af194923a6d2d764f93786573`
- `tests/eval_retrieval.py`：`8dd8906654c02ce0514e7340ea2e3108934d08e5891e2f48f7f5cb3d4c422236`

## 5. 未评测项（阻断 TASK-606 完成）

1. 真实课程 PDF 与人工标注页码 / `key_points`；缺少 `annotator_id`、`annotated_at`、`reviewed_by`、`review_status`。
2. 真实 Embedding 供应商的检索质量与 τ_reject 标定。
3. 真实 Chat 模型的引用零虚构率、引用实质准确率、负样本正确声明率。
4. 人工答案 Pass Rate 与错例归因。
5. 真实跨用户 / 跨课程越权请求验证（当前仅检查合成 Collection 文档名）。
6. v1.1 BM25/RRF/Reranker（MVP 未实现，不得计入）。

## 6. 复现方式

```bash
cd sc-python-ai
.venv/bin/python tests/eval_retrieval.py
```

该命令只复现“链路冒烟/替身基线”，**不能**作为 P6 质量准出证据。获得真实课程 PDF、人工标注与真实模型 Key 后，必须重新生成 Golden Dataset 与报告。
