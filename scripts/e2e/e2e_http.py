"""E2E 脚本共用的标准库 HTTP 请求、注册登录与文件上传辅助。"""

from __future__ import annotations

import json
import urllib.error
import urllib.request


def call(
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: dict | None = None,
    timeout: float = 60,
) -> tuple[int, dict]:
    """发送 JSON HTTP 请求并解析响应码与 JSON 载荷。"""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base_url + path, method=method, data=data)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token is not None:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))


def check(name: str, ok: bool, detail: str = "") -> bool:
    """按统一格式打印单条断言结果并返回是否通过。"""
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + ("" if ok else f"（{detail}）"))
    return ok


def register_or_login(
    base_url: str,
    username: str,
    password: str,
    *,
    timezone: str = "Asia/Shanghai",
    timeout: float = 60,
) -> tuple[str, str]:
    """注册临时账号；已存在时回落登录，返回 (access_token, user_id)。"""
    status, payload = call(
        base_url,
        "POST",
        "/api/v1/auth/register",
        body={"username": username, "password": password, "timezone": timezone},
        timeout=timeout,
    )
    if status == 201:
        return payload["data"]["access_token"], payload["data"]["user_id"]
    status, payload = call(
        base_url,
        "POST",
        "/api/v1/auth/login",
        body={"username": username, "password": password},
        timeout=timeout,
    )
    if status != 200:
        raise SystemExit(f"登录失败：{status} {payload}")
    return payload["data"]["access_token"], payload["data"]["user_id"]


def upload_file(
    base_url: str,
    token: str,
    course_id: str,
    filename: str,
    content: bytes,
    content_type: str = "application/pdf",
    *,
    boundary: str = "----scholarclaw-e2e-boundary",
    timeout: float = 60,
) -> tuple[int, dict]:
    """以 multipart/form-data 上传课程文档并返回响应码与 JSON 载荷。"""
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/courses/{course_id}/docs/upload",
        data=body,
        method="POST",
    )
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8", "replace"))
