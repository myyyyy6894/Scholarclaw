#!/usr/bin/env python3
"""P8 Web + QQ 联调：Web 站内通知中心与 QQ 离线时核心业务可用。

前置：
1. 整栈已启动（Java/Web 为最新 P8 镜像，临时 env 已开启快速提醒调度）；
2. 本脚本假设 OneBot Action 替身已停止，用于验证“QQ 离线时 Web 仍可用”；
3. 临时 venv 已安装 playwright==1.51.0，系统 Chrome 可用。

用法：
    /tmp/sc-e2e-venv/bin/python scripts/e2e/p8_browser_e2e.py \
        --base-url http://127.0.0.1
"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1"
NAPCAT_WEBUI_URL = "http://127.0.0.1:6099"
FAILS: list[str] = []


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


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


def wait_for(fn, timeout: float = 40.0, interval: float = 0.4):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


def create_due_task(client: httpx.Client, token: str, title: str) -> str:
    due = datetime.now(ZoneInfo("Asia/Shanghai")).replace(microsecond=0) + timedelta(hours=5)
    status, body, text = call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_p8_ui_" + uuid.uuid4().hex,
        "course_name": "P8 Web 联调课程",
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
        if item.get("biz_id") == task_id and item.get("biz_type") == "DDL_T6H":
            return item
    return None


def main() -> int:
    global BASE, NAPCAT_WEBUI_URL
    parser = argparse.ArgumentParser(description="P8 Web notification browser E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--password", default="Passw0rd123")
    parser.add_argument("--screenshot", default="/tmp/sc_p8_notifications.png")
    parser.add_argument("--napcat-webui-url", default=NAPCAT_WEBUI_URL,
                        help="设置页 NapCat 入口的期望 href（构建期 VITE_NAPCAT_WEBUI_URL）")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    NAPCAT_WEBUI_URL = args.napcat_webui_url.rstrip("/")

    client = httpx.Client(timeout=30.0, trust_env=False)
    suffix = str(int(time.time()))[-8:]
    username = f"p8_ui_{suffix}"
    im_uid = f"2471{suffix}"
    status, body, text = call(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": args.password, "timezone": "Asia/Shanghai"})
    if status != 201:
        raise RuntimeError(f"注册失败 {status} {text}")
    data = data_of(body)
    token = data["access_token"]
    uid = str(data["user_id"])
    print(f"[user] {username}")

    # 绑定一个不存在的 OneBot Action 地址由外部保证（QQ 离线场景）；绑定仅影响出站状态。
    status, body, text = call(client, "PUT", "/api/v1/user/preferences", token, json={
        "timezone": "Asia/Shanghai", "dnd_start_time": "23:30",
        "dnd_end_time": "07:30", "bound_im_platform": "QQ_ONEBOT",
        "bound_im_uid": im_uid,
    })
    check("绑定 IM 偏好", status == 200, (status, text))

    title = "P8 Web 通知联调任务"
    task_id = create_due_task(client, token, title)
    note = wait_for(lambda: find_notification(client, token, task_id))
    check("Web 侧任务已生成站内通知", note is not None, note)
    if note is None:
        print(f"\nP8 browser E2E: {len(FAILS)} failure(s)")
        return 1
    # QQ 离线时不能只接受“任意非终态”：必须最终降级为 FALLBACK_WEB_ONLY，
    # 否则说明调度器仍卡在 PENDING_IM / RATE_LIMITED_DELAYED，不能证明降级链路可用。
    final_note = wait_for(lambda: (lambda item: item if item and item.get("outbound_status")
                                  == "FALLBACK_WEB_ONLY" else None)(
        find_notification(client, token, task_id)), timeout=60)
    check("QQ 离线时通知最终 FALLBACK_WEB_ONLY", final_note is not None,
          find_notification(client, token, task_id))
    note = final_note or note

    session = json.dumps({"token": token, "user": {"user_id": uid, "username": username}})
    console_errors: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True,
                                             args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        context.add_init_script(
            f"localStorage.setItem('sc.auth.session', {json.dumps(session)});")
        page = context.new_page()
        page.on("console", lambda msg: console_errors.append(msg.text)
                if msg.type == "error" else None)
        page.goto(BASE + "/ddl", wait_until="networkidle")
        page.wait_for_selector(".sc-notification-trigger", timeout=15000)
        check("DDL 页面加载", title in page.locator("body").inner_text(),
              page.locator("body").inner_text()[:500])

        page.locator(".sc-notification-trigger").click()
        page.wait_for_selector(".sc-notification-panel", timeout=10000)
        page.wait_for_selector(f"text={title}", timeout=10000)
        panel = page.locator(".sc-notification-panel").inner_text()
        check("通知中心展示任务标题", title in panel, panel[:500])
        check("通知中心展示未读角标/未读文案", "未读" in panel, panel[:500])
        page.screenshot(path=args.screenshot, full_page=True)

        page.get_by_role("button", name="全部已读").click()
        page.wait_for_timeout(1200)
        status, body, _ = call(client, "GET", "/api/v1/notifications?size=100", token)
        unread = (data_of(body) or {}).get("unread_count") if status == 200 else None
        check("全部已读后未读数为 0", unread == 0, (status, body))

        # 设置页必须提供可用的 NapCat WebUI 跳转入口（构建期 VITE_NAPCAT_WEBUI_URL）。
        page.goto(BASE + "/settings", wait_until="networkidle")
        entry = page.get_by_role("link", name="打开 NapCat 登录页")
        entry_count = entry.count()
        entry_href = entry.first.get_attribute("href") if entry_count else None
        check("设置页 NapCat 登录入口存在", entry_count == 1, entry_count)
        check("设置页 NapCat 登录入口 href 正确", entry_href == NAPCAT_WEBUI_URL,
              entry_href)
        page.screenshot(path=args.screenshot.replace(".png", "-settings.png"),
                        full_page=True)

        check("页面无 console error", console_errors == [], console_errors[:5])
        browser.close()

    print(f"\nP8 browser E2E: {len(FAILS)} failure(s); screenshot={args.screenshot}")
    if FAILS:
        print("FAILED: " + ", ".join(FAILS))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
