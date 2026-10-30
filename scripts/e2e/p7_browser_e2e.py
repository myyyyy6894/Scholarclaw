#!/usr/bin/env python3
"""P7 DDL 真实浏览器 E2E（Playwright + 系统 Chrome，可复现基线）。

前置：整栈已启动、模型替身 `p7_mock_model.py` 可达、临时 venv 已安装 playwright。

用法：

    /tmp/sc-e2e-venv/bin/python scripts/e2e/p7_browser_e2e.py \\
        --base-url http://127.0.0.1 \\
        --model-base-url http://<mock-ip>:11434/v1
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
from playwright.sync_api import sync_playwright

BASE = "http://127.0.0.1"
MOCK = "http://172.20.0.250:11434/v1"
FAILS: list[str] = []


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


def api_call(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = resp.json()
    except Exception:
        body = {"raw": resp.text}
    return resp.status_code, body


def main() -> int:
    global BASE, MOCK
    parser = argparse.ArgumentParser(description="P7 DDL real browser E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--model-base-url", default=MOCK)
    parser.add_argument("--username", default=None)
    parser.add_argument("--password", default="Passw0rd123")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")
    MOCK = args.model_base_url.rstrip("/")
    # 固定 reference time 并据此生成未来日期，避免固定日期随时间流逝而失效。
    reference = datetime.now(ZoneInfo("Asia/Shanghai")).replace(microsecond=0)
    exact_day = (reference + timedelta(days=3)).date()
    changed_day = (reference + timedelta(days=40)).date()
    activate_day = (reference + timedelta(days=10)).date()

    def ymd(day) -> str:
        return f"{day.year}年{day.month}月{day.day}日"

    print(f"[reference] {reference.isoformat()}")
    print(f"[generated] EXACT={exact_day} CHANGED={changed_day} ACTIVATE={activate_day}")
    client = httpx.Client(timeout=30.0, trust_env=False)
    suffix = str(int(time.time()))[-8:]
    username = args.username or f"p7r3ui_{suffix}"
    password = args.password
    st, body = api_call(client, "POST", "/api/v1/auth/register",
                        json={"username": username, "password": password,
                              "timezone": "Asia/Shanghai"})
    assert st in (200, 201), body
    data = body["data"]
    token = data["access_token"]
    uid = data["user_id"]
    st, body = api_call(client, "PUT", "/api/v1/user/model-config", token, json={
        "chat_provider": "OPENAI_COMPATIBLE", "chat_base_url": MOCK,
        "chat_api_key": "sk-test", "chat_model": "mock-chat",
        "default_embedding_provider": "OPENAI_COMPATIBLE",
        "embedding_base_url": MOCK, "reuse_chat_api_key": True,
        "default_embedding_model": "mock-embed", "migrate_existing_courses": False,
    })
    assert st == 200, body

    # 预置一个 DATE_ONLY 过去的待确认任务，用于验证 23:59 推断说明。
    st, body = api_call(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_ui_dateonly_" + uuid.uuid4().hex,
        "course_name": "操作系统原理", "task_title": "补录日期任务",
        "time_precision": "DATE_ONLY", "due_date": "2026-10-01",
        "due_time": None, "deliverable_format": None, "submission_channel": None})
    assert st in (200, 201), body

    session = json.dumps({"token": token, "user": {"user_id": uid, "username": username}})
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True,
                                    args=["--no-sandbox"])
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        context.add_init_script(
            f"localStorage.setItem('sc.auth.session', {json.dumps(session)});")
        page = context.new_page()
        page.goto(BASE + "/ddl", wait_until="networkidle")
        page.wait_for_selector("text=DDL 待办工作台", timeout=15000)

        def body_text() -> str:
            return page.locator("body").inner_text()

        def extract(raw: str) -> None:
            page.get_by_role("button", name="粘贴通知提取").click()
            dialog = page.locator(".el-dialog", has_text="粘贴通知提取 DDL")
            dialog.locator("textarea").fill(raw)
            dialog.get_by_role("button", name="开始提取").click()
            page.wait_for_timeout(1200)

        def refresh() -> None:
            page.get_by_role("button", name="刷新").click()
            page.wait_for_timeout(800)

        # 0. 空状态与错误状态可区分
        check("浏览器：空状态可区分", "没有进行中的任务。" in body_text()
              and "还没有已完成的任务。" in body_text(), body_text()[:800])
        page.route("**/api/v1/ddl/tasks*", lambda route: route.abort())
        page.get_by_role("button", name="刷新").click()
        page.wait_for_timeout(800)
        check("浏览器：加载失败显示错误状态", "DDL 看板加载失败" in body_text(),
              body_text()[:800])
        page.unroute("**/api/v1/ddl/tasks*")
        refresh()

        # 1. 通知提取：EXACT
        exact_text = f"请于{ymd(exact_day)} 22:00 前提交实验报告"
        extract(exact_text)
        text = body_text()
        check("浏览器：通知提取建单", "提交实验二报告" in text, text[:400])
        check("浏览器：进行中分组出现任务", "进行中与已逾期（" in text)
        # FR-DDL-001：用户可见截止时间必须带时区标签。
        check("浏览器：截止时间显示用户时区", "Asia/Shanghai" in text, text[:600])

        # 2. 待确认收件箱：VAGUE
        extract("请大家尽快提交实验报告")
        text = body_text()
        check("浏览器：VAGUE 进入待确认收件箱", "尽快提交实验报告" in text
              and "待补充时间" in text, text[:600])
        check("浏览器：待确认原因展示",
              "未生成确定截止时间" in text or "未给出可用的截止时间" in text, text[:900])

        # 3. DATE_ONLY 待确认卡片的 23:59 说明
        refresh()
        text = body_text()
        check("浏览器：DATE_ONLY 待确认卡片显示 23:59 推断",
              "补录日期任务" in text and "原通知未注明具体时刻，暂按当日 23:59 统计" in text,
              text[:800])

        # 4. 草案三向决策（在完成目标前执行）
        extract(f"请于{ymd(changed_day)} 22:00 前提交实验二报告")
        draft_dialog = page.locator(".el-dialog", has_text="重复通知变更确认")
        check("浏览器：重复通知弹出变更确认", draft_dialog.count() > 0,
              body_text()[:800])
        draft_dialog.get_by_role("button", name="确认更新原任务").click()
        page.wait_for_timeout(1200)
        text = body_text()
        check("浏览器：CONFIRM_UPDATE 生效", changed_day.isoformat() in text,
              text[:1400])
        check("浏览器：草案对比显示时区", "Asia/Shanghai" in text, text[:1400])

        extract(exact_text)
        draft_dialog = page.locator(".el-dialog", has_text="重复通知变更确认")
        check("浏览器：第二次草案弹出", draft_dialog.count() > 0)
        draft_dialog.get_by_role("button", name="忽略草案").click()
        page.wait_for_timeout(1000)
        check("浏览器：DISCARD 后原任务仍在", "提交实验二报告" in body_text())

        extract(exact_text)
        draft_dialog = page.locator(".el-dialog", has_text="重复通知变更确认")
        check("浏览器：第三次草案弹出", draft_dialog.count() > 0)
        draft_dialog.get_by_role("button", name="仍然新建").click()
        page.wait_for_timeout(1400)
        text = body_text()
        check("浏览器：CREATE_NEW 产生独立任务",
              text.count("提交实验二报告") >= 2, text[:1800])

        # 5. 编辑激活：把 PENDING_TIME 任务补充为未来时间
        pending_card = page.locator(".sc-ddl-inbox__card", has_text="尽快提交实验报告")
        pending_card.get_by_role("button", name="补充时间 / 编辑").click()
        edit_dialog = page.locator(".el-dialog", has_text="编辑任务")
        edit_dialog.get_by_text("精确到时刻", exact=True).click()
        page.wait_for_timeout(300)
        edit_dialog.get_by_placeholder("选择截止日期").fill(activate_day.isoformat())
        edit_dialog.get_by_placeholder("选择截止日期").press("Enter")
        edit_dialog.get_by_placeholder("选择截止时刻").fill("22:00")
        edit_dialog.get_by_placeholder("选择截止时刻").press("Enter")
        edit_dialog.get_by_role("button", name="保存").click()
        page.wait_for_timeout(1400)
        active_section = body_text().split("进行中与已逾期")[1].split("已完成")[0]
        check("浏览器：编辑激活（PENDING_TIME → ACTIVE）",
              "尽快提交实验报告" in active_section, active_section[:800])

        # 6. 完成态编辑：勾选完成后在已完成表格编辑标题
        row = page.locator("tr", has_text="尽快提交实验报告").first
        row.locator(".el-checkbox").click()
        page.wait_for_timeout(1400)
        completed_card = page.locator(".el-card", has_text="已完成（最近")
        completed_row = completed_card.locator("tr", has_text="尽快提交实验报告").first
        completed_row.get_by_role("button", name="编辑").click()
        edit_dialog = page.locator(".el-dialog", has_text="编辑任务")
        edit_dialog.get_by_placeholder("如：提交实验二报告与源码").fill("完成态改名任务")
        edit_dialog.get_by_role("button", name="保存").click()
        page.wait_for_timeout(1400)
        text = body_text()
        check("浏览器：完成态编辑保持已完成", "完成态改名任务" in text
              and "已完成" in text, text[:1600])

        # 7. 删除任务：用 display_code 精确断言被删除的那一条从看板移除
        #    （同名任务可能有多条，不能只数标题出现次数）
        cancel_row = page.locator("tr", has_text="提交实验二报告").first
        canceled_code = cancel_row.locator(".sc-ddl-code").first.inner_text().strip()
        cancel_row.get_by_role("button", name="删除", exact=True).click()
        page.locator(".el-message-box").get_by_role("button", name="删除", exact=True).click()
        page.wait_for_timeout(1200)
        # 成功提示 toast 里也会出现任务编号，必须只看表格行。
        check("浏览器：删除任务后从看板移除",
              page.locator("tr", has_text=canceled_code).count() == 0,
              body_text()[:1600])

        # 8. 手工新建：服务端已建单但响应丢失，修改表单后重试必须换新请求 ID
        page.get_by_role("button", name="手动新建").click()
        create_dialog = page.locator(".el-dialog", has_text="手动新建 DDL 任务")
        create_dialog.get_by_placeholder("如：操作系统原理").fill("操作系统原理")
        create_dialog.get_by_placeholder("如：提交实验二报告与源码").fill("超时重试任务")
        create_dialog.get_by_text("仅日期", exact=True).click()
        page.wait_for_timeout(200)
        create_dialog.get_by_placeholder("选择截止日期").fill(activate_day.isoformat())
        create_dialog.get_by_placeholder("选择截止日期").press("Enter")
        lost = {"done": False}

        def lose_response(route):
            if lost["done"]:
                route.continue_()
                return
            lost["done"] = True
            try:
                route.fetch()   # 服务端确实建单
            finally:
                route.abort()   # 客户端看不到响应，模拟超时

        page.route("**/api/v1/ddl/tasks", lose_response)
        create_dialog.get_by_role("button", name="创建任务").click()
        page.wait_for_timeout(1400)
        check("浏览器：超时后新建弹窗保留", create_dialog.count() > 0)
        create_dialog.get_by_placeholder("如：提交实验二报告与源码").fill("超时重试任务（已修改）")
        create_dialog.get_by_role("button", name="创建任务").click()
        page.wait_for_timeout(1600)
        page.unroute("**/api/v1/ddl/tasks")
        refresh()
        text = body_text()
        check("浏览器：超时后改表单换新请求 ID 并成功创建",
              "超时重试任务（已修改）" in text and "超时重试任务" in text, text[:1800])

        page.screenshot(path="/tmp/sc_p7_ddl_browser.png", full_page=True)
        browser.close()

    client.close()
    print("\nFAILED:", FAILS if FAILS else "none")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
