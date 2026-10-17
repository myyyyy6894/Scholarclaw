"""健康检查的依赖探针（`04` §4.9.8、`02` §13.3）。

约定：

* 只暴露**依赖存活状态**（`UP` / `DOWN`），不暴露连接串、路径、内部地址或凭据
  （`04` `API-CONF-03` 明确要求 `data` 内仅含 `service`、`status` 与可公开的依赖状态）。
* 探针**不实现尚未落地的基础设施**：ChromaDB 客户端属 TASK-504，因此 `chromadb` 探针
  到那时才注册；本服务当前的 `dependencies` 只包含确实能验证的依赖，不做"假报 UP"。
* 探针异常一律按 `DOWN` 处理，并把原因写进日志（日志中允许出现路径，响应里不允许）。
"""

from __future__ import annotations

import os
from collections.abc import Callable, Mapping

from app.core.config import Settings
from app.core.logging import get_logger

logger = get_logger(__name__)

STATUS_UP = "UP"
STATUS_DOWN = "DOWN"

DependencyProbe = Callable[[], bool]


def build_dependency_probes(settings: Settings) -> dict[str, DependencyProbe]:
    """当前可验证的依赖探针集合。

    * `shared_storage_ro`：共享卷只读挂载是否可读（`/data/scholarclaw/storage/raw`）。
      Python 侧只读访问，绝不写入或删除源文件（`05` §4.3）。
    """
    return {
        "shared_storage_ro": lambda: is_readable_directory(settings.storage_raw_path),
    }


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
    """聚合状态：全部探针 UP 才是 UP；无探针时视为 UP（服务本身可用）。"""
    if not dependencies:
        return STATUS_UP
    return STATUS_UP if all(status == STATUS_UP for status in dependencies.values()) else STATUS_DOWN


def is_readable_directory(path: str) -> bool:
    """目录存在且可读可进入。"""
    return os.path.isdir(path) and os.access(path, os.R_OK | os.X_OK)
