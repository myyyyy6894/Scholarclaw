#!/usr/bin/env python3
"""认证 + BYOK 冒烟脚本（可重复执行，不写真实密钥）。

覆盖 `04` §4.1/§4.2 的关键路径：
注册、重复注册、错误密码、未认证访问、伪造 `X-User-Id`、登录、配置读取、
SSRF 拒绝（内部服务名/内部端口/云元数据）、非法 `BOTH`、偏好校验、Key 销毁、
登出后旧 token 失效。

用法：
    python3 scripts/e2e/auth_byok_smoke.py
    python3 scripts/e2e/auth_byok_smoke.py --base-url http://127.0.0.1

说明：
* 只使用临时测试账号与**假** API Key（假 Key 只进入被 SSRF 前置拦截的请求体，不会出站）；
* 不修改 `.env`、不修改容器；脚本结束后打印测试用户名，可按需手工清理；
* 需要整栈已启动（`docker compose up -d`）且 Nginx 入口可达。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# 临时测试账号口令（非真实凭据）；可用 E2E_PASSWORD 覆盖，便于在共享环境里避免固定值。
PASSWORD = os.environ.get("E2E_PASSWORD", "E2eSmoke2026Pass1")
FAKE_KEY = "sk-e2e-smoke-not-a-real-key"
USER_AGENT = "scholarclaw-e2e-smoke/1.0"


class Smoke:
    def __init__(self, base_url: str, username: str) -> None:
        self.base = base_url.rstrip("/")
        self.username = username
        self.failures: list[str] = []

    def request(self, method: str, path: str, *, body: dict | None = None,
                token: str | None = None, headers: dict[str, str] | None = None) -> tuple[int, dict]:
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(f"{self.base}/api/v1{path}", data=data, method=method)
        request.add_header("Accept", "application/json")
        request.add_header("User-Agent", USER_AGENT)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", f"Bearer {token}")
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def check(self, name: str, actual: tuple[int, dict], expected_status: int,
              expected_code: str = "OK") -> dict:
        status, payload = actual
        ok = status == expected_status and payload.get("code") == expected_code
        print(f"{'PASS' if ok else 'FAIL'}  {name}: HTTP {status} code={payload.get('code')}")
        if not ok:
            self.failures.append(f"{name}: 期望 HTTP {expected_status}/{expected_code}，"
                                 f"实际 HTTP {status}/{payload.get('code')}")
        return payload

    def run(self) -> int:
        print(f"测试账号：{self.username}  base={self.base}")

        # ---- 认证生命周期 ----
        registered = self.check("注册", self.request("POST", "/auth/register", body={
            "username": self.username, "password": PASSWORD,
        }), 201)
        user_id = registered.get("data", {}).get("user_id")
        token = registered.get("data", {}).get("access_token")
        if not isinstance(user_id, str) or not token:
            self.failures.append("注册响应缺少字符串 user_id 或 access_token")

        self.check("重复注册 → 409", self.request("POST", "/auth/register", body={
            "username": self.username, "password": PASSWORD,
        }), 409, "USER_ALREADY_EXISTS")

        self.check("错误密码 → 401", self.request("POST", "/auth/login", body={
            "username": self.username, "password": PASSWORD + "x",
        }), 401, "AUTH_INVALID_CREDENTIALS")

        self.check("未认证访问配置 → 401", self.request("GET", "/user/config"),
                   401, "AUTH_UNAUTHORIZED")

        logged_in = self.check("登录", self.request("POST", "/auth/login", body={
            "username": self.username, "password": PASSWORD,
        }), 200)
        token = logged_in.get("data", {}).get("access_token") or token

        # ---- 身份边界 ----
        forged = self.check("伪造 X-User-Id 仍按 JWT", self.request(
            "GET", "/user/config", token=token, headers={"X-User-Id": "999999"}), 200)
        if forged.get("data", {}).get("user_id") != user_id:
            self.failures.append("伪造 X-User-Id 改变了当前用户（身份边界失效）")

        # ---- 公共探测契约与 SSRF ----
        self.check("公共探测拒绝 BOTH → 400", self.request(
            "POST", "/user/model-config/probe", token=token, body={
                "probe_type": "BOTH", "provider": "DEEPSEEK",
                "base_url": "https://api.deepseek.com/v1",
                "api_key": FAKE_KEY, "model_name": "deepseek-chat",
            }), 400, "VALIDATION_ERROR")

        for target in (
            "https://169.254.169.254/latest/meta-data",
            "https://100.100.100.200/latest/meta-data",
            "https://[fd00:ec2::254]/",
            "https://sc-mysql:3306/v1",
            "https://127.0.0.1:3306/v1",
            "https://api.deepseek.com/v1/%2e%2e/admin",
            "https://api.deepseek.com/v1/%252e%252e/admin",
            "https://api.deepseek.com/v1/%5cadmin",
            "https://api.deepseek.com/v1/\u0000admin",
            "https://api.deepseek.com/v1/\u007fadmin",
        ):
            self.check(f"SSRF 拦截 {target}", self.request(
                "POST", "/user/model-config/probe", token=token, body={
                    "probe_type": "CHAT", "provider": "DEEPSEEK",
                    "base_url": target, "api_key": FAKE_KEY, "model_name": "deepseek-chat",
                }), 400, "E-SYS-02")

        # ---- 偏好校验 ----
        self.check("偏好更新", self.request("PUT", "/user/preferences", token=token, body={
            "timezone": "Asia/Shanghai", "dnd_start_time": "23:30",
            "dnd_end_time": "07:30", "bound_im_platform": None, "bound_im_uid": None,
        }), 200)

        self.check("偏好成对校验 → 400", self.request("PUT", "/user/preferences", token=token, body={
            "timezone": "Asia/Shanghai", "dnd_start_time": "23:30",
            "dnd_end_time": "07:30", "bound_im_platform": "QQ_ONEBOT", "bound_im_uid": None,
        }), 400, "VALIDATION_ERROR")

        self.check("非法时区 → 400", self.request("PUT", "/user/preferences", token=token, body={
            "timezone": "Mars/Olympus", "dnd_start_time": "23:30", "dnd_end_time": "07:30",
        }), 400, "VALIDATION_ERROR")

        # ---- Key 销毁（幂等）----
        cleared = self.check("销毁全部 Key", self.request(
            "DELETE", "/user/model-config/keys?target=ALL", token=token), 200)
        if cleared.get("data", {}).get("has_chat_api_key") is not False:
            self.failures.append("销毁 Key 后 has_chat_api_key 仍为 true")
        self.check("销毁 Key 幂等", self.request(
            "DELETE", "/user/model-config/keys?target=ALL", token=token), 200)

        # ---- 登出与旧 token ----
        self.check("登出", self.request("POST", "/auth/logout", token=token), 200)
        self.check("旧 token 失效 → 401", self.request("GET", "/user/config", token=token),
                   401, "AUTH_TOKEN_REVOKED")

        if self.failures:
            print("\n失败项：")
            for failure in self.failures:
                print(f"  - {failure}")
            return 1
        print(f"\n全部通过。测试账号 `{self.username}` 可按需手工清理。")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="ScholarClaw 认证 + BYOK 冒烟")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--username", default=f"e2e_smoke_{int(time.time())}")
    args = parser.parse_args()
    return Smoke(args.base_url, args.username).run()


if __name__ == "__main__":
    sys.exit(main())
