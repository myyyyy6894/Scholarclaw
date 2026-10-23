#!/usr/bin/env python3
"""TASK-506 文档异步处理端到端联调（可复现）。

覆盖：上传真实文字版 PDF → MQ → 解析 → 父块入库 → Embedding → ChromaDB → READY；
扫描件失败、重复/过期消息幂等、服务中断恢复、依赖故障后的手动重试恢复、
单文档重建与删除后的向量清理。

前置（见 `scripts/e2e/README.md` §7）：
* 整栈已启动，解析消费者已开启（`SC_INGEST_CONSUMER_ENABLED=true`）；
* mock 模型容器 `sc-e2e-mock-model` 运行中且已加入本地白名单；
* 脚本只读数据库/ChromaDB 做断言，不修改业务状态（唯一例外是停止/启动测试容器）。

账号策略：脚本注册的临时账号保留，按 README 的手工 SQL 清理。
"""

from __future__ import annotations

import argparse
import base64
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FAKE_KEY = "sk-ingest-not-a-real-key"
MOCK_CONTAINER = "sc-e2e-mock-model"
PYTHON_SERVICE = "sc-python-ai"
EXCHANGE = "rag.ingest.exchange"
QUEUE = "rag.ingest.queue"


def call(base_url: str, method: str, path: str, *, token: str | None = None,
         body: dict | None = None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base_url + path, method=method, data=data)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + ("" if ok else f"（{detail}）"))
    return ok


def docker(*args: str, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["docker", "compose", *args], cwd=REPO_ROOT, text=True,
                          capture_output=True, timeout=timeout)


def mysql_query(sql: str) -> str:
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-mysql", "sh", "-lc",
         'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B'],
        cwd=REPO_ROOT, input=sql, text=True, capture_output=True, timeout=120,
    )
    if completed.returncode != 0:
        raise SystemExit(f"MySQL 读取失败：{completed.stderr.strip()}")
    return completed.stdout.strip()


def register_or_login(base_url: str, username: str, password: str) -> tuple[str, str]:
    status, payload = call(base_url, "POST", "/api/v1/auth/register", body={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status == 201:
        return payload["data"]["access_token"], payload["data"]["user_id"]
    status, payload = call(base_url, "POST", "/api/v1/auth/login", body={
        "username": username, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败：{status} {payload}")
    return payload["data"]["access_token"], payload["data"]["user_id"]


def save_models(base_url: str, token: str, mock_base_url: str,
                embedding_model: str = "embed-mock",
                migrate_existing_courses: bool = False) -> tuple[int, dict]:
    return call(base_url, "PUT", "/api/v1/user/model-config", token=token, body={
        "chat_provider": "OPENAI_COMPATIBLE",
        "chat_base_url": mock_base_url,
        "chat_api_key": FAKE_KEY,
        "chat_model": "chat-mock",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": mock_base_url,
        "embedding_api_key": FAKE_KEY,
        "reuse_chat_api_key": False,
        "default_embedding_model": embedding_model,
        "migrate_existing_courses": migrate_existing_courses,
    })


def mysql_session():
    """开启一个持久 MySQL 会话（用于构造真实 RR 并发窗口）。"""
    return subprocess.Popen(
        ["docker", "compose", "exec", "-T", "sc-mysql", "sh", "-lc",
         'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B'],
        cwd=REPO_ROOT, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL, text=True,
    )


def upload_file(base_url: str, token: str, course_id: str, filename: str,
                content: bytes, content_type: str = "application/pdf") -> tuple[int, dict]:
    boundary = "----scholarclaw-ingest-e2e"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/courses/{course_id}/docs/upload", data=body, method="POST")
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))


def build_pdf(pages: list[str]) -> bytes:
    """生成最小合法文字版 PDF（Helvetica，未压缩内容流），不依赖第三方库。"""
    objects: list[bytes] = []
    page_ids = [3 + index * 2 for index in range(len(pages))]
    font_id = 3 + len(pages) * 2
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>".encode())
    for page_id, text in zip(page_ids, pages):
        content_id = page_id + 1
        escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        stream = f"BT /F1 14 Tf 72 720 Td ({escaped}) Tj ET".encode()
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> "
            f"/Contents {content_id} 0 R >>".encode())
        objects.append(b"<< /Length " + str(len(stream)).encode()
                       + b" >>\nstream\n" + stream + b"\nendstream")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(len(output))
        output += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(output)
    output += f"xref\n0 {len(objects) + 1}\n".encode()
    output += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        output += f"{offset:010d} 00000 n \n".encode()
    output += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
               f"startxref\n{xref}\n%%EOF\n").encode()
    return bytes(output)


def scanned_pdf() -> bytes:
    """只有页面框架、没有文字操作符的 PDF：解析器应判定为扫描件。"""
    stream = b"q 0.5 w 72 720 m 540 720 l S Q"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, obj in enumerate(objects, start=1):
        offsets.append(len(output))
        output += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(output)
    output += f"xref\n0 {len(objects) + 1}\n".encode()
    output += b"0000000000 65535 f \n"
    for offset in offsets[1:]:
        output += f"{offset:010d} 00000 n \n".encode()
    output += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
               f"startxref\n{xref}\n%%EOF\n").encode()
    return bytes(output)


def _chroma_request(method: str, path: str, payload: dict | None = None) -> object:
    """通过内部网络临时容器访问 ChromaDB REST API，不依赖 Python 服务是否在运行。"""
    encoded = "" if payload is None else base64.b64encode(
        json.dumps(payload).encode()).decode()
    code = """
import base64, json, os, sys, urllib.error, urllib.request

data = base64.b64decode(os.environ["P_BODY"]) if os.environ.get("P_BODY") else None
request = urllib.request.Request(
    "http://sc-chromadb:8000" + os.environ["P_PATH"], data=data,
    method=os.environ["P_METHOD"])
if data is not None:
    request.add_header("Content-Type", "application/json")
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        print(response.read().decode())
except urllib.error.HTTPError as error:
    if error.code == 404:
        print("null")
    else:
        sys.stderr.write(error.read().decode("utf-8", "replace"))
        raise SystemExit(1)
"""
    completed = subprocess.run(
        ["docker", "run", "--rm", "--network", "scholarclaw-internal",
         "-e", f"P_METHOD={method}", "-e", f"P_PATH={path}", "-e", f"P_BODY={encoded}",
         "python:3.11-slim", "python3", "-c", code],
        text=True, capture_output=True, timeout=120,
    )
    if completed.returncode != 0:
        raise SystemExit(f"ChromaDB 请求失败：{completed.stderr.strip()}")
    return json.loads(completed.stdout)


def _collection_id(name: str) -> str | None:
    collections = _chroma_request("GET", "/api/v1/collections")
    for item in collections or []:
        if item.get("name") == name:
            return item.get("id")
    return None


def chroma_count(user_id: str, course_id: str, version: int,
                 doc_id: str | None = None, parse_version: int | None = None) -> int:
    name = f"sc_u{user_id}_c{course_id}_v{version}"
    collection_id = _collection_id(name)
    if collection_id is None:
        return 0
    conditions = []
    if doc_id is not None:
        conditions.append({"doc_id": int(doc_id)})
    if parse_version is not None:
        conditions.append({"parse_version": int(parse_version)})
    if len(conditions) == 1:
        where = conditions[0]
    elif conditions:
        where = {"$and": conditions}
    else:
        where = None
    payload = {"include": []}
    if where is not None:
        payload["where"] = where
    result = _chroma_request(
        "POST", f"/api/v1/collections/{collection_id}/get", payload)
    return len((result or {}).get("ids", []))


def chroma_collections(prefix: str) -> list[str]:
    collections = _chroma_request("GET", "/api/v1/collections")
    return [item.get("name", "") for item in collections or []
            if item.get("name", "").startswith(prefix)]


def post_internal(path: str, body: dict, user_id: str) -> tuple[int, dict]:
    """经内部网络临时容器调 Java 内部接口；不依赖 Python 服务是否在运行。"""
    token = docker("exec", "-T", "sc-java-backend", "sh", "-lc",
                   'printf %s "$SC_INTERNAL_SERVICE_TOKEN"').stdout.strip()
    if not token:
        raise SystemExit("无法读取内部服务令牌")
    encoded = base64.b64encode(json.dumps(body).encode()).decode()
    code = """
import base64, json, os, urllib.error, urllib.request

request = urllib.request.Request(
    "http://sc-java-backend:8080" + os.environ["MSG_PATH"],
    data=base64.b64decode(os.environ["MSG_BODY"]), method="POST")
request.add_header("Content-Type", "application/json")
request.add_header("X-Internal-Token", os.environ["MSG_TOKEN"])
request.add_header("X-User-Id", os.environ["MSG_USER"])
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        print(response.status)
        print(response.read().decode())
except urllib.error.HTTPError as error:
    print(error.code)
    print(error.read().decode("utf-8", "replace"))
"""
    completed = subprocess.run(
        ["docker", "run", "--rm", "-i", "--network", "scholarclaw-internal",
         "-e", f"MSG_BODY={encoded}", "-e", f"MSG_PATH={path}",
         "-e", f"MSG_USER={user_id}", "-e", f"MSG_TOKEN={token}",
         "python:3.11-slim", "python3", "-"],
        input=code, text=True, capture_output=True, timeout=120,
    )
    if completed.returncode != 0:
        raise SystemExit(f"内部接口调用失败：{completed.stderr.strip()}")
    lines = completed.stdout.strip().splitlines()
    return int(lines[0]), json.loads(lines[1])


def publish_message(payload: dict) -> None:
    encoded = base64.b64encode(json.dumps(payload).encode()).decode()
    code = """
import asyncio, base64, json, os
import aio_pika

async def main():
    connection = await aio_pika.connect_robust(
        host=os.environ['SC_RABBITMQ_HOST'],
        port=int(os.environ['SC_RABBITMQ_PORT']),
        login=os.environ['SC_RABBITMQ_USER'],
        password=os.environ['SC_RABBITMQ_PASSWORD'],
    )
    channel = await connection.channel()
    exchange = await channel.get_exchange(os.environ['MSG_EXCHANGE'])
    body = base64.b64decode(os.environ['MSG_BODY'])
    await exchange.publish(
        aio_pika.Message(body=body, delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                         content_type='application/json'),
        routing_key=os.environ['MSG_QUEUE'],
    )
    await connection.close()

asyncio.run(main())
"""
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"MSG_BODY={encoded}",
         "-e", f"MSG_EXCHANGE={EXCHANGE}", "-e", f"MSG_QUEUE={QUEUE}",
         PYTHON_SERVICE, "python3", "-"],
        cwd=REPO_ROOT, input=code, text=True, capture_output=True, timeout=120,
    )
    if completed.returncode != 0:
        raise SystemExit(f"MQ 发布失败：{completed.stderr.strip()}")


def get_doc(base_url: str, token: str, course_id: str, doc_id: str) -> dict:
    status, payload = call(base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    if status != 200:
        raise SystemExit(f"文档列表读取失败：{status} {payload}")
    return next((item for item in payload.get("data") or [] if item["doc_id"] == doc_id), {})


def wait_doc(base_url: str, token: str, course_id: str, doc_id: str,
             statuses: set[str], timeout: float) -> dict:
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        last = get_doc(base_url, token, course_id, doc_id)
        if last.get("status") in statuses:
            return last
        time.sleep(2)
    return last


def doc_row(doc_id: str) -> dict:
    raw = mysql_query(
        "SELECT doc_id, user_id, course_id, course_index_version, parse_version, "
        "doc_name, storage_rel_path, file_sha256, retry_count, last_ingest_job_id "
        f"FROM t_knowledge_doc WHERE doc_id={int(doc_id)};")
    if not raw:
        raise SystemExit(f"数据库缺少文档 {doc_id}")
    values = raw.split("\t")
    keys = ["doc_id", "user_id", "course_id", "course_index_version", "parse_version",
            "doc_name", "storage_rel_path", "file_sha256", "retry_count", "last_ingest_job_id"]
    return dict(zip(keys, values))


def ingest_message(row: dict, *, job_id: str, parse_version: int) -> dict:
    return {
        "ingest_job_id": job_id,
        "job_type": "INITIAL_INGEST",
        "trace_id": "tr_e2e_ingest",
        "user_id": row["user_id"],
        "course_id": row["course_id"],
        "course_index_version": int(row["course_index_version"]),
        "doc_id": row["doc_id"],
        "parse_version": parse_version,
        "doc_name": row["doc_name"],
        "storage_rel_path": row["storage_rel_path"],
        "file_sha256": row["file_sha256"],
        "retry_count": int(row["retry_count"] or 0),
        "enqueued_at": "2026-10-22T00:00:00.000+08:00",
    }


def ready_callback_body(row: dict, doc_id: str, *, parse_version: int,
                        course_version: int) -> dict:
    """构造一份历史版本的 READY 回调，用于验证迟到回调清理孤儿向量。"""
    return {
        "ingest_job_id": f"job_e2e_stale_{parse_version}",
        "user_id": row["user_id"],
        "course_id": row["course_id"],
        "doc_id": doc_id,
        "parse_version": parse_version,
        "course_index_version": course_version,
        "status": "READY",
        "indexed_embedding_provider": "OPENAI_COMPATIBLE",
        "indexed_embedding_model": "embed-mock",
        "indexed_embedding_dim": 8,
        "page_count": 1,
        "chunk_count": 1,
        "parent_chunks": [{
            "parent_chunk_id": f"pc_{doc_id}_v{parse_version}_1",
            "chunk_seq": 1,
            "page_start": 1,
            "page_end": 1,
            "section_title": None,
            "content": "stale worker output",
            "char_length": 20,
        }],
        "error_code": None,
        "error_msg": None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="TASK-506 文档异步处理端到端联调")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--mock-base-url", required=True,
                        help="mock 模型地址，必须是已加入白名单的私网 IP 字面量，"
                             "如 http://172.20.0.8:9000/v1")
    parser.add_argument("--ready-timeout", type=float, default=120,
                        help="单个文档等待 READY/FAILED 的秒数")
    args = parser.parse_args()

    suffix = str(int(time.time()))
    username = f"e2e_ingest_{suffix}"
    results: list[bool] = []
    created: list[str] = []

    token, user_id = register_or_login(args.base_url, username, "E2e-Ingest-Pass-123")
    status, payload = save_models(args.base_url, token, args.mock_base_url)
    results.append(check("保存 mock Embedding 配置", status == 200
                         and payload["data"]["default_embedding_dim"] == 8,
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "POST", "/api/v1/courses", token=token,
                           body={"course_name": f"异步联调 {suffix}", "description": "TASK-506"})
    course_id = (payload.get("data") or {}).get("course_id")
    results.append(check("创建课程", status == 201 and course_id is not None,
                         f"{status} {payload}"))
    if course_id is None:
        return 1

    # ---- 1. 真实文字版 PDF：上传 → 解析 → 父块 → 向量 → READY ----
    pdf = build_pdf([
        "Operating system chapter one: process scheduling and context switching.",
        "Chapter two covers virtual memory, paging and page replacement policies.",
        "Chapter three introduces file systems, inodes and journaling.",
    ])
    status, payload = upload_file(args.base_url, token, course_id, "os-notes.pdf", pdf)
    doc_a = (payload.get("data") or {}).get("doc_id")
    created.append(doc_a)
    results.append(check("上传文字版 PDF 进入 PARSING 且有任务 ID", status == 202
                         and (payload.get("data") or {}).get("status") == "PARSING"
                         and bool((payload.get("data") or {}).get("last_ingest_job_id")),
                         f"{status} {payload}"))
    ready = wait_doc(args.base_url, token, course_id, doc_a, {"READY", "FAILED"},
                     args.ready_timeout)
    results.append(check("真实 PDF 端到端 READY", ready.get("status") == "READY", str(ready)))
    results.append(check("READY 携带真实页数/块数与索引快照", ready.get("page_count") == 3
                         and (ready.get("chunk_count") or 0) >= 3
                         and ready.get("indexed_embedding_model") == "embed-mock"
                         and ready.get("indexed_embedding_dim") == 8
                         and ready.get("ready_at") is not None,
                         str(ready)))
    parents = int(mysql_query(
        f"SELECT COUNT(*) FROM t_knowledge_parent_chunk WHERE doc_id={int(doc_a)};") or 0)
    page_range = mysql_query(
        f"SELECT MIN(parse_version), MAX(parse_version), MIN(page_start), MAX(page_end) "
        f"FROM t_knowledge_parent_chunk WHERE doc_id={int(doc_a)};")
    results.append(check("父块已持久化且版本/页码一致", parents >= 1
                         and page_range == "1\t1\t1\t3", f"{parents} / {page_range}"))
    vectors = chroma_count(user_id, course_id, 1, doc_a)
    results.append(check("ChromaDB 子块向量数与 chunk_count 一致",
                         vectors == ready.get("chunk_count"), f"{vectors}"))
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    course = next((item for item in payload.get("data") or []
                   if item["course_id"] == course_id), {})
    results.append(check("课程统计与状态收敛为 ACTIVE",
                         course.get("ready_doc_count") == 1
                         and course.get("index_status") == "ACTIVE"
                         and course.get("parsing_doc_count") == 0,
                         str(course)))

    # ---- 2. 重复消息与过期消息：不得产生重复父块/向量或状态回退 ----
    row = doc_row(doc_a)
    publish_message(ingest_message(row, job_id=f"job_e2e_dup_{suffix}", parse_version=1))
    publish_message(ingest_message(row, job_id=f"job_e2e_dup2_{suffix}", parse_version=1))
    time.sleep(6)
    duplicate = get_doc(args.base_url, token, course_id, doc_a)
    parents_after = int(mysql_query(
        f"SELECT COUNT(*) FROM t_knowledge_parent_chunk WHERE doc_id={int(doc_a)};") or 0)
    vectors_after = chroma_count(user_id, course_id, 1, doc_a)
    results.append(check("重复消息不改变 READY 状态、父块与向量",
                         duplicate.get("status") == "READY"
                         and parents_after == parents and vectors_after == vectors,
                         f"{duplicate.get('status')} {parents_after}/{parents} "
                         f"{vectors_after}/{vectors}"))

    # ---- 2.1 真实迟到回调竞态：v2 解析期间 v1 回调必须清理孤儿向量 ----
    status, payload = call(args.base_url, "POST", f"/api/v1/docs/{doc_a}/reindex", token=token)
    reindexed = payload.get("data") or {}
    results.append(check("重建使 READY → PARSING 且 parse_version 递增", status == 200
                         and reindexed.get("status") == "PARSING"
                         and reindexed.get("parse_version") == 2, f"{status} {payload}"))
    stale_body = ready_callback_body(row, doc_a, parse_version=1, course_version=1)
    code, stale_payload = post_internal("/internal/api/v1/rag/ingest-result",
                                        stale_body, user_id)
    stale_data = stale_payload.get("data") or {}
    results.append(check("迟到的 v1 回调被拒绝并触发向量清理", code == 200
                         and stale_data.get("applied") is False
                         and stale_data.get("stale_vector_cleanup_triggered") is True,
                         f"{code} {stale_payload}"))
    time.sleep(3)
    stale_vectors = chroma_count(user_id, course_id, 1, doc_a, parse_version=1)
    results.append(check("孤儿 v1 向量已清空", stale_vectors == 0, str(stale_vectors)))
    rebuilt = wait_doc(args.base_url, token, course_id, doc_a, {"READY", "FAILED"},
                       args.ready_timeout)
    v2_vectors = chroma_count(user_id, course_id, 1, doc_a, parse_version=2)
    results.append(check("v2 重建完成且当前向量不受影响", rebuilt.get("status") == "READY"
                         and rebuilt.get("parse_version") == 2 and v2_vectors == vectors,
                         f"{rebuilt.get('status')} v{rebuilt.get('parse_version')} "
                         f"{v2_vectors}/{vectors}"))

    # ---- 2.3 真实 MySQL RR 窗口：读取 v1 后、UPDATE 前并发提交 v2 ----
    status, payload = upload_file(args.base_url, token, course_id, "rr-window.pdf",
                                  build_pdf(["RR window page with enough text to parse."]))
    doc_rr = (payload.get("data") or {}).get("doc_id")
    created.append(doc_rr)
    ready_rr = wait_doc(args.base_url, token, course_id, doc_rr, {"READY", "FAILED"},
                        args.ready_timeout)
    results.append(check("RR 竞态文档 READY", ready_rr.get("status") == "READY", str(ready_rr)))
    row_rr = doc_row(doc_rr)
    rr_parents = int(mysql_query(
        f"SELECT COUNT(*) FROM t_knowledge_parent_chunk WHERE doc_id={int(doc_rr)};") or 0)
    rr_vectors_before = chroma_count(user_id, course_id, 1, doc_rr, parse_version=1)
    mysql_query(f"UPDATE t_knowledge_doc SET status='PARSING' "
                f"WHERE doc_id={int(doc_rr)} AND parse_version=1;")
    session = mysql_session()
    race_result: dict = {}
    try:
        session.stdin.write("START TRANSACTION;\n")
        session.stdin.write(
            f"UPDATE t_knowledge_doc SET parse_version=2, status='PARSING', updated_at=NOW(3) "
            f"WHERE doc_id={int(doc_rr)} AND parse_version=1 AND status='PARSING';\n")
        session.stdin.flush()
        time.sleep(1)

        def invoke_late_callback() -> None:
            race_result["value"] = post_internal(
                "/internal/api/v1/rag/ingest-result",
                ready_callback_body(row_rr, doc_rr, parse_version=1, course_version=1),
                user_id)

        thread = threading.Thread(target=invoke_late_callback)
        thread.start()
        # 回调已读到 v1 并在 UPDATE 上等待行锁；此时提交 v2，制造真实 RR 竞态
        time.sleep(1.5)
        session.stdin.write("COMMIT;\n")
        session.stdin.flush()
        thread.join(timeout=30)
        code, race_payload = race_result.get("value", (0, {}))
        race_data = race_payload.get("data") or {}
        results.append(check("RR 竞态回调 applied=false 且登记清理", code == 200
                             and race_data.get("applied") is False
                             and race_data.get("stale_vector_cleanup_triggered") is True,
                             f"{code} {race_payload}"))
        deadline = time.monotonic() + 20
        rr_vectors_after = -1
        while time.monotonic() < deadline:
            rr_vectors_after = chroma_count(user_id, course_id, 1, doc_rr,
                                            parse_version=1)
            if rr_vectors_after == 0:
                break
            time.sleep(2)
        rr_state = mysql_query(
            f"SELECT status, parse_version FROM t_knowledge_doc WHERE doc_id={int(doc_rr)};")
        rr_parents_after = int(mysql_query(
            f"SELECT COUNT(*) FROM t_knowledge_parent_chunk WHERE doc_id={int(doc_rr)};") or 0)
        results.append(check("RR 竞态旧向量清理且当前状态/父块不受影响",
                             rr_vectors_before > 0 and rr_vectors_after == 0
                             and rr_state == "PARSING\t2" and rr_parents_after == rr_parents,
                             f"v1={rr_vectors_before}->{rr_vectors_after} state={rr_state} "
                             f"parents={rr_parents}->{rr_parents_after}"))
        call(args.base_url, "DELETE", f"/api/v1/docs/{doc_rr}", token=token)
    finally:
        if session.poll() is None:
            try:
                session.stdin.write("ROLLBACK;\n")
                session.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass
            session.terminate()

    # ---- 3. 解析失败：扫描件 PDF 有明确状态与错误码 ----
    status, payload = upload_file(args.base_url, token, course_id, "scan.pdf", scanned_pdf())
    doc_scan = (payload.get("data") or {}).get("doc_id")
    created.append(doc_scan)
    failed = wait_doc(args.base_url, token, course_id, doc_scan, {"READY", "FAILED"},
                      args.ready_timeout)
    results.append(check("扫描件进入 FAILED 且错误码明确", failed.get("status") == "FAILED"
                         and failed.get("error_code") == "E-RAG-01",
                         str(failed)))
    results.append(check("失败文档不暴露索引快照", failed.get("page_count") == 0
                         and failed.get("chunk_count") == 0, str(failed)))

    # ---- 4. 服务中断恢复：停消费者时上传保持 PARSING，恢复后自动 READY ----
    stopped = docker("stop", PYTHON_SERVICE)
    results.append(check("停止 Python 消费者", stopped.returncode == 0, stopped.stderr))
    md = ("# 数据库事务\n\nACID 指原子性、一致性、隔离性与持久性。\n\n"
          "## 隔离级别\n\n读已提交与可重复读是最常用的两种隔离级别。\n")
    status, payload = upload_file(args.base_url, token, course_id, "transaction.md",
                                  md.encode(), "text/markdown")
    doc_b = (payload.get("data") or {}).get("doc_id")
    created.append(doc_b)
    results.append(check("中断期间上传成功并入队", status == 202
                         and (payload.get("data") or {}).get("status") == "PARSING",
                         f"{status} {payload}"))
    time.sleep(4)
    pending = get_doc(args.base_url, token, course_id, doc_b)
    results.append(check("消费者离线时文档保持 PARSING", pending.get("status") == "PARSING",
                         str(pending)))
    started = docker("start", PYTHON_SERVICE)
    results.append(check("重新启动 Python 服务", started.returncode == 0, started.stderr))
    recovered = wait_doc(args.base_url, token, course_id, doc_b, {"READY", "FAILED"},
                         args.ready_timeout)
    results.append(check("消费者恢复后自动完成 READY", recovered.get("status") == "READY",
                         str(recovered)))

    # ---- 5. 依赖故障 → FAILED → 恢复依赖后手动重试 → READY ----
    stopped = subprocess.run(["docker", "stop", MOCK_CONTAINER], text=True,
                             capture_output=True, timeout=120)
    results.append(check("停止 mock 模型服务", stopped.returncode == 0, stopped.stderr))
    status, payload = upload_file(args.base_url, token, course_id, "recovery.pdf",
                                  build_pdf(["Recovery scenario page with enough text to parse."]))
    doc_c = (payload.get("data") or {}).get("doc_id")
    created.append(doc_c)
    broken = wait_doc(args.base_url, token, course_id, doc_c, {"READY", "FAILED"},
                      args.ready_timeout)
    results.append(check("依赖不可用时文档明确失败", broken.get("status") == "FAILED"
                         and broken.get("error_code") is not None, str(broken)))
    started = subprocess.run(["docker", "start", MOCK_CONTAINER], text=True,
                             capture_output=True, timeout=120)
    results.append(check("恢复 mock 模型服务", started.returncode == 0, started.stderr))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        probe = call(args.base_url, "GET", "/api/v1/user/config", token=token)
        if probe[0] == 200:
            break
        time.sleep(1)
    status, payload = call(args.base_url, "POST", f"/api/v1/docs/{doc_c}/retry", token=token)
    results.append(check("失败文档可手动重试", status == 200
                         and (payload.get("data") or {}).get("status") == "PARSING",
                         f"{status} {payload}"))
    retried = wait_doc(args.base_url, token, course_id, doc_c, {"READY", "FAILED"},
                       args.ready_timeout)
    results.append(check("重试后恢复为 READY", retried.get("status") == "READY", str(retried)))

    # ---- 6. PARSING 僵尸巡检：超时未完成的任务必须递增版本/重试次数并补投 ----
    stopped = docker("stop", PYTHON_SERVICE)
    results.append(check("巡检测试停止消费者", stopped.returncode == 0, stopped.stderr))
    status, payload = upload_file(args.base_url, token, course_id, "zombie.pdf",
                                  build_pdf(["Zombie task page with enough text to parse."]))
    doc_z = (payload.get("data") or {}).get("doc_id")
    created.append(doc_z)
    results.append(check("上传后保持 PARSING 等待巡检", status == 202
                         and (payload.get("data") or {}).get("status") == "PARSING",
                         f"{status} {payload}"))
    code, cross_user = post_internal(
        f"/internal/api/v1/docs/{doc_z}/retry-attempt?parse_version=1", {},
        str(int(user_id) + 1))
    results.append(check("内部重试计数按用户隔离（跨用户不生效）",
                         code == 200 and cross_user.get("data") is False,
                         f"{code} {cross_user}"))
    deadline = time.monotonic() + 180
    zombie_state = ""
    while time.monotonic() < deadline:
        zombie_state = mysql_query(
            "SELECT status, parse_version, retry_count, "
            "IF(enqueued_at IS NULL, '0', '1') "
            f"FROM t_knowledge_doc WHERE doc_id={int(doc_z)};")
        if zombie_state.startswith("PARSING\t2\t1\t1"):
            break
        time.sleep(5)
    results.append(check("巡检递增 parse_version/retry_count 并补投",
                         zombie_state.startswith("PARSING\t2\t1\t1"), zombie_state))
    started = docker("start", PYTHON_SERVICE)
    results.append(check("恢复消费者处理巡检任务", started.returncode == 0, started.stderr))
    # Python 启动后 Java 侧连接需要几秒才稳定；避免把清理请求打到启动中的容器
    time.sleep(8)
    zombie_ready = wait_doc(args.base_url, token, course_id, doc_z, {"READY", "FAILED"},
                            args.ready_timeout)
    results.append(check("巡检补投后完成 READY", zombie_ready.get("status") == "READY",
                         str(zombie_ready)))

    # ---- 7. 删除后迟到回调：向量不得复活 ----
    row_b = doc_row(doc_b)
    status, _ = call(args.base_url, "DELETE", f"/api/v1/docs/{doc_b}", token=token)
    time.sleep(1)
    code, late_payload = post_internal("/internal/api/v1/rag/ingest-result",
                                       ready_callback_body(row_b, doc_b,
                                                           parse_version=1,
                                                           course_version=1),
                                       user_id)
    late_data = late_payload.get("data") or {}
    results.append(check("删除中文档的迟到回调触发清理", status == 200 and code == 200
                         and late_data.get("applied") is False
                         and late_data.get("stale_vector_cleanup_triggered") is True,
                         f"{status} {code} {late_payload}"))
    deadline = time.monotonic() + 20
    deleted_vectors = -1
    while time.monotonic() < deadline:
        deleted_vectors = chroma_count(user_id, course_id, 1, doc_b)
        if deleted_vectors == 0:
            break
        time.sleep(2)
    results.append(check("迟到回调后该文档向量仍为 0", deleted_vectors == 0,
                         str(deleted_vectors)))

    # ---- 8. 删除文档后向量同步清理（TASK-505 身份透传回归）----
    if doc_a is not None and doc_scan is not None:
        call(args.base_url, "DELETE", f"/api/v1/docs/{doc_scan}", token=token)
        before_delete = chroma_count(user_id, course_id, 1, doc_a)
        status, _ = call(args.base_url, "DELETE", f"/api/v1/docs/{doc_a}", token=token)
        time.sleep(2)
        remaining = chroma_count(user_id, course_id, 1, doc_a)
        results.append(check("删除文档后其向量被清理",
                             status == 200 and before_delete > 0 and remaining == 0,
                             f"{status} {before_delete} → {remaining}"))

    # ---- 9. 删除课程：全部版本 Collection 必须删除 ----
    collection_prefix = f"sc_u{user_id}_c{course_id}_v"
    before_collections = chroma_collections(collection_prefix)
    status, _ = call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}", token=token)
    time.sleep(4)
    after_collections = chroma_collections(collection_prefix)
    results.append(check("删除课程后所有版本 Collection 已清理", status == 200
                         and before_collections and not after_collections,
                         f"{before_collections} → {after_collections}"))

    # ---- 清理：删除测试课程（账号按约定保留）----
    call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}", token=token)
    # ---- 10. 默认 Embedding 变更：未确认拒绝覆盖，确认后批量迁移并重建 ----
    status, payload = call(args.base_url, "POST", "/api/v1/courses", token=token,
                           body={"course_name": f"迁移测试 {suffix}"})
    course2 = (payload.get("data") or {}).get("course_id")
    status, payload = upload_file(args.base_url, token, course2, "migrate.pdf",
                                  build_pdf(["Migration page with enough text to parse."]))
    doc_m = (payload.get("data") or {}).get("doc_id")
    ready_m = wait_doc(args.base_url, token, course2, doc_m, {"READY", "FAILED"},
                       args.ready_timeout)
    results.append(check("迁移测试课程文档 READY", ready_m.get("status") == "READY",
                         str(ready_m)))
    rejected, rejected_payload = save_models(args.base_url, token, args.mock_base_url,
                                             embedding_model="embed-mock-v2")
    results.append(check("未确认迁移时拒绝覆盖默认 Key", rejected == 400
                         and rejected_payload.get("code") == "VALIDATION_ERROR",
                         f"{rejected} {rejected_payload}"))
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    still_old = next((item for item in payload.get("data") or []
                      if item["course_id"] == course2), {})
    results.append(check("拒绝后课程仍锁定旧模型", still_old.get("embedding_model") == "embed-mock"
                         and still_old.get("index_version") == 1, str(still_old)))
    accepted, accepted_payload = save_models(args.base_url, token, args.mock_base_url,
                                             embedding_model="embed-mock-v2",
                                             migrate_existing_courses=True)
    results.append(check("确认迁移后保存成功", accepted == 200, f"{accepted} {accepted_payload}"))
    migrated_doc = wait_doc(args.base_url, token, course2, doc_m, {"READY", "FAILED"},
                            args.ready_timeout)
    results.append(check("迁移后文档按新模型重建 READY",
                         migrated_doc.get("status") == "READY"
                         and migrated_doc.get("parse_version") == 2
                         and migrated_doc.get("indexed_embedding_model") == "embed-mock-v2",
                         str(migrated_doc)))
    deadline = time.monotonic() + 60
    course2_state = {}
    while time.monotonic() < deadline:
        status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
        course2_state = next((item for item in payload.get("data") or []
                              if item["course_id"] == course2), {})
        if (course2_state.get("index_version") == 2
                and course2_state.get("index_status") == "ACTIVE"):
            break
        time.sleep(3)
    results.append(check("课程版本升级并收敛 ACTIVE",
                         course2_state.get("index_version") == 2
                         and course2_state.get("index_status") == "ACTIVE"
                         and course2_state.get("embedding_model") == "embed-mock-v2",
                         str(course2_state)))
    v2_count = chroma_count(user_id, course2, 2, doc_m, parse_version=2)
    v1_collections = chroma_collections(f"sc_u{user_id}_c{course2}_v1")
    results.append(check("新版本向量写入且旧 Collection 已删除",
                         v2_count > 0 and not v1_collections,
                         f"{v2_count} / {v1_collections}"))
    mismatch = mysql_query(
        "SELECT COUNT(*) FROM t_course_namespace WHERE "
        f"user_id={int(user_id)} AND is_deleting=0 AND "
        "(embedding_provider <> 'OPENAI_COMPATIBLE' OR "
        "embedding_model <> 'embed-mock-v2' OR embedding_dim <> 8);")
    user_default = mysql_query(
        "SELECT default_embedding_model FROM t_user_config "
        f"WHERE user_id={int(user_id)};")
    results.append(check("迁移后活动课程目标与用户当前 Key 目标一致",
                         mismatch == "0" and user_default == "embed-mock-v2",
                         f"mismatch={mismatch} default={user_default}"))
    call(args.base_url, "DELETE", f"/api/v1/courses/{course2}", token=token)

    print(f"\n账号 {username} 已保留（按 README §5 手工清理）。")
    print(f"通过 {sum(results)}/{len(results)} 项")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
