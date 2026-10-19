"""健康检查的依赖探针（`04` §4.9.8、`02` §13.3）。

约定：

* 只暴露**依赖存活状态**（`UP` / `DOWN`），不暴露连接串、路径、内部地址或凭据
  （`04` `API-CONF-03` 明确要求 `data` 内仅含 `service`、`status`、可公开的依赖状态与跨服务观测）。
* **自身依赖**（`04` §4.9.8 列明：ChromaDB HttpClient、共享存储卷 `/raw` 只读挂载）参与
  `data.status` 判定；**跨服务观测**放在 `data.peers`，不参与判定——理由见
  `app/schemas/health.py` 中 `peers` 的说明（健康接口互调会成环）。
* 探针**不实现尚未落地的基础设施**：消息队列消费者属 TASK-502，因此没有 `rabbitmq` 探针；
  本服务当前只注册确实能验证的依赖，不做"假报 UP"。
* 探针异常一律按 `DOWN` 处理，并把原因（异常类型）写进日志（日志中允许出现路径名，响应里不允许）。
"""

from __future__ import annotations

import os
from collections.abc import Callable, Mapping

import httpx

from app.core.config import Settings
from app.core.logging import get_logger

logger = get_logger(__name__)

STATUS_UP = "UP"
STATUS_DOWN = "DOWN"

#: ChromaDB 心跳路径（`02` §13.3 的健康检查对象；与 Compose 中 chromadb 自身 healthcheck 同一路径）。
CHROMADB_HEARTBEAT_PATH = "/api/v1/heartbeat"

#: 单个探针的超时上限：容器 healthcheck 的超时是 10s，探针必须远小于它，
#: 否则依赖故障时探针会一直挂到被外部杀掉，日志里看不到"谁挂了"。
DEFAULT_PROBE_TIMEOUT_SECONDS = 3.0

DependencyProbe = Callable[[], bool]


def build_dependency_probes(settings: Settings) -> dict[str, DependencyProbe]:
    """当前可验证的自身依赖探针集合（键即响应体 `data.dependencies` 的键）。

    * `shared_storage_ro`：共享卷只读挂载是否可读（`/data/scholarclaw/storage/raw`）。
      Python 侧只读访问，绝不写入或删除源文件（`05` §4.3）。
    * `chromadb`：ChromaDB HTTP 心跳是否可用（`04` §4.9.8 列明的检查对象）。
      未注入 `SC_CHROMADB_BASE_URL` 时不注册——单机只跑本服务时不误报 DOWN；
      容器编排里该变量必然注入（见 `docker-compose.yml`）。
    """
    probes: dict[str, DependencyProbe] = {
        "shared_storage_ro": lambda: is_readable_directory(settings.storage_raw_path),
    }
    if settings.chromadb_base_url:
        heartbeat_url = f"{settings.chromadb_base_url.rstrip('/')}{CHROMADB_HEARTBEAT_PATH}"
        probes["chromadb"] = lambda: is_http_endpoint_up(heartbeat_url)
    return probes


def run_dependency_probes(probes: Mapping[str, DependencyProbe]) -> dict[str, str]:
    """逐个执行探针，返回 `依赖名 -> UP/DOWN`；探针异常按 `DOWN` 处理且不影响其他探针。"""
    results: dict[str, str] = {}
    for name, probe in probes.items():
        try:
            results[name] = STATUS_UP if probe() else STATUS_DOWN
        except Exception as exc:  # noqa: BLE001 - 探针失败不得让健康接口本身报错
            logger.warning("依赖探针执行失败 dependency=%s error=%s", name, type(exc).__name__)
            results[name] = STATUS_DOWN
    return results


def overall_status(dependencies: Mapping[str, str]) -> str:
    """聚合状态：只看自身依赖，全部 UP 才是 UP；无自身依赖时视为 UP（服务本身可用）。

    跨服务观测项（`peers`）**不传入**本函数——那不是本服务的可用性判据，
    传入会让 `sc-java-backend` 的健康反过来决定本服务，与 `02` §13.3 的启动链成环。
    """
    if not dependencies:
        return STATUS_UP
    return STATUS_UP if all(status == STATUS_UP for status in dependencies.values()) else STATUS_DOWN


def is_readable_directory(path: str) -> bool:
    """目录存在且可读可进入。"""
    return os.path.isdir(path) and os.access(path, os.R_OK | os.X_OK)


def is_http_endpoint_up(
    url: str,
    timeout: float = DEFAULT_PROBE_TIMEOUT_SECONDS,
) -> bool:
    """HTTP 探针：目标返回 200 才算 UP。

    * 显式 `follow_redirects=False`：内部探针同样不允许被重定向带偏（`NFR-SEC-05` 的取向），
      3xx 一律按 DOWN 处理，避免把"跳去别处"当成"依赖健康"。
    * 显式 `trust_env=False`：**绝不继承进程的代理环境变量**。httpx 默认读取
      `HTTP_PROXY` / `ALL_PROXY` 等变量；本机实测（TASK-105 复核）在设置
      `ALL_PROXY=socks://…` 且未安装 socks 扩展时，对 `127.0.0.1` 的探针会直接抛
      `ValueError: Unknown scheme for proxy URL` 而被判成 DOWN——容器内网调用被宿主/环境代理
      带偏会表现为"依赖明明活着却报不健康"。内部探针必须直连。
    * 任何异常（连接失败、超时、DNS、代理配置非法）都按 DOWN 处理，不向上抛。
    """
    try:
        response = httpx.get(url, timeout=timeout, follow_redirects=False, trust_env=False)
    except Exception as exc:  # noqa: BLE001 - 探针必须自己吞掉异常
        logger.debug("HTTP 探针失败 error=%s", type(exc).__name__)
        return False
    return response.status_code == 200
