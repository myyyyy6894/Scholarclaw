# RAG 评测报告目录

| 文件 | 性质 | 能否作为质量准出 |
|---|---|---|
| `rag_baseline_*.md` / `rag_baseline_latest.json` | `eval_retrieval.py`：12 页合成 Markdown + 确定性哈希向量 | **不能**。仅验证检索/引用/拒答代码路径，属于“链路冒烟” |
| `rag_quality_real.md`（运行后生成） | `eval_quality.py`：真实课程 PDF + 人工标注 + 真实 Embedding/Chat | 可以作为 TASK-606 / TASK-905 的真实质量证据，前提是数据集通过严格校验且报告记录了完整环境 |

真实评测步骤：

```bash
# 1. 复制模板并补齐 2 门课程、3~5 份 PDF、30~50 道人工标注题
cp tests/fixtures/rag_golden_dataset.real.template.json \
   tests/fixtures/rag_golden_dataset.real.json
#    每份 PDF 的 sha256 必须与实际文件一致
#    每题必须填写 annotator_id / annotated_at / reviewed_by / review_status

# 2. 设置真实供应商 Key（不得写入仓库或 .env）
export SC_EVAL_API_KEY='...'

# 3. 运行
.venv/bin/python tests/eval_quality.py \
  --dataset tests/fixtures/rag_golden_dataset.real.json \
  --embedding-base-url https://<provider>/v1 --embedding-model <embedding-model> \
  --embedding-dim <dim> \
  --chat-base-url https://<provider>/v1 --chat-model <chat-model> \
  --judge-model <judge-model> \
  --threshold 0.35 --report tests/eval_reports/rag_quality_real.md
```

脚本在数据集不是 `real-annotated`、PDF/SHA-256 不匹配、标注字段缺失或 Key 未设置时**退出码 2**，
不会降级为合成替身，也不会生成“通过”结论。
