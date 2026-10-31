#!/usr/bin/env python3
"""TASK-901 级联清理端到端验证（真实栈）。

覆盖：
1. 单文档删除：向量清理 -> 物理文件删除 -> 文档 `DELETED`；
2. 课程删除：课程行物理删除、文档 `DELETED`、课程 raw 目录消失；
3. 账号注销：JWT 立即失效、MySQL 各表、Redis 业务键、共享卷用户目录全部清零。

前置：整栈已启动；调用方在仓库根目录执行；`sc-java-backend` 可读取共享卷。
用法：
    sc-python-ai/.venv/bin/python scripts/e2e/cleanup_e2e.py --base-url http://127.0.0.1
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx

FAILS: list[str] = []
BASE = "http://127.0.0.1"
MODEL_BASE = "http://host.docker.internal:11434/v1"
ROOT = Path(__file__).resolve().parents[2]


def env_value(name: str) -> str:
    path = ROOT / ".env"
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith(name + "="):
            return line.split("=", 1)[1]
    raise RuntimeError(f".env 缺少 {name}")


DB_PASSWORD = env_value("SC_DB_PASSWORD")
REDIS_PASSWORD = env_value("SC_REDIS_PASSWORD")


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


def api(client: httpx.Client, method: str, path: str, token: str | None = None,
        **kw) -> tuple[int, dict, str]:
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = "Bearer " + token
    response = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text}
    return response.status_code, body, response.text


def data_of(body: dict):
    return body.get("data") if isinstance(body, dict) else None


def mysql(sql: str) -> list[str]:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={DB_PASSWORD}",
         "sc-mysql", "mysql", "--default-character-set=utf8mb4", "-N", "-B",
         "-uscholarclaw", "scholarclaw", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    return [line for line in proc.stdout.splitlines() if line != ""]


def redis_keys(pattern: str) -> list[str]:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-redis", "redis-cli",
         "-a", REDIS_PASSWORD, "--no-auth-warning", "--scan", "--pattern", pattern],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    return [line for line in proc.stdout.splitlines() if line]


def file_exists_in_java(path: str) -> bool:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-java-backend", "test", "-e", path],
        cwd=ROOT, capture_output=True, text=True, check=False)
    return proc.returncode == 0


def wait_for(fn, timeout: float = 30.0, interval: float = 0.5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(interval)
    return None


def register(client: httpx.Client, prefix: str) -> tuple[str, str, str]:
    suffix = uuid.uuid4().hex[:8]
    username = f"{prefix}_{suffix}"
    password = "Passw0rd123"
    status, body, text = api(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status not in (200, 201):
        raise RuntimeError(f"注册失败 {status} {text}")
    data = data_of(body)
    return data["access_token"], str(data["user_id"]), password


PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"trailer<</Root 1 0 R>>\n%%EOF\n"
)


def configure_models(client: httpx.Client, token: str) -> None:
    payload = {
        "chat_provider": "OPENAI_COMPATIBLE",
        "chat_base_url": MODEL_BASE,
        "chat_api_key": "sk-cleanup-e2e",
        "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MODEL_BASE,
        "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed",
        "migrate_existing_courses": False,
    }
    # Java/Python 容器刚重建时探针可能 502；等待服务就绪，避免把启动竞态误判为失败。
    deadline = time.time() + 30
    while True:
        status, body, text = api(client, "PUT", "/api/v1/user/model-config", token,
                                 json=payload)
        if status == 200:
            return
        if status == 502 and time.time() < deadline:
            time.sleep(2)
            continue
        raise RuntimeError(f"模型配置失败 {status} {text}")


def create_course(client: httpx.Client, token: str, name: str) -> str:
    status, body, text = api(client, "POST", "/api/v1/courses", token,
                             json={"course_name": name})
    if status not in (200, 201):
        raise RuntimeError(f"建课失败 {status} {text}")
    return str(data_of(body)["course_id"])


def upload_doc(client: httpx.Client, token: str, course_id: str) -> str:
    status, body, text = api(
        client, "POST", f"/api/v1/courses/{course_id}/docs/upload", token,
        files={"file": ("cleanup.pdf", PDF_BYTES, "application/pdf")})
    if status not in (200, 201, 202):
        raise RuntimeError(f"上传失败 {status} {text}")
    return str(data_of(body)["doc_id"])


def main() -> int:
    global BASE
    parser = argparse.ArgumentParser(description="TASK-901 cleanup E2E")
    global MODEL_BASE
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MODEL_BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MODEL_BASE = args.model_base_url.rstrip("/")
    client = httpx.Client(timeout=30.0, trust_env=False)

    # ---------- 1. 单文档删除 ----------
    token, uid, _ = register(client, "p9doc")
    configure_models(client, token)
    course = create_course(client, token, f"清理课程_{uuid.uuid4().hex[:6]}")
    doc = upload_doc(client, token, course)
    raw_path = f"/data/scholarclaw/storage/raw/{uid}/{course}/{doc}.pdf"
    check("上传后 raw 文件存在", file_exists_in_java(raw_path), raw_path)

    status, body, text = api(client, "DELETE", f"/api/v1/docs/{doc}", token)
    reached = wait_for(lambda: mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc} AND user_id={uid};")
        and mysql(f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc} "
                  f"AND user_id={uid};")[0] == "DELETED")
    check("删除文档返回 200", status == 200, (status, text))
    check("文档进入 DELETED 终态", bool(reached), mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc};"))
    check("文档 raw 文件已删除", not file_exists_in_java(raw_path), raw_path)

    # ---------- 2. 课程删除 ----------
    token_c, uid_c, _ = register(client, "p9course")
    configure_models(client, token_c)
    course_c_name = f"整课清理_{uuid.uuid4().hex[:6]}"
    course_c = create_course(client, token_c, course_c_name)
    doc_c = upload_doc(client, token_c, course_c)
    raw_dir = f"/data/scholarclaw/storage/raw/{uid_c}/{course_c}"
    check("课程 raw 目录存在", file_exists_in_java(raw_dir), raw_dir)
    status, body, text = api(client, "POST", "/api/v1/ddl/tasks", token_c, json={
        "client_request_id": "p9c_" + uuid.uuid4().hex,
        "course_id": course_c, "course_name": course_c_name,
        "task_title": "课程删除保留任务", "time_precision": "EXACT_DATETIME",
        "due_date": "2099-12-31", "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    task_c = str(data_of(body)["task_id"]) if status in (200, 201) else None
    check("课程删除前可创建关联任务", task_c is not None, (status, text))

    status, body, text = api(client, "DELETE", f"/api/v1/courses/{course_c}", token_c)
    check("删除课程返回 200", status == 200, (status, text))
    course_gone = wait_for(lambda: not mysql(
        f"SELECT course_id FROM t_course_namespace WHERE course_id={course_c};"))
    check("课程行已物理删除", bool(course_gone), mysql(
        f"SELECT course_id FROM t_course_namespace WHERE course_id={course_c};"))
    doc_deleted = wait_for(lambda: mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_c} "
        f"AND status='DELETED';"))
    check("课程下文档进入 DELETED", bool(doc_deleted), mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_c};"))
    check("课程 raw 目录已删除", not file_exists_in_java(raw_dir), raw_dir)
    task_row = mysql(f"SELECT course_id, course_name FROM t_ddl_task "
                     f"WHERE task_id={task_c} AND user_id={uid_c};") if task_c else []
    check("课程删除后 DDL 任务保留且已解绑",
          bool(task_row) and task_row[0].split("\t")[0] == "NULL",
          task_row)

    # ---------- 3. 账号注销 ----------
    token_u, uid_u, password_u = register(client, "p9user")
    configure_models(client, token_u)
    course_u = create_course(client, token_u, f"注销课程_{uuid.uuid4().hex[:6]}")
    upload_doc(client, token_u, course_u)
    status, body, text = api(client, "POST", "/api/v1/ddl/tasks", token_u, json={
        "client_request_id": "p9_" + uuid.uuid4().hex,
        "course_name": "注销课程", "task_title": "注销前任务",
        "time_precision": "EXACT_DATETIME", "due_date": "2099-12-31",
        "due_time": "22:00", "deliverable_format": None,
        "submission_channel": None})
    check("注销前可创建任务", status in (200, 201), (status, text))

    status, body, text = api(client, "POST", "/api/v1/user/account/deactivate", token_u,
                             json={"password": "wrong", "confirm_text": "CONFIRM_DEACTIVATE"})
    check("错误密码注销被拒绝", status == 401, (status, text))
    status, body, text = api(client, "POST", "/api/v1/user/account/deactivate", token_u,
                             json={"password": password_u, "confirm_text": "WRONG"})
    check("错误确认口令被拒绝", status == 400, (status, text))

    status, body, text = api(client, "POST", "/api/v1/user/account/deactivate", token_u,
                             json={"password": password_u,
                                   "confirm_text": "CONFIRM_DEACTIVATE"})
    check("注销请求返回 200", status == 200, (status, text))
    status, body, text = api(client, "GET", "/api/v1/user/config", token_u)
    check("原 JWT 立即失效", status == 401
          and (data_of(body) is None), (status, text))

    cleanup_done = wait_for(lambda: not mysql(
        f"SELECT user_id FROM t_user_config WHERE user_id={uid_u};"), timeout=45)
    check("用户行已物理删除", bool(cleanup_done), mysql(
        f"SELECT user_id FROM t_user_config WHERE user_id={uid_u};"))
    check("用户课程行清零", mysql(
        f"SELECT course_id FROM t_course_namespace WHERE user_id={uid_u};") == [],
        mysql(f"SELECT course_id FROM t_course_namespace WHERE user_id={uid_u};"))
    check("用户文档行清零", mysql(
        f"SELECT doc_id FROM t_knowledge_doc WHERE user_id={uid_u};") == [],
        mysql(f"SELECT doc_id FROM t_knowledge_doc WHERE user_id={uid_u};"))
    check("用户任务行清零", mysql(
        f"SELECT task_id FROM t_ddl_task WHERE user_id={uid_u};") == [],
        mysql(f"SELECT task_id FROM t_ddl_task WHERE user_id={uid_u};"))
    left_keys = [key for key in redis_keys(f"sc:u:{uid_u}:*")
                 if not key.endswith(":deactivated")]
    check("Redis 业务键已清理（仅保留注销阻断键）", left_keys == [], left_keys)
    check("用户 raw 目录已删除",
          not file_exists_in_java(f"/data/scholarclaw/storage/raw/{uid_u}"),
          f"/data/scholarclaw/storage/raw/{uid_u}")

    print(f"\nTASK-901 cleanup E2E: {len(FAILS)} failure(s)")
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
