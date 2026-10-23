#!/usr/bin/env python3
"""P4 浏览器 E2E（Playwright + 系统 Chrome）。

前置：
  1. 整栈已启动，Nginx 入口可达；
  2. mock 模型容器已启动，且其**私网 IP 字面量**已写入
     `SC_ALLOW_LOCAL_MODEL_BASE_URL=true` 与 `SC_LOCAL_MODEL_ALLOWED_ORIGINS`；
  3. 通过 `--mock-base-url` 传入同一地址（不要使用 Docker 服务名）。

覆盖：注册/登录、空课程状态、创建课程、上传文字版 PDF、真实解析到 READY、
文档失败后重试入口、READY 后重建入口、默认 Embedding 变更的迁移确认弹窗、
DELETING 默认可见、课程切换、退出登录后课程上下文清空、0 console error。
脚本只使用假 Key，不读取/打印 `.env`。

账号策略：脚本会注册临时账号并保留（不自动删除），只清理本次创建的文档与课程；
账号清理需由维护者在授权后按 README 的手工 SQL 执行。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

from ingest_e2e import build_pdf, scanned_pdf

FAKE_KEY = "sk-p4-browser-not-a-real-key"
PASSWORD = "E2e-P4-Pass-123"


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
    parser = argparse.ArgumentParser(description="P4 浏览器 E2E")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--username", default=f"e2e_p4_browser_{int(time.time())}")
    parser.add_argument("--mock-base-url", required=True,
                        help="mock 模型地址，必须是已加入白名单的私网 IP 字面量，"
                             "如 http://172.20.0.8:9000/v1")
    parser.add_argument("--chrome-channel", default="chrome")
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--screenshot-dir", default="/tmp")
    args = parser.parse_args()

    status, payload = call(args.base_url, "POST", "/api/v1/auth/register", body={
        "username": args.username, "password": PASSWORD})
    if status != 201:
        raise SystemExit(f"注册失败：{status} {payload}")
    token = payload["data"]["access_token"]
    status, payload = save_models(args.base_url, token, args.mock_base_url)
    if status != 200 or payload["data"].get("default_embedding_dim") != 8:
        raise SystemExit(f"保存 mock 模型失败：{status} {payload}")

    course_a = f"P4 浏览器课程 A {int(time.time())}"
    course_b = f"P4 浏览器课程 B {int(time.time())}"
    pdf_path = "/tmp/sc-p4-browser-upload.pdf"
    with open(pdf_path, "wb") as handle:
        handle.write(build_pdf([
            "Operating system chapter one: process scheduling and context switching.",
            "Chapter two covers virtual memory and page replacement policies."]))
    scan_path = "/tmp/sc-p4-browser-scan.pdf"
    with open(scan_path, "wb") as handle:
        handle.write(scanned_pdf())

    results: list[bool] = []
    console_errors: list[str] = []

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            channel=args.chrome_channel, headless=not args.headed)
        page = browser.new_page(viewport={"width": 1536, "height": 1024})
        page.on("console", lambda message: console_errors.append(message.text)
                if message.type == "error" else None)

        page.goto(f"{args.base_url}/login", wait_until="networkidle")
        page.get_by_placeholder("3~64 位字母、数字、下划线或连字符").fill(args.username)
        page.get_by_placeholder("至少 8 位，需同时包含字母与数字").fill(PASSWORD)
        page.get_by_role("button", name="登录").click()
        page.wait_for_url(lambda url: "/login" not in url, timeout=15000)
        results.append(check("登录成功并离开登录页", True))

        page.goto(f"{args.base_url}/knowledge", wait_until="networkidle")
        results.append(check("空课程状态可见",
                             page.get_by_text("还没有课程空间").is_visible(timeout=10000)))

        page.get_by_role("link", name="课程空间").click()
        page.wait_for_url("**/courses", timeout=10000)
        for name in (course_a, course_b):
            page.get_by_role("button", name="新建课程").first.click()
            page.locator(".el-dialog input").first.fill(name)
            page.locator(".el-dialog").get_by_role("button", name="创建").click()
            page.get_by_text(name).first.wait_for(timeout=10000)
        results.append(check("通过页面创建两门课程", True))

        page.get_by_role("link", name="知识库").click()
        page.wait_for_url("**/knowledge", timeout=10000)
        page.get_by_role("button", name=course_a, exact=False).first.click()
        page.locator("input[type=file]").set_input_files(pdf_path)
        ready_row = page.locator(".el-table__row", has_text="sc-p4-browser-upload.pdf").first
        ready_row.wait_for(timeout=15000)
        ready_row.get_by_text("就绪").wait_for(timeout=90000)
        results.append(check("上传文字版 PDF 后由消费者完成真实解析并 READY", True))

        ready_row.get_by_role("button", name="重建", exact=True).click()
        page.get_by_text("已重新进入向量重建队列", exact=False).first.wait_for(timeout=15000)
        results.append(check("READY 文档展示重试/重建入口并可触发重建", True))
        ready_row.get_by_text("就绪").wait_for(timeout=90000)

        page.locator("input[type=file]").set_input_files(scan_path)
        scan_row = page.locator(".el-table__row", has_text="sc-p4-browser-scan.pdf").first
        scan_row.wait_for(timeout=15000)
        scan_row.get_by_text("失败").wait_for(timeout=90000)
        scan_row.get_by_role("button", name="重试", exact=True).click()
        page.get_by_text("已重新进入解析队列", exact=False).first.wait_for(timeout=15000)
        results.append(check("失败文档展示重试入口并可重新入队", True))

        page.wait_for_timeout(2600)
        page.screenshot(path=f"{args.screenshot_dir}/sc-p4-knowledge.png", full_page=True)

        scan_row.get_by_role("button", name="删除", exact=True).click()
        page.locator(".el-message-box").get_by_role("button", name="删除").click()
        page.get_by_text("删除中").first.wait_for(timeout=15000)
        results.append(check("删除后 DELETING 默认可见", True))
        page.wait_for_timeout(2600)
        page.screenshot(path=f"{args.screenshot_dir}/sc-p4-deleting.png", full_page=True)

        # 课程切换（使用已保存默认 Embedding；探测走 mock）
        page.get_by_role("link", name="课程空间").click()
        page.wait_for_url("**/courses", timeout=10000)
        row = page.locator(".el-table__row", has_text=course_a).first
        row.get_by_role("button", name="切换模型").click()
        dialog = page.locator(".el-dialog", has_text="切换课程 Embedding 模型")
        dialog.get_by_text("重建期间文档暂停检索", exact=False).wait_for(timeout=10000)
        dialog.get_by_role("button", name="确认切换").click()
        page.get_by_text("已提交重建请求", exact=False).first.wait_for(timeout=15000)
        results.append(check("课程切换成功且明示重建期间暂停检索", True))
        page.wait_for_timeout(2600)
        page.screenshot(path=f"{args.screenshot_dir}/sc-p4-courses.png", full_page=True)

        # 退出登录后课程上下文清空：先选中 A，重新登录后应回到默认第一门（B）
        page.get_by_role("link", name="知识库").click()
        page.wait_for_url("**/knowledge", timeout=10000)
        page.get_by_role("button", name=course_a, exact=False).first.click()
        page.wait_for_timeout(500)
        page.get_by_role("button", name="退出").click()
        page.wait_for_url("**/login", timeout=10000)

        page.get_by_placeholder("3~64 位字母、数字、下划线或连字符").fill(args.username)
        page.get_by_placeholder("至少 8 位，需同时包含字母与数字").fill(PASSWORD)
        page.get_by_role("button", name="登录").click()
        page.wait_for_url(lambda url: "/login" not in url, timeout=15000)
        page.goto(f"{args.base_url}/knowledge", wait_until="networkidle")
        active = page.locator(".sc-knowledge__course--active").first.inner_text()
        results.append(check("退出登录后课程上下文已清空（不再保留 A）",
                             course_a not in active, active))

        # 默认 Embedding 变更：存在已建课程时必须在保存前弹窗确认迁移
        page.goto(f"{args.base_url}/settings", wait_until="networkidle")
        page.locator('input[name="sc-embedding-model-name"]').fill("embed-mock-v2")
        page.locator('input[name="sc-embedding-api-key"]').fill(FAKE_KEY)
        page.get_by_role("button", name="保存模型配置").click()
        migrate_dialog = page.locator(".el-message-box", has_text="迁移已有课程")
        migrate_dialog.wait_for(timeout=15000)
        results.append(check("保存前弹窗提示迁移已有课程", True))
        migrate_dialog.get_by_role("button", name="取消").click()
        page.wait_for_timeout(800)
        page.get_by_role("button", name="保存模型配置").click()
        migrate_dialog.wait_for(timeout=15000)
        migrate_dialog.get_by_role("button", name="迁移并保存").click()
        page.get_by_text("已有课程已提交重建", exact=False).first.wait_for(timeout=30000)
        results.append(check("确认后保存并提交课程重建", True))

        results.append(check("浏览器 console 无 error", not console_errors,
                             "; ".join(console_errors[:3])))
        browser.close()

    # 清理：重新登录拿 token，删除测试文档与课程
    status, payload = call(args.base_url, "POST", "/api/v1/auth/login",
                           body={"username": args.username, "password": PASSWORD})
    if status == 200:
        token = payload["data"]["access_token"]
        status, payload = call(args.base_url, "GET", "/api/v1/courses", token=token)
        for course in payload.get("data") or []:
            docs = call(args.base_url, "GET",
                        f"/api/v1/courses/{course['course_id']}/docs", token=token)[1]
            for doc in docs.get("data") or []:
                call(args.base_url, "DELETE", f"/api/v1/docs/{doc['doc_id']}", token=token)
            call(args.base_url, "DELETE", f"/api/v1/courses/{course['course_id']}", token=token)

    failed = results.count(False)
    print(f"P4 浏览器 E2E：{len(results) - failed}/{len(results)} 项通过；账号 {args.username}（保留，未自动删除）")
    print(f"截图：{args.screenshot_dir}/sc-p4-{{knowledge,deleting,courses}}.png")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
