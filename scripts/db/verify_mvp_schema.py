#!/usr/bin/env python3
"""兼容入口：委托 ``verify_schema.py --scope mvp`` 核对 V1.0.0–V1.0.8 结构。

已停止解析本地 ``docs/03_数据库设计文档.md``，改由随仓库交付的
``scripts/db/schema_spec.json`` 提供结构规范。推荐主入口：
``python3 scripts/db/verify_schema.py --scope all``
"""

from __future__ import annotations

import sys
from pathlib import Path

DB_DIR = Path(__file__).resolve().parent
if str(DB_DIR) not in sys.path:
    sys.path.insert(0, str(DB_DIR))

from verify_schema import run_cli  # noqa: E402


def main() -> int:
    return run_cli(
        fixed_scope="mvp",
        require_tcp=False,
        query_error_exit_code=1,
        description=__doc__,
    )


if __name__ == "__main__":
    raise SystemExit(main())
