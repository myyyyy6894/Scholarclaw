"""出站 SSRF 二次校验（`NFR-SEC-05`、`02` §10.3）。

DNS 用 monkeypatch 固定为真实公网 IP（8.8.8.8），测试不依赖外部网络，也不靠"某域名恰好能解析"。
"""

from __future__ import annotations

import socket

import pytest

from app.core.exceptions import BaseUrlRejectedError
from app.services.gateway.ssrf_guard import origin_of, validate_outbound_url

ALLOWED = ("http://127.0.0.1:11434", "http://192.168.1.50:8000")

#: 测试内固定解析结果：公网域名 → 真实公网 IP；内网/元数据域名 → 对应危险地址。
_DNS_TABLE = {
    "api.example.com": "8.8.8.8",
    "gw.example.com": "8.8.4.4",
    "internal.example.com": "10.0.0.5",
    "metadata.example.com": "169.254.169.254",
    "localhost": "127.0.0.1",
}


@pytest.fixture(autouse=True)
def fixed_dns(monkeypatch: pytest.MonkeyPatch) -> None:
    real_getaddrinfo = socket.getaddrinfo

    def fake_getaddrinfo(host, port, *args, **kwargs):  # noqa: ANN001, ANN202
        if host in _DNS_TABLE:
            return real_getaddrinfo(_DNS_TABLE[host], port, *args, **kwargs)
        if host in _DNS_TABLE.values():
            return real_getaddrinfo(host, port, *args, **kwargs)
        raise socket.gaierror(f"unresolvable in tests: {host}")

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)


def test_public_https_is_allowed() -> None:
    validate_outbound_url("https://api.example.com/v1", allow_local=False, allowed_origins=())


def test_public_https_custom_8443_is_allowed() -> None:
    validate_outbound_url("https://gw.example.com:8443/v1", allow_local=False, allowed_origins=())


@pytest.mark.parametrize("url", [
    "http://api.example.com/v1",                 # 公网 http 一律拒绝
    "https://api.example.com:8080/v1",           # 公网非 443/8443 端口
    "ftp://api.example.com/v1",                  # 非 http(s)
    "https://user:pass@api.example.com/v1",      # 带凭据
    "https://api.example.com/../admin",          # 路径穿越
    "https://api.example.com\\\\v1",           # 反斜杠
    "https://metadata.example.com/latest/meta-data",  # 解析到云元数据地址
    "https://internal.example.com/v1",           # 解析到私网
    "https://localhost/v1",                      # 解析到回环
])
def test_unsafe_urls_are_rejected(url: str) -> None:
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=False, allowed_origins=ALLOWED)


def test_local_origin_requires_switch_and_exact_whitelist() -> None:
    # 开关关闭：即使命中白名单也不放行
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url("http://127.0.0.1:11434/v1", allow_local=False, allowed_origins=ALLOWED)

    # 开关开启 + 精确命中：放行
    validate_outbound_url("http://127.0.0.1:11434/v1", allow_local=True, allowed_origins=ALLOWED)

    # 开关开启但端口不同（origin 不精确）：拒绝
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url("http://127.0.0.1:9999/v1", allow_local=True, allowed_origins=ALLOWED)


def test_unresolvable_host_is_rejected() -> None:
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url("https://no-such-host.invalid/v1", allow_local=False, allowed_origins=())


def test_origin_of_normalizes_scheme_and_host() -> None:
    assert origin_of("HTTPS://API.Example.com:8443/v1") == "https://api.example.com:8443"
    assert origin_of("http://127.0.0.1:11434/v1") == "http://127.0.0.1:11434"
    assert origin_of("not a url") == ""
