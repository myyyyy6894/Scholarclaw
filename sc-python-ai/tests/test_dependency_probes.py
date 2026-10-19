"""依赖探针本体（`04` §4.9.8、`02` §13.3）。

用 stdlib 起一个真实 HTTP 桩服务来验证 ChromaDB 探针：不只是"函数返回 False"，
而是"HTTP 状态码怎么映射成 UP/DOWN"。其中重定向必须判 DOWN——这是内部探针的
SSRF 取向（`NFR-SEC-05`）在健康检查上的同一要求。
"""

from __future__ import annotations

import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from app.core.config import load_settings
from app.core.health import (
    CHROMADB_HEARTBEAT_PATH,
    STATUS_DOWN,
    STATUS_UP,
    build_dependency_probes,
    is_http_endpoint_up,
    is_readable_directory,
    overall_status,
    run_dependency_probes,
)


class _StubHandler(BaseHTTPRequestHandler):
    """按类属性返回指定状态码的极简桩服务。"""

    status_code = 200

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 的接口命名
        self.send_response(self.status_code)
        if self.status_code in (301, 302):
            self.send_header("Location", f"http://{self.headers['Host']}{CHROMADB_HEARTBEAT_PATH}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args) -> None:  # noqa: ANN002 - 静音桩服务日志
        return


@pytest.fixture
def stub_server() -> Iterator[tuple[str, type[_StubHandler]]]:
    """启动一个本地 HTTP 桩服务，返回 (base_url, handler 类型)。"""
    server = ThreadingHTTPServer(("127.0.0.1", 0), _StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", _StubHandler
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_storage_probe_follows_directory_readability(tmp_path: Path) -> None:
    readable = tmp_path / "raw"
    readable.mkdir()

    assert is_readable_directory(str(readable)) is True
    assert is_readable_directory(str(tmp_path / "missing")) is False


def test_http_probe_is_up_on_200(stub_server: tuple[str, type[_StubHandler]]) -> None:
    base_url, handler = stub_server
    handler.status_code = 200

    assert is_http_endpoint_up(f"{base_url}{CHROMADB_HEARTBEAT_PATH}") is True


@pytest.mark.parametrize("status_code", [204, 404, 500, 503])
def test_http_probe_is_down_on_non_200(
    stub_server: tuple[str, type[_StubHandler]], status_code: int
) -> None:
    base_url, handler = stub_server
    handler.status_code = status_code

    assert is_http_endpoint_up(f"{base_url}{CHROMADB_HEARTBEAT_PATH}") is False


@pytest.mark.parametrize("status_code", [301, 302])
def test_http_probe_does_not_follow_redirects(
    stub_server: tuple[str, type[_StubHandler]], status_code: int
) -> None:
    """内部探针不接受"跳去别处"：3xx 一律判 DOWN（`NFR-SEC-05`）。"""
    base_url, handler = stub_server
    handler.status_code = status_code

    assert is_http_endpoint_up(f"{base_url}{CHROMADB_HEARTBEAT_PATH}") is False


def test_http_probe_is_down_when_target_unreachable() -> None:
    # 127.0.0.1:1 不会有服务监听，连接必然失败
    assert is_http_endpoint_up("http://127.0.0.1:1" + CHROMADB_HEARTBEAT_PATH, timeout=0.5) is False


def test_chromadb_probe_is_registered_only_when_base_url_configured() -> None:
    without = build_dependency_probes(load_settings({"SC_STORAGE_RAW_PATH": "/tmp"}))

    assert "chromadb" not in without
    assert set(without) == {"shared_storage_ro"}


def test_chromadb_probe_uses_documented_heartbeat_path(stub_server: tuple[str, type[_StubHandler]]) -> None:
    base_url, handler = stub_server
    handler.status_code = 200
    settings = load_settings(
        {"SC_STORAGE_RAW_PATH": "/tmp", "SC_CHROMADB_BASE_URL": f"{base_url}/"}
    )

    probes = build_dependency_probes(settings)

    assert run_dependency_probes(probes) == {"shared_storage_ro": STATUS_UP, "chromadb": STATUS_UP}


def test_probe_exception_is_reported_as_down_and_does_not_break_others() -> None:
    def exploding() -> bool:
        raise RuntimeError("探测内部异常")

    results = run_dependency_probes({"broken": exploding, "ok": lambda: True})

    assert results == {"broken": STATUS_DOWN, "ok": STATUS_UP}


def test_overall_status_only_considers_passed_in_dependencies() -> None:
    """`peers` 不参与判定：空映射与全 UP 都算 UP，任一 DOWN 即 DOWN。"""
    assert overall_status({}) == STATUS_UP
    assert overall_status({"chromadb": STATUS_UP, "shared_storage_ro": STATUS_UP}) == STATUS_UP
    assert overall_status({"chromadb": STATUS_DOWN, "shared_storage_ro": STATUS_UP}) == STATUS_DOWN


def test_http_probe_ignores_proxy_environment(
    stub_server: tuple[str, type[_StubHandler]], monkeypatch: pytest.MonkeyPatch
) -> None:
    """进程配了代理也不得影响内部探针。

    回归用例：本机 `ALL_PROXY=socks://…` 时 httpx 默认会去走代理，未装 socks 扩展就抛
    `Unknown scheme for proxy URL`，把可达的依赖报成 DOWN（TASK-105 复核实测到的真实缺陷）。
    修复是显式 `trust_env=False`。
    """
    base_url, handler = stub_server
    handler.status_code = 200
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("ALL_PROXY", "socks://127.0.0.1:1")
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:1")

    assert is_http_endpoint_up(f"{base_url}{CHROMADB_HEARTBEAT_PATH}") is True
