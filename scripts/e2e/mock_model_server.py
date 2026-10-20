#!/usr/bin/env python3
"""本地 mock OpenAI 兼容模型服务（仅用于 E2E，不进入生产）。

提供：
* `POST .../chat/completions` → 返回一个最小 choices 结构；
* `POST .../embeddings` → 返回 8 维向量（用于验证 `actual_dim` 回填）。

用法（见 scripts/e2e/README.md）：
    docker run -d --name sc-e2e-mock-model --network scholarclaw-internal \
      -v "$PWD/scripts/e2e/mock_model_server.py:/server.py:ro" \
      python:3.11-slim python3 /server.py
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, HTTPServer

DIM = 8


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 接口
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        if self.path.rstrip("/").endswith("/embeddings"):
            payload = {"data": [{"embedding": [0.01] * DIM, "index": 0}]}
        else:
            payload = {"choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}}]}
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args) -> None:  # noqa: ANN002 - 静音
        return


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
