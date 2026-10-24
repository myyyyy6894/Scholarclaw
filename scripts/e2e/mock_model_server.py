#!/usr/bin/env python3
"""本地 mock OpenAI 兼容模型服务（仅用于 E2E，不进入生产）。

提供：
* `POST .../chat/completions`：非流式返回最小 choices；`stream=true` 时返回 SSE 增量；
* `POST .../embeddings`：返回 8 维向量（用于维度回填与检索链路）。

RAG E2E 约定（只在测试中使用）：
* 从 Prompt 中提取首个 `文件名：... 页码：start-end`，默认输出该页面的合法引用；
* Prompt 含 `请拒答` → 课件调用输出 `NO_ANSWER_IN_COURSE`，通用补充调用输出正常补充；
* 含 `请伪造引用` → 输出越界页码引用；
* 含 `请越界引用` → 合法引用 + 未召回页码；
* 含 `请畸形引用` → 合法引用 + 缺少冒号的畸形来源标签；
* 含 `请中文括号` → 合法引用 + 中文方括号伪造标签；
* 含 `请长引用` → 合法引用 + 超过 512 字符的未闭合引用；
* 含 `请无引用` → 输出无引用的编造内容；
* 无课件片段（通用补充调用）→ 输出通用补充文本。

用法（见 scripts/e2e/README.md）：
    docker run -d --name sc-e2e-mock-model --network scholarclaw-internal \
      -v "$PWD/scripts/e2e/mock_model_server.py:/server.py:ro" \
      python:3.11-slim python3 /server.py
"""

from __future__ import annotations

import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = 8
_CONTEXT = re.compile(r"文件名：(.+?)\s+页码：(\d+)-(\d+)")


def _chat_answer(payload: dict) -> str:
    messages = payload.get("messages") or []
    system_text = "\n".join(str(item.get("content", "")) for item in messages
                            if isinstance(item, dict) and item.get("role") == "system")
    # 拒答后的通用补充调用没有课件片段，必须与课件调用区分开
    if "你是学习助手" in system_text:
        return "通用知识补充回答（非课件原文，仅供学习参考）。"
    prompt = "\n".join(str(item.get("content", "")) for item in messages
                       if isinstance(item, dict))
    current = ""
    if messages and isinstance(messages[-1], dict):
        current = str(messages[-1].get("content", ""))
        if "用户问题：" in current:
            current = current.rsplit("用户问题：", 1)[1]
    match = _CONTEXT.search(prompt)
    valid = ""
    doc_name = "不存在.pdf"
    if match is not None:
        doc_name, page_start, _page_end = match.groups()
        doc_name = doc_name.strip()
        valid = f"[来源：{doc_name} - 第{page_start}页]"
    if "请拒答" in current:
        return "NO_ANSWER_IN_COURSE"
    if "请畸形引用" in current:
        return f"结论{valid}；伪来源[来源不存在.pdf - 第999页]"
    if "请中文括号" in current:
        return f"结论{valid}【来源：不存在.pdf - 第999页】"
    if "请越界引用" in current:
        return f"结论{valid}又一段[来源：{doc_name} - 第999页]"
    if "请长引用" in current:
        return f"结论{valid}[来源：" + "不存在" * 200
    if "请伪造引用" in current:
        return "模型编造的内容。[来源：不存在.pdf - 第999页]"
    if "请无引用" in current:
        return "模型编造的内容，没有任何课件引用。"
    if match is None:
        return "通用知识补充回答。"
    return f"根据课件，该内容见对应页码。{valid}"


class Handler(BaseHTTPRequestHandler):
    timeout = 10
    protocol_version = "HTTP/1.1"

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 接口
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            payload = {}
        if self.path.rstrip("/").endswith("/embeddings"):
            inputs = payload.get("input", "")
            count = len(inputs) if isinstance(inputs, list) else 1
            body = json.dumps({"data": [
                {"embedding": [0.01] * DIM, "index": index}
                for index in range(count)
            ]}).encode()
            self._json(body)
            return
        answer = _chat_answer(payload)
        if payload.get("stream") is True:
            self._stream(answer)
            return
        self._json(json.dumps({"choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": answer},
        }]}, ensure_ascii=False).encode())

    def _json(self, body: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _stream(self, text: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for start in range(0, len(text), 6):
            chunk = text[start:start + 6]
            event = json.dumps({"choices": [{"delta": {"content": chunk}}]},
                               ensure_ascii=False)
            self._write_chunk(f"data: {event}\n\n".encode())
            time.sleep(0.02)
        self._write_chunk(b"data: [DONE]\n\n")
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _write_chunk(self, payload: bytes) -> None:
        self.wfile.write(f"{len(payload):X}\r\n".encode() + payload + b"\r\n")
        self.wfile.flush()

    def log_message(self, *args) -> None:  # noqa: ANN002 - 静音
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 9000), Handler)
    server.daemon_threads = True
    server.serve_forever()
