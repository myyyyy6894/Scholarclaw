"""`/internal/ai/v1/**` 路由聚合（`04` §3.2.2 / §4.9）。

本服务只暴露内部接口，全部要求 `X-Internal-Token`（`/health` 按 `API-CONF-03` 允许环回免令牌）。
后续任务在各自 `endpoints_*.py` 中追加路由并**必须**加上 `require_internal_token` 依赖：

* `endpoints_probe.py`（TASK-302，已实现）：`POST /model/probe`
* `endpoints_agent.py`（TASK-706）：`POST /agent/route-and-extract`
* `endpoints_rag.py`（TASK-504/604/605）：`DELETE /rag/vectors`、`POST /rag/qa-stream`
"""

from __future__ import annotations

from fastapi import APIRouter

from app.api.internal_v1 import endpoints_health, endpoints_probe, endpoints_rag

INTERNAL_API_PREFIX = "/internal/ai/v1"

internal_v1_router = APIRouter()
internal_v1_router.include_router(endpoints_health.router)
internal_v1_router.include_router(endpoints_probe.router)
internal_v1_router.include_router(endpoints_rag.router)

__all__ = ["INTERNAL_API_PREFIX", "internal_v1_router"]
