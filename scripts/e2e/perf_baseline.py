#!/usr/bin/env python3
"""TASK-905 性能基线（真实栈 + mock 模型）。

测量（不是目标达成声明）：
1. 普通 API 延迟（100 次 GET /courses）；
2. OneBot Webhook ACK（NFR-PERF-01 口径：5 QPS × 100 条）；
3. RAG 30 次 SSE：wall TTFT、`retrieval_cost_ms`、`llm_first_token_ms`（mock 模型）；
4. 文档 enqueued_at→ready_at 延迟（3 份 20 页合成 PDF，样本很小）。

前置：整栈启动 + mock 模型（`--model-base-url`）与临时白名单 env；OneBot Token 从 `.env` 读取。
用法：
    sc-python-ai/.venv/bin/python scripts/e2e/perf_baseline.py \
        --base-url http://127.0.0.1 --model-base-url http://<mock-ip>:9000/v1
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from pathlib import Path
from urllib.parse import urlparse

import httpx

BASE = "http://127.0.0.1"
MODEL_BASE = "http://host.docker.internal:9000/v1"
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "e2e"))
from ingest_e2e import build_pdf  # noqa: E402


def env_value(name: str) -> str:
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(name + "="):
            return line.strip().split("=", 1)[1]
    raise RuntimeError(f".env 缺少 {name}")


DB_PASSWORD = env_value("SC_DB_PASSWORD")
ONEBOT_TOKEN = env_value("SC_ONEBOT_ACCESS_TOKEN")


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(p / 100 * (len(ordered) - 1)))))
    return ordered[index]


def stats(values: list[float]) -> dict:
    return {
        "n": len(values),
        "p50": round(pct(values, 50), 2),
        "p95": round(pct(values, 95), 2),
        "p99": round(pct(values, 99), 2),
        "max": round(max(values), 2) if values else None,
    }


def api(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = "Bearer " + token
    response = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text}
    return response.status_code, body


def data_of(body):
    return body.get("data") if isinstance(body, dict) else None


def register(client: httpx.Client, prefix: str):
    username = f"{prefix}_{uuid.uuid4().hex[:8]}"
    status, body = api(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": "Passw0rd123", "timezone": "Asia/Shanghai"})
    if status not in (200, 201):
        raise RuntimeError(f"注册失败 {status} {body}")
    data = data_of(body)
    return data["access_token"], str(data["user_id"]), username


def save_models(client: httpx.Client, token: str) -> None:
    payload = {
        "chat_provider": "OPENAI_COMPATIBLE", "chat_base_url": MODEL_BASE,
        "chat_api_key": "sk-perf", "chat_model": "chat-mock",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MODEL_BASE, "reuse_chat_api_key": True,
        "default_embedding_model": "embed-mock", "migrate_existing_courses": False,
    }
    deadline = time.time() + 30
    while True:
        status, body = api(client, "PUT", "/api/v1/user/model-config", token,
                           json=payload)
        if status == 200:
            return
        if status == 502 and time.time() < deadline:
            time.sleep(2)
            continue
        raise RuntimeError(f"模型配置失败 {status} {body}")


def create_course(client: httpx.Client, token: str, name: str) -> str:
    status, body = api(client, "POST", "/api/v1/courses", token,
                       json={"course_name": name})
    if status not in (200, 201):
        raise RuntimeError(f"建课失败 {status} {body}")
    return str(data_of(body)["course_id"])


def upload(client: httpx.Client, token: str, course_id: str, name: str, pages: list[str]):
    status, body = api(client, "POST", f"/api/v1/courses/{course_id}/docs/upload", token,
                       files={"file": (name, build_pdf(pages), "application/pdf")})
    if status not in (200, 201, 202):
        raise RuntimeError(f"上传失败 {status} {body}")
    return str(data_of(body)["doc_id"])


def mysql(sql: str) -> str:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={DB_PASSWORD}",
         "sc-mysql", "mysql", "--default-character-set=utf8mb4", "-N", "-B",
         "-uscholarclaw", "scholarclaw", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    return proc.stdout.strip()


def wait_ready(doc_id: str, timeout: float = 180) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = mysql(f"SELECT status FROM t_knowledge_doc WHERE doc_id={doc_id};")
        if status in ("READY", "FAILED"):
            return status
        time.sleep(1)
    return "TIMEOUT"


def sse_timing(token: str, course_id: str, query: str) -> dict:
    parsed = urlparse(BASE)
    connection = HTTPConnection(parsed.hostname, parsed.port or 80, timeout=120)
    body = json.dumps({"course_id": course_id, "query": query}).encode()
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream",
               "Authorization": f"Bearer {token}"}
    started = time.perf_counter()
    first_token = None
    done = None
    event = ""
    data_lines: list[str] = []
    try:
        connection.request("POST", "/api/v1/rag/qa/stream", body=body, headers=headers)
        response = connection.getresponse()
        if response.status != 200:
            return {"status": response.status, "wall_ttft_ms": None}
        while True:
            line = response.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").rstrip("\r\n")
            if text == "":
                if event and data_lines:
                    payload = "\n".join(data_lines)
                    if event == "token" and first_token is None:
                        first_token = (time.perf_counter() - started) * 1000
                    if event == "done":
                        done = json.loads(payload)
                event = ""
                data_lines = []
                continue
            if text.startswith("event:"):
                event = text[6:].strip()
            elif text.startswith("data:"):
                data_lines.append(text[5:].strip())
    finally:
        connection.close()
    return {
        "status": 200,
        "wall_ttft_ms": round(first_token, 2) if first_token else None,
        "retrieval_cost_ms": done.get("retrieval_cost_ms") if done else None,
        "llm_first_token_ms": done.get("llm_first_token_ms") if done else None,
        "total_ms": round((time.perf_counter() - started) * 1000, 2),
    }


def environment() -> dict:
    cpu = subprocess.run(["bash", "-lc",
                          "lscpu | awk -F: '/Model name/{print $2; exit}'"],
                         capture_output=True, text=True).stdout.strip()
    cores = subprocess.run(["nproc"], capture_output=True, text=True).stdout.strip()
    memory = subprocess.run(["bash", "-lc",
                             "free -g | awk '/Mem:/{print $2\"GiB total, \"$7\"GiB available\"}'"],
                            capture_output=True, text=True).stdout.strip()
    limits = subprocess.run(
        ["bash", "-lc",
         "docker inspect sc-java-backend sc-python-ai sc-mysql --format "
         "'{{.Name}} mem={{.HostConfig.Memory}} nano={{.HostConfig.NanoCpus}}'"],
        cwd=ROOT, capture_output=True, text=True).stdout.strip().splitlines()
    return {"cpu": cpu, "cores": cores, "memory": memory, "container_limits": limits}


def main() -> int:
    global BASE, MODEL_BASE
    parser = argparse.ArgumentParser(description="TASK-905 performance baseline")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MODEL_BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MODEL_BASE = args.model_base_url.rstrip("/")
    client = httpx.Client(timeout=120.0, trust_env=False)
    result: dict = {"environment": environment()}

    token, uid, username = register(client, "perf")
    save_models(client, token)
    course = create_course(client, token, f"perf_{uuid.uuid4().hex[:6]}")

    # 1. 普通 API
    api_lat = []
    for _ in range(100):
        started = time.perf_counter()
        status, _ = api(client, "GET", "/api/v1/courses", token)
        api_lat.append((time.perf_counter() - started) * 1000)
        if status != 200:
            raise RuntimeError(f"课程列表失败 {status}")
    result["api_courses_list"] = stats(api_lat)

    # 2. Webhook ACK（NFR-PERF-01 口径）
    fake_im = "9" + uid[-8:] + "0"
    status, body = api(client, "PUT", "/api/v1/user/preferences", token, json={
        "timezone": "Asia/Shanghai", "dnd_start_time": "23:30", "dnd_end_time": "07:30",
        "bound_im_platform": "QQ_ONEBOT", "bound_im_uid": fake_im})
    if status != 200:
        raise RuntimeError(f"绑定 IM 失败 {status} {body}")

    ack_lat: list[float] = []
    failures = 0
    lock = threading.Lock()

    def send(index: int) -> None:
        nonlocal failures
        target = start_time + index * 0.2
        delay = target - time.perf_counter()
        if delay > 0:
            time.sleep(delay)
        payload = {
            "post_type": "message", "message_type": "private", "sub_type": "friend",
            "message_id": int(time.time() * 1000) + index,
            "user_id": int(fake_im), "raw_message": "/list",
            "message": [{"type": "text", "data": {"text": "/list"}}],
            "time": int(time.time()),
        }
        began = time.perf_counter()
        try:
            response = client.post(BASE + "/webhooks/onebot/v11", json=payload,
                                   headers={"Authorization": "Bearer " + ONEBOT_TOKEN})
            elapsed = (time.perf_counter() - began) * 1000
            with lock:
                if response.status_code == 200:
                    ack_lat.append(elapsed)
                else:
                    failures += 1
        except Exception:
            with lock:
                failures += 1

    start_time = time.perf_counter()
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(send, range(100)))
    result["webhook_ack"] = {**stats(ack_lat), "failures": failures}

    # 3. RAG 检索/SSE（单课程 1 份约 40 页合成 PDF）
    pages = [f"Page {i}: two phase locking, isolation levels, and recovery "
             f"content for performance baseline page {i}." for i in range(1, 41)]
    doc = upload(client, token, course, "perf-rag.pdf", pages)
    ready = wait_ready(doc)
    result["rag_ingest_one_doc"] = {
        "status": ready,
        "latency_ms": mysql(
            f"SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, enqueued_at, ready_at)/1000, 1) "
            f"FROM t_knowledge_doc WHERE doc_id={doc};") if ready == "READY" else None,
    }
    rag_runs = [sse_timing(token, course, "第一页讲了什么？") for _ in range(30)]
    result["rag_sse"] = {
        "runs": len(rag_runs),
        "failures": sum(1 for run in rag_runs if run.get("status") != 200),
        "wall_ttft_ms": stats([r["wall_ttft_ms"] for r in rag_runs
                               if r.get("wall_ttft_ms") is not None]),
        "retrieval_cost_ms": stats([r["retrieval_cost_ms"] for r in rag_runs
                                    if r.get("retrieval_cost_ms") is not None]),
        "llm_first_token_ms": stats([r["llm_first_token_ms"] for r in rag_runs
                                     if r.get("llm_first_token_ms") is not None]),
    }

    # 4. 解析延迟（3 份 20 页合成 PDF；小样本）
    ingest_lat = []
    for index in range(3):
        doc_i = upload(client, token, course, f"perf-ingest-{index}.pdf",
                       [f"Perf ingest page {p} doc {index} with enough text to chunk."
                        for p in range(1, 21)])
        if wait_ready(doc_i) != "READY":
            continue
        value = mysql(
            f"SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, enqueued_at, ready_at)/1000, 1) "
            f"FROM t_knowledge_doc WHERE doc_id={doc_i};")
        ingest_lat.append(float(value))
    result["ingest_latency_ms"] = stats(ingest_lat)

    token_prefix = token[:12]
    result["account"] = {"username": username, "user_id": uid,
                         "token_prefix": token_prefix, "retained": True}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    Path("/tmp/perf_baseline.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
