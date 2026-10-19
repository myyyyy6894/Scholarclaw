"""出站地址的 SSRF 二次校验（`NFR-SEC-05`、`02` §10.3、`04` §4.2.2 业务规则 2）。

与 Java 的 `BaseUrlSecurityValidator` 分工：Java 在落库/发起前做**第一道**校验（用户可编辑的
`base_url` 天然不可信），本模块在真正发起出站请求前做**第二道**——因为 DNS 可能在两次校验之间
变成内网地址（DNS rebinding），只信一次解析结果的做法会漏。

规则（与 `04` 一致）：

* 公网：仅 `https`，端口仅 `443` / `8443`；
* 本地/内网：仅当 `SC_ALLOW_LOCAL_MODEL_BASE_URL=true` 且该**精确 origin**（scheme://host:port）
  命中 `SC_LOCAL_MODEL_ALLOWED_ORIGINS` 白名单时才放行；
* 任何情况下都拒绝 URL 中的 `user:pass@`、反斜杠、`..` 片段；
* 拒绝解析到回环/私有/链路本地/保留/组播/未指定地址（除非命中上述白名单）；
* 拒绝云元数据地址 `169.254.169.254`。

命中任何一条即抛 `BaseUrlRejectedError`（`E-SYS-02`，HTTP 400），错误消息不复述用户输入。
"""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlsplit

from app.core.exceptions import BaseUrlRejectedError

PUBLIC_ALLOWED_PORTS = frozenset({443, 8443})
CLOUD_METADATA_IPS = frozenset({"169.254.169.254"})

#: 探测类出站请求的默认读超时（秒）：必须显著小于 Java 侧读取超时 10s（`04` §4.9.1）。
DEFAULT_PROBE_READ_TIMEOUT_SECONDS = 5.0


def origin_of(url: str) -> str:
    """返回 `scheme://host[:port]`；解析失败返回空串。"""
    parts = urlsplit(url)
    if not parts.scheme or not parts.hostname:
        return ""
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme.lower()}://{parts.hostname.lower()}{port}"


def validate_outbound_url(
    base_url: str,
    *,
    allow_local: bool,
    allowed_origins: tuple[str, ...],
) -> None:
    """校验出站地址；不通过即抛 :class:`BaseUrlRejectedError`。"""
    candidate = (base_url or "").strip()
    if not candidate:
        raise BaseUrlRejectedError()
    if "\\" in candidate or ".." in candidate:
        raise BaseUrlRejectedError()

    parts = urlsplit(candidate)
    if parts.username or parts.password:
        raise BaseUrlRejectedError()

    scheme = parts.scheme.lower()
    if scheme not in {"http", "https"}:
        raise BaseUrlRejectedError()

    origin = origin_of(candidate)
    whitelisted = allow_local and origin in {item.lower() for item in allowed_origins}
    if not whitelisted:
        if scheme != "https":
            raise BaseUrlRejectedError()
        if parts.port not in (None, *PUBLIC_ALLOWED_PORTS):
            raise BaseUrlRejectedError()

    host = parts.hostname or ""
    for address in _resolve(host):
        if str(address) in CLOUD_METADATA_IPS:
            raise BaseUrlRejectedError()
        if not whitelisted and _is_internal(address):
            raise BaseUrlRejectedError()


def _resolve(host: str) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...]:
    """解析主机名到全部 IP；解析失败按拒绝处理（不把"解析不了"当成"安全"）。"""
    try:
        infos = socket.getaddrinfo(host, None)
    except (socket.gaierror, UnicodeError, ValueError):
        raise BaseUrlRejectedError() from None
    addresses = []
    for info in infos:
        try:
            addresses.append(ipaddress.ip_address(info[4][0]))
        except ValueError:
            raise BaseUrlRejectedError() from None
    if not addresses:
        raise BaseUrlRejectedError()
    return tuple(addresses)


def _is_internal(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_reserved
        or address.is_multicast
        or address.is_unspecified
    )
