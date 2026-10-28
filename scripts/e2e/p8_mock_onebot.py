#!/usr/bin/env python3
"""P8 OneBot 11 Action API 替身（仅用于真实栈 E2E）。

它模拟 NapCat 的最小动作集：
* `send_private_msg`：记录调用并返回成功；对指定 QQ 号返回 retcode 失败，用于验证重试/降级；
* `get_file`：返回 base64 PDF；
* `get_forward_msg`：返回单层文本；`forward-nested` 返回嵌套节点，验证 E-IM-03；
* `GET /__calls` / `POST /__reset`：供宿主机 E2E 脚本读取/清空调用记录。

不连接 QQ、不读取真实凭据；只记录请求体中的动作参数，不记录 Authorization。
"""

from __future__ import annotations

import argparse
import base64
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CALLS: list[dict] = []
LOCK = threading.Lock()
FAIL_UID = 24681014
PDF_BYTES = b"%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"


def record(action: str, body: dict) -> None:
    with LOCK:
        CALLS.append({"action": action, "body": body, "ts": time.time()})


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, code: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.path == "/__calls":
            with LOCK:
                self._json(200, {"calls": list(CALLS)})
            return
        if self.path == "/__health":
            self._json(200, {"status": "ok"})
            return
        self._json(404, {"status": "failed"})

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._json(200, {"status": "failed", "retcode": 1400})
            return
        if self.path == "/__reset":
            with LOCK:
                CALLS.clear()
            self._json(200, {"status": "ok"})
            return
        if self.path == "/send_private_msg":
            record("send_private_msg", body)
            # 约定：以 14 结尾的测试 QQ 号模拟协议端拒收，用于验证重试/降级。
            if str(body.get("user_id")).endswith("14"):
                self._json(200, {"status": "failed", "retcode": 1400, "data": None})
            else:
                self._json(200, {"status": "ok", "retcode": 0, "data": {"message_id": 1}})
            return
        if self.path == "/get_file":
            record("get_file", body)
            encoded = base64.b64encode(PDF_BYTES).decode()
            self._json(200, {
                "status": "ok",
                "retcode": 0,
                "data": {
                    "file_name": "p8_e2e.pdf",
                    "file_size": len(PDF_BYTES),
                    "file": "base64://" + encoded,
                },
            })
            return
        if self.path == "/get_forward_msg":
            record("get_forward_msg", body)
            forward_id = str(body.get("id", ""))
            if "nested" in forward_id:
                messages = [{"message": [{"type": "forward", "data": {"id": "inner"}}]}]
            else:
                messages = [
                    {"message": [{"type": "text", "data": {
                        "text": "通知：请于 2099年12月31日 22:00 前提交实验报告"}}]},
                    {"message": [{"type": "text", "data": {"text": "提交到学习通"}}]},
                ]
            self._json(200, {"status": "ok", "retcode": 0,
                             "data": {"messages": messages}})
            return
        self._json(404, {"status": "failed", "retcode": 1404})

    def log_message(self, *args) -> None:
        return


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="P8 OneBot Action API double")
    parser.add_argument("--port", type=int, default=19090)
    parser.add_argument("--fail-uid", type=int, default=FAIL_UID)
    args = parser.parse_args()
    FAIL_UID = args.fail_uid
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
