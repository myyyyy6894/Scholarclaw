#!/usr/bin/env python3
"""清理 Redis 中的旧版本 / 过期 DDL 变更草案。

背景：草案键 `sc:u:{uid}:task_draft:{task_id}` 只有 24h TTL，旧版本（缺少
`schema_version` 或 `target_task_updated_at`）在 TTL 到期前仍会残留；运行时代码已经会
在读取时删除它们，但部署清理仍需要一个可审计的一次性脚本。

用法（默认 dry-run，只统计不删除）：

    python3 scripts/maintenance/cleanup_ddl_drafts.py \
        --host 127.0.0.1 --port 6380 [--password ...] [--db 0] [--apply]

脚本只使用标准库（最小 RESP 客户端），不引入运行时依赖；不读取任何用户内容，只输出计数。
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
from datetime import datetime
from typing import Iterable
from zoneinfo import ZoneInfo

DRAFT_PATTERN = "sc:u:*:task_draft:*"
CURRENT_SCHEMA_VERSION = 2


class RedisClient:
    """极简 RESP 客户端：只实现 AUTH / SELECT / SCAN / GET / DEL。"""

    def __init__(self, host: str, port: int, timeout: float = 5.0) -> None:
        self._sock = socket.create_connection((host, port), timeout=timeout)
        self._file = self._sock.makefile("rb")

    def close(self) -> None:
        try:
            self._file.close()
        finally:
            self._sock.close()

    def command(self, *parts: str):
        payload = [f"*{len(parts)}\r\n".encode()]
        for part in parts:
            data = str(part).encode()
            payload.append(f"${len(data)}\r\n".encode())
            payload.append(data + b"\r\n")
        self._sock.sendall(b"".join(payload))
        return self._read()

    def _read(self):
        line = self._file.readline()
        if not line:
            raise ConnectionError("Redis 连接已关闭")
        prefix, body = line[:1], line[1:-2]
        if prefix == b"+":
            return body.decode()
        if prefix == b"-":
            raise RuntimeError(body.decode())
        if prefix == b":":
            return int(body)
        if prefix == b"$":
            length = int(body)
            if length < 0:
                return None
            data = self._file.read(length + 2)
            return data[:-2].decode()
        if prefix == b"*":
            count = int(body)
            return [self._read() for _ in range(count)]
        raise RuntimeError(f"未知 RESP 前缀: {prefix!r}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="清理旧版本/过期 DDL 草案")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6380)
    parser.add_argument("--password", default=None)
    parser.add_argument("--password-file", default=None,
                        help="从文件读取 Redis 口令（避免口令出现在命令行/进程列表）")
    parser.add_argument("--db", type=int, default=0)
    parser.add_argument("--timezone", default="Asia/Shanghai",
                        help="应用时区（草案 expires_at 为应用时区本地时间）")
    parser.add_argument("--apply", action="store_true",
                        help="实际删除；缺省只统计（dry-run）")
    return parser.parse_args()


def iter_keys(client: RedisClient) -> Iterable[str]:
    cursor = "0"
    while True:
        reply = client.command("SCAN", cursor, "MATCH", DRAFT_PATTERN, "COUNT", "200")
        cursor = reply[0]
        for key in reply[1]:
            yield key
        if cursor == "0":
            return


def is_stale(raw: str | None, now: datetime) -> tuple[bool, str]:
    if raw is None:
        return True, "missing"
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return True, "corrupt"
    if payload.get("schema_version") != CURRENT_SCHEMA_VERSION:
        return True, "old-schema"
    if not payload.get("target_task_updated_at"):
        return True, "missing-target-version"
    expires_at = payload.get("expires_at")
    if not expires_at:
        return True, "missing-expires-at"
    try:
        expires = datetime.fromisoformat(expires_at)
    except ValueError:
        return True, "bad-expires-at"
    if expires <= now:
        return True, "expired"
    return False, "ok"


def main() -> int:
    args = parse_args()
    password = args.password
    if password is None and args.password_file:
        with open(args.password_file, encoding="utf-8") as handle:
            password = handle.read().strip()
    client = RedisClient(args.host, args.port)
    try:
        if password:
            client.command("AUTH", password)
        if args.db:
            client.command("SELECT", str(args.db))
        # 草案 expires_at 是应用时区的 LocalDateTime（不带偏移量），必须用同一时区比较。
        now = datetime.now(ZoneInfo(args.timezone)).replace(tzinfo=None)
        counts: dict[str, int] = {}
        stale_keys: list[str] = []
        for key in iter_keys(client):
            stale, reason = is_stale(client.command("GET", key), now)
            counts[reason] = counts.get(reason, 0) + 1
            if stale:
                stale_keys.append(key)
        print(f"[cleanup] scanned drafts, reasons={counts}")
        print(f"[cleanup] stale keys: {len(stale_keys)}")
        for key in stale_keys[:20]:
            print(f"  - {key}")
        if len(stale_keys) > 20:
            print(f"  ... 还有 {len(stale_keys) - 20} 个")
        if args.apply and stale_keys:
            for key in stale_keys:
                client.command("DEL", key)
            print(f"[cleanup] deleted {len(stale_keys)} keys")
        elif stale_keys:
            print("[cleanup] dry-run：加 --apply 才会真正删除")
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
