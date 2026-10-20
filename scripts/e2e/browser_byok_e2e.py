#!/usr/bin/env python3
"""浏览器 E2E（Playwright + 系统 Chrome）：登录/注册 + BYOK 设置页。

依赖（固定版本见 scripts/e2e/README.md）：
    pip install playwright==1.51.0
    playwright install chromium   # 可选；默认使用系统 Chrome channel

用法：
    python3 scripts/e2e/browser_byok_e2e.py
    python3 scripts/e2e/browser_byok_e2e.py --mock-base-url http://sc-e2e-mock-model:9000/v1

不传 `--mock-base-url` 时只验证登录/注册、设置页渲染、空状态、偏好保存与登出；
传了才执行探测/保存/掩码/刷新/销毁 Key（需要后端已把 mock origin 加入白名单）。
"""

from __future__ import annotations

import argparse
import sys
import time

from playwright.sync_api import sync_playwright

FAKE_KEY = "sk-browser-e2e-not-a-real-key"
PASSWORD = "E2eBrowser2026Pass1"


def main() -> int:
    parser = argparse.ArgumentParser(description="ScholarClaw 浏览器 E2E")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--username", default=f"e2e_browser_{int(time.time())}")
    parser.add_argument("--mock-base-url", default=None,
                        help="已加入后端白名单的 mock 模型地址，如 http://sc-e2e-mock-model:9000/v1")
    parser.add_argument("--chrome-channel", default="chrome")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    failures: list[str] = []

    def expect(condition: bool, message: str) -> None:
        print(f"{'PASS' if condition else 'FAIL'}  {message}")
        if not condition:
            failures.append(message)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            channel=args.chrome_channel, headless=not args.headed, args=["--no-sandbox"])
        page = browser.new_page()
        console_errors: list[str] = []
        page.on("console", lambda message: console_errors.append(message.text)
                if message.type == "error" else None)

        # 1. 注册并自动登录
        page.goto(f"{base}/login")
        page.wait_for_load_state("networkidle")
        page.get_by_role("tab", name="注册").click()
        page.locator('input[placeholder="3~64 位字母、数字、下划线或连字符"]').fill(args.username)
        page.locator('input[placeholder="至少 8 位，需同时包含字母与数字"]').fill(PASSWORD)
        page.locator('input[placeholder="再次输入密码"]').fill(PASSWORD)
        page.get_by_role("button", name="注册并登录").click()
        page.wait_for_url("**/settings", timeout=20000)
        page.get_by_text("尚未配置模型").wait_for(timeout=20000)
        expect(True, "注册并进入设置页 + 未配置空状态")

        # 2. 可选：mock 探测 / 保存 / 掩码 / 刷新 / 销毁
        if args.mock_base_url:
            page.locator('input[placeholder="https://api.deepseek.com/v1"]').fill(args.mock_base_url)
            page.locator('input[placeholder="deepseek-chat"]').fill("deepseek-chat")
            page.locator('input[type="password"]').nth(0).fill(FAKE_KEY)
            page.locator('input[placeholder="https://api.siliconflow.cn/v1"]').fill(args.mock_base_url)
            page.locator('input[placeholder="BAAI/bge-m3"]').fill("mock-embed")
            page.get_by_text("复用对话模型的 API Key").click()

            page.get_by_role("button", name="测试对话模型").click()
            page.get_by_text("延迟", exact=False).first.wait_for(timeout=20000)
            expect(True, "对话模型探测成功")

            page.get_by_role("button", name="测试 Embedding 模型").click()
            page.get_by_text("维度 8", exact=False).first.wait_for(timeout=20000)
            expect(True, "Embedding 探测成功（维度 8）")

            page.get_by_role("button", name="保存模型配置").click()
            page.get_by_text("模型配置已保存", exact=False).first.wait_for(timeout=20000)
            page.wait_for_load_state("networkidle")
            expect("sk-****" in page.content(), "保存后显示密钥掩码")

            page.reload()
            page.get_by_text("sk-****", exact=False).first.wait_for(timeout=20000)
            expect(FAKE_KEY not in page.evaluate("() => JSON.stringify(window.localStorage)"),
                   "刷新后掩码仍在，localStorage 无明文 Key")

            page.get_by_role("button", name="销毁对话 Key").click()
            page.get_by_role("button", name="销毁", exact=True).click()
            page.get_by_text("已销毁对话模型 API Key", exact=False).first.wait_for(timeout=20000)
            expect(True, "销毁对话 Key（二次确认）")

        # 3. 偏好保存
        page.locator('input[placeholder="Asia/Shanghai"]').fill("Asia/Tokyo")
        page.get_by_role("button", name="保存偏好").click()
        page.get_by_text("个人偏好已更新", exact=False).first.wait_for(timeout=20000)
        expect(True, "保存个人偏好")

        # 4. 登出
        page.get_by_role("button", name="退出").click()
        page.wait_for_url("**/login", timeout=20000)
        expect(True, "登出回到登录页")

        expect(not console_errors, f"控制台无 error（实际 {len(console_errors)} 条）")
        if console_errors:
            for error in console_errors:
                print(f"  console: {error}")

        browser.close()

    if failures:
        print("\n失败项：")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print(f"\n浏览器 E2E 全部通过。测试账号 `{args.username}` 可按需手工清理。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
