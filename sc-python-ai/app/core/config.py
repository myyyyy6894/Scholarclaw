"""环境变量配置读取（`02` §13.2 最小权限注入 + `05` §4.6）。

设计要点：

1. **白名单读取**：只从 :data:`ALLOWED_ENV_VARS` 列出的变量名读取，不提供"读取任意环境变量"
   的入口。这样 `TASK-102` 的验收条件「不读取 `SC_MASTER_KEY` 或数据库凭据」不是口头承诺，
   而是可机械验证的结构性事实（见 `tests/test_credentials_isolation.py`）。
2. **显式时区**：默认 `Asia/Shanghai`，由 :func:`load_settings` 装载到模块级生效时区，
   :func:`now_in_active_timezone` 是对外时间戳的唯一来源。禁止使用无时区的本地时间
   （`ENVIRONMENT.md` ENV-01：宿主机与容器默认时区都不可信）。
3. **失败关闭**：布尔值与数值解析遇到无法识别的值直接报错，不做"猜一个默认值"的兜底；
   内部令牌缺失不在此处抛错（那是鉴权层的失败关闭行为），但会通过
   :attr:`Settings.internal_token_configured` 暴露状态。
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from datetime import datetime
from functools import lru_cache
from typing import Mapping
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ENV_INTERNAL_SERVICE_TOKEN = "SC_INTERNAL_SERVICE_TOKEN"
ENV_TIMEZONE = "SC_TIMEZONE"
ENV_ALLOW_LOCAL_MODEL_BASE_URL = "SC_ALLOW_LOCAL_MODEL_BASE_URL"
ENV_LOCAL_MODEL_ALLOWED_ORIGINS = "SC_LOCAL_MODEL_ALLOWED_ORIGINS"
ENV_STORAGE_RAW_PATH = "SC_STORAGE_RAW_PATH"
ENV_LOG_LEVEL = "SC_LOG_LEVEL"
ENV_MODEL_CONNECT_TIMEOUT_SECONDS = "SC_MODEL_CONNECT_TIMEOUT_SECONDS"
ENV_MODEL_READ_TIMEOUT_SECONDS = "SC_MODEL_READ_TIMEOUT_SECONDS"
#: ChromaDB 内部基址（TASK-105 起用于健康探针；未注入则不注册该探针，见 app/core/health.py）。
ENV_CHROMADB_BASE_URL = "SC_CHROMADB_BASE_URL"
ENV_JAVA_BACKEND_BASE_URL = "SC_JAVA_BACKEND_BASE_URL"
ENV_RABBITMQ_HOST = "SC_RABBITMQ_HOST"
ENV_RABBITMQ_PORT = "SC_RABBITMQ_PORT"
ENV_RABBITMQ_USER = "SC_RABBITMQ_USER"
ENV_RABBITMQ_PASSWORD = "SC_RABBITMQ_PASSWORD"
ENV_MQ_INGEST_QUEUE = "SC_MQ_INGEST_QUEUE"
ENV_MQ_INGEST_DLQ = "SC_MQ_INGEST_DLQ"
ENV_INGEST_CONSUMER_ENABLED = "SC_INGEST_CONSUMER_ENABLED"
ENV_INGEST_MAX_PAGES = "SC_INGEST_MAX_PAGES"

#: 允许本服务读取的全部环境变量名。新增配置必须先在此登记并说明用途。
ALLOWED_ENV_VARS = frozenset(
    {
        ENV_INTERNAL_SERVICE_TOKEN,
        ENV_TIMEZONE,
        ENV_ALLOW_LOCAL_MODEL_BASE_URL,
        ENV_LOCAL_MODEL_ALLOWED_ORIGINS,
        ENV_STORAGE_RAW_PATH,
        ENV_LOG_LEVEL,
        ENV_MODEL_CONNECT_TIMEOUT_SECONDS,
        ENV_MODEL_READ_TIMEOUT_SECONDS,
        ENV_CHROMADB_BASE_URL,
        ENV_JAVA_BACKEND_BASE_URL,
        ENV_RABBITMQ_HOST,
        ENV_RABBITMQ_PORT,
        ENV_RABBITMQ_USER,
        ENV_RABBITMQ_PASSWORD,
        ENV_MQ_INGEST_QUEUE,
        ENV_MQ_INGEST_DLQ,
        ENV_INGEST_CONSUMER_ENABLED,
        ENV_INGEST_MAX_PAGES,
    }
)

#: 明确**禁止**进入本服务的敏感变量（`02` §13.2）。
#: 它们的存在只用于启动时告警与测试断言，本服务从不读取其值。
FORBIDDEN_ENV_VARS = frozenset(
    {
        "SC_MASTER_KEY",
        "SC_JWT_SECRET",
        "SC_DB_PASSWORD",
        "MYSQL_ROOT_PASSWORD",
        "SC_REDIS_PASSWORD",
    }
)

DEFAULT_TIMEZONE = "Asia/Shanghai"
DEFAULT_STORAGE_RAW_PATH = "/data/scholarclaw/storage/raw"
DEFAULT_LOG_LEVEL = "INFO"
#: ChromaDB 基址默认留空 = 不注册探针：单机只跑本服务时不误报 DOWN；
#: 容器编排里由 docker-compose.yml 注入 http://sc-chromadb:8000。
DEFAULT_CHROMADB_BASE_URL = ""
DEFAULT_MODEL_CONNECT_TIMEOUT_SECONDS = 5.0
DEFAULT_MODEL_READ_TIMEOUT_SECONDS = 30.0
DEFAULT_RABBITMQ_HOST = "localhost"
DEFAULT_RABBITMQ_PORT = 5672
DEFAULT_MQ_INGEST_QUEUE = "rag.ingest.queue"
DEFAULT_MQ_INGEST_DLQ = "rag.ingest.dlq"
DEFAULT_INGEST_MAX_PAGES = 300
#: 模型出站连接超时上限（`04` §4.9.1 / `NFR-REL-01`）：超过 5s 会挤压 Java 的 10s 读取预算。
MAX_MODEL_CONNECT_TIMEOUT_SECONDS = 5.0

_TRUE_VALUES = frozenset({"1", "true", "yes", "on"})
_FALSE_VALUES = frozenset({"0", "false", "no", "off"})

_active_timezone: ZoneInfo = ZoneInfo(DEFAULT_TIMEZONE)


class SettingsError(RuntimeError):
    """配置非法（取值无法识别或超出允许范围）。"""


@dataclass(frozen=True)
class Settings:
    """本服务运行所需的全部配置，字段与白名单环境变量一一对应。"""

    internal_service_token: str
    timezone: str
    allow_local_model_base_url: bool
    local_model_allowed_origins: tuple[str, ...]
    storage_raw_path: str
    log_level: str
    model_connect_timeout_seconds: float
    model_read_timeout_seconds: float
    chromadb_base_url: str
    java_backend_base_url: str
    rabbitmq_host: str
    rabbitmq_port: int
    rabbitmq_user: str
    rabbitmq_password: str
    mq_ingest_queue: str
    mq_ingest_dlq: str
    ingest_consumer_enabled: bool
    ingest_max_pages: int

    @property
    def internal_token_configured(self) -> bool:
        """内部令牌是否已注入；未注入时鉴权层对所有跨容器调用一律拒绝（失败关闭）。"""
        return bool(self.internal_service_token.strip())


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    """从进程环境（或显式传入的映射，便于测试）装载配置。

    副作用：把生效时区固定到模块级 :data:`_active_timezone`，使 :func:`now_in_active_timezone`
    与响应时间戳不依赖宿主机默认时区。
    """
    env = os.environ if environ is None else environ

    timezone_name = _read(env, ENV_TIMEZONE, DEFAULT_TIMEZONE)
    zone = _parse_timezone(timezone_name)
    origins = _parse_origins(_read(env, ENV_LOCAL_MODEL_ALLOWED_ORIGINS, ""))

    settings = Settings(
        internal_service_token=_read(env, ENV_INTERNAL_SERVICE_TOKEN, ""),
        timezone=timezone_name,
        allow_local_model_base_url=_parse_bool(env, ENV_ALLOW_LOCAL_MODEL_BASE_URL, False),
        local_model_allowed_origins=origins,
        storage_raw_path=_read(env, ENV_STORAGE_RAW_PATH, DEFAULT_STORAGE_RAW_PATH),
        log_level=_read(env, ENV_LOG_LEVEL, DEFAULT_LOG_LEVEL).upper(),
        model_connect_timeout_seconds=min(
            _parse_positive_float(
                env, ENV_MODEL_CONNECT_TIMEOUT_SECONDS, DEFAULT_MODEL_CONNECT_TIMEOUT_SECONDS
            ),
            MAX_MODEL_CONNECT_TIMEOUT_SECONDS,
        ),
        model_read_timeout_seconds=_parse_positive_float(
            env, ENV_MODEL_READ_TIMEOUT_SECONDS, DEFAULT_MODEL_READ_TIMEOUT_SECONDS
        ),
        chromadb_base_url=_read(env, ENV_CHROMADB_BASE_URL, DEFAULT_CHROMADB_BASE_URL).rstrip("/"),
        java_backend_base_url=_read(
            env, ENV_JAVA_BACKEND_BASE_URL, "").rstrip("/"),
        rabbitmq_host=_read(env, ENV_RABBITMQ_HOST, DEFAULT_RABBITMQ_HOST),
        rabbitmq_port=_parse_positive_int(env, ENV_RABBITMQ_PORT, DEFAULT_RABBITMQ_PORT),
        rabbitmq_user=_read(env, ENV_RABBITMQ_USER, "scholarclaw"),
        rabbitmq_password=_read(env, ENV_RABBITMQ_PASSWORD, ""),
        mq_ingest_queue=_read(env, ENV_MQ_INGEST_QUEUE, DEFAULT_MQ_INGEST_QUEUE),
        mq_ingest_dlq=_read(env, ENV_MQ_INGEST_DLQ, DEFAULT_MQ_INGEST_DLQ),
        ingest_consumer_enabled=_parse_bool(env, ENV_INGEST_CONSUMER_ENABLED, False),
        ingest_max_pages=_parse_positive_int(env, ENV_INGEST_MAX_PAGES, DEFAULT_INGEST_MAX_PAGES),
    )

    _pin_active_timezone(zone)
    return settings


def detect_forbidden_env_vars(environ: Mapping[str, str] | None = None) -> list[str]:
    """返回当前环境中出现的**禁止变量名**（只返回名字，绝不返回值）。

    用于启动时告警：本服务从不读取这些变量，但它们出现通常意味着容器注入边界被破坏
    （`02` §13.2 要求最小权限注入、严禁全量 `env_file`）。
    """
    env = os.environ if environ is None else environ
    return sorted(name for name in FORBIDDEN_ENV_VARS if name in env)


def active_timezone() -> ZoneInfo:
    """当前生效的应用时区。"""
    return _active_timezone


def now_in_active_timezone() -> datetime:
    """带应用时区偏移量的当前时间；对外时间戳唯一来源。"""
    return datetime.now(_active_timezone)


def format_timestamp(moment: datetime) -> str:
    """序列化为带偏移量的 ISO-8601（`04` §2.5.2 规定格式）。"""
    return moment.isoformat(timespec="milliseconds")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例配置，供 FastAPI 依赖注入使用。"""
    return load_settings()


def reset_settings_cache() -> None:
    """清空配置缓存（测试与配置热更新场景使用）。"""
    get_settings.cache_clear()


def _read(env: Mapping[str, str], name: str, default: str) -> str:
    if name not in ALLOWED_ENV_VARS:  # 防御：白名单外的名字不允许被读取
        raise SettingsError(f"环境变量 {name} 不在白名单内，不得读取")
    value = env.get(name)
    return default if value is None or value.strip() == "" else value.strip()


def _parse_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    normalized = raw.strip().lower()
    if normalized in _TRUE_VALUES:
        return True
    if normalized in _FALSE_VALUES:
        return False
    raise SettingsError(f"{name} 取值 {raw!r} 无法识别为布尔值，请使用 true/false")


def _parse_positive_int(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw.strip())
    except ValueError as error:
        raise SettingsError(f"{name} 必须为正整数") from error
    if value <= 0:
        raise SettingsError(f"{name} 必须为正整数")
    return value


def _parse_positive_float(env: Mapping[str, str], name: str, default: float) -> float:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise SettingsError(f"{name} 取值 {raw!r} 不是合法数字") from exc
    if value <= 0:
        raise SettingsError(f"{name} 必须为正数，当前为 {value}")
    return value


def _parse_origins(raw: str) -> tuple[str, ...]:
    """解析本地模型白名单 Origin 列表（逗号分隔的精确 `scheme://host:port`）。"""
    if not raw.strip():
        return ()
    return tuple(item.strip() for item in raw.split(",") if item.strip())


def _parse_timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise SettingsError(f"{ENV_TIMEZONE} 取值 {name!r} 不是合法 IANA 时区名") from exc


def _pin_active_timezone(zone: ZoneInfo) -> None:
    global _active_timezone
    _active_timezone = zone
