#!/usr/bin/env python3
"""RAG Web 工作台浏览器 E2E（TASK-605，Playwright + 系统 Chrome）。

本脚本**不依赖后端**：通过 Playwright 路由拦截模拟 `API-COURSE-001`、
`API-CHAT-001/002` 与 `API-RAG-001` 的 SSE 响应，验证页面在
“正常流式回答 / 引用卡片 / 错误帧 / 清空会话”下的真实渲染与状态区分。

用法（开发服务器已启动）：
    python3 -m venv /tmp/sc-e2e-venv
    /tmp/sc-e2e-venv/bin/pip install playwright==1.51.0
    /tmp/sc-e2e-venv/bin/python scripts/e2e/rag_web_e2e.py \
        --base-url http://127.0.0.1:5173

脚本只使用假令牌与假数据，不读取 `.env`。
"""

from __future__ import annotations

import argparse
import json
import sys
from urllib.parse import urlparse

from playwright.sync_api import Page, Route, sync_playwright

COURSE = {
    "course_id": "2001",
    "course_name": "数据库系统概念",
    "description": "E2E 模拟课程",
    "is_default": True,
    "embedding_provider": "OPENAI_COMPATIBLE",
    "embedding_model": "embed",
    "embedding_dim": 1024,
    "index_version": 3,
    "index_status": "ACTIVE",
    "ready_doc_count": 2,
    "parsing_doc_count": 0,
    "failed_doc_count": 0,
    "created_at": "2026-10-23T10:00:00.000+08:00",
    "updated_at": "2026-10-23T10:00:00.000+08:00",
}

CITATION = {
    "parent_chunk_id": "pc_50001_v1_3",
    "doc_id": "50001",
    "doc_name": "第5章_事务处理.pdf",
    "page_start": 14,
    "page_end": 14,
    "section_title": "5.2 两阶段锁协议",
    "content": "两阶段锁协议(2PL)规定事务分扩展阶段与收缩阶段……",
    "similarity_score": 0.842,
}


def _message(message_id: str, role: str, content: str, **extra: object) -> dict:
    base = {
        "message_id": message_id,
        "course_id": "2001",
        "platform": "WEB",
        "role": role,
        "intent_type": "KB_QA",
        "content": content,
        "citations": None,
        "is_rejected": False,
        "trace_id": "tr_0123456789ab",
        "retrieval_cost_ms": None,
        "llm_first_token_ms": None,
        "created_at": "2026-10-23T10:00:00.000+08:00",
    }
    base.update(extra)
    return base


INITIAL_MESSAGES = [
    _message("90001", "USER", "第 5 章讲什么？"),
    _message("90002", "ASSISTANT", "第 5 章介绍事务与并发控制。",
             retrieval_cost_ms=120, llm_first_token_ms=900),
]

UPDATED_MESSAGES = INITIAL_MESSAGES + [
    _message("90003", "USER", "两阶段锁协议是什么？"),
    _message(
        "90004", "ASSISTANT", "答案[来源：第5章_事务处理.pdf - 第14页]。",
        citations=[CITATION], retrieval_cost_ms=185, llm_first_token_ms=1120,
    ),
]

CITATION_EVENT = {
    "user_message_id": "90003",
    "course_index_status": "ACTIVE",
    "rebuild_progress_note": None,
    "citations": [CITATION],
}

SSE_BODY = (
    "event:citation\n"
    f"data:{json.dumps(CITATION_EVENT, ensure_ascii=False)}\n\n"
    "event:token\n"
    'data:{"delta":"答案","is_general_supplement":false}\n\n'
    "event:token\n"
    'data:{"delta":"[来源：第5章_事务处理.pdf - 第14页]。","is_general_supplement":false}\n\n'
    "event:done\n"
    'data:{"user_message_id":"90003","assistant_message_id":"90004",'
    '"is_rejected":false,"retrieval_cost_ms":185,"llm_first_token_ms":1120,'
    '"stripped_citation_count":0,"trace_id":"tr_0123456789ab"}\n\n'
)

ERROR_SSE_BODY = (
    "event:error\n"
    'data:{"code":"E-RAG-03","message":"知识库上下文读取暂时不可用，请稍后重试",'
    '"trace_id":"tr_0123456789ab"}\n\n'
)

CITATION_FAILURE_SSE_BODY = (
    "event:citation\n"
    f"data:{json.dumps(CITATION_EVENT, ensure_ascii=False)}\n\n"
    "event:token\n"
    'data:{"delta":"模型编造的内容。","is_general_supplement":false}\n\n'
    "event:error\n"
    'data:{"code":"E-RAG-03","message":"引用校验未通过（存在非法引用；有效引用 0，'
    '非法引用 1，超长标签 0，畸形标签 0）；本次实际召回切片为：'
    '《第5章_事务处理.pdf》第14-14页。请重试或换一种问法。",'
    '"trace_id":"tr_0123456789ab"}\n\n'
)

TAIL_ERROR_SSE_BODY = SSE_BODY + (
    "event:error\n"
    'data:{"code":"UPSTREAM_MODEL_ERROR","message":"连接尾部异常",'
    '"trace_id":"tr_0123456789ab"}\n\n'
)


def envelope(data: object) -> dict:
    return {
        "code": "OK",
        "message": "Success",
        "data": data,
        "trace_id": "tr_0123456789ab",
        "timestamp": "2026-10-23T10:00:00.000+08:00",
    }


class MockState:
    def __init__(self) -> None:
        self.streamed = False
        self.cleared = False
        self.saw_token_request = False

    def messages(self) -> dict:
        if self.cleared:
            return {"items": [], "next_cursor": None, "has_more": False}
        if self.streamed:
            return {"items": UPDATED_MESSAGES, "next_cursor": None, "has_more": False}
        return {"items": INITIAL_MESSAGES, "next_cursor": None, "has_more": False}


def install_routes(page: Page, state: MockState) -> None:
    def handler(route: Route) -> None:
        request = route.request
        path = urlparse(request.url).path
        if request.method == "POST" and path == "/api/v1/rag/qa/stream":
            body = json.loads(request.post_data or "{}")
            query = str(body.get("query"))
            if "触发引用失败" in query:
                payload = CITATION_FAILURE_SSE_BODY
            elif "完成后尾错误" in query:
                payload = TAIL_ERROR_SSE_BODY
                state.streamed = True
            elif "触发错误" in query:
                payload = ERROR_SSE_BODY
            else:
                payload = SSE_BODY
                state.streamed = True
            state.saw_token_request = True
            route.fulfill(status=200, headers={
                "Content-Type": "text/event-stream; charset=utf-8",
                "Cache-Control": "no-cache",
            }, body=payload)
            return
        if request.method == "GET" and path == "/api/v1/courses":
            route.fulfill(status=200, content_type="application/json",
                          body=json.dumps(envelope([COURSE]), ensure_ascii=False))
            return
        if path == "/api/v1/courses/2001/messages":
            if request.method == "DELETE":
                state.cleared = True
                route.fulfill(status=200, content_type="application/json",
                              body=json.dumps(envelope(
                                  {"course_id": "2001", "deleted_count": 4}),
                                  ensure_ascii=False))
                return
            route.fulfill(status=200, content_type="application/json",
                          body=json.dumps(envelope(state.messages()), ensure_ascii=False))
            return
        route.fulfill(status=404, content_type="application/json",
                      body=json.dumps(envelope(None), ensure_ascii=False))

    page.route("**/api/v1/**", handler)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5173")
    parser.add_argument("--screenshot", default="/tmp/sc-rag-web.png")
    parser.add_argument("--normal-screenshot", default="/tmp/sc-rag-web-normal.png")
    args = parser.parse_args()

    state = MockState()
    console_errors: list[str] = []
    checks: list[tuple[str, bool]] = []

    def check(name: str, condition: bool) -> None:
        checks.append((name, condition))
        print(("PASS " if condition else "FAIL ") + name)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 960})
        page = context.new_page()
        page.on("console", lambda message: console_errors.append(message.text)
                if message.type == "error" else None)
        page.add_init_script(
            "localStorage.setItem('sc.auth.session', JSON.stringify("
            "{token:'e2e-token',user:{user_id:'1',username:'e2e_student'}}))")
        install_routes(page, state)

        page.goto(args.base_url + "/rag", wait_until="networkidle")
        page.get_by_role("heading", name="课程问答").wait_for()
        check("课程名称渲染", page.get_by_role("heading", name="数据库系统概念").is_visible())
        check("历史消息渲染", page.get_by_text("第 5 章介绍事务与并发控制。").is_visible())

        page.get_by_role("textbox").fill("两阶段锁协议是什么？")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("第5章_事务处理.pdf", exact=True).first.wait_for(timeout=5000)
        check("流式回答渲染", page.get_by_text("答案[来源：第5章_事务处理.pdf - 第14页]。").first.is_visible())
        check("内联引用高亮", page.locator(".sc-rag__inline-citation").count() >= 1)
        check("引用卡片展示文件名与页码", page.get_by_text("第 14 页", exact=True).first.is_visible())
        page.locator(".sc-rag__citation summary").first.click()
        check("父块切片可展开", page.get_by_text(
            "两阶段锁协议(2PL)规定事务分扩展阶段与收缩阶段……", exact=False).first.is_visible())
        check("耗时指标展示", page.get_by_text("检索 185 ms", exact=False).first.is_visible())
        check("SSE 请求已发出", state.saw_token_request)
        page.screenshot(path=args.normal_screenshot, full_page=True)

        page.get_by_role("button", name="清空当前课程会话").click()
        page.locator(".el-message-box").get_by_role("button", name="清空").click()
        page.get_by_text("当前课程还没有对话").wait_for(timeout=5000)
        check("清空会话调用 DELETE 并回到空状态", state.cleared)

        page.get_by_role("textbox").fill("触发引用失败")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("引用校验未通过", exact=False).first.wait_for(timeout=5000)
        check("引用失败时半截回答被丢弃",
              page.get_by_text("模型编造的内容。", exact=False).count() == 0)
        check("引用失败时引用卡片被清空",
              page.get_by_text("第5章_事务处理.pdf", exact=True).count() == 0)

        page.get_by_role("textbox").fill("触发错误")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("E-RAG-03", exact=False).first.wait_for(timeout=5000)
        check("错误帧与正常完成可区分", page.get_by_text("E-RAG-03", exact=False).first.is_visible())

        page.get_by_role("textbox").fill("完成后尾错误")
        page.get_by_role("button", name="发送").click()
        page.get_by_text("答案[来源：第5章_事务处理.pdf - 第14页]。", exact=False).first.wait_for(timeout=5000)
        check("done 后忽略传输尾错误",
              page.get_by_text("连接尾部异常", exact=False).count() == 0)

        check("浏览器无 console error", not console_errors)
        page.screenshot(path=args.screenshot, full_page=True)
        browser.close()

    failed = [name for name, ok in checks if not ok]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} checks passed; screenshot={args.screenshot}")
    if console_errors:
        for error in console_errors:
            print("console error:", error)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
