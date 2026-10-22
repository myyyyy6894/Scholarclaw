#!/usr/bin/env python3
"""P4 API/状态机冒烟（`04` §4.3 `API-COURSE-001~004`、§4.4 `API-DOC-001~005`）。

注意：本脚本用 SQL 直接构造 READY/FAILED 夹具，验证的是 P4 API 与状态机，
不代表“上传→解析→READY”真实闭环（该闭环属 TASK-501/502/503/506）。

账号策略：脚本注册的临时账号会保留（不自动删除），按 README 的手工 SQL 清理。

前置：整栈已启动，且 Java/Python 已使用临时 env 加入 mock 模型白名单
（见 `scripts/e2e/README.md` 的临时 env 流程）。脚本只使用假 Key，不读取/打印 `.env`。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

FAKE_KEY = "sk-course-not-a-real-key"


def call(base_url: str, method: str, path: str, *, token: str | None = None,
         body: dict | None = None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base_url + path, method=method, data=data)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + ("" if ok else f"（{detail}）"))
    return ok


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


def mysql_query(sql: str) -> str:
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", "sc-mysql", "sh", "-lc",
         'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B'],
        input=sql,
        text=True,
        capture_output=True,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit("MySQL 校验命令失败")
    return completed.stdout.strip()


def docker_exec(service: str, command: str) -> str:
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", service, "sh", "-lc", command],
        text=True, capture_output=True,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"{service} 命令失败")
    return completed.stdout.strip()


def upload_file(base_url: str, token: str, course_id: str, filename: str,
                content: bytes, content_type: str) -> tuple[int, dict]:
    boundary = "----scholarclaw-e2e-boundary"
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
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))


def save_models(base_url: str, token: str, mock_base_url: str) -> tuple[int, dict]:
    return call(base_url, "PUT", "/api/v1/user/model-config", token=token, body={
        "chat_provider": "OPENAI_COMPATIBLE",
        "chat_base_url": mock_base_url,
        "chat_api_key": FAKE_KEY,
        "chat_model": "chat-mock",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": mock_base_url,
        "embedding_api_key": FAKE_KEY,
        "reuse_chat_api_key": False,
        "default_embedding_model": "embed-mock",
    })


def main() -> int:
    parser = argparse.ArgumentParser(description="TASK-401 课程空间 API 冒烟")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--mock-base-url", required=True,
                        help="mock 模型地址，必须是已加入 Java/Python 白名单的私网 IP 字面量，"
                             "如 http://172.20.0.8:9000/v1（不要使用 Docker 服务名）")
    args = parser.parse_args()

    suffix = str(int(time.time()))
    username = f"e2e_course_{suffix}"
    password = "E2e-Course-Pass-123"
    results: list[bool] = []

    token, user_id = register_or_login(args.base_url, username, password)
    status, payload = save_models(args.base_url, token, args.mock_base_url)
    results.append(check("保存已验证 Embedding 配置", status == 200
                         and payload["data"]["default_embedding_dim"] == 8, f"{status} {payload}"))
    results.append(check("保存时 existing_course_count=0",
                         payload["data"].get("existing_course_count") == 0))

    status, payload = call(args.base_url, "GET", "/api/v1/user/config", token=token)
    results.append(check("has_byok_config=true", status == 200
                         and payload["data"]["has_byok_config"] is True))

    status, payload = call(args.base_url, "POST", "/api/v1/courses", token=token, body={
        "course_name": "操作系统原理", "description": "2026 秋"})
    created = payload.get("data") or {}
    course_id = created.get("course_id")
    results.append(check("创建课程 201", status == 201, f"{status} {payload}"))
    results.append(check("课程锁定已验证维度 8", created.get("embedding_dim") == 8,
                         str(created.get("embedding_dim"))))
    results.append(check("普通课程 is_default=false", created.get("is_default") is False))
    results.append(check("index_version=1 / ACTIVE", created.get("index_version") == 1
                         and created.get("index_status") == "ACTIVE"))

    status, payload = call(args.base_url, "POST", "/api/v1/courses", token=token, body={
        "course_name": "操作系统原理"})
    results.append(check("同名课程 409", status == 409
                         and payload.get("code") == "COURSE_NAME_DUPLICATE", f"{status} {payload}"))

    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("课程列表包含新课程", status == 200
                         and len(payload.get("data", [])) == 1
                         and payload["data"][0]["course_id"] == course_id))

    # TASK-403：真实上传 PDF（路径穿越文件名应被净化），再验证 TASK-402 状态机
    pdf_bytes = b"%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
    status, payload = upload_file(args.base_url, token, course_id,
                                  "../../etc/passwd.pdf", pdf_bytes, "application/pdf")
    uploaded = payload.get("data") or {}
    doc_id = uploaded.get("doc_id")
    results.append(check("上传合法 PDF 返回 202/UPLOADED", status == 202
                         and uploaded.get("status") == "UPLOADED"
                         and uploaded.get("doc_name") == "passwd.pdf",
                         f"{status} {payload}"))
    file_exists = docker_exec("sc-java-backend",
                              f"test -f /data/scholarclaw/storage/raw/{int(user_id)}/"
                              f"{int(course_id)}/{int(doc_id)}.pdf && echo yes || echo no")
    results.append(check("原始文件写入共享卷 raw/{uid}/{cid}/{doc_id}.pdf",
                         file_exists == "yes", file_exists))

    # FIX-01：手工构造带旧快照的 PARSING 文档，API 必须隐藏旧快照
    mysql_query(
        "UPDATE t_knowledge_doc SET status='PARSING', parse_version=2, "
        "indexed_embedding_provider='OPENAI_COMPATIBLE', indexed_embedding_model='old-model', "
        f"indexed_embedding_dim=8, page_count=9, chunk_count=9, ready_at=NOW(3) "
        f"WHERE doc_id={int(doc_id)};")
    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    stale = next((d for d in (payload.get("data") or []) if d["doc_id"] == doc_id), {})
    results.append(check("非 READY 文档不暴露旧索引快照", status == 200
                         and stale.get("status") == "PARSING"
                         and stale.get("indexed_embedding_model") is None
                         and stale.get("indexed_embedding_dim") is None
                         and stale.get("page_count") == 0
                         and stale.get("chunk_count") == 0
                         and stale.get("ready_at") is None,
                         f"{status} {payload}"))
    mysql_query(f"UPDATE t_knowledge_doc SET status='UPLOADED', parse_version=1 WHERE doc_id={int(doc_id)};")

    status, payload = upload_file(args.base_url, token, course_id,
                                  "passwd.pdf", pdf_bytes, "application/pdf")
    results.append(check("同课程重复文件 409 且携带已有 doc_id", status == 409
                         and payload.get("code") == "DOC_DUPLICATE_SHA256"
                         and (payload.get("data") or {}).get("existing_doc_id") == doc_id,
                         f"{status} {payload}"))
    status, payload = upload_file(args.base_url, token, course_id,
                                  "fake.pdf", b"not a pdf", "application/pdf")
    results.append(check("伪造 PDF Magic 400", status == 400
                         and payload.get("code") == "FILE_MAGIC_INVALID",
                         f"{status} {payload}"))
    status, payload = upload_file(args.base_url, token, course_id,
                                  "a.exe", b"MZ", "application/octet-stream")
    results.append(check("非白名单扩展名 400", status == 400
                         and payload.get("code") == "FILE_TYPE_UNSUPPORTED",
                         f"{status} {payload}"))

    # 将上传文档模拟为 READY（实际解析属 TASK-501），以验证课程统计/切换/删除事件
    mysql_query(
        "UPDATE t_knowledge_doc SET status = 'READY', indexed_embedding_provider = "
        "'OPENAI_COMPATIBLE', indexed_embedding_model = 'embed-mock', "
        "indexed_embedding_dim = 8, page_count = 1, chunk_count = 1 "
        f"WHERE doc_id = {int(doc_id)};")
    mysql_query(
        "INSERT INTO t_knowledge_parent_chunk (parent_chunk_id, doc_id, user_id, course_id, "
        "parse_version, chunk_seq, page_start, page_end, section_title, content, char_length) "
        f"VALUES ('pc_{doc_id}_v1_1', {int(doc_id)}, {int(user_id)}, {int(course_id)}, "
        "1, 1, 1, 1, '第5章', '事务处理', 4);")

    # P4 门禁：MQ 生产者未接入前，重试/重建必须 501 且不改变数据库状态
    status, payload = call(args.base_url, "POST", f"/api/v1/docs/{doc_id}/reindex", token=token)
    results.append(check("重建接口在 P4 返回 501 门禁", status == 501
                         and payload.get("code") == "FEATURE_NOT_AVAILABLE",
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "POST", f"/api/v1/docs/{doc_id}/retry", token=token)
    results.append(check("重试接口在 P4 返回 501 门禁", status == 501
                         and payload.get("code") == "FEATURE_NOT_AVAILABLE",
                         f"{status} {payload}"))
    doc_after_gate = mysql_query(
        f"SELECT status, parse_version FROM t_knowledge_doc WHERE doc_id = {int(doc_id)};")
    results.append(check("门禁拦截后文档仍为 READY 且版本未变", doc_after_gate == "READY\t1",
                         repr(doc_after_gate)))

    # FIX-05：DELETING 过渡态必须出现在默认列表中
    status, payload = upload_file(args.base_url, token, course_id,
                                  "second.pdf", pdf_bytes + b"% second\n",
                                  "application/pdf")
    second_doc_id = (payload.get("data") or {}).get("doc_id")
    results.append(check("第二份课件上传 202", status == 202 and second_doc_id is not None,
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "DELETE", f"/api/v1/docs/{second_doc_id}", token=token)
    results.append(check("删除第二份课件返回 DELETING", status == 200
                         and (payload.get("data") or {}).get("status") == "DELETING",
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    docs = payload.get("data") or []
    results.append(check("默认列表仍显示 DELETING 文档",
                         status == 200 and any(d["doc_id"] == second_doc_id
                                               and d["status"] == "DELETING" for d in docs),
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "GET",
                           f"/api/v1/courses/{course_id}/docs?status=DELETING", token=token)
    docs = payload.get("data") or []
    results.append(check("status=DELETING 过滤可用", status == 200
                         and len(docs) == 1 and docs[0]["doc_id"] == second_doc_id,
                         f"{status} {payload}"))

    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    docs = {d["doc_id"]: d for d in (payload.get("data") or [])}
    results.append(check("文档列表返回 READY 与 DELETING 文档", status == 200 and len(docs) == 2
                         and (docs.get(doc_id) or {}).get("status") == "READY"
                         and (docs.get(second_doc_id) or {}).get("status") == "DELETING",
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("课程统计 ready_doc_count=1", status == 200
                         and payload["data"][0]["ready_doc_count"] == 1,
                         str(payload.get("data"))))
    mysql_query(f"UPDATE t_course_namespace SET index_version=2 WHERE course_id={int(course_id)};")
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("旧索引版本 READY 文档不计入可用数", status == 200
                         and payload["data"][0]["ready_doc_count"] == 0,
                         str(payload.get("data"))))
    mysql_query(f"UPDATE t_course_namespace SET index_version=1 WHERE course_id={int(course_id)};")

    # 真实数据库校验：删除课程时 DDL 任务解绑保留（course_id=NULL，course_name 保留）
    ddl_key = f"e2e-ddl-{suffix}"
    mysql_query(
        "INSERT INTO t_ddl_task (user_id, course_id, course_name, task_title, status, "
        "source_platform, source_msg_id, idempotency_key) VALUES ("
        f"{int(user_id)}, {int(course_id)}, '操作系统原理', '提交实验报告', 'PENDING_CONFIRM', "
        f"'WEB', '{ddl_key}', SHA2(CONCAT('{int(user_id)}:WEB:{ddl_key}:DDL'), 256));")

    status, payload = save_models(args.base_url, token, args.mock_base_url)
    results.append(check("建课后 existing_course_count=1", status == 200
                         and payload["data"].get("existing_course_count") == 1,
                         str(payload.get("data", {}).get("existing_course_count"))))

    status, payload = call(args.base_url, "PUT",
                           f"/api/v1/courses/{course_id}/embedding-model", token=token,
                           body={"use_user_default": False,
                                 "embedding_provider": "OPENAI_COMPATIBLE",
                                 "embedding_model": "embed-mock-v2"})
    results.append(check("旧 Key 绑定新模型名被 400 拒绝", status == 400
                         and payload.get("code") == "VALIDATION_ERROR",
                         f"{status} {payload}"))

    status, payload = call(args.base_url, "PUT",
                           f"/api/v1/courses/{course_id}/embedding-model", token=token,
                           body={"use_user_default": True})
    switched = payload.get("data") or {}
    results.append(check("使用已保存默认模型切换成功", status == 200
                         and switched.get("embedding_dim") == 8
                         and switched.get("current_index_version") == 2
                         and switched.get("index_status") == "REBUILDING"
                         and switched.get("reindexing_doc_count") == 1,
                         f"{status} {payload}"))

    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    docs = {d["doc_id"]: d for d in (payload.get("data") or [])}
    first = docs.get(doc_id) or {}
    results.append(check("切换后文档转 PARSING（旧索引立即失效）",
                         status == 200 and first.get("status") == "PARSING"
                         and first.get("parse_version") == 2
                         and first.get("course_index_version") == 2,
                         f"{status} {payload}"))
    results.append(check("切换后索引快照被清空",
                         first.get("indexed_embedding_model") is None
                         and first.get("indexed_embedding_dim") is None
                         and first.get("page_count") == 0
                         and first.get("chunk_count") == 0
                         and first.get("ready_at") is None,
                         str(first)))
    chunk_count = mysql_query(
        f"SELECT COUNT(*) FROM t_knowledge_parent_chunk WHERE doc_id = {int(doc_id)};")
    results.append(check("切换后旧父块已清空", chunk_count == "0", chunk_count))

    other_name = f"e2e_course_other_{suffix}"
    other_token, _ = register_or_login(args.base_url, other_name, password)
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=other_token)
    results.append(check("其他用户看不到课程", status == 200 and payload.get("data") == []))
    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs",
                           token=other_token)
    results.append(check("其他用户读取课程文档 404",
                         status == 404 and payload.get("code") == "RESOURCE_NOT_FOUND",
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}",
                           token=other_token)
    results.append(check("跨用户删除 404", status == 404
                         and payload.get("code") == "RESOURCE_NOT_FOUND", f"{status} {payload}"))

    status, payload = call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}", token=token)
    results.append(check("删除课程成功", status == 200
                         and payload["data"]["deleted_course_id"] == course_id))
    ddl_row = mysql_query(
        "SELECT IFNULL(course_id, 'NULL'), course_name FROM t_ddl_task "
        f"WHERE source_msg_id = '{ddl_key}';")
    results.append(check("DDL 任务已解绑且课程名保留", ddl_row == "NULL\t操作系统原理",
                         repr(ddl_row)))
    doc_state = mysql_query(
        f"SELECT status, parse_version FROM t_knowledge_doc WHERE doc_id = {int(doc_id)};")
    results.append(check("删除课程后文档转 DELETING 且版本递增",
                         doc_state == "DELETING\t3", repr(doc_state)))
    status, payload = call(args.base_url, "DELETE", f"/api/v1/docs/{doc_id}", token=token)
    results.append(check("文档删除接口幂等返回 DELETING", status == 200
                         and (payload.get("data") or {}).get("status") == "DELETING",
                         f"{status} {payload}"))
    mysql_query(f"DELETE FROM t_knowledge_doc WHERE doc_id = {int(doc_id)};")
    mysql_query(f"DELETE FROM t_knowledge_doc WHERE doc_id = {int(second_doc_id)};")
    mysql_query(f"DELETE FROM t_knowledge_parent_chunk WHERE doc_id = {int(doc_id)};")
    mysql_query(f"DELETE FROM t_knowledge_parent_chunk WHERE doc_id = {int(second_doc_id)};")
    docker_exec("sc-java-backend",
                f"rm -f /data/scholarclaw/storage/raw/{int(user_id)}/{int(course_id)}/"
                f"{int(doc_id)}.pdf /data/scholarclaw/storage/raw/{int(user_id)}/"
                f"{int(course_id)}/{int(second_doc_id)}.pdf")
    mysql_query(f"DELETE FROM t_ddl_task WHERE source_msg_id = '{ddl_key}';")
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("删除后列表为空", status == 200 and payload.get("data") == []))
    status, payload = call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}", token=token)
    results.append(check("重复删除 404", status == 404
                         and payload.get("code") == "RESOURCE_NOT_FOUND", f"{status} {payload}"))

    failed = results.count(False)
    print(f"P4 API/状态机冒烟（非真实解析 E2E）：{len(results) - failed}/{len(results)} 项通过；"
          f"测试账号 {username}（保留，未自动删除）")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
