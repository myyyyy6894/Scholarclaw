#!/usr/bin/env python3
"""兼容入口：委托 ``verify_schema.py --scope schedule`` 核对 V1.0.9–V1.0.11 课表结构。"""

from __future__ import annotations

import sys
from pathlib import Path

DB_DIR = Path(__file__).resolve().parent
if str(DB_DIR) not in sys.path:
    sys.path.insert(0, str(DB_DIR))

from verify_schema import run_cli  # noqa: E402


def main() -> int:
    return run_cli(
        fixed_scope="schedule",
        require_tcp=True,
        query_error_exit_code=2,
        description=__doc__,
    )


if __name__ == "__main__":
    raise SystemExit(main())
