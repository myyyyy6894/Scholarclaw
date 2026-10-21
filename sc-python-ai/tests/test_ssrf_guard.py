"""出站 SSRF 二次校验（`NFR-SEC-05`、`02` §10.3）。

DNS 用 monkeypatch 固定为真实公网 IP（8.8.8.8），测试不依赖外部网络，也不靠"某域名恰好能解析"。
"""

from __future__ import annotations

import ipaddress
import socket
import time

import pytest

from app.core.exceptions import BaseUrlRejectedError
from app.services.gateway import ssrf_guard
from app.services.gateway.ssrf_guard import origin_of, validate_outbound_url

ALLOWED = ("http://127.0.0.1:11434", "http://192.168.1.50:8000")

#: 被管理员误配的“毒化白名单”：内部服务名/端口与元数据地址必须仍然硬拒绝。
POISONED = (
    "http://sc-mysql:3306",
    "http://127.0.0.1:3306",
    "http://100.100.100.200",
    "http://[fd00:ec2::254]",
    "http://[fe80::1]",
    "http://127.0.0.1:11434",
    "http://[::1]:11434",
)

#: 测试内固定解析结果：公网域名 → 真实公网 IP；内网/元数据域名 → 对应危险地址。
_DNS_TABLE = {
    "api.example.com": "8.8.8.8",
    "gw.example.com": "8.8.4.4",
    "internal.example.com": "10.0.0.5",
    "metadata.example.com": "169.254.169.254",
    "localhost": "127.0.0.1",
    "::1": "::1",
    # 非点分十进制的回环写法：libc 会解析成 127.0.0.1，校验必须基于解析结果而不是字符串
    "2130706433": "127.0.0.1",
    "0x7f000001": "127.0.0.1",
    "0177.0.0.1": "127.0.0.1",
    "127.1": "127.0.0.1",
    "::ffff:100.100.100.200": "100.100.100.200",
    "::ffff:169.254.169.254": "169.254.169.254",
    "::ffff:127.0.0.1": "127.0.0.1",
    "100.64.0.1": "100.64.0.1",
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
    "https://100.100.100.200/latest/meta-data",  # 阿里云元数据地址
    "https://[fd00:ec2::254]/",                  # AWS IPv6 元数据地址
    "https://[fe80::1]/",                        # IPv6 链路本地
    "https://2130706433/v1",                     # 十进制回环
    "https://0x7f000001/v1",                     # 十六进制回环
    "https://0177.0.0.1/v1",                     # 八进制回环
    "https://127.1/v1",                          # 省略段回环
])
def test_unsafe_urls_are_rejected(url: str) -> None:
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=False, allowed_origins=ALLOWED)


@pytest.mark.parametrize("url", [item for item in POISONED if item not in {
    "http://127.0.0.1:11434", "http://[::1]:11434"}])
def test_whitelist_never_bypasses_hard_denied_targets(url: str) -> None:
    """白名单只豁免本地/私有地址与 http，不得放行内部服务名、内部端口或元数据地址。"""
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=True, allowed_origins=POISONED)


def test_whitelist_still_allows_legal_local_ollama_origins() -> None:
    validate_outbound_url("http://127.0.0.1:11434/v1", allow_local=True, allowed_origins=POISONED)
    validate_outbound_url("http://[::1]:11434/v1", allow_local=True, allowed_origins=POISONED)


def test_whitelist_does_not_allow_public_addresses_or_https() -> None:
    """白名单只放行本地/私有/回环 + http；公网地址即使命中白名单也必须拒绝。"""
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(
            "http://api.example.com/v1", allow_local=True,
            allowed_origins=("http://api.example.com",))
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(
            "https://127.0.0.1:11434/v1", allow_local=True,
            allowed_origins=("https://127.0.0.1:11434",))


def test_whitelist_checks_every_dns_result(monkeypatch: pytest.MonkeyPatch) -> None:
    """白名单命中后仍须检查全部解析结果：一条私网 + 一条公网也必须拒绝。"""
    monkeypatch.setattr(
        ssrf_guard,
        "_resolve",
        lambda _host: (ipaddress.ip_address("127.0.0.1"), ipaddress.ip_address("8.8.8.8")),
    )

    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(
            "http://127.0.0.1:11434/v1", allow_local=True,
            allowed_origins=("http://127.0.0.1:11434",))


@pytest.mark.parametrize("url", [
    "https://[::ffff:100.100.100.200]/",
    "https://[::ffff:169.254.169.254]/",
    "https://[::ffff:127.0.0.1]/",
    "https://100.64.0.1/",
])
def test_ipv4_mapped_and_shared_addresses_are_rejected(url: str) -> None:
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=False, allowed_origins=())


def test_hard_denied_mapped_metadata_beats_whitelist() -> None:
    origin = "http://[::ffff:100.100.100.200]"
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(origin, allow_local=True, allowed_origins=(origin,))


@pytest.mark.asyncio
async def test_async_whitelist_checks_every_dns_result(monkeypatch: pytest.MonkeyPatch) -> None:
    async def mixed_resolve(_host: str):  # noqa: ANN202
        return (ipaddress.ip_address("127.0.0.1"), ipaddress.ip_address("8.8.8.8"))

    monkeypatch.setattr(ssrf_guard, "_resolve_async", mixed_resolve)

    with pytest.raises(BaseUrlRejectedError):
        await ssrf_guard.validate_outbound_url_async(
            "http://127.0.0.1:11434/v1", allow_local=True,
            allowed_origins=("http://127.0.0.1:11434",))


@pytest.mark.parametrize("url", [
    "https://api.example.com:99999/v1",
    "https://api.example.com:0x1/v1",
    "https://[::1:bad]/v1",
])
def test_invalid_port_or_ipv6_maps_to_e_sys_02_not_value_error(url: str) -> None:
    """非法端口/非法 IPv6 必须映射为 E-SYS-02，不能让内部接口返回 500。"""
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=False, allowed_origins=())


def test_origin_of_never_raises_on_invalid_port() -> None:
    assert origin_of("https://api.example.com:99999/v1") == ""
    assert origin_of("https://api.example.com:0x1/v1") == ""


def test_dns_timeout_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    def slow_getaddrinfo(*_args, **_kwargs):  # noqa: ANN001, ANN202
        time.sleep(0.3)
        raise socket.gaierror("slow")

    monkeypatch.setattr(ssrf_guard, "DNS_TIMEOUT_SECONDS", 0.05)
    monkeypatch.setattr(socket, "getaddrinfo", slow_getaddrinfo)

    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url("https://api.example.com/v1", allow_local=False, allowed_origins=())


def test_validation_resolves_dns_once_and_does_not_pin(monkeypatch: pytest.MonkeyPatch) -> None:
    """记录残余风险：校验只解析一次 DNS，实际连接时 httpx 会再次解析（不宣称已防住 rebinding）。"""
    calls: list[str] = []
    real_getaddrinfo = socket.getaddrinfo

    def counting_getaddrinfo(host, port, *args, **kwargs):  # noqa: ANN001, ANN202
        if host == "api.example.com":
            calls.append(host)
        return real_getaddrinfo(host, port, *args, **kwargs)

    monkeypatch.setattr(socket, "getaddrinfo", counting_getaddrinfo)

    validate_outbound_url("https://api.example.com/v1", allow_local=False, allowed_origins=())

    assert calls == ["api.example.com"]


def test_rejection_message_never_echoes_the_target() -> None:
    """错误响应不得暴露内部网络信息（`04` §4.2.2 规则 3 / TASK-204 验收条件）。"""
    url = "https://169.254.169.254/latest/meta-data"

    with pytest.raises(BaseUrlRejectedError) as excinfo:
        validate_outbound_url(url, allow_local=False, allowed_origins=())

    assert url not in str(excinfo.value)
    assert "169.254" not in str(excinfo.value)


@pytest.mark.parametrize("url", [
    "https://api.example.com/v1/\x00admin",
    "https://api.example.com/v1/\x1fadmin",
    "https://api.example.com/v1/\x7fadmin",
    "https://api.example.com/v1/\x85admin",       # C1 控制字符
    "https://api.example.com/v1/%2e%2e/admin",
    "https://api.example.com/v1/%2E%2E/admin",
    "https://api.example.com/v1/%252e%252e/admin",  # 双层编码
    "https://api.example.com/v1/%5cadmin",
    "https://api.example.com/v1/%00admin",
])
def test_control_characters_and_encoded_traversal_are_rejected(url: str) -> None:
    """C0/C1/DEL 控制字符与编码后的路径穿越必须统一返回字典错误。"""
    with pytest.raises(BaseUrlRejectedError):
        validate_outbound_url(url, allow_local=False, allowed_origins=())


def test_legal_dots_are_not_rejected() -> None:
    """合法点号（单点）不能被路径穿越规则误拦。"""
    validate_outbound_url("https://api.example.com/v1.0/models", allow_local=False, allowed_origins=())
    validate_outbound_url("https://api.example.com/a.b/c", allow_local=False, allowed_origins=())


@pytest.mark.asyncio
async def test_async_validation_applies_the_same_rules() -> None:
    await ssrf_guard.validate_outbound_url_async(
        "https://api.example.com/v1", allow_local=False, allowed_origins=())
    await ssrf_guard.validate_outbound_url_async(
        "https://api.example.com/v1.0/models", allow_local=False, allowed_origins=())

    with pytest.raises(BaseUrlRejectedError):
        await ssrf_guard.validate_outbound_url_async(
            "https://internal.example.com/v1", allow_local=False, allowed_origins=())
    with pytest.raises(BaseUrlRejectedError):
        await ssrf_guard.validate_outbound_url_async(
            "https://api.example.com/v1/%2e%2e/admin", allow_local=False, allowed_origins=())


@pytest.mark.asyncio
async def test_async_dns_timeout_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    def slow_getaddrinfo(*_args, **_kwargs):  # noqa: ANN001, ANN202
        time.sleep(0.3)
        raise socket.gaierror("slow")

    monkeypatch.setattr(ssrf_guard, "DNS_TIMEOUT_SECONDS", 0.05)
    monkeypatch.setattr(socket, "getaddrinfo", slow_getaddrinfo)

    with pytest.raises(BaseUrlRejectedError):
        await ssrf_guard.validate_outbound_url_async(
            "https://api.example.com/v1", allow_local=False, allowed_origins=())


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


def test_public_ipv6_is_not_misclassified_as_shared_ipv4() -> None:
    assert ssrf_guard._is_internal(
        ipaddress.ip_address("2001:4860:4860::8888")) is False


def test_origin_of_normalizes_scheme_and_host() -> None:
    assert origin_of("HTTPS://API.Example.com:8443/v1") == "https://api.example.com:8443"
    assert origin_of("http://127.0.0.1:11434/v1") == "http://127.0.0.1:11434"
    assert origin_of("not a url") == ""
