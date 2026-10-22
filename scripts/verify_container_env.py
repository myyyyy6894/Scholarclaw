#!/usr/bin/env python3
"""核对运行容器的环境变量是否与文档一致（不打印变量值，只报缺失/越界）。

用法：
    python3 scripts/verify_container_env.py

检查项：
* Java 容器必须收到文档声明的全部配置键（含模型探测超时）；
* Python 容器必须收到自己的配置键，且**绝不能**出现主密钥、数据库/Redis 凭据或 JWT 密钥；
* 任一缺失或越界即退出码 1。
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
COMPOSE = REPO / "docker-compose.yml"

JAVA_REQUIRED = {
    "TZ", "SC_TIMEZONE", "SC_LOG_LEVEL",
    "SC_MASTER_KEY", "SC_JWT_SECRET",
    "SC_DB_HOST", "SC_DB_PORT", "SC_DB_NAME", "SC_DB_USER", "SC_DB_PASSWORD",
    "SC_REDIS_HOST", "SC_REDIS_PORT", "SC_REDIS_PASSWORD",
    "SC_RABBITMQ_HOST", "SC_RABBITMQ_PORT", "SC_RABBITMQ_USER", "SC_RABBITMQ_PASSWORD",
    "SC_INTERNAL_SERVICE_TOKEN",
    "SC_JWT_TTL_SECONDS", "SC_DB_MIGRATION_ENABLED", "SC_DB_POOL_PROBE", "SC_REDIS_POOL_PROBE",
    "SC_DEPENDENCY_PROBE_TIMEOUT_MILLIS",
    "SC_MODEL_CONNECT_TIMEOUT_MILLIS", "SC_MODEL_READ_TIMEOUT_MILLIS",
    "SC_PYTHON_AI_BASE_URL",
    "SC_ALLOW_LOCAL_MODEL_BASE_URL", "SC_LOCAL_MODEL_ALLOWED_ORIGINS",
    "SC_IM_PUSH_ENABLED", "SC_ONEBOT_ACCESS_TOKEN",
    "SC_MANUAL_INGEST_ENABLED", "SC_UPLOAD_RECOVERY_ENABLED",
    "SC_MQ_INGEST_ENABLED", "SC_MQ_INGEST_EXCHANGE", "SC_MQ_INGEST_QUEUE",
    "SC_MQ_INGEST_DLQ", "SC_ZOMBIE_SCAN_ENABLED",
}

PYTHON_REQUIRED = {
    "TZ", "SC_TIMEZONE", "SC_LOG_LEVEL",
    "SC_INTERNAL_SERVICE_TOKEN",
    "SC_RABBITMQ_HOST", "SC_RABBITMQ_PORT", "SC_RABBITMQ_USER", "SC_RABBITMQ_PASSWORD",
    "SC_ALLOW_LOCAL_MODEL_BASE_URL", "SC_LOCAL_MODEL_ALLOWED_ORIGINS",
    "SC_MODEL_CONNECT_TIMEOUT_SECONDS", "SC_MODEL_READ_TIMEOUT_SECONDS",
    "SC_STORAGE_RAW_PATH", "SC_CHROMADB_BASE_URL", "SC_JAVA_BACKEND_BASE_URL",
    "SC_RABBITMQ_HOST", "SC_RABBITMQ_PORT", "SC_RABBITMQ_USER", "SC_RABBITMQ_PASSWORD",
    "SC_MQ_INGEST_QUEUE", "SC_MQ_INGEST_DLQ", "SC_INGEST_CONSUMER_ENABLED",
    "SC_INGEST_MAX_PAGES",
}

PYTHON_FORBIDDEN = {
    "SC_MASTER_KEY", "SC_JWT_SECRET",
    "SC_DB_HOST", "SC_DB_PORT", "SC_DB_NAME", "SC_DB_USER",
    "SC_DB_PASSWORD", "SC_DB_ROOT_PASSWORD",
    "SC_REDIS_HOST", "SC_REDIS_PORT", "SC_REDIS_PASSWORD",
}


def container_env(service: str) -> set[str]:
    result = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "exec", "-T", service, "env"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        print(f"无法读取 {service} 的环境变量：{result.stderr.strip()}", file=sys.stderr)
        sys.exit(1)
    return {line.split("=", 1)[0] for line in result.stdout.splitlines() if "=" in line}


def check(service: str, required: set[str], forbidden: set[str] = frozenset()) -> list[str]:
    present = container_env(service)
    problems = [f"{service}: 缺少 {key}" for key in sorted(required - present)]
    problems += [f"{service}: 不应出现 {key}" for key in sorted(forbidden & present)]
    return problems


def main() -> int:
    problems = check("sc-java-backend", JAVA_REQUIRED)
    problems += check("sc-python-ai", PYTHON_REQUIRED, PYTHON_FORBIDDEN)
    if problems:
        print("环境变量核对失败：")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("环境变量核对通过：Java / Python 容器与文档一致，Python 未持有任何主密钥或数据库凭据。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
