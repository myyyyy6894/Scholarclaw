#!/usr/bin/env python3
"""TASK-902 GC 补偿端到端验证（真实栈，需短周期 GC env）。

模拟“第一阶段已提交、第二阶段中断”的残留：
1. `DELETING` 文档 -> GC 删向量/文件并置 `DELETED`；
2. `is_deleting=1` 课程 -> GC 清理课程资源并物理删课程行；
3. raw 孤儿文件 -> GC 删除；
4. `DEACTIVATING` 用户 -> GC 重放注销清理并删除用户行；
5. 有效文档文件不被误删。

用法（Java/Python 需以短周期 GC env 启动）：
    sc-python-ai/.venv/bin/python scripts/e2e/gc_e2e.py --base-url http://127.0.0.1 \
        --model-base-url http://<mock-ip>:11434/v1
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
PDF_BYTES = (
    b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
    b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
    b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
    b"trailer<</Root 1 0 R>>\n%%EOF\n"
)


def env_value(name: str) -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(f".env 缺少 {name}")


DB_PASSWORD = env_value("SC_DB_PASSWORD")
REDIS_PASSWORD = env_value("SC_REDIS_PASSWORD")


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


def api(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = "Bearer " + token
    response = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text}
    return response.status_code, body, response.text


def data_of(body):
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


def redis_set_deactivated(uid: str) -> None:
    subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-redis", "redis-cli", "-a",
         REDIS_PASSWORD, "--no-auth-warning", "SET", f"sc:u:{uid}:deactivated", "1",
         "EX", "604800"], cwd=ROOT, capture_output=True, text=True, check=False)


def java_exec(script: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-java-backend", "sh", "-lc", script],
        cwd=ROOT, capture_output=True, text=True, check=False)


def file_exists(path: str) -> bool:
    return java_exec(f"test -e '{path}'").returncode == 0


def wait_for(fn, timeout: float = 60.0, interval: float = 1.0):
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


def configure_models(client: httpx.Client, token: str) -> None:
    payload = {
        "chat_provider": "OPENAI_COMPATIBLE", "chat_base_url": MODEL_BASE,
        "chat_api_key": "sk-gc-e2e", "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MODEL_BASE, "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed", "migrate_existing_courses": False,
    }
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
        files={"file": ("gc.pdf", PDF_BYTES, "application/pdf")})
    if status not in (200, 201, 202):
        raise RuntimeError(f"上传失败 {status} {text}")
    return str(data_of(body)["doc_id"])


def create_task(client: httpx.Client, token: str, course_id: str, name: str) -> str:
    status, body, text = api(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "gc_" + uuid.uuid4().hex,
        "course_id": course_id, "course_name": name,
        "task_title": "GC 保留任务", "time_precision": "EXACT_DATETIME",
        "due_date": "2099-12-31", "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    if status not in (200, 201):
        raise RuntimeError(f"建任务失败 {status} {text}")
    return str(data_of(body)["task_id"])


def main() -> int:
    global BASE, MODEL_BASE
    parser = argparse.ArgumentParser(description="TASK-902 GC E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MODEL_BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MODEL_BASE = args.model_base_url.rstrip("/")
    client = httpx.Client(timeout=30.0, trust_env=False)

    # ---------- 1. 中断的文档删除 ----------
    token, uid, _ = register(client, "gc_doc")
    configure_models(client, token)
    course = create_course(client, token, f"GC文档_{uuid.uuid4().hex[:6]}")
    doc = upload_doc(client, token, course)
    raw = f"/data/scholarclaw/storage/raw/{uid}/{course}/{doc}.pdf"
    check("文档 raw 文件存在", file_exists(raw), raw)
    mysql(f"UPDATE t_knowledge_doc SET status='DELETING', updated_at=NOW(3) "
          f"- INTERVAL 10 MINUTE WHERE doc_id={doc};")
    finished = wait_for(lambda: mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc} AND status='DELETED';"))
    check("GC 完成中断的文档删除", bool(finished), mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc};"))
    check("GC 删除文档 raw 文件", not file_exists(raw), raw)

    # ---------- 2. 中断的课程删除 ----------
    token_c, uid_c, _ = register(client, "gc_course")
    configure_models(client, token_c)
    course_name = f"GC课程_{uuid.uuid4().hex[:6]}"
    course_c = create_course(client, token_c, course_name)
    doc_c = upload_doc(client, token_c, course_c)
    task_c = create_task(client, token_c, course_c, course_name)
    raw_dir = f"/data/scholarclaw/storage/raw/{uid_c}/{course_c}"
    check("课程 raw 目录存在", file_exists(raw_dir), raw_dir)
    mysql(f"UPDATE t_course_namespace SET is_deleting=1, updated_at=NOW(3) "
          f"- INTERVAL 10 MINUTE WHERE course_id={course_c};")
    mysql(f"UPDATE t_knowledge_doc SET status='DELETING', "
          f"parse_version=parse_version+1, updated_at=NOW(3) - INTERVAL 10 MINUTE "
          f"WHERE doc_id={doc_c};")
    mysql(f"UPDATE t_ddl_task SET course_id=NULL WHERE task_id={task_c};")
    course_gone = wait_for(lambda: not mysql(
        f"SELECT course_id FROM t_course_namespace WHERE course_id={course_c};"))
    check("GC 物理删除中断的课程行", bool(course_gone), mysql(
        f"SELECT course_id FROM t_course_namespace WHERE course_id={course_c};"))
    doc_done = wait_for(lambda: mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_c} AND status='DELETED';"))
    check("GC 完成课程下文档终态", bool(doc_done), mysql(
        f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_c};"))
    check("GC 删除课程 raw 目录", not file_exists(raw_dir), raw_dir)
    check("课程任务仍保留", mysql(
        f"SELECT task_id FROM t_ddl_task WHERE task_id={task_c};") != [],
        task_c)

    # ---------- 3. raw 孤儿文件 ----------
    orphan = f"/data/scholarclaw/storage/raw/{uid}/{course}/990001.pdf"
    java_exec(f"mkdir -p '/data/scholarclaw/storage/raw/{uid}/{course}' && "
              f"printf x > '{orphan}' && touch -d '2 hours ago' '{orphan}'")
    check("孤儿文件已创建", file_exists(orphan), orphan)
    orphan_gone = wait_for(lambda: not file_exists(orphan))
    check("GC 删除 raw 孤儿文件", bool(orphan_gone), orphan)

    # ---------- 4. 中断的账号注销 ----------
    token_u, uid_u, _ = register(client, "gc_user")
    configure_models(client, token_u)
    course_u = create_course(client, token_u, f"GC注销_{uuid.uuid4().hex[:6]}")
    upload_doc(client, token_u, course_u)
    create_task(client, token_u, course_u, "GC注销")
    redis_set_deactivated(uid_u)
    mysql(f"UPDATE t_user_config SET account_status='DEACTIVATING', "
          f"chat_api_key_cipher=NULL, embedding_api_key_cipher=NULL, "
          f"bound_im_platform=NULL, bound_im_uid=NULL, updated_at=NOW(3) "
          f"- INTERVAL 10 MINUTE WHERE user_id={uid_u};")
    user_gone = wait_for(lambda: not mysql(
        f"SELECT user_id FROM t_user_config WHERE user_id={uid_u};"), timeout=90)
    check("GC 重放并删除注销用户行", bool(user_gone), mysql(
        f"SELECT user_id FROM t_user_config WHERE user_id={uid_u};"))
    check("GC 注销用户数据清零", mysql(
        f"SELECT doc_id FROM t_knowledge_doc WHERE user_id={uid_u};") == []
        and mysql(f"SELECT task_id FROM t_ddl_task WHERE user_id={uid_u};") == []
        and mysql(f"SELECT course_id FROM t_course_namespace WHERE user_id={uid_u};") == [],
        uid_u)
    check("GC 删除注销用户 raw 目录",
          not file_exists(f"/data/scholarclaw/storage/raw/{uid_u}"), uid_u)

    # ---------- 5. 有效资源不被误删 ----------
    token_v, uid_v, _ = register(client, "gc_valid")
    configure_models(client, token_v)
    course_v = create_course(client, token_v, f"GC有效_{uuid.uuid4().hex[:6]}")
    doc_v = upload_doc(client, token_v, course_v)
    raw_v = f"/data/scholarclaw/storage/raw/{uid_v}/{course_v}/{doc_v}.pdf"
    time.sleep(8)
    check("有效文档未被 GC 误删", file_exists(raw_v)
          and mysql(f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_v};")[0]
          != "DELETED", raw_v)

    print(f"\nTASK-902 GC E2E: {len(FAILS)} failure(s)")
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
