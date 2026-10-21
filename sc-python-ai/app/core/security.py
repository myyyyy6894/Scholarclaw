"""内部调用鉴权（`NFR-SEC-04`、`04` §2.7.1 / §6.1 / `API-CONF-03`）。

规则：

1. `/internal/ai/v1/**` 的全部请求必须携带 `X-Internal-Token`，与 `SC_INTERNAL_SERVICE_TOKEN`
   做**常量时间比较**，失败立即返回 `403 AUTH_FORBIDDEN`，且**不回显**收到的令牌。
2. **失败关闭**：令牌未注入（环境变量缺失）时，不是"跳过校验"，而是拒绝所有请求——
   否则部署时漏注入变量就等于内部接口完全裸奔。
3. **健康检查环回例外**（`04` `API-CONF-03` 默认方案）：仅 `/internal/ai/v1/health` 允许来自
   容器本机环回地址的只读探针免 Token 访问，方便 Docker Compose `healthcheck` 无需转义令牌。
   判定只依据 TCP 对端地址，**绝不**读取 `X-Forwarded-For` 之类的可伪造头；业务接口不适用该例外。
4. **身份信任**：只有令牌校验通过后，才把 `X-User-Id` 绑定进追踪上下文供日志使用。
"""

from __future__ import annotations

import hmac
import ipaddress
from typing import Final

from fastapi import Request

from app.core import tracing
from app.core.config import Settings, get_settings
from app.core.exceptions import AiErrorCode, InternalTokenInvalidError, NonRetryableAiError
from app.core.logging import get_logger

INTERNAL_TOKEN_HEADER: Final = "X-Internal-Token"

logger = get_logger(__name__)


def is_loopback_host(host: str | None) -> bool:
    """判断 TCP 对端是否为环回地址（`127.0.0.0/8`、`::1`，含 IPv4-mapped 形式）。

    主机名（如 `localhost`）与非法字符串一律返回 `False`：对端地址是内核给出的 IP，
    不应出现主机名；出现即视为不可信来源。
    """
    if not host:
        return False
    candidate = host.strip().strip("[]")
    if candidate.startswith("::ffff:") or candidate.startswith("::FFFF:"):
        candidate = candidate[len("::ffff:") :]
    try:
        return ipaddress.ip_address(candidate).is_loopback
    except ValueError:
        return False


def constant_time_equals(provided: str | None, expected: str) -> bool:
    """常量时间字符串比较；对 `None` 也保持恒定时序。"""
    if provided is None:
        provided = ""
    return hmac.compare_digest(provided.encode("utf-8"), expected.encode("utf-8"))


def verify_internal_token(request: Request, *, allow_loopback: bool = False) -> None:
    """校验内部令牌；失败抛 :class:`InternalTokenInvalidError`（403）。

    :param allow_loopback: 仅健康检查探针使用。为 `True` 且对端为环回地址时免令牌放行。
    """
    settings: Settings = get_settings()

    if allow_loopback and is_loopback_host(request.client.host if request.client else None):
        logger.debug("健康探针来自环回地址，按 API-CONF-03 免内部令牌放行")
        return

    if not settings.internal_token_configured:
        logger.error("内部令牌未注入（SC_INTERNAL_SERVICE_TOKEN 为空），按失败关闭拒绝访问 path=%s",
                     request.url.path)
        raise InternalTokenInvalidError()

    provided = request.headers.get(INTERNAL_TOKEN_HEADER)
    if not constant_time_equals(provided, settings.internal_service_token):
        logger.warning("内部接口鉴权失败，已拒绝访问 path=%s method=%s", request.url.path, request.method)
        raise InternalTokenInvalidError()

    tracing.bind_user_id(_sanitize_user_id(request.headers.get(tracing.USER_ID_HEADER)))


async def require_internal_token(request: Request) -> None:
    """业务内部接口的依赖：必须携带合法令牌。"""
    verify_internal_token(request)


async def require_internal_identity(request: Request) -> str:
    """业务内部接口的依赖：合法令牌 + 合法十进制 `X-User-Id`。

    身份头是内部调用契约的一部分（`04` §2.7.1）：缺失、非十进制或超长都返回
    `400 VALIDATION_ERROR`，不得继续执行探测。健康检查走环回例外，不使用本依赖。
    """
    verify_internal_token(request)
    user_id = _require_user_id(request.headers.get(tracing.USER_ID_HEADER))
    tracing.bind_user_id(user_id)
    return user_id


async def require_internal_token_or_loopback(request: Request) -> None:
    """健康检查接口的依赖：环回地址免令牌，其余仍需合法令牌。"""
    verify_internal_token(request, allow_loopback=True)


def _sanitize_user_id(raw: str | None) -> str | None:
    """`X-User-Id` 只接受十进制字符串（`04` §2.7.1），其余一律丢弃，避免污染日志。"""
    if raw is None:
        return None
    candidate = raw.strip()
    return candidate if candidate.isdigit() else None


def _require_user_id(raw: str | None) -> str:
    """业务内部接口的严格身份校验：缺失、非十进制或超长一律 400。"""
    if raw is None:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR, "内部请求必须携带 X-User-Id。")
    candidate = raw.strip()
    if not candidate.isdigit() or not 1 <= len(candidate) <= 19:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR, "X-User-Id 必须为 1~19 位十进制字符串。")
    return candidate
