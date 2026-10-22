"""解析管线（TASK-502）：上下文核验 → 只读文件 → 解析 → 父子分块。

ChromaDB 写入与 Java 结果回调分别属 TASK-504 / TASK-503；本模块只产出确定性的
`ParseResult`，不在解析阶段伪造向量或最终状态。
"""

from __future__ import annotations

from pathlib import Path

from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.services.ingest.chunker import chunk_document
from app.services.ingest.models import ParseContext, ParseResult
from app.services.ingest.parser import parse_document


def resolve_storage_path(raw_root: str, relative_path: str) -> Path:
    """把 `raw/{uid}/{cid}/{doc_id}.ext` 限制在只读共享卷根目录内。

    Java 返回的 `storage_rel_path` 相对共享卷根 `/data/scholarclaw/storage`，
    而 Python 的 `SC_STORAGE_RAW_PATH` 已指向其 `raw/` 子目录，因此需要剥离前缀。
    """
    root = Path(raw_root).resolve()
    relative = relative_path.lstrip("/")
    if relative.startswith("raw/"):
        relative = relative[len("raw/"):]
    candidate = (root / relative).resolve()
    if root != candidate and root not in candidate.parents:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "源文件路径越界。")
    return candidate


def parse_task(raw_root: str, context: ParseContext, *, max_pages: int = 300) -> ParseResult:
    if not context.storage_rel_path:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "解析上下文缺少文件路径。")
    path = resolve_storage_path(raw_root, context.storage_rel_path)
    pages = parse_document(path, context.file_ext or path.suffix, max_pages=max_pages)
    return chunk_document(pages, doc_id=context.doc_id, parse_version=context.parse_version)
