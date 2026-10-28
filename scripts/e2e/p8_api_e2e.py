#!/usr/bin/env python3
"""P8 Web + QQ（OneBot 协议替身）真实栈 API E2E。

覆盖：
* OneBot Webhook 令牌/白名单/去重/快速 ACK、私聊/群 @、单层转发、文件与 DB-07 拦截；
* 站内通知查询/未读/已读；
* DDL 提醒 → 站内通知 → IM 出站成功；
* 未绑定用户与协议端失败 → FALLBACK_WEB_ONLY，Web 站内信仍可查。

前置：
1. 整栈已启动；
2. `p7_mock_model.py` 与 `p8_mock_onebot.py` 已在内部网络运行；
3. Java 使用临时 env 启动（见 scripts/e2e/README.md §7）。

用法：
    sc-python-ai/.venv/bin/python scripts/e2e/p8_api_e2e.py \\
        --base-url http://127.0.0.1 \\
        --model-base-url http://<p7-mock-ip>:11434/v1 \\
        --onebot-admin-url http://127.0.0.1:19090 \\
        --onebot-token p8-e2e-token
"""

from __future__ import annotations

import argparse
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx

BASE = "http://127.0.0.1"
MOCK = "http://127.0.0.1:11434/v1"
ONEBOT_ADMIN = "http://127.0.0.1:19090"
ONEBOT_TOKEN = "p8-e2e-token"
FAILS: list[str] = []


def check(name: str, cond: bool, detail: object = "") -> bool:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)
    return cond


def call(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = response.json()
    except Exception:
        body = None
    return response.status_code, body, response.text


def data_of(body):
    return body.get("data") if isinstance(body, dict) else None


def webhook(client: httpx.Client, payload: dict, token: str | None = None):
    response = client.post(BASE + "/webhooks/onebot/v11",
                           headers={"Authorization": f"Bearer {token or ONEBOT_TOKEN}"},
                           json=payload)
    try:
        body = response.json()
    except Exception:
        body = None
    return response.status_code, body, response.text


def onebot_calls(client: httpx.Client) -> list[dict]:
    response = client.get(ONEBOT_ADMIN + "/__calls", timeout=5.0)
    response.raise_for_status()
    return response.json().get("calls", [])


def onebot_reset(client: httpx.Client) -> None:
    client.post(ONEBOT_ADMIN + "/__reset", timeout=5.0)


def wait_for(fn, timeout: float = 20.0, interval: float = 0.3):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


def register(client: httpx.Client, prefix: str, password: str) -> dict:
    suffix = str(int(time.time()))[-8:]
    username = f"{prefix}_{suffix}"
    status, body, text = call(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status != 201:
        raise RuntimeError(f"注册失败 {status} {text}")
    data = data_of(body)
    return {"username": username, "token": data["access_token"], "uid": str(data["user_id"])}


def save_model_config(client: httpx.Client, token: str) -> None:
    status, body, text = call(client, "PUT", "/api/v1/user/model-config", token, json={
        "chat_provider": "OPENAI_COMPATIBLE",
        "chat_base_url": MOCK,
        "chat_api_key": "sk-p8-not-a-real-key",
        "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MOCK,
        "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed",
        "migrate_existing_courses": False,
    })
    if status != 200:
        raise RuntimeError(f"保存模型配置失败 {status} {text}")


def bind_im(client: httpx.Client, token: str, im_uid: str | None) -> None:
    status, body, text = call(client, "PUT", "/api/v1/user/preferences", token, json={
        "timezone": "Asia/Shanghai",
        "dnd_start_time": "23:30",
        "dnd_end_time": "07:30",
        "bound_im_platform": "QQ_ONEBOT" if im_uid else None,
        "bound_im_uid": im_uid,
    })
    if status != 200:
        raise RuntimeError(f"绑定 IM 失败 {status} {text}")


def create_due_task(client: httpx.Client, token: str, title: str) -> str:
    due = datetime.now(ZoneInfo("Asia/Shanghai")).replace(microsecond=0) + timedelta(hours=5)
    status, body, text = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_p8_" + uuid.uuid4().hex,
        "course_name": "P8 联调课程",
        "task_title": title,
        "time_precision": "EXACT_DATETIME",
        "due_date": due.date().isoformat(),
        "due_time": due.strftime("%H:%M:%S"),
        "deliverable_format": "PDF",
        "submission_channel": "学习通",
    })
    if status not in (200, 201):
        raise RuntimeError(f"建任务失败 {status} {text}")
    return str(data_of(body)["task_id"])


def find_notification(client: httpx.Client, token: str, task_id: str):
    status, body, _ = call(client, "GET", "/api/v1/notifications?size=100", token)
    if status != 200:
        return None
    for item in data_of(body).get("items", []):
        if item.get("biz_type") == "DDL_T6H" and item.get("biz_id") == task_id:
            return item
    return None


def main() -> int:
    global BASE, MOCK, ONEBOT_ADMIN, ONEBOT_TOKEN
    parser = argparse.ArgumentParser(description="P8 Web+OneBot real-stack API E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MOCK)
    parser.add_argument("--onebot-admin-url", default=ONEBOT_ADMIN)
    parser.add_argument("--onebot-token", default=ONEBOT_TOKEN)
    parser.add_argument("--password", default="Passw0rd123")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MOCK = args.model_base_url.rstrip("/")
    ONEBOT_ADMIN = args.onebot_admin_url.rstrip("/")
    ONEBOT_TOKEN = args.onebot_token

    client = httpx.Client(timeout=30.0, trust_env=False)
    suffix = str(int(time.time()))[-8:]
    im_a = f"2468{suffix}"
    im_b = f"2469{suffix}"
    im_c = f"2470{suffix[:-2]}14"
    user_a = register(client, "p8_qq_a", args.password)
    save_model_config(client, user_a["token"])
    bind_im(client, user_a["token"], im_a)
    user_b = register(client, "p8_qq_b", args.password)
    bind_im(client, user_b["token"], im_b)
    user_c = register(client, "p8_qq_c", args.password)
    bind_im(client, user_c["token"], im_c)
    print(f"[users] A={user_a['username']} B={user_b['username']} C={user_c['username']}")

    # 1. Webhook 鉴权与忽略路径
    status, body, text = webhook(client, {"post_type": "meta_event"}, token="wrong")
    check("Webhook 错误令牌 → 401 空响应", status == 401 and text == "", (status, text))
    status, body, text = webhook(client, {
        "post_type": "message", "message_type": "private", "message_id": "u1",
        "user_id": 24999999, "time": int(time.time()),
        "message": [{"type": "text", "data": {"text": "/list"}}]})
    check("未绑定发送者 → 200 空响应", status == 200 and text == "", (status, text))

    onebot_reset(client)
    status, body, _ = webhook(client, {"post_type": "meta_event",
                                       "meta_event_type": "heartbeat"})
    check("meta_event → RECEIVED",
          status == 200 and (body or {}).get("inbound_status") == "RECEIVED", body)

    # 2. 去重与私聊文本
    payload = {
        "post_type": "message", "message_type": "private", "message_id": "p8-dup-1",
        "user_id": int(im_a), "self_id": 987654321, "time": int(time.time()),
        "message": [{"type": "text", "data": {"text": "/list"}}],
    }
    onebot_reset(client)
    status, body, _ = webhook(client, payload)
    check("私聊 /list → RECEIVED",
          status == 200 and (body or {}).get("inbound_status") == "RECEIVED", body)
    first_calls = wait_for(lambda: (lambda c: c if c else None)(onebot_calls(client)))
    check("私聊 /list 收到一条回复",
          bool(first_calls) and first_calls[-1]["action"] == "send_private_msg",
          first_calls)
    status, body, _ = webhook(client, payload)
    check("重复消息 → DUPLICATE_IGNORED",
          status == 200 and (body or {}).get("inbound_status") == "DUPLICATE_IGNORED", body)
    time.sleep(1.0)
    check("重复消息不再次回复", len(onebot_calls(client)) == len(first_calls or []),
          onebot_calls(client))

    # 3. 群聊 @ 规则
    onebot_reset(client)
    group_no_at = {
        "post_type": "message", "message_type": "group", "message_id": "p8-group-no-at",
        "user_id": int(im_a), "self_id": 987654321, "time": int(time.time()),
        "message": [{"type": "text", "data": {"text": "/list"}}],
    }
    status, body, _ = webhook(client, group_no_at)
    check("群聊未 @ → 200 忽略", status == 200
          and (body or {}).get("inbound_status") == "RECEIVED", body)
    time.sleep(1.0)
    check("群聊未 @ 不回复", onebot_calls(client) == [], onebot_calls(client))

    group_at = dict(group_no_at)
    group_at["message_id"] = "p8-group-at"
    group_at["message"] = [
        {"type": "at", "data": {"qq": "987654321"}},
        {"type": "text", "data": {"text": "/list"}},
    ]
    status, body, _ = webhook(client, group_at)
    check("群聊 @ 机器人 → 处理", status == 200, body)
    check("群聊 @ 收到回复", wait_for(lambda: bool(onebot_calls(client))), onebot_calls(client))

    # 4. 单层转发与嵌套转发
    onebot_reset(client)
    forward = {
        "post_type": "message", "message_type": "private", "message_id": "p8-forward-1",
        "user_id": int(im_a), "time": int(time.time()),
        "message": [{"type": "forward", "data": {"id": "forward-ok"}}],
    }
    status, body, _ = webhook(client, forward)
    check("单层转发 → ACK", status == 200, body)
    calls = wait_for(lambda: (lambda c: c if len(c) >= 2 else None)(onebot_calls(client)),
                     timeout=25)
    check("单层转发触发 get_forward_msg",
          bool(calls) and any(c["action"] == "get_forward_msg" for c in calls), calls)
    check("单层转发产生私聊回复", bool(calls) and any(
        c["action"] == "send_private_msg" and str(c["body"].get("user_id")) == im_a
        for c in calls), calls)

    onebot_reset(client)
    nested = dict(forward)
    nested["message_id"] = "p8-forward-nested"
    nested["message"] = [{"type": "forward", "data": {"id": "forward-nested"}}]
    status, body, _ = webhook(client, nested)
    check("嵌套转发 → ACK", status == 200, body)
    calls = wait_for(lambda: (lambda c: c if any(
        item["action"] == "send_private_msg"
        and "未能完整读取" in str(item["body"].get("message")) for item in c) else None)(
            onebot_calls(client)))
    check("嵌套转发回复 E-IM-03 指引", bool(calls), calls)

    # 5. 文件：未配置 Embedding 前置拦截
    onebot_reset(client)
    file_payload = {
        "post_type": "message", "message_type": "private", "message_id": "p8-file-no-emb",
        "user_id": int(im_b), "time": int(time.time()),
        "message": [{"type": "file", "data": {
            "file_id": "file-ok", "name": "p8_e2e.pdf", "file_size": 100}}],
    }
    status, body, _ = webhook(client, file_payload)
    check("无 Embedding 文件事件 → ACK", status == 200, body)
    calls = wait_for(lambda: (lambda c: c if c else None)(onebot_calls(client)))
    check("无 Embedding 不调用 get_file",
          bool(calls) and not any(c["action"] == "get_file" for c in calls), calls)
    check("无 Embedding 回复配置引导", bool(calls) and any(
        c["action"] == "send_private_msg" and "尚未配置完整" in str(c["body"].get("message"))
        for c in calls), calls)

    # 6. 文件：配置完整 → 默认课程入库
    onebot_reset(client)
    file_ok = dict(file_payload)
    file_ok["message_id"] = "p8-file-ok"
    file_ok["user_id"] = int(im_a)
    status, body, _ = webhook(client, file_ok)
    check("合法文件事件 → ACK", status == 200, body)
    calls = wait_for(lambda: (lambda c: c if len(c) >= 2 else None)(onebot_calls(client)),
                     timeout=25)
    check("合法文件触发 get_file",
          bool(calls) and any(c["action"] == "get_file" for c in calls), calls)
    check("合法文件回执包含默认课程", bool(calls) and any(
        c["action"] == "send_private_msg" and "未分类课程" in str(c["body"].get("message"))
        for c in calls), calls)
    status, body, _ = call(client, "GET", "/api/v1/courses", user_a["token"])
    courses = data_of(body) or []
    default_course = next((c for c in courses if c.get("course_name") == "未分类课程"), None)
    check("默认课程已创建", status == 200 and default_course is not None, body)
    if default_course:
        status, body, _ = call(client, "GET",
                               f"/api/v1/courses/{default_course['course_id']}/docs",
                               user_a["token"])
        docs = data_of(body) or []
        check("文件进入文档列表", status == 200
              and any(d.get("doc_name") == "p8_e2e.pdf" for d in docs), body)

    # 7. DDL 提醒 → 站内通知 → IM 发送
    onebot_reset(client)
    title_a = "P8 联调实验报告 A"
    task_a = create_due_task(client, user_a["token"], title_a)
    note_a = wait_for(lambda: find_notification(client, user_a["token"], task_a),
                      timeout=45)
    check("用户 A 生成 T-6h 站内通知", note_a is not None, note_a)
    if note_a:
        task_ref = f"#T{task_a}"
        sent = wait_for(lambda: (lambda c: c if any(
            item["action"] == "send_private_msg"
            and str(item["body"].get("user_id")) == im_a
            and task_ref in str(item["body"].get("message")) for item in c) else None)(
                onebot_calls(client)), timeout=25)
        check("用户 A 收到 IM 推送", bool(sent), sent)
        latest = wait_for(lambda: (lambda n: n if n and n.get("outbound_status")
                                   == "SENT_IM" else None)(
            find_notification(client, user_a["token"], task_a)), timeout=25)
        check("用户 A 通知转 SENT_IM", latest is not None, latest)
        status, body, _ = call(client, "PATCH", "/api/v1/notifications/read",
                               user_a["token"], json={
                                   "mark_all": False,
                                   "notification_ids": [note_a["notification_id"]]})
        check("标记单条已读", status == 200
              and (data_of(body) or {}).get("updated_count") >= 1, body)

    # 8. 未绑定用户 → FALLBACK_WEB_ONLY，Web 仍有站内信
    onebot_reset(client)
    title_b = "P8 联调未绑定用户 B"
    # user_b 先解绑验证 fallback
    bind_im(client, user_b["token"], None)
    task_b = create_due_task(client, user_b["token"], title_b)
    note_b = wait_for(lambda: find_notification(client, user_b["token"], task_b),
                      timeout=45)
    check("未绑定用户生成站内通知", note_b is not None, note_b)
    check("未绑定用户直接 FALLBACK_WEB_ONLY", note_b is not None
          and note_b.get("outbound_status") == "FALLBACK_WEB_ONLY", note_b)
    time.sleep(2.0)
    check("未绑定用户不发送 IM", not any(
        str(c["body"].get("user_id")) == im_b for c in onebot_calls(client)),
        onebot_calls(client))

    # 9. 协议端失败 → 重试耗尽 FALLBACK_WEB_ONLY
    onebot_reset(client)
    title_c = "P8 联调失败用户 C"
    task_c = create_due_task(client, user_c["token"], title_c)
    note_c = wait_for(lambda: find_notification(client, user_c["token"], task_c),
                      timeout=45)
    check("失败用户生成站内通知", note_c is not None, note_c)
    final_c = wait_for(lambda: (lambda n: n if n and n.get("outbound_status")
                                == "FALLBACK_WEB_ONLY" else None)(
        find_notification(client, user_c["token"], task_c)), timeout=40)
    check("协议端失败最终 FALLBACK_WEB_ONLY", final_c is not None, final_c)
    fail_calls = [c for c in onebot_calls(client)
                  if c["action"] == "send_private_msg"
                  and str(c["body"].get("user_id")) == im_c]
    check("失败用户发送尝试 3 次后降级", len(fail_calls) == 3, fail_calls)

    print(f"\nP8 API E2E: {len(FAILS)} failure(s)")
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
