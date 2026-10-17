"""测试夹具。

两个关键点：

* **配置隔离**：每个测试都把进程环境替换为确定值（含随测试创建的只读存储目录），
  并清空 `get_settings()` 的缓存，避免测试之间以及与真实环境互相污染。
* **对端地址可控**：`ASGIClientHostOverride` 直接改写 ASGI `scope["client"]`，用来验证
  `04` `API-CONF-03` 的环回例外——不依赖 TestClient 的内部实现细节。
"""

from __future__ import annotations

from collections.abc import Callable, Iterator
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from app.core.config import reset_settings_cache

TEST_INTERNAL_TOKEN = "test-internal-token-4a7f1c9d"

LOOPBACK_HOST = "127.0.0.1"
REMOTE_HOST = "10.0.0.7"

_MANAGED_ENV_VARS = (
    "SC_INTERNAL_SERVICE_TOKEN",
    "SC_TIMEZONE",
    "SC_STORAGE_RAW_PATH",
    "SC_LOG_LEVEL",
    "SC_ALLOW_LOCAL_MODEL_BASE_URL",
    "SC_LOCAL_MODEL_ALLOWED_ORIGINS",
    "SC_MODEL_CONNECT_TIMEOUT_SECONDS",
    "SC_MODEL_READ_TIMEOUT_SECONDS",
)


class ASGIClientHostOverride:
    """把 ASGI `scope["client"]` 固定为指定 IP，用于区分环回与非环回来源。"""

    def __init__(self, app, host: str) -> None:  # noqa: ANN001 - ASGI 应用
        self.app = app
        self.host = host

    async def __call__(self, scope, receive, send) -> None:  # noqa: ANN001 - ASGI 协议
        if scope["type"] == "http":
            scope = {**scope, "client": (self.host, 51234)}
        await self.app(scope, receive, send)


ClientFactory = Callable[..., TestClient]


@pytest.fixture(autouse=True)
def isolated_settings(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> Iterator[None]:
    """把配置来源固定为测试值，并清空单例缓存。"""
    raw = tmp_path / "storage" / "raw"
    raw.mkdir(parents=True, exist_ok=True)

    for name in _MANAGED_ENV_VARS:
        monkeypatch.delenv(name, raising=False)

    monkeypatch.setenv("SC_INTERNAL_SERVICE_TOKEN", TEST_INTERNAL_TOKEN)
    monkeypatch.setenv("SC_TIMEZONE", "Asia/Shanghai")
    monkeypatch.setenv("SC_STORAGE_RAW_PATH", str(raw))
    monkeypatch.setenv("SC_LOG_LEVEL", "WARNING")

    reset_settings_cache()
    yield
    reset_settings_cache()


@pytest.fixture
def client_factory(monkeypatch: pytest.MonkeyPatch) -> ClientFactory:
    """按需构造测试客户端，可覆盖对端地址与配置。"""

    def _make(
        host: str = LOOPBACK_HOST,
        *,
        token: str | None = TEST_INTERNAL_TOKEN,
        storage_raw_path: Path | str | None = None,
        **extra_env: str,
    ) -> TestClient:
        if token is None:
            monkeypatch.delenv("SC_INTERNAL_SERVICE_TOKEN", raising=False)
        else:
            monkeypatch.setenv("SC_INTERNAL_SERVICE_TOKEN", token)
        if storage_raw_path is not None:
            monkeypatch.setenv("SC_STORAGE_RAW_PATH", str(storage_raw_path))
        for name, value in extra_env.items():
            monkeypatch.setenv(name, value)

        reset_settings_cache()

        from app.main import create_app

        return TestClient(ASGIClientHostOverride(create_app(), host), raise_server_exceptions=False)

    return _make


@pytest.fixture
def loopback_client(client_factory: ClientFactory) -> Iterator[TestClient]:
    with client_factory(LOOPBACK_HOST) as client:
        yield client


@pytest.fixture
def remote_client(client_factory: ClientFactory) -> Iterator[TestClient]:
    with client_factory(REMOTE_HOST) as client:
        yield client


@pytest.fixture
def authorized_remote_client(client_factory: ClientFactory) -> Iterator[TestClient]:
    """带合法内部令牌的非环回客户端，等价于 Java 跨容器调用。"""
    with client_factory(REMOTE_HOST) as client:
        client.headers.update({"X-Internal-Token": TEST_INTERNAL_TOKEN})
        yield client


@pytest.fixture
def storage_raw_dir(tmp_path: Path) -> Path:
    """模拟共享卷只读挂载点（`/data/scholarclaw/storage/raw`）。"""
    raw = tmp_path / "storage" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    return raw
