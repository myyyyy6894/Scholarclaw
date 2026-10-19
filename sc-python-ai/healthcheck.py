#!/usr/bin/env python3
"""`sc-python-ai` 容器健康检查（Docker Compose `healthcheck` 入口）。

为什么单独写一个脚本，而不是把一长串命令塞进 `docker-compose.yml`：

1. **必须解析响应体**：`04` `API-CONF-03` 与第 7 章没有定义"依赖不健康"这类 HTTP 语义，
   健康接口恒返回 200 + `code = "OK"`，依赖状态只在 `data.status` 里。
   因此 `curl -fsS` 之类的"只看 HTTP 成功"等于只看进程存活，探不出依赖故障。
2. **双向调通要持续验证**：`02` §15.1 的阶段 1 准出条件要求
   「Java 与 Python 双向 `/health` 携带 `X-Internal-Token` 与 `X-Trace-Id` 调通」。
   本脚本在每次探活时真实调用 Java 的内部健康接口并校验追踪号回显，
   使这条准出条件不是一次性验收动作，而是每 15s 自动重验的常驻守卫。
3. **只用标准库**：健康检查不应依赖应用自身的依赖树（依赖装坏了就永远探不出问题）。

判定规则（刻意区分两个方向）：

* 自身：HTTP 200 + `code = "OK"` + `data.status = "UP"`（自身依赖：共享卷只读挂载、ChromaDB）。
* 对端 Java：HTTP 200 + `code = "OK"` + `trace_id` 与本次发出的追踪号完全一致
  （证明令牌被接受、跨语言追踪链未断）。
  **不要求** Java 的 `data.status == "UP"`：Java 自己的 MySQL/Redis/MQ 故障不该把 Python
  判成不健康——Python 不连这些依赖，误判会造成级联假告警。

输出只包含状态码与失败原因，**绝不**打印令牌或响应体全文。

退出码：0 = 健康，1 = 不健康。
"""

from __future__ import annotations

import json
import os
import secrets
import sys
import urllib.error
import urllib.request

SELF_HEALTH_URL = os.environ.get(
    "SC_HEALTHCHECK_SELF_URL", "http://127.0.0.1:8000/internal/ai/v1/health"
)
JAVA_HEALTH_URL = (
    os.environ.get("SC_JAVA_BACKEND_BASE_URL", "http://sc-java-backend:8080").rstrip("/")
    + "/internal/api/v1/health"
)
INTERNAL_TOKEN = os.environ.get("SC_INTERNAL_SERVICE_TOKEN", "")

SELF_TIMEOUT_SECONDS = 3.0
PEER_TIMEOUT_SECONDS = 5.0

TRACE_ID_HEADER = "X-Trace-Id"
INTERNAL_TOKEN_HEADER = "X-Internal-Token"

# 禁用代理：内部探针必须直连（与 app/core/health.py 里 httpx 的 trust_env=False 同一取向）。
# urllib 默认会读取进程的 HTTP_PROXY / ALL_PROXY，一旦容器环境里存在这些变量，
# 对 sc-java-backend 的探针就会被代理带偏，表现为"Java 明明活着却报不健康"。
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))


def fetch_json(url: str, headers: dict[str, str], timeout: float) -> tuple[int | None, dict | None, str]:
    """取回并解析 JSON；返回 (HTTP 状态码, 解析后的响应体, 失败原因)。"""
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw), ""
    except urllib.error.HTTPError as exc:
        # 只看状态码，不打印响应体（可能含内部信息）
        return exc.code, None, f"HTTP {exc.code}"
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        return None, None, type(exc).__name__


def check_self() -> str:
    """自身健康；返回空字符串表示通过，否则返回失败原因。"""
    status, body, failure = fetch_json(SELF_HEALTH_URL, {}, SELF_TIMEOUT_SECONDS)
    if status != 200 or body is None:
        return f"自身健康接口不可用（{failure or f'HTTP {status}'}）"
    if body.get("code") != "OK":
        return f"自身健康接口信封异常 code={body.get('code')!r}"
    reported = (body.get("data") or {}).get("status")
    if reported != "UP":
        dependencies = (body.get("data") or {}).get("dependencies")
        return f"自身依赖不可用 status={reported!r} dependencies={dependencies!r}"
    return ""


def check_java_peer() -> str:
    """Python → Java 方向；返回空字符串表示通过，否则返回失败原因。"""
    trace_id = "tr_" + secrets.token_hex(6)
    headers = {INTERNAL_TOKEN_HEADER: INTERNAL_TOKEN, TRACE_ID_HEADER: trace_id}
    status, body, failure = fetch_json(JAVA_HEALTH_URL, headers, PEER_TIMEOUT_SECONDS)
    if status != 200 or body is None:
        return f"调用 sc-java-backend 健康接口失败（{failure or f'HTTP {status}'}）"
    if body.get("code") != "OK":
        return f"sc-java-backend 健康接口信封异常 code={body.get('code')!r}"
    if body.get("trace_id") != trace_id:
        return "sc-java-backend 未按契约原样回显 X-Trace-Id（跨语言追踪链断裂）"
    return ""


def main() -> int:
    failures = [reason for reason in (check_self(), check_java_peer()) if reason]
    if failures:
        print("UNHEALTHY: " + "; ".join(failures))
        return 1
    print("HEALTHY: 自身依赖 UP，且与 sc-java-backend 内部健康接口双向调通")
    return 0


if __name__ == "__main__":
    sys.exit(main())
