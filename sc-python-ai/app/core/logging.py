"""结构化日志与敏感信息脱敏（`NFR-SEC-02`、`02` §11）。

三条规则：

1. **每条日志强制带 `[trace_id=...] [user_id=...]`**，与 Java 侧格式一致，便于跨语言串联
   （`NFR-REL-02`）。取值来自 :mod:`app.core.tracing` 的上下文变量。
2. **脱敏在过滤器里做**，而不是要求每个调用点自觉避开敏感值——后者漏掉一处就是红线违规。
   覆盖形态与 Java 侧 `SensitiveDataMaskingConverter` 保持一致：JWT、`v1:{iv}:{ct}` 密文、
   `sk-/rk-/pk-` 前缀密钥、JSON 与 key=value 形式的凭据字段、`Bearer` 头。
3. **不打印请求体原文、异常堆栈中的凭据、课件正文**；堆栈本身允许进日志（便于排障），
   但同样经过掩码过滤器。

已知边界（不夸大能力）：脱敏是模式匹配，无法识别任意自定义命名的凭据字段，
因此调用点仍必须遵守"不主动打印凭据"的约束，本过滤器是兜底而非免罪符。
"""

from __future__ import annotations

import logging
import re
import sys
from typing import Any

from app.core import tracing

MASK = "****"

_LOGGER_NAME_PREFIX = "sc"

_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    # AES-256-GCM 密文 v1:{base64_iv}:{base64_ciphertext_with_tag}：密文同样不得入日志
    (re.compile(r"\bv1:[A-Za-z0-9+/=_\-]+:[A-Za-z0-9+/=_\-]+"), f"v1:{MASK}:{MASK}"),
    # JWT：header.payload.signature
    (re.compile(r"\beyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*"), MASK),
    # 常见模型厂商密钥前缀
    (re.compile(r"\b(sk|rk|pk)-[A-Za-z0-9_\-]{6,}"), rf"\1-{MASK}"),
    # JSON 双引号字段（允许值内含空格）
    (
        re.compile(
            r'(?i)("(?:api[_-]?key|apikey|authorization|password|passwd|secret|token'
            r'|access[_-]?token)"\s*:\s*")([^"]*)(")'
        ),
        rf"\1{MASK}\3",
    ),
    # 裸露 key=value / key: value
    (
        re.compile(
            r"(?i)\b(api[_-]?key|apikey|authorization|password|passwd|secret|token)\b"
            r"(\s*[:=]\s*)(\"?)([^\s,;\"}]+)"
        ),
        rf"\1\2\3{MASK}",
    ),
    # Authorization: Bearer <token>
    (re.compile(r"(?i)\b(bearer)(\s+)([A-Za-z0-9._\-]{6,})"), rf"\1\2{MASK}"),
)


def mask_sensitive(raw: str) -> str:
    """对任意文本做脱敏；`None` 原样返回。"""
    if raw is None or raw == "":
        return raw
    masked = raw
    for pattern, replacement in _PATTERNS:
        masked = pattern.sub(replacement, masked)
    return masked


class SensitiveDataMaskingFilter(logging.Filter):
    """在格式化前把整条日志消息替换为脱敏版本。"""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            rendered = record.getMessage()
        except Exception:  # pragma: no cover - 格式化失败时不应让日志本身抛错
            return True
        masked = mask_sensitive(rendered)
        if masked != rendered:
            record.msg = masked
            record.args = ()
        return True


class TraceContextFilter(logging.Filter):
    """注入 `trace_id` / `user_id` 字段，供格式串 `%(trace_id)s`、`%(user_id)s` 使用。"""

    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = tracing.current_trace_id() or "-"
        record.user_id = tracing.current_user_id() or "-"
        return True


def get_logger(name: str) -> logging.Logger:
    """获取本服务命名空间下的 logger。

    统一加 `sc.` 前缀，便于容器日志按服务过滤，也避免与第三方库 logger 混淆。
    """
    if name.startswith(f"{_LOGGER_NAME_PREFIX}."):
        return logging.getLogger(name)
    if name == "app" or name.startswith("app."):
        return logging.getLogger(f"{_LOGGER_NAME_PREFIX}.{name}")
    return logging.getLogger(name)


def configure_logging(settings: Any) -> None:
    """按配置初始化根 logger（幂等，可重复调用）。

    同时把 uvicorn 自带的 logger 收敛到同一套 handler：uvicorn 默认会给自己装一套
    不带 `trace_id` 的格式，若不收敛，访问日志就会游离在 `NFR-REL-02`「两侧日志强制输出
    `[trace_id={}] [user_id={}]`」的要求之外。
    """
    level = getattr(logging, settings.log_level, None)
    if not isinstance(level, int):
        level = logging.INFO

    formatter = logging.Formatter(
        fmt="%(asctime)s %(levelname)-5s [%(name)s] [trace_id=%(trace_id)s] [user_id=%(user_id)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(formatter)
    handler.addFilter(SensitiveDataMaskingFilter())
    handler.addFilter(TraceContextFilter())

    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(level)

    logging.getLogger(_LOGGER_NAME_PREFIX).setLevel(level)

    for uvicorn_logger_name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        third_party = logging.getLogger(uvicorn_logger_name)
        for existing in list(third_party.handlers):
            third_party.removeHandler(existing)
        third_party.propagate = True
