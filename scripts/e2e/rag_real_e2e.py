#!/usr/bin/env python3
"""P6 RAG 真实链路 E2E（Nginx → Java → Python → MySQL/Chroma，TASK-604/605 复核修复）。

前置：
  1. 整栈已重建并启动（Java/Python/Web 镜像包含 TASK-601~606 修复）；
  2. mock 模型容器 `sc-e2e-mock-model` 已启动，并已把其私网 IP 字面量注入
     `SC_ALLOW_LOCAL_MODEL_BASE_URL=true` 与 `SC_LOCAL_MODEL_ALLOWED_ORIGINS`；
  3. 通过 `--mock-base-url http://<ip>:9000/v1` 传入同一地址（不要用 Docker 服务名）。

覆盖：
  * 正常 SSE（citation → token → done）且 curl 返回码 0；
  * 拒答、event:error（引用校验失败/无引用/超长引用）、客户端断流；
  * 401/404/400/409 建流前错误在 Accept: text/event-stream 下仍返回 JSON 信封；
  * 虚构引用/无引用/长引用不落库，历史接口无虚构来源；
  * 真实跨用户/跨课程 404；
  * 真实 Chroma/MySQL 参与（文档需解析到 READY）。

脚本只使用假 Key，不读取/打印 `.env`。
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import time
from http.client import HTTPConnection
from urllib.parse import urlparse

from ingest_e2e import (
    build_pdf,
    call,
    check,
    register_or_login,
    save_models,
    upload_file,
    wait_doc,
)

PASSWORD = "E2e-P6-Pass-123"


def create_course(base_url: str, token: str, name: str) -> str:
    status, payload = call(base_url, "POST", "/api/v1/courses", token=token,
                           body={"course_name": name, "description": "P6 RAG E2E"})
    if status != 201:
        raise SystemExit(f"创建课程失败：{status} {payload}")
    return str(payload["data"]["course_id"])


def sse_request(base_url: str, token: str | None, course_id: str, query: str,
                *, stop_after: int | None = None) -> tuple[int, list[tuple[str, str]], bytes]:
    """通过 Nginx 发起 SSE；返回 (status, frames, raw_body_for_non_200)。"""
    parsed = urlparse(base_url)
    connection = HTTPConnection(parsed.hostname, parsed.port or 80, timeout=120)
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps({"course_id": course_id, "query": query}).encode()
    connection.request("POST", "/api/v1/rag/qa/stream", body=body, headers=headers)
    response = connection.getresponse()
    if response.status != 200:
        raw = response.read()
        connection.close()
        return response.status, [], raw
    frames: list[tuple[str, str]] = []
    event = ""
    data: list[str] = []
    seen = 0
    while True:
        line = response.readline()
        if not line:
            break
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if text == "":
            if event and data:
                frames.append((event, "\n".join(data)))
                seen += 1
                if stop_after is not None and seen >= stop_after:
                    connection.close()
                    return 200, frames, b""
            event = ""
            data = []
        elif text.startswith("event:"):
            event = text[len("event:"):].strip()
        elif text.startswith("data:"):
            data.append(text[len("data:"):].lstrip())
    connection.close()
    return 200, frames, b""


def frames_of(frames: list[tuple[str, str]], name: str) -> list[dict]:
    result = []
    for event, data in frames:
        if event == name:
            result.append(json.loads(data))
    return result


def history(base_url: str, token: str, course_id: str) -> list[dict]:
    status, payload = call(base_url, "GET",
                           f"/api/v1/courses/{course_id}/messages?limit=50", token=token)
    if status != 200:
        raise SystemExit(f"读取历史失败：{status} {payload}")
    return payload["data"]["items"]


def curl_stream(base_url: str, token: str, course_id: str, query: str) -> tuple[int, int, str]:
    with tempfile.NamedTemporaryFile("w+", suffix=".sse") as output:
        result = subprocess.run(
            [
                "curl", "-sS", "-N", "--max-time", "60",
                "-o", output.name, "-w", "%{http_code}",
                "-X", "POST", f"{base_url}/api/v1/rag/qa/stream",
                "-H", f"Authorization: Bearer {token}",
                "-H", "Accept: text/event-stream",
                "-H", "Content-Type: application/json",
                "--data-binary", json.dumps({"course_id": course_id, "query": query}),
            ],
            capture_output=True, text=True, timeout=90,
        )
        output.seek(0)
        return result.returncode, int(result.stdout.strip() or 0), output.read()


def browser_check(base_url: str, token: str, user_id: str, course_name: str) -> list[bool]:
    """真实 Nginx + 浏览器交互（Playwright + 系统 Chrome）。"""
    from playwright.sync_api import sync_playwright

    checks: list[bool] = []
    console_errors: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        page = context.new_page()
        page.on("console", lambda message: console_errors.append(message.text)
                if message.type == "error" else None)
        page.add_init_script(
            "localStorage.setItem('sc.auth.session', JSON.stringify("
            f"{{token:'{token}',user:{{user_id:'{user_id}',username:'e2e_rag'}}}}))")
        page.goto(base_url + "/rag", wait_until="networkidle")
        page.get_by_role("heading", name="课程问答").wait_for()
        page.locator(".sc-rag__course-select").click()
        page.locator(".el-select-dropdown__item", has_text=course_name).first.click()
        page.get_by_role("heading", name=course_name).wait_for()
        page.get_by_role("textbox").fill("第一页讲了什么？")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("根据课件", exact=False).first.wait_for(timeout=30000)
        checks.append(check("真实浏览器：流式回答与引用渲染",
                            page.get_by_text("rag-a.pdf", exact=True).first.is_visible()))
        checks.append(check("真实浏览器：内联引用高亮",
                            page.locator(".sc-rag__inline-citation").count() >= 1))
        checks.append(check("真实浏览器：无流式错误条",
                            page.locator(".sc-rag__stream-error").count() == 0))
        page.get_by_role("textbox").fill("请拒答")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("未找到与", exact=False).first.wait_for(timeout=30000)
        checks.append(check("真实浏览器：拒答声明渲染", True))
        page.reload(wait_until="networkidle")
        # 课程选择不持久化，刷新后需重新选中被测课程
        page.locator(".sc-rag__course-select").click()
        page.locator(".el-select-dropdown__item", has_text=course_name).first.click()
        page.get_by_text("未找到与", exact=False).first.wait_for(timeout=30000)
        checks.append(check("真实浏览器：刷新后拒答声明仍存在", True))
        checks.append(check("真实浏览器：刷新后通用补充独立展示",
                            page.locator(".sc-rag__supplement").count() >= 1
                            and page.get_by_text("通用知识补充回答", exact=False).count() >= 1))
        page.get_by_role("textbox").fill("请伪造引用")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("引用校验未通过", exact=False).first.wait_for(timeout=30000)
        checks.append(check("真实浏览器：引用失败清空虚构回答与引用",
                            page.get_by_text("模型编造的内容", exact=False).count() == 0
                            and page.get_by_text("不存在.pdf", exact=False).count() == 0))
        page.screenshot(path="/tmp/sc-rag-real.png", full_page=True)
        checks.append(check("真实浏览器：0 console error", not console_errors))
        browser.close()
    return checks


def main() -> int:
    parser = argparse.ArgumentParser(description="P6 RAG 真实链路 E2E")
    parser.add_argument("--base-url", default="http://127.0.0.1")
    parser.add_argument("--mock-base-url", required=True)
    parser.add_argument("--ready-timeout", type=float, default=180)
    parser.add_argument("--skip-browser", action="store_true",
                        help="跳过 Playwright 真实浏览器检查")
    args = parser.parse_args()

    stamp = str(int(time.time()))
    user_a = f"e2e_rag_a_{stamp}"
    user_b = f"e2e_rag_b_{stamp}"
    token_a, id_a = register_or_login(args.base_url, user_a, PASSWORD)
    token_b, _id_b = register_or_login(args.base_url, user_b, PASSWORD)

    results: list[bool] = []

    status, payload = save_models(args.base_url, token_a, args.mock_base_url,
                                  embedding_model="embed-mock")
    results.append(check("用户 A 保存 mock 模型配置", status == 200, f"{status} {payload}"))
    course_a_name = f"p6-rag-a-{stamp}"
    course_a = create_course(args.base_url, token_a, course_a_name)
    status, payload = upload_file(
        args.base_url, token_a, course_a, "rag-a.pdf",
        build_pdf([
            "Chapter one: two phase locking uses growing and shrinking phases. "
            "This page is long enough for deterministic chunking and retrieval.",
            "Chapter two: isolated levels include read committed and serializable. "
            "This page is also long enough for deterministic chunking and retrieval.",
        ]))
    doc_a = str((payload.get("data") or {}).get("doc_id"))
    ready_a = wait_doc(args.base_url, token_a, course_a, doc_a, {"READY", "FAILED"},
                       args.ready_timeout)
    results.append(check("用户 A 文档真实解析到 READY", ready_a.get("status") == "READY",
                         str(ready_a)))

    # 1. 正常 SSE + curl 返回码 0
    code, frames, _ = sse_request(args.base_url, token_a, course_a, "第一页讲了什么？")
    results.append(check("正常 SSE 返回 200 且包含 citation/token/done",
                         code == 200 and bool(frames_of(frames, "citation"))
                         and bool(frames_of(frames, "token"))
                         and bool(frames_of(frames, "done")), str(frames[:3])))
    curl_code, http_code, body = curl_stream(args.base_url, token_a, course_a, "第一页讲了什么？")
    results.append(check("curl 正常流返回码 0（无 chunked 截断）",
                         curl_code == 0 and http_code == 200
                         and "event:citation" in body and "event:done" in body,
                         f"curl={curl_code} http={http_code} body_tail={body[-200:]}"))

    # 2. 拒答：声明与通用补充都要落库，刷新历史后仍然存在
    code, frames, _ = sse_request(args.base_url, token_a, course_a, "请拒答")
    notices = frames_of(frames, "rejection_notice")
    dones = frames_of(frames, "done")
    results.append(check("拒答路径发送 rejection_notice + done(is_rejected)",
                         code == 200 and bool(notices) and bool(dones)
                         and dones[-1].get("is_rejected") is True, str(frames[:4])))
    rejected_rows = [item for item in history(args.base_url, token_a, course_a)
                     if item.get("role") == "ASSISTANT" and item.get("is_rejected")]
    stored_rejected = rejected_rows[-1] if rejected_rows else {}
    stored_content = stored_rejected.get("content") or ""
    supplement_part = stored_content.split("💡 以下为基于通用专业知识的补充参考", 1)
    results.append(check("拒答声明与通用补充同时落库且补充不含课件引用",
                         "未找到与" in stored_content
                         and "通用知识补充回答" in stored_content
                         and len(supplement_part) == 2
                         and "来源：" not in supplement_part[1]
                         and ".pdf" not in supplement_part[1]
                         and not stored_rejected.get("citations"),
                         str(stored_rejected)[:300]))

    # 3. 引用校验失败：虚构文件名、未召回页码、畸形标签、无引用、长引用；均不得落库
    failure_cases = [
        ("请伪造引用", "虚构文件名/越界页码"),
        ("请越界引用", "合法引用+未召回页码"),
        ("请畸形引用", "合法引用+无冒号畸形标签"),
        ("请中文括号", "合法引用+中文方括号伪造标签"),
        ("请无引用", "完全没有引用"),
        ("请长引用", "超过 512 字符的引用"),
    ]
    before = len(history(args.base_url, token_a, course_a))
    for query, label in failure_cases:
        code, frames, _ = sse_request(args.base_url, token_a, course_a, query)
        errors = frames_of(frames, "error")
        results.append(check(f"{label} 返回 event:error 且无 done",
                             code == 200 and bool(errors)
                             and not frames_of(frames, "done")
                             and "E-RAG-03" == errors[-1].get("code"), str(frames[:4])))
    after = len(history(args.base_url, token_a, course_a))
    stored = history(args.base_url, token_a, course_a)
    fabricated = [item for item in stored if item.get("role") == "ASSISTANT"
                  and ("不存在.pdf" in (item.get("content") or "")
                       or "模型编造" in (item.get("content") or ""))]
    results.append(check("引用失败不落库、历史无虚构来源",
                         not fabricated and after - before == len(failure_cases),  # 仅 USER 消息
                         f"before={before} after={after} fabricated={fabricated}"))

    # 4. 客户端断流：读到首个事件后关闭，服务端不得报 500；不落库半截回答
    before_abort = len(history(args.base_url, token_a, course_a))
    code, frames, _ = sse_request(args.base_url, token_a, course_a,
                                  "第一页讲了什么？", stop_after=1)
    time.sleep(1)
    after_abort = len(history(args.base_url, token_a, course_a))
    code2, frames2, _ = sse_request(args.base_url, token_a, course_a, "第一页讲了什么？")
    results.append(check("客户端断流后服务仍可用且不落库半截回答",
                         code == 200 and code2 == 200
                         and bool(frames_of(frames2, "done"))
                         and after_abort - before_abort <= 1,
                         f"before={before_abort} after={after_abort} frames={frames2[:2]}"))

    # 5. 建流前错误：401 / 404 / 400 / 409，Accept 仍是 text/event-stream
    status, _, raw = sse_request(args.base_url, None, course_a, "问题")
    results.append(check("401 未登录返回 JSON 信封",
                         status == 401 and b"AUTH_UNAUTHORIZED" in raw, raw[:200].decode("utf-8", "replace")))
    status, _, raw = sse_request(args.base_url, token_b, course_a, "问题")
    results.append(check("404 跨用户课程返回 JSON 信封",
                         status == 404 and b"RESOURCE_NOT_FOUND" in raw,
                         raw[:200].decode("utf-8", "replace")))
    status, _ = call(args.base_url, "GET", f"/api/v1/courses/{course_a}/messages", token=token_b)
    results.append(check("404 跨用户历史查询", status == 404))
    status, _ = call(args.base_url, "DELETE", f"/api/v1/courses/{course_a}/messages", token=token_b)
    results.append(check("404 跨用户历史清空", status == 404))

    empty_course = create_course(args.base_url, token_a, f"p6-rag-empty-{stamp}")
    status, _, raw = sse_request(args.base_url, token_a, empty_course, "问题")
    results.append(check("400 无 READY 课件返回 JSON 信封",
                         status == 400 and b"COURSE_NO_READY_DOCS" in raw,
                         raw[:200].decode("utf-8", "replace")))

    # 6. 重复文件名无法唯一归属 → 引用校验失败
    dup_course = create_course(args.base_url, token_a, f"p6-rag-dup-{stamp}")
    dup_docs = []
    for index, text in enumerate([
            "Duplicate file page one content for the first document.",
            "Duplicate file page one content for the second document."], start=1):
        status, payload = upload_file(
            args.base_url, token_a, dup_course, "dup.pdf", build_pdf([text]))
        dup_docs.append(str((payload.get("data") or {}).get("doc_id")))
    ready_dup = [wait_doc(args.base_url, token_a, dup_course, doc_id,
                          {"READY", "FAILED"}, args.ready_timeout)
                 for doc_id in dup_docs]
    status, frames, _ = sse_request(args.base_url, token_a, dup_course,
                                    "第一页讲了什么？")
    errors = frames_of(frames, "error")
    results.append(check("重复文件名无法唯一归属 → 引用校验失败",
                         all(item.get("status") == "READY" for item in ready_dup)
                         and bool(errors) and errors[-1].get("code") == "E-RAG-03",
                         str(frames[:4])))

    # 7. 255 字符文件名：引用缓冲区必须容纳合法长文件名
    long_course = create_course(args.base_url, token_a, f"p6-rag-long-{stamp}")
    long_name = "a" * 251 + ".pdf"
    status, payload = upload_file(
        args.base_url, token_a, long_course, long_name,
        build_pdf(["Long file name page with enough text for deterministic retrieval."]))
    long_doc = str((payload.get("data") or {}).get("doc_id"))
    ready_long = wait_doc(args.base_url, token_a, long_course, long_doc,
                          {"READY", "FAILED"}, args.ready_timeout)
    code, frames, _ = sse_request(args.base_url, token_a, long_course, "第一页讲了什么？")
    long_citations = frames_of(frames, "citation")
    results.append(check("255 字符合法文件名可正常引用",
                         ready_long.get("status") == "READY"
                         and bool(frames_of(frames, "done"))
                         and bool(long_citations)
                         and long_citations[-1]["citations"][0]["doc_name"] == long_name,
                         str(frames[:2])))

    # 8. 真实浏览器（Nginx + 前端 + 后端 + mock 模型）
    if not args.skip_browser:
        results.extend(browser_check(args.base_url, token_a, str(id_a), course_a_name))

    # 9. 课程重建且新版本无 READY → 409
    status, payload = call(args.base_url, "PUT",
                           f"/api/v1/courses/{course_a}/embedding-model", token=token_a,
                           body={"use_user_default": True})
    if status == 200:
        status_sse, _, raw = sse_request(args.base_url, token_a, course_a, "问题")
        results.append(check("409 课程重建且无 READY 返回 JSON 信封",
                             status_sse == 409 and b"COURSE_INDEX_REBUILDING" in raw,
                             raw[:200].decode("utf-8", "replace")))
    else:
        results.append(check("课程切换模型触发重建", False, f"{status} {payload}"))

    # 10. 400 模型配置缺失：用户 B 建课/入库后清空 Key
    status, payload = save_models(args.base_url, token_b, args.mock_base_url,
                                  embedding_model="embed-mock")
    course_b = create_course(args.base_url, token_b, f"p6-rag-b-{stamp}")
    status, payload = upload_file(
        args.base_url, token_b, course_b, "rag-b.pdf",
        build_pdf(["User B page with enough text for deterministic retrieval and parsing."]))
    doc_b = str((payload.get("data") or {}).get("doc_id"))
    ready_b = wait_doc(args.base_url, token_b, course_b, doc_b, {"READY", "FAILED"},
                       args.ready_timeout)
    status, payload = call(args.base_url, "DELETE",
                           "/api/v1/user/model-config/keys?target=ALL", token=token_b)
    status_sse, _, raw = sse_request(args.base_url, token_b, course_b, "问题")
    results.append(check("400 模型配置缺失返回 E-SYS-01 JSON 信封",
                         ready_b.get("status") == "READY" and status_sse == 400
                         and b"E-SYS-01" in raw, raw[:200].decode("utf-8", "replace")))

    passed = sum(1 for item in results if item)
    print(f"\n{passed}/{len(results)} checks passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
