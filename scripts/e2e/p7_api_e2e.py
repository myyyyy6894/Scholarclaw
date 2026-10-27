#!/usr/bin/env python3
"""P7 DDL 真实 HTTP API E2E（可复现基线）。

前置：整栈已启动、模型替身 `p7_mock_model.py` 可达、Java 允许该模型地址。
用法：
    sc-python-ai/.venv/bin/python scripts/e2e/p7_api_e2e.py \
        --base-url http://127.0.0.1 \
        --model-base-url http://<mock-ip>:11434/v1
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

BASE = "http://127.0.0.1"
MOCK = "http://172.20.0.250:11434/v1"
FAILS: list[str] = []


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


def call(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = resp.json()
    except Exception:
        body = {"raw": resp.text}
    return resp.status_code, body


def data_of(body: dict):
    return body.get("data") if isinstance(body, dict) else None


def main() -> int:
    global BASE, MOCK
    parser = argparse.ArgumentParser(description="P7 DDL real HTTP API E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MOCK)
    parser.add_argument("--username", default=None)
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--redis-container", default="sc-redis",
                        help="用于构造过期草案的 Redis 容器名；不可用时该项跳过")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MOCK = args.model_base_url.rstrip("/")
    # 固定 reference time 并据此生成未来/历史日期，避免固定日期随时间流逝而失效。
    reference = datetime.now(ZoneInfo("Asia/Shanghai")).replace(microsecond=0)
    date_only_day = (reference + timedelta(days=2)).date()
    exact_day = (reference + timedelta(days=3)).date()
    changed_day = (reference + timedelta(days=40)).date()

    def ymd(day) -> str:
        return f"{day.year}年{day.month}月{day.day}日"

    print(f"[reference] {reference.isoformat()}")
    print(f"[generated] DATE_ONLY={date_only_day} EXACT={exact_day} CHANGED={changed_day}")
    client = httpx.Client(timeout=30.0, trust_env=False)
    suffix = str(int(time.time()))[-8:]
    username = args.username or f"p7r3v_{suffix}"
    password = args.password
    status, body = call(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status == 409:
        status, body = call(client, "POST", "/api/v1/auth/login", json={
            "username": username, "password": password})
    check("注册/登录", status in (200, 201) and data_of(body), (status, body))
    token = data_of(body)["access_token"]
    uid = str(data_of(body)["user_id"])

    status, body = call(client, "PUT", "/api/v1/user/model-config", token, json={
        "chat_provider": "OPENAI_COMPATIBLE",
        "chat_base_url": MOCK,
        "chat_api_key": "sk-test",
        "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MOCK,
        "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed",
        "migrate_existing_courses": False,
    })
    check("保存模型配置", status == 200, (status, body))

    def extract(raw: str, req: str | None = None, force: bool = False,
                course_id: str | None = None, reference: str | None = None):
        return call(client, "POST", "/api/v1/ddl/tasks/extract", token, json={
            "raw_text": raw,
            "client_request_id": req or ("req_" + uuid.uuid4().hex),
            "force_create": force,
            "course_id": course_id,
            "reference_time": reference,
        })

    # 1. EXACT_DATETIME（远未来日期按 reference 动态生成）
    exact_text = f"请于{ymd(exact_day)} 22:00 前提交实验报告"
    st, b = extract(exact_text)
    d = data_of(b) or {}
    task = d.get("task") or {}
    check("EXACT 提取建单", st == 200 and d.get("extract_outcome") == "CREATED", (st, b))
    check("EXACT due_at/状态",
          task.get("due_at", "").startswith(f"{exact_day.isoformat()}T22:00:00+08:00")
          and task.get("status") == "ACTIVE" and task.get("time_inferred") is False,
          task)
    exact_task_id = task.get("task_id")

    # 2. DATE_ONLY
    st, b = extract(f"请于{ymd(date_only_day)}提交日期任务")
    task = (data_of(b) or {}).get("task") or {}
    check("DATE_ONLY 字段", st == 200 and task.get("due_time") is None
          and task.get("due_at", "").startswith(f"{date_only_day.isoformat()}T23:59:59")
          and task.get("time_inferred") is True, (st, task))
    date_only_task_id = task.get("task_id")

    # 3. VAGUE
    st, b = extract("请大家尽快提交实验报告")
    task = (data_of(b) or {}).get("task") or {}
    check("VAGUE → PENDING_TIME", st == 200 and task.get("due_at") is None
          and task.get("status") == "PENDING_TIME", (st, task))

    # 4. 过去时间（显式年份，不跨年）
    st, b = extract("请于2026年10月1日 22:00 前提交过去时间报告")
    task = (data_of(b) or {}).get("task") or {}
    check("过去时间 → PENDING_CONFIRM", st == 200
          and task.get("time_precision") == "INVALID_OR_PAST"
          and task.get("status") == "PENDING_CONFIRM", (st, task))
    past_task_id = task.get("task_id")
    past_reason = task.get("time_ambiguity_reason")

    # 5. 非法日期
    st, b = extract("请于2月29日提交非法日期报告")
    task = (data_of(b) or {}).get("task") or {}
    check("非法日期 → PENDING_TIME/无 due_at", st == 200
          and task.get("time_precision") == "INVALID_OR_PAST"
          and task.get("status") == "PENDING_TIME" and task.get("due_at") is None,
          (st, task))

    # 6. 周末“下周一”（reference 设为周六）
    st, b = extract("请于下周一 22:00 前提交下周报告",
                    reference="2026-10-24T10:00:00+08:00")
    task = (data_of(b) or {}).get("task") or {}
    check("周六收到下周一 → 10-26 22:00 + 待确认", st == 200
          and task.get("due_at", "").startswith("2026-10-26T22:00:00")
          and task.get("status") == "PENDING_CONFIRM"
          and "下周一" in (task.get("time_ambiguity_reason") or ""), (st, task))

    # 7. 凌晨“今天”（reference 设为凌晨 2 点）
    st, b = extract("请于今天 22:00 前提交今日报告",
                    reference="2026-10-25T02:00:00+08:00")
    task = (data_of(b) or {}).get("task") or {}
    check("凌晨收到今天 → 10-25 22:00 + 待确认", st == 200
          and task.get("due_at", "").startswith("2026-10-25T22:00:00")
          and task.get("status") == "PENDING_CONFIRM"
          and "凌晨" in (task.get("time_ambiguity_reason") or ""), (st, task))

    # 8. 跨年
    st, b = extract("请于1月5日 22:00 前提交跨年报告",
                    reference="2026-12-20T10:00:00+08:00")
    task = (data_of(b) or {}).get("task") or {}
    check("跨年顺延 2027-01-05", st == 200
          and task.get("due_at", "").startswith("2027-01-05T22:00:00")
          and "跨年顺延至 2027-01-05" in (task.get("time_ambiguity_reason") or ""),
          (st, task))
    crossyear_task_id = task.get("task_id")

    # 9. 幂等：同 ID 重试与换内容
    rid = "req_idem_" + uuid.uuid4().hex
    st1, b1 = extract(exact_text, req=rid, force=True)
    st2, b2 = extract(exact_text, req=rid, force=True)
    check("同 ID 重试 → IDEMPOTENT_HIT", st2 == 200
          and (data_of(b2) or {}).get("extract_outcome") == "IDEMPOTENT_HIT", (st2, b2))
    st3, b3 = extract(f"请于{ymd(exact_day)} 23:00 前提交实验报告", req=rid)
    check("同 ID 换内容 → 409 IDEMPOTENCY_CONFLICT", st3 == 409
          and b3.get("code") == "IDEMPOTENCY_CONFLICT", (st3, b3))

    # 10. 手工建单与通知提取复用同一 ID
    rid_manual = "req_manual_" + uuid.uuid4().hex
    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": rid_manual, "course_name": "操作系统原理",
        "task_title": "手工任务", "time_precision": "DATE_ONLY",
        "due_date": date_only_day.isoformat(), "due_time": None,
        "deliverable_format": None, "submission_channel": None})
    check("手工建单", st in (200, 201), (st, b))
    st, b = extract(exact_text, req=rid_manual)
    check("手工 ID 复用于提取 → 409", st == 409
          and b.get("code") == "IDEMPOTENCY_CONFLICT", (st, b))

    # 10b. force_create：不改变幂等键公式；跳过语义层但同 ID 换内容仍冲突
    st, b = extract(f"请于{ymd(exact_day)} 23:30 前提交实验报告", req=rid, force=True)
    check("旧 ID + force_create 换内容 → 409", st == 409
          and b.get("code") == "IDEMPOTENCY_CONFLICT", (st, b))
    st, b = extract(exact_text)
    check("同内容新请求 → SEMANTIC_DUPLICATE_HINT", st == 200
          and (data_of(b) or {}).get("extract_outcome") == "SEMANTIC_DUPLICATE_HINT", (st, b))
    st, b = extract(exact_text, force=True)
    check("force_create 跳过语义层 → CREATED", st == 200
          and (data_of(b) or {}).get("extract_outcome") == "CREATED", (st, b))

    # 10c. 编辑时间语义保持：只保存原字段不得把 INVALID_OR_PAST 升级为 ACTIVE
    st, b = call(client, "PUT", f"/api/v1/ddl/tasks/{past_task_id}", token, json={
        "course_id": None, "course_name": "操作系统原理",
        "task_title": "提交过去时间报告（改名）", "time_precision": "EXACT_DATETIME",
        "due_date": "2026-10-01", "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    edited_past = data_of(b) or {}
    check("仅改标题保持 INVALID_OR_PAST/PENDING_CONFIRM/原因", st == 200
          and edited_past.get("time_precision") == "INVALID_OR_PAST"
          and edited_past.get("status") == "PENDING_CONFIRM"
          and edited_past.get("time_ambiguity_reason") == past_reason
          and edited_past.get("due_at") == "2026-10-01T22:00:00+08:00", (st, edited_past))

    # 10d. 显式 reconfirm_time：字段不变也允许激活未来待确认任务
    st, b = call(client, "PUT", f"/api/v1/ddl/tasks/{crossyear_task_id}", token, json={
        "course_id": None, "course_name": "操作系统原理",
        "task_title": "提交跨年报告", "time_precision": "EXACT_DATETIME",
        "due_date": "2027-01-05", "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None,
        "reconfirm_time": True})
    reconfirmed = data_of(b) or {}
    check("reconfirm_time 显式确认 → ACTIVE 且清空原因", st == 200
          and reconfirmed.get("status") == "ACTIVE"
          and reconfirmed.get("time_ambiguity_reason") is None, (st, reconfirmed))

    # 11. 状态机：ACTIVE 直接取消完成 → 409；完成→取消完成→ACTIVE
    st, b = call(client, "PATCH", f"/api/v1/ddl/tasks/{exact_task_id}/completion",
                 token, json={"completed": False})
    check("ACTIVE 取消完成 → 409 STATE_CONFLICT", st == 409
          and b.get("code") == "STATE_CONFLICT", (st, b))
    st, b = call(client, "PATCH", f"/api/v1/ddl/tasks/{exact_task_id}/completion",
                 token, json={"completed": True})
    check("标记完成", st == 200 and (data_of(b) or {}).get("status") == "COMPLETED", (st, b))
    st, b = call(client, "PATCH", f"/api/v1/ddl/tasks/{exact_task_id}/completion",
                 token, json={"completed": False})
    task = data_of(b) or {}
    check("取消完成恢复 ACTIVE", st == 200 and task.get("status") == "ACTIVE"
          and task.get("completed_at") is None, (st, task))

    # 12. 已完成任务编辑：先重新标记完成，再编辑，断言状态/完成时间/字段均保持
    st, b = call(client, "PATCH", f"/api/v1/ddl/tasks/{exact_task_id}/completion",
                 token, json={"completed": True})
    completed = data_of(b) or {}
    check("重新标记完成（编辑前置）", st == 200 and completed.get("status") == "COMPLETED"
          and completed.get("completed_at"), (st, b))
    completed_at = completed.get("completed_at")
    new_title = str(completed.get("task_title")) + "（完成态编辑）"
    st, b = call(client, "PUT", f"/api/v1/ddl/tasks/{exact_task_id}", token, json={
        "course_id": None, "course_name": completed.get("course_name"),
        "task_title": new_title, "time_precision": "EXACT_DATETIME",
        "due_date": exact_day.isoformat(), "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    edited = data_of(b) or {}
    check("完成态编辑保持 COMPLETED 与 completed_at", st == 200
          and edited.get("status") == "COMPLETED"
          and edited.get("completed_at") == completed_at
          and edited.get("task_title") == new_title, (st, edited))

    # 13. IDOR：另一个用户不能读取/修改
    u2 = f"p7r3b_{suffix}"
    st, b = call(client, "POST", "/api/v1/auth/register", json={
        "username": u2, "password": password, "timezone": "Asia/Shanghai"})
    if st == 409:
        st, b = call(client, "POST", "/api/v1/auth/login",
                     json={"username": u2, "password": password})
    token2 = data_of(b)["access_token"]
    st, b = call(client, "GET", f"/api/v1/ddl/tasks/{exact_task_id}", token2)
    if st == 405:
        st, b = call(client, "PATCH", f"/api/v1/ddl/tasks/{exact_task_id}/completion",
                     token2, json={"completed": True})
    check("跨用户 IDOR → 404", st in (403, 404), (st, b))

    # 14. 变更草案：同课程同标题、截止时间变更
    st, b = extract(f"请于{ymd(changed_day)} 22:00 前提交实验二报告")
    d = data_of(b) or {}
    draft = d.get("change_draft") or {}
    check("重复通知 → CHANGE_DRAFT_CONFIRM_REQUIRED", st == 200
          and d.get("extract_outcome") == "CHANGE_DRAFT_CONFIRM_REQUIRED"
          and draft.get("target_task_id") and draft.get("user_timezone"),
          (st, d))
    target_id = draft.get("target_task_id")

    # 15. 并发不同请求 ID/不同 decision 决策同一草案
    def decide(decision: str):
        return call(client, "POST", f"/api/v1/ddl/tasks/{target_id}/draft-decision",
                    token, json={"decision": decision,
                                 "client_request_id": "req_dec_" + uuid.uuid4().hex})

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        f1 = pool.submit(decide, "CONFIRM_UPDATE")
        f2 = pool.submit(decide, "CREATE_NEW")
        r1, r2 = f1.result(), f2.result()
    statuses = sorted([r1[0], r2[0]])
    check("并发决策只允许一个成功", statuses[0] == 200
          and statuses[1] in (404, 409), (r1, r2))

    # 15b. 课程删除后同 client_request_id 重试手动建单：必须返回原任务，不能 404
    st, b = call(client, "POST", "/api/v1/courses", token, json={"course_name": "重试课程"})
    retry_course = (data_of(b) or {}).get("course_id")
    check("创建重试课程", st in (200, 201) and retry_course, (st, b))
    retry_rid = "req_retry_manual_" + uuid.uuid4().hex
    retry_payload = {
        "client_request_id": retry_rid, "course_id": retry_course,
        "course_name": "重试课程", "task_title": "重试任务",
        "time_precision": "DATE_ONLY", "due_date": "2099-12-31", "due_time": None,
        "deliverable_format": None, "submission_channel": None,
    }
    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json=retry_payload)
    retry_task_id = (data_of(b) or {}).get("task_id")
    check("创建重试任务", st in (200, 201) and retry_task_id, (st, b))
    st, b = call(client, "DELETE", f"/api/v1/courses/{retry_course}", token)
    check("删除重试课程", st in (200, 202), (st, b))
    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json=retry_payload)
    check("删课后同 ID 重试手动建单返回原任务且 HTTP 200", st == 200
          and str((data_of(b) or {}).get("task_id")) == str(retry_task_id), (st, b))

    # 15c. 创建任务 vs 删除课程并发：最终不得留下指向已删除课程的 course_id
    concurrent_ok = True
    for i in range(5):
        st, b = call(client, "POST", "/api/v1/courses",
                     token, json={"course_name": f"并发课程{i}"})
        if st not in (200, 201):
            concurrent_ok = False
            break
        ccid = (data_of(b) or {}).get("course_id")
        title = f"并发课程任务{i}"
        payload = {
            "client_request_id": f"req_po05_conc_{i}_" + uuid.uuid4().hex,
            "course_id": ccid, "course_name": f"并发课程{i}",
            "task_title": title, "time_precision": "DATE_ONLY",
            "due_date": "2099-12-31", "due_time": None,
            "deliverable_format": None, "submission_channel": None,
        }
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            create_f = pool.submit(call, client, "POST", "/api/v1/ddl/tasks", token,
                                   json=payload)
            delete_f = pool.submit(call, client, "DELETE", f"/api/v1/courses/{ccid}", token)
            create_f.result()
            delete_f.result()
        st, b = call(client, "GET", "/api/v1/ddl/tasks?status_group=ALL", token)
        tasks = []
        for key in ("pending_inbox_tasks", "active_tasks", "completed_tasks"):
            tasks.extend((data_of(b) or {}).get(key) or [])
        rows = [t for t in tasks if t.get("task_title") == title]
        if any(t.get("course_id") is not None for t in rows):
            concurrent_ok = False
            break
    check("PO-05 并发建单/删课最终 course_id 均解绑", concurrent_ok)

    # 16. 课程删除 + 草案（PO-05）：最新草案被清理或解绑，不重新关联
    st, b = call(client, "POST", "/api/v1/courses", token, json={"course_name": "PO05课程"})
    course = data_of(b) or {}
    check("创建课程", st in (200, 201) and course.get("course_id"), (st, b))
    cid = course.get("course_id")
    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_po05_" + uuid.uuid4().hex,
        "course_id": cid, "course_name": "PO05课程",
        "task_title": "提交实验二报告", "time_precision": "EXACT_DATETIME",
        "due_date": exact_day.isoformat(), "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    check("创建绑定课程任务", st in (200, 201), (st, b))
    st, b = extract(f"请于{ymd(changed_day)} 22:00 前提交实验二报告", course_id=cid)
    po_draft = (data_of(b) or {}).get("change_draft") or {}
    check("课程任务生成草案", st == 200 and po_draft.get("target_task_id"), (st, b))
    po_target = po_draft.get("target_task_id")
    st, b = call(client, "DELETE", f"/api/v1/courses/{cid}", token)
    check("删除课程", st in (200, 202), (st, b))
    st, b = call(client, "GET", "/api/v1/ddl/tasks?status_group=ALL", token)
    tasks = []
    for key in ("pending_inbox_tasks", "active_tasks", "completed_tasks"):
        tasks.extend((data_of(b) or {}).get(key) or [])
    bound = [t for t in tasks if str(t.get("task_id")) == str(po_target)]
    check("PO-05 任务解绑保留 course_name", st == 200 and bound
          and bound[0].get("course_id") is None
          and bound[0].get("course_name") == "PO05课程", (st, bound))
    if po_target:
        st, b = call(client, "POST", f"/api/v1/ddl/tasks/{po_target}/draft-decision",
                     token, json={"decision": "CONFIRM_UPDATE",
                                  "client_request_id": "req_po05dec_" + uuid.uuid4().hex})
        check("删课后旧草案不可重新关联", st in (404, 409), (st, b))
        st, b = call(client, "GET", "/api/v1/ddl/tasks?status_group=ALL", token)
        tasks = []
        for key in ("pending_inbox_tasks", "active_tasks", "completed_tasks"):
            tasks.extend((data_of(b) or {}).get(key) or [])
        after = [t for t in tasks if str(t.get("task_id")) == str(po_target)]
        check("PO-05 确认后 course_id 仍为 NULL", after
              and after[0].get("course_id") is None, after)

    # 16b. 草案业务过期：直接改写 Redis 草案的 expires_at，决策必须 404 且看板不展示
    def redis_cli(*cli_args, stdin: str | None = None) -> subprocess.CompletedProcess:
        # Redis 需要口令；在容器内用 $REDIS_PASSWORD 展开，避免密码出现在宿主命令行。
        command = "redis-cli -a \"$SC_REDIS_PASSWORD\" --no-auth-warning " + " ".join(
            f'"{arg}"' for arg in cli_args)
        return subprocess.run(
            ["docker", "compose", "exec", "-T", args.redis_container, "sh", "-lc", command],
            input=None if stdin is None else stdin.encode(),
            capture_output=True, check=False)

    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_exp_" + uuid.uuid4().hex,
        "course_name": "操作系统原理", "task_title": "提交实验二报告",
        "time_precision": "EXACT_DATETIME", "due_date": exact_day.isoformat(),
        "due_time": "22:00", "deliverable_format": None, "submission_channel": None})
    exp_task_id = (data_of(b) or {}).get("task_id")
    st, b = extract(f"请于{ymd(changed_day)} 23:00 前提交过期草案实验报告")
    exp_draft = (data_of(b) or {}).get("change_draft") or {}
    exp_target = exp_draft.get("target_task_id") or exp_task_id
    if exp_draft.get("target_task_id"):
        exp_target = exp_draft.get("target_task_id")
    key = f"sc:u:{uid}:task_draft:{exp_target}"
    got = redis_cli("GET", key)
    if got.returncode != 0 or not got.stdout.strip():
        print("SKIP 草案业务过期（无法通过 docker compose exec 访问 sc-redis）")
    else:
        payload = json.loads(got.stdout.decode().strip())
        payload["expires_at"] = "2026-01-01T00:00:00"
        set_result = redis_cli("-x", "SET", key, stdin=json.dumps(payload, ensure_ascii=False))
        st, b = call(client, "POST", f"/api/v1/ddl/tasks/{exp_target}/draft-decision",
                     token, json={"decision": "CONFIRM_UPDATE",
                                  "client_request_id": "req_exp_dec_" + uuid.uuid4().hex})
        check("过期草案决策 → 404 DRAFT_EXPIRED_OR_NOT_FOUND",
              set_result.returncode == 0 and st == 404
              and b.get("code") == "DRAFT_EXPIRED_OR_NOT_FOUND", (set_result.returncode, st, b))
        st, b = call(client, "GET", "/api/v1/ddl/tasks?status_group=ALL", token)
        board = data_of(b) or {}
        check("过期草案不出现在看板",
              all(str(d.get("target_task_id")) != str(exp_target)
                  for d in board.get("pending_change_drafts") or []), board)

    # 17. DST 不存在/重复时刻：America/New_York 用户，手工建单必须 400
    ny_user = f"p7ny_{suffix}"
    st, b = call(client, "POST", "/api/v1/auth/register",
                 json={"username": ny_user, "password": password, "timezone": "America/New_York"})
    ny_token = (data_of(b) or {}).get("access_token")
    st, b = call(client, "PUT", "/api/v1/user/model-config", ny_token, json={
        "chat_provider": "OPENAI_COMPATIBLE", "chat_base_url": MOCK,
        "chat_api_key": "sk-test", "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MOCK, "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed", "migrate_existing_courses": False,
    })
    dst_payload = {
        "client_request_id": "req_dst_" + uuid.uuid4().hex,
        "course_name": "操作系统原理", "task_title": "DST 跳变任务",
        "time_precision": "EXACT_DATETIME", "due_date": "2027-03-14",
        "due_time": "02:30", "deliverable_format": None, "submission_channel": None,
    }
    st, b = call(client, "POST", "/api/v1/ddl/tasks", ny_token, json=dst_payload)
    check("DST 不存在时刻 → 400 INVALID_CALENDAR_DATE", st == 400
          and b.get("code") == "INVALID_CALENDAR_DATE", (st, b))
    dst_payload["client_request_id"] = "req_dst_ok_" + uuid.uuid4().hex
    dst_payload["due_time"] = "03:30"
    st, b = call(client, "POST", "/api/v1/ddl/tasks", ny_token, json=dst_payload)
    check("DST 跳变后的正常时刻可建单", st in (200, 201) and data_of(b), (st, b))

    # 18. PO-05：编辑 vs 删除课程 并发
    po05_ok = True
    for i in range(3):
        st, b = call(client, "POST", "/api/v1/courses", token,
                     json={"course_name": f"编辑并发课程{i}"})
        ccid = (data_of(b) or {}).get("course_id")
        st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json={
            "client_request_id": f"req_po05_edit_{i}_" + uuid.uuid4().hex,
            "course_id": ccid, "course_name": f"编辑并发课程{i}",
            "task_title": f"编辑并发任务{i}", "time_precision": "DATE_ONLY",
            "due_date": "2099-12-31", "due_time": None,
            "deliverable_format": None, "submission_channel": None})
        tid = (data_of(b) or {}).get("task_id")
        edit_payload = {
            "course_id": ccid, "course_name": f"编辑并发课程{i}",
            "task_title": f"编辑并发任务{i}（已编辑）", "time_precision": "DATE_ONLY",
            "due_date": "2099-12-31", "due_time": None,
            "deliverable_format": None, "submission_channel": None,
        }
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            edit_f = pool.submit(call, client, "PUT", f"/api/v1/ddl/tasks/{tid}", token,
                                 json=edit_payload)
            del_f = pool.submit(call, client, "DELETE", f"/api/v1/courses/{ccid}", token)
            edit_r, del_r = edit_f.result(), del_f.result()
        if edit_r[0] >= 500 or del_r[0] >= 500:
            po05_ok = False
            break
        st, b = call(client, "GET", "/api/v1/ddl/tasks?status_group=ALL", token)
        rows = []
        for key in ("pending_inbox_tasks", "active_tasks", "completed_tasks"):
            rows.extend((data_of(b) or {}).get(key) or [])
        bound = [t for t in rows if str(t.get("task_id")) == str(tid)]
        if bound and bound[0].get("course_id") is not None:
            po05_ok = False
            break
    check("PO-05 编辑/删课并发无 5xx 且最终解绑", po05_ok)

    # 19. PO-05：草案应用 vs 删除课程 并发
    st, b = call(client, "POST", "/api/v1/courses", token, json={"course_name": "草案并发课程"})
    dcid = (data_of(b) or {}).get("course_id")
    st, b = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_po05_draft_" + uuid.uuid4().hex,
        "course_id": dcid, "course_name": "草案并发课程",
        "task_title": "提交实验二报告", "time_precision": "EXACT_DATETIME",
        "due_date": exact_day.isoformat(), "due_time": "22:00",
        "deliverable_format": None, "submission_channel": None})
    dtid = (data_of(b) or {}).get("task_id")
    st, b = extract(f"请于{ymd(changed_day)} 22:00 前提交实验二报告", course_id=dcid)
    has_draft = (data_of(b) or {}).get("extract_outcome") == "CHANGE_DRAFT_CONFIRM_REQUIRED"
    draft_ok = True
    if has_draft:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            decide_f = pool.submit(call, client, "POST",
                                   f"/api/v1/ddl/tasks/{dtid}/draft-decision", token,
                                   json={"decision": "CONFIRM_UPDATE",
                                         "client_request_id": "req_pd_" + uuid.uuid4().hex})
            del_f = pool.submit(call, client, "DELETE", f"/api/v1/courses/{dcid}", token)
            decide_r, del_r = decide_f.result(), del_f.result()
        draft_ok = decide_r[0] < 500 and del_r[0] < 500
    check("PO-05 草案应用/删课并发无 5xx", has_draft and draft_ok, has_draft)

    client.close()
    print("\nFAILED:", FAILS if FAILS else "none")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
