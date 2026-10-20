"""出站地址的 SSRF 二次校验（`NFR-SEC-05`、`02` §10.3、`04` §4.2.2 业务规则 2）。

与 Java 的 `BaseUrlSecurityValidator` 分工：Java 在落库/发起前做**第一道**校验（用户可编辑的
`base_url` 天然不可信），本模块在真正发起出站请求前做**第二道**——因为 DNS 可能在两次校验之间
变成内网地址（DNS rebinding），只信一次解析结果的做法会漏。

**两层语义（与 Java 完全一致）：**

* **硬拒绝（任何配置都不可绕过）：** 内部服务名、内部端口、云元数据地址
  （`169.254.169.254`、`100.100.100.200`、`fd00:ec2::254`）、IPv4/IPv6 链路本地地址；
* **白名单例外（只豁免“本地/私有地址 + http”）：** 仅当
  `SC_ALLOW_LOCAL_MODEL_BASE_URL=true` 且该**精确 origin**（scheme://host:port）命中
  `SC_LOCAL_MODEL_ALLOWED_ORIGINS` 时，才允许回环/私有地址与非 https 协议。
  白名单**不能**放行内部服务名、内部端口或元数据地址。

**残余风险（必须明确，不得宣称已完全防住 DNS rebinding）：** 本模块在出站前解析一次 DNS
并校验结果，但 httpx 建立连接时会再次解析；两次解析之间域名仍可能被切换为内网地址。
当前 MVP 不实现 IP 钉扎（解析出的 IP 用于实际连接、保留原主机名做 SNI/Host），
因此该窗口仍然存在。缓解手段是：禁止重定向、禁止代理、出站前校验，以及容器网络本身
不暴露数据库/Redis 端口。若后续网关（TASK-301）需要更强保证，应改为受控 transport + IP 钉扎。
"""

from __future__ import annotations

import asyncio
import ipaddress
import socket
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeoutError
from urllib.parse import unquote, urlsplit

from app.core.exceptions import BaseUrlRejectedError

PUBLIC_ALLOWED_PORTS = frozenset({443, 8443})

#: 内部服务端口：**硬拒绝**，白名单也不放行。
HARD_DENIED_PORTS = frozenset({3306, 6379, 5672, 15672, 8000, 8080})

#: 容器名与内部主机名：**硬拒绝**。
HARD_DENIED_HOSTS = frozenset({
    "mysql", "redis", "rabbitmq", "chromadb",
    "sc-java-backend", "sc-python-ai",
    "sc-mysql", "sc-redis", "sc-rabbitmq", "sc-chromadb", "sc-napcat-onebot",
})

#: 云元数据主机名：**硬拒绝**。
METADATA_HOSTS = frozenset({"metadata.google.internal", "metadata.tencentyun.com"})

#: 云元数据 IP：**硬拒绝**（不受白名单影响）。
METADATA_IPS = frozenset({"169.254.169.254", "100.100.100.200", "fd00:ec2::254"})

#: DNS 解析超时（秒）：解析卡住时按拒绝处理，不能把请求线程无限挂起。
DNS_TIMEOUT_SECONDS = 3.0

#: 探测类出站请求的默认读超时（秒）：必须显著小于 Java 侧读取超时 10s（`04` §4.9.1）。
DEFAULT_PROBE_READ_TIMEOUT_SECONDS = 5.0

#: 单次非流式响应体上限（`NFR-SEC-05`）：2 MiB。
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


#: 百分比编码的最大解码轮数：覆盖 `%2e%2e`、`%252e%252e` 等多层编码。
ENCODED_TRAVERSAL_DECODE_ROUNDS = 3


def origin_of(url: str) -> str:
    """返回 `scheme://host[:port]`；任何解析异常都返回空串，绝不向上抛。"""
    try:
        parts = urlsplit(url)
        host = parts.hostname
        if not parts.scheme or not host:
            return ""
        port = f":{parts.port}" if parts.port else ""
    except (ValueError, UnicodeError):
        return ""
    return f"{parts.scheme.lower()}://{_format_host(host.lower())}{port}"


def _format_host(host: str) -> str:
    """IPv6 字面量在 URL 中必须带方括号，否则无法与白名单的精确 origin 匹配。"""
    return f"[{host}]" if ":" in host else host


def validate_outbound_url(
    base_url: str,
    *,
    allow_local: bool,
    allowed_origins: tuple[str, ...],
) -> None:
    """同步校验出站地址；不通过即抛 :class:`BaseUrlRejectedError`（绝不抛裸 ValueError）。"""
    host, whitelisted = _validate_structure(
        base_url, allow_local=allow_local, allowed_origins=allowed_origins
    )
    _check_resolved(_resolve(host), whitelisted)


async def validate_outbound_url_async(
    base_url: str,
    *,
    allow_local: bool,
    allowed_origins: tuple[str, ...],
) -> None:
    """异步校验出站地址：DNS 解析走事件循环，不阻塞协程（网关的出站入口使用本函数）。"""
    host, whitelisted = _validate_structure(
        base_url, allow_local=allow_local, allowed_origins=allowed_origins
    )
    _check_resolved(await _resolve_async(host), whitelisted)


def _validate_structure(
    base_url: str,
    *,
    allow_local: bool,
    allowed_origins: tuple[str, ...],
) -> tuple[str, bool]:
    """完成协议/主机/端口/硬拒绝/白名单的字面量校验，返回 `(host, whitelisted)`。

    DNS 解析与 IP 判定由调用方在同步或异步路径中完成，避免同一套规则写两遍。
    """
    candidate = (base_url or "").strip()
    if not candidate:
        raise BaseUrlRejectedError()
    _reject_unsafe_characters(candidate)

    try:
        parts = urlsplit(candidate)
        scheme = parts.scheme.lower()
        host = (parts.hostname or "").rstrip(".").lower()
        port = parts.port
    except (ValueError, UnicodeError):
        # 非法端口（如 99999）、非法 IPv6、编码异常统一映射为 E-SYS-02
        raise BaseUrlRejectedError() from None

    if not host or scheme not in {"http", "https"}:
        raise BaseUrlRejectedError()
    if parts.username or parts.password:
        raise BaseUrlRejectedError()

    # ---------- 第一层：硬拒绝（任何配置都不可绕过） ----------
    if host in HARD_DENIED_HOSTS or host in METADATA_HOSTS:
        raise BaseUrlRejectedError()
    if port in HARD_DENIED_PORTS:
        raise BaseUrlRejectedError()
    if _is_metadata_literal(host):
        raise BaseUrlRejectedError()

    # ---------- 第二层：白名单只豁免"本地/私有地址 + http" ----------
    origin = f"{scheme}://{_format_host(host)}{f':{port}' if port else ''}"
    whitelisted = allow_local and origin in {item.lower() for item in allowed_origins}
    if not whitelisted:
        if scheme != "https":
            raise BaseUrlRejectedError()
        if port not in (None, *PUBLIC_ALLOWED_PORTS):
            raise BaseUrlRejectedError()

    return host, whitelisted


def _reject_unsafe_characters(candidate: str) -> None:
    """拒绝控制字符与编码后的路径穿越（`%2e%2e`、`%5c`、多层编码等）。

    百分号编码可能绕过简单的字符串检查：`https://host/%2e%2e/admin` 在 URL 解析后
    等价于 `../admin`。这里先检查原文的控制字符，再逐层解码并检查 `..` 与反斜杠。
    合法点号（如 `api.example.com`、`v1.0`）不会被误拦，因为只拒绝连续两个点。
    """
    if _has_control_character(candidate):
        raise BaseUrlRejectedError()

    decoded = candidate
    for _ in range(ENCODED_TRAVERSAL_DECODE_ROUNDS):
        if "\\" in decoded or ".." in decoded or _has_control_character(decoded):
            raise BaseUrlRejectedError()
        try:
            next_value = unquote(decoded, errors="strict")
        except (UnicodeError, ValueError):
            raise BaseUrlRejectedError() from None
        if next_value == decoded:
            return
        decoded = next_value
    if "\\" in decoded or ".." in decoded or _has_control_character(decoded):
        raise BaseUrlRejectedError()


def _has_control_character(value: str) -> bool:
    """C0（`0x00-0x1F`）、DEL（`0x7F`）与 C1（`0x80-0x9F`）控制字符。"""
    return any(ord(ch) < 0x20 or 0x7F <= ord(ch) <= 0x9F for ch in value)


def _is_metadata_literal(host: str) -> bool:
    if host in METADATA_IPS:
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return False
    # IPv4/IPv6 链路本地（169.254.0.0/16、fe80::/10）不可能是合法模型地址
    return address.is_link_local


def _is_hard_denied_ip(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """元数据与链路本地地址：白名单也不能放行。回环/私网不在其中（白名单可显式放行）。"""
    return str(address) in METADATA_IPS or address.is_link_local


def _check_resolved(
    addresses: tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...],
    whitelisted: bool,
) -> None:
    for address in addresses:
        if _is_hard_denied_ip(address):
            raise BaseUrlRejectedError()
        if not whitelisted and _is_internal(address):
            raise BaseUrlRejectedError()


def _resolve(host: str) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...]:
    """同步解析主机名到全部 IP（带超时；受控线程，供非异步调用方使用）。

    解析失败或超时按拒绝处理（不把"解析不了"当成"安全"）。
    **注意：** 这里解析出的 IP 仅用于校验；httpx 连接时会再次解析，DNS rebinding
    窗口仍然存在（见模块 docstring）。
    """
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="sc-ssrf-dns")
    future = executor.submit(socket.getaddrinfo, host, None)
    try:
        infos = future.result(timeout=DNS_TIMEOUT_SECONDS)
    except FutureTimeoutError:
        future.cancel()
        raise BaseUrlRejectedError() from None
    except (socket.gaierror, UnicodeError, ValueError):
        raise BaseUrlRejectedError() from None
    finally:
        executor.shutdown(wait=False, cancel_futures=True)
    return _addresses_from(infos)


async def _resolve_async(
    host: str,
) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...]:
    """异步解析主机名（`loop.getaddrinfo`），DNS 等待不阻塞事件循环。

    超时、解析失败与非法地址一律按拒绝处理（`E-SYS-02`）。
    """
    loop = asyncio.get_running_loop()
    try:
        infos = await asyncio.wait_for(loop.getaddrinfo(host, None), timeout=DNS_TIMEOUT_SECONDS)
    except (asyncio.TimeoutError, socket.gaierror, UnicodeError, ValueError):
        raise BaseUrlRejectedError() from None
    return _addresses_from(infos)


def _addresses_from(
    infos: list[tuple],
) -> tuple[ipaddress.IPv4Address | ipaddress.IPv6Address, ...]:
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
