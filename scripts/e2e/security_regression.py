#!/usr/bin/env python3
"""TASK-903 安全回归（真实栈）。

覆盖：JWT 生命周期与篡改、IDOR/多租户、内部 Token 与 Nginx 边界、BYOK 加密与
日志脱敏、SSRF 硬拒绝、上传路径穿越/扩展名/Magic、共享卷只读与容器 env 白名单。

前置：整栈启动；模型探测需要 mock 模型（`--model-base-url`）与临时 env 白名单。
用法：
    sc-python-ai/.venv/bin/python scripts/e2e/security_regression.py \
        --base-url http://127.0.0.1 --model-base-url http://<mock-ip>:11434/v1
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
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
INTERNAL_TOKEN = env_value("SC_INTERNAL_SERVICE_TOKEN")


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


def mysql(sql: str) -> str:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={DB_PASSWORD}",
         "sc-mysql", "mysql", "--default-character-set=utf8mb4", "-N", "-B",
         "-uscholarclaw", "scholarclaw", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    return proc.stdout.strip()


def register(client: httpx.Client, prefix: str, password: str = "Passw0rd123"):
    username = f"{prefix}_{uuid.uuid4().hex[:8]}"
    status, body, text = api(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status not in (200, 201):
        raise RuntimeError(f"注册失败 {status} {text}")
    data = data_of(body)
    return data["access_token"], str(data["user_id"]), username, password


def create_course(client: httpx.Client, token: str, name: str) -> str:
    status, body, text = api(client, "POST", "/api/v1/courses", token,
                             json={"course_name": name})
    if status not in (200, 201):
        raise RuntimeError(f"建课失败 {status} {text}")
    return str(data_of(body)["course_id"])


def upload_doc(client: httpx.Client, token: str, course_id: str, filename: str = "sec.pdf",
               content: bytes = PDF_BYTES):
    return api(client, "POST", f"/api/v1/courses/{course_id}/docs/upload", token,
               files={"file": (filename, content, "application/pdf")})


def save_model(client: httpx.Client, token: str, **overrides):
    payload = {
        "chat_provider": "OPENAI_COMPATIBLE", "chat_base_url": MODEL_BASE,
        "chat_api_key": "sk-security-e2e", "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MODEL_BASE, "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed", "migrate_existing_courses": False,
    }
    payload.update(overrides)
    return api(client, "PUT", "/api/v1/user/model-config", token, json=payload)


def ensure_model(client: httpx.Client, token: str) -> None:
    import time
    deadline = time.time() + 30
    while True:
        status, body, text = save_model(client, token)
        if status == 200:
            return
        if status == 502 and time.time() < deadline:
            time.sleep(2)
            continue
        raise RuntimeError(f"模型配置失败 {status} {text}")


def main() -> int:
    global BASE, MODEL_BASE
    parser = argparse.ArgumentParser(description="TASK-903 security regression")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MODEL_BASE)
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MODEL_BASE = args.model_base_url.rstrip("/")
    client = httpx.Client(timeout=30.0, trust_env=False)

    # ---------- JWT ----------
    status, _, _ = api(client, "GET", "/api/v1/user/config")
    check("无 Token 访问配置 → 401", status == 401, status)
    status, _, _ = api(client, "GET", "/api/v1/user/config", "not-a-jwt")
    check("非法 Token → 401", status == 401, status)

    password = "Sec" + uuid.uuid4().hex[:10] + "!"
    token_a, uid_a, user_a, _ = register(client, "sec_a", password)
    token_b, uid_b, _, _ = register(client, "sec_b")
    ensure_model(client, token_a)
    ensure_model(client, token_b)
    tampered = token_a[:-2] + ("aa" if token_a[-2:] != "aa" else "bb")
    status, _, _ = api(client, "GET", "/api/v1/user/config", tampered)
    check("篡改 Token → 401", status == 401, status)

    status, body, _ = api(client, "POST", "/api/v1/auth/login",
                          json={"username": user_a, "password": "wrong"})
    check("错误密码登录 → 401", status == 401, status)
    status, body, _ = api(client, "POST", "/api/v1/auth/login",
                          json={"username": user_a, "password": password})
    check("正确密码登录成功", status == 200, status)
    token_login = data_of(body)["access_token"] if status == 200 else token_a

    # ---------- IDOR / 多租户 ----------
    course_a = create_course(client, token_a, f"安全A_{uuid.uuid4().hex[:6]}")
    status, body, _ = upload_doc(client, token_a, course_a)
    doc_a = str(data_of(body)["doc_id"]) if status in (200, 201, 202) else None
    status, body, text = api(client, "POST", "/api/v1/ddl/tasks", token_a, json={
        "client_request_id": "sec_" + uuid.uuid4().hex, "course_id": course_a,
        "course_name": "安全A", "task_title": "安全任务",
        "time_precision": "EXACT_DATETIME", "due_date": "2099-12-31",
        "due_time": "22:00", "deliverable_format": None, "submission_channel": None})
    task_a = str(data_of(body)["task_id"]) if status in (200, 201) else None
    check("A 创建课程/文档/任务", doc_a is not None and task_a is not None,
          (status, text))

    status, _, _ = api(client, "DELETE", f"/api/v1/courses/{course_a}", token_b)
    check("B 删除 A 课程 → 404", status == 404, status)
    status, _, _ = api(client, "GET", f"/api/v1/courses/{course_a}/docs", token_b)
    check("B 读取 A 课程文档 → 404", status == 404, status)
    status, _, _ = api(client, "DELETE", f"/api/v1/docs/{doc_a}", token_b)
    check("B 删除 A 文档 → 404", status == 404, status)
    status, _, _ = api(client, "DELETE", f"/api/v1/ddl/tasks/{task_a}", token_b)
    check("B 删除 A 任务 → 404", status == 404, status)
    status, body, _ = api(client, "GET", "/api/v1/courses", token_b)
    names = [c.get("course_name") for c in (data_of(body) or [])]
    check("B 课程列表不含 A 的课程", f"安全A" not in " ".join(names), names)
    status, body, text = upload_doc(client, token_b, course_a)
    check("B 向 A 课程上传文档 → 404", status == 404, (status, text))

    # ---------- Nginx / 内部 Token ----------
    status, _, _ = api(client, "GET", "/internal/api/v1/health")
    check("Nginx 外部访问 /internal → 404", status == 404, status)

    script = (
        "import os,urllib.request,json\n"
        "url='http://sc-java-backend:8080/internal/api/v1/rag/parent-chunks/batch-get'\n"
        "body=json.dumps({'user_id':'1','course_id':'1','course_index_version':1,"
        "'parent_chunk_ids':['1']}).encode()\n"
        "req=urllib.request.Request(url,data=body,method='POST',"
        "headers={'Content-Type':'application/json'})\n"
        "try:\n"
        " r=urllib.request.urlopen(req)\n"
        " print(r.status)\n"
        "except urllib.error.HTTPError as e:\n"
        " print(e.code)\n"
    )
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-python-ai", "python", "-c", script],
        cwd=ROOT, capture_output=True, text=True, check=False)
    check("内部接口无 Token → 403", proc.stdout.strip() == "403", (proc.stdout, proc.stderr))
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", "SC_TOKEN=" + INTERNAL_TOKEN,
         "sc-python-ai", "python", "-c",
         "import os\n" + script.replace(
             "headers={'Content-Type':'application/json'}",
             "headers={'Content-Type':'application/json','X-Internal-Token':os.environ['SC_TOKEN'],"
             "'X-User-Id':'1'}")],
        cwd=ROOT, capture_output=True, text=True, check=False)
    check("内部接口正确 Token 不被 403 拒绝", proc.stdout.strip() != "403", proc.stdout.strip())

    # ---------- BYOK 加密 / 掩码 / 日志 ----------
    secret = "sksec" + uuid.uuid4().hex
    status, body, text = save_model(client, token_a, chat_api_key=secret)
    check("保存 BYOK 配置成功", status == 200, (status, text))
    status, body, text = api(client, "GET", "/api/v1/user/config", token_a)
    check("配置响应不含明文 Key", secret not in text, "LEAK")
    check("配置仅返回掩码", bool(data_of(body).get("chat_api_key_masked")), data_of(body))
    cipher = mysql(f"SELECT chat_api_key_cipher FROM t_user_config WHERE user_id={uid_a};")
    check("数据库存密文且不等于明文", cipher != secret and cipher.startswith("v1:"),
          cipher[:8])

    logs = subprocess.run(["docker", "compose", "logs", "--since", "30m",
                           "sc-java-backend", "sc-python-ai"],
                          cwd=ROOT, capture_output=True, text=True, check=False).stdout
    check("日志不含明文 API Key", secret not in logs)
    check("日志不含登录密码", password not in logs)

    status, body, text = save_model(client, token_a, chat_api_key="",
                                    reuse_chat_api_key=True,
                                    chat_provider="DEEPSEEK")
    check("复用 Key 但 Provider 改变 → 400", status == 400, (status, text))
    status, body, text = api(client, "POST", "/api/v1/user/model-config/probe", token_a,
                             json={"probe_type": "CHAT", "provider": "DEEPSEEK",
                                   "base_url": MODEL_BASE, "use_saved_key": True,
                                   "model_name": "mock-chat"})
    check("探测复用 Key 但目标不同 → 400", status == 400, (status, text))

    # ---------- SSRF ----------
    for label, url in [
        ("云元数据", "http://169.254.169.254/v1"),
        ("回环", "http://127.0.0.1:8080/v1"),
        ("私网", "http://10.0.0.5/v1"),
        ("userinfo", "http://user:pass@example.com/v1"),
    ]:
        status, body, text = api(client, "POST", "/api/v1/user/model-config/probe",
                                 token_a, json={
                                     "probe_type": "CHAT",
                                     "provider": "OPENAI_COMPATIBLE",
                                     "base_url": url, "api_key": "sk-x",
                                     "use_saved_key": False,
                                     "model_name": "m"})
        check(f"SSRF {label} 被拒绝", status == 400, (status, text))

    # ---------- 上传安全 ----------
    # 使用唯一内容，避免命中同课程 SHA-256 查重（那是正确行为，不是上传安全问题）
    traversal_content = PDF_BYTES + b"%" + uuid.uuid4().hex.encode() + b"\n"
    status, body, text = upload_doc(client, token_a, course_a,
                                    filename="../../evil.pdf",
                                    content=traversal_content)
    doc_name = data_of(body).get("doc_name") if data_of(body) else None
    traversal_doc = str(data_of(body).get("doc_id")) if data_of(body) else None
    check("路径穿越文件名被净化", status in (200, 201, 202)
          and doc_name == "evil.pdf", (status, doc_name, text))
    status, body, text = upload_doc(client, token_a, course_a, filename="evil.exe")
    check("非白名单扩展名被拒绝", status == 400
          and body.get("code") == "FILE_TYPE_UNSUPPORTED", (status, text))
    status, body, text = upload_doc(client, token_a, course_a, filename="fake.pdf",
                                    content=b"not a pdf")
    check("Magic Number 不符被拒绝", status == 400
          and body.get("code") == "FILE_MAGIC_INVALID", (status, text))

    # ---------- 容器 env / 共享卷 ----------
    proc = subprocess.run([sys.executable, "scripts/verify_container_env.py"],
                          cwd=ROOT, capture_output=True, text=True, check=False)
    check("容器 env 白名单校验通过", proc.returncode == 0, proc.stdout[-400:])
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-python-ai", "sh", "-c",
         "touch /data/scholarclaw/storage/raw/sec_probe 2>/dev/null; echo $?"],
        cwd=ROOT, capture_output=True, text=True, check=False)
    check("Python 共享卷只读", proc.stdout.strip() != "0", proc.stdout.strip())
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-python-ai", "sh", "-c",
         "test -z \"$SC_MASTER_KEY\"; echo $?"],
        cwd=ROOT, capture_output=True, text=True, check=False)
    check("Python 未注入主密钥", proc.stdout.strip() == "0", proc.stdout.strip())

    # ---------- 登出吊销 ----------
    status, _, _ = api(client, "POST", "/api/v1/auth/logout", token_login)
    check("登出成功", status == 200, status)
    status, body, _ = api(client, "GET", "/api/v1/user/config", token_login)
    check("登出后旧 Token → 401", status == 401, status)

    # ---------- 清理 ----------
    api(client, "DELETE", f"/api/v1/docs/{doc_a}", token_a)
    if traversal_doc:
        api(client, "DELETE", f"/api/v1/docs/{traversal_doc}", token_a)
    api(client, "DELETE", f"/api/v1/courses/{course_a}", token_a)

    print(f"\nTASK-903 security regression: {len(FAILS)} failure(s)")
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
