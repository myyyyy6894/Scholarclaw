#!/usr/bin/env python3
"""TASK-401 课程空间冒烟（`04` §4.3 `API-COURSE-001~004`）。

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
    parser.add_argument("--mock-base-url", default="http://sc-e2e-mock-model:9000/v1")
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

    # 插入一份 READY 文档与父块，验证 TASK-402 文档列表/统计/课程事件状态迁移
    doc_id = mysql_query(
        "INSERT INTO t_knowledge_doc (user_id, course_id, doc_name, file_ext, file_size_bytes, "
        "file_sha256, storage_rel_path, status, parse_version, course_index_version, "
        "indexed_embedding_provider, indexed_embedding_model, indexed_embedding_dim, "
        "page_count, chunk_count, retry_count) VALUES ("
        f"{int(user_id)}, {int(course_id)}, '第5章_事务处理.pdf', '.pdf', 1024, "
        f"REPEAT('a', 64), 'raw/{int(user_id)}/{int(course_id)}/placeholder.pdf', 'READY', "
        "1, 1, 'OPENAI_COMPATIBLE', 'embed-mock', 8, 1, 1, 0);\n"
        "SELECT LAST_INSERT_ID();")
    mysql_query(
        "INSERT INTO t_knowledge_parent_chunk (parent_chunk_id, doc_id, user_id, course_id, "
        "parse_version, chunk_seq, page_start, page_end, section_title, content, char_length) "
        f"VALUES ('pc_{doc_id}_v1_1', {int(doc_id)}, {int(user_id)}, {int(course_id)}, "
        "1, 1, 1, 1, '第5章', '事务处理', 4);")

    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    docs = payload.get("data") or []
    results.append(check("文档列表返回 READY 文档", status == 200 and len(docs) == 1
                         and docs[0]["doc_id"] == doc_id and docs[0]["status"] == "READY",
                         f"{status} {payload}"))
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("课程统计 ready_doc_count=1", status == 200
                         and payload["data"][0]["ready_doc_count"] == 1,
                         str(payload.get("data"))))

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
    switched = payload.get("data") or {}
    expected_status = "REBUILDING" if (switched.get("reindexing_doc_count") or 0) > 0 else "ACTIVE"
    results.append(check("切换模型成功并回填探测维度", status == 200
                         and switched.get("embedding_dim") == 8
                         and switched.get("current_index_version") == 2
                         and switched.get("index_status") == expected_status,
                         f"{status} {payload}"))

    status, payload = call(args.base_url, "GET", f"/api/v1/courses/{course_id}/docs", token=token)
    docs = payload.get("data") or []
    results.append(check("切换后文档转 PARSING（旧索引立即失效）",
                         status == 200 and docs and docs[0]["status"] == "PARSING"
                         and docs[0]["parse_version"] == 2
                         and docs[0]["course_index_version"] == 2,
                         f"{status} {payload}"))
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
    mysql_query(f"DELETE FROM t_knowledge_doc WHERE doc_id = {int(doc_id)};")
    mysql_query(f"DELETE FROM t_knowledge_parent_chunk WHERE doc_id = {int(doc_id)};")
    mysql_query(f"DELETE FROM t_ddl_task WHERE source_msg_id = '{ddl_key}';")
    status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
    results.append(check("删除后列表为空", status == 200 and payload.get("data") == []))
    status, payload = call(args.base_url, "DELETE", f"/api/v1/courses/{course_id}", token=token)
    results.append(check("重复删除 404", status == 404
                         and payload.get("code") == "RESOURCE_NOT_FOUND", f"{status} {payload}"))

    failed = results.count(False)
    print(f"TASK-401 课程冒烟：{len(results) - failed}/{len(results)} 项通过；测试账号 {username}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
