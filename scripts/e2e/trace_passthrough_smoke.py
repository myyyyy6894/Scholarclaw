#!/usr/bin/env python3
"""Java → Python 追踪透传冒烟（`02` §11、`04` §2.7.1 / §6.2）。

验证链路：脚本（Python 容器内）→ Java `/internal/api/v1/health` → Python
`/internal/ai/v1/health`。Java 在健康检查中调用 `PythonAiHealthProbe`，会：

1. 把当前 `trace_id`（本脚本注入的 `tr_cron_*`）经 `X-Trace-Id` 发给 Python；
2. 比对 Python 响应体中的 `trace_id` 是否原样回显；
3. 回显不一致时把 `peers.python_ai` 判为 `DOWN`。

因此本脚本同时断言响应头、响应体和两侧日志中的 trace 完全一致。

前置：`docker compose up -d` 且 `sc-java-backend` / `sc-python-ai` 已运行。
不读取、不打印 `.env` 中的任何密钥；令牌只在容器内部按环境变量使用。
"""

from __future__ import annotations

import argparse
import json
import re
import secrets
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TRACE_ID_PATTERN = re.compile(r"^tr_cron_[0-9a-f]{12}$")

# 在 sc-python-ai 容器内执行：调用 Java 健康接口，输出状态、响应头与响应体。
INNER_SCRIPT = r"""
import json
import os
import urllib.error
import urllib.request

token = os.environ["SC_INTERNAL_SERVICE_TOKEN"]
trace_id = os.environ["SC_E2E_TRACE_ID"]
request = urllib.request.Request(
    "http://sc-java-backend:8080/internal/api/v1/health",
    headers={"X-Internal-Token": token, "X-Trace-Id": trace_id},
)
try:
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read().decode("utf-8")
        result = {
            "status": response.status,
            "header_trace_id": response.headers.get("X-Trace-Id"),
            "body": body,
        }
except urllib.error.HTTPError as error:
    result = {
        "status": error.code,
        "header_trace_id": error.headers.get("X-Trace-Id"),
        "body": error.read().decode("utf-8", "replace"),
    }
print(json.dumps(result))
"""


def _run(command: list[str], *, input_text: str | None = None) -> str:
    completed = subprocess.run(
        command,
        input=input_text,
        text=True,
        capture_output=True,
        cwd=REPO_ROOT,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stdout)
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"命令失败：{' '.join(command)}")
    return completed.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description="验证 Java → Python tr_* / tr_cron_* 追踪透传")
    parser.add_argument("--skip-logs", action="store_true", help="跳过两侧容器日志核对")
    args = parser.parse_args()

    trace_id = f"tr_cron_{secrets.token_hex(6)}"
    assert TRACE_ID_PATTERN.match(trace_id)

    output = _run(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "-e",
            f"SC_E2E_TRACE_ID={trace_id}",
            "sc-python-ai",
            "python3",
            "-",
        ],
        input_text=INNER_SCRIPT,
    )
    result = json.loads(output.strip().splitlines()[-1])
    body = json.loads(result["body"])

    checks: list[tuple[str, bool, str]] = [
        ("HTTP 200", result["status"] == 200, str(result["status"])),
        ("响应头 X-Trace-Id 原样回显", result["header_trace_id"] == trace_id, str(result["header_trace_id"])),
        ("响应体 trace_id 原样回显", body.get("trace_id") == trace_id, str(body.get("trace_id"))),
        (
            "Java → Python 健康探针判定 UP（回显一致）",
            body.get("data", {}).get("peers", {}).get("python_ai") == "UP",
            str(body.get("data", {}).get("peers", {})),
        ),
    ]

    if not args.skip_logs:
        java_logs = _run(
            ["docker", "compose", "logs", "--since", "3m", "--no-color", "sc-java-backend"]
        )
        python_logs = _run(
            ["docker", "compose", "logs", "--since", "3m", "--no-color", "sc-python-ai"]
        )
        checks.append(("Java 日志出现同一 trace", f"[trace_id={trace_id}]" in java_logs,
                       "found" if f"[trace_id={trace_id}]" in java_logs else "missing"))
        checks.append(("Python 日志出现同一 trace", f"[trace_id={trace_id}]" in python_logs,
                       "found" if f"[trace_id={trace_id}]" in python_logs else "missing"))

    failed = [name for name, ok, _ in checks if not ok]
    for name, ok, actual in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + ("" if ok else f"（实测 {actual}）"))
    print(f"trace_id={trace_id}，{len(checks) - len(failed)}/{len(checks)} 项通过")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
