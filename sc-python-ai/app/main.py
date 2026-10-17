"""FastAPI 应用入口（`02` §5.3）。

启动流程刻意保持最小：

1. 装载配置（白名单环境变量）并固定应用时区；
2. 初始化结构化日志（`[trace_id=...] [user_id=...]` + 凭据脱敏）；
3. 若检测到禁止注入本服务的敏感变量，只告警变量**名**（绝不打印值）；
4. 注册追踪中间件、统一异常映射与内部接口路由。

**尚未接入**（按任务分阶段落地，不建空壳）：RabbitMQ 消费者生命周期（TASK-502）、
ChromaDB 客户端（TASK-504）、`/model/probe`（TASK-302）、`/agent/route-and-extract`（TASK-706）、
`/rag/qa-stream` 与 `/rag/vectors`（TASK-604/605）。
"""

from __future__ import annotations

from fastapi import FastAPI

from app.api.internal_v1 import INTERNAL_API_PREFIX, internal_v1_router
from app.core.config import detect_forbidden_env_vars, get_settings
from app.core.exceptions import register_exception_handlers
from app.core.logging import configure_logging, get_logger
from app.core.tracing import TraceIdMiddleware

SERVICE_TITLE = "sc-python-ai"
SERVICE_VERSION = "1.0.0"

logger = get_logger(__name__)


def create_app() -> FastAPI:
    """构造应用实例；供 uvicorn 与测试共用。"""
    settings = get_settings()
    configure_logging(settings)

    forbidden = detect_forbidden_env_vars()
    if forbidden:
        logger.warning(
            "检测到禁止注入本服务的敏感变量（仅列出变量名，未读取其值）：%s；"
            "请检查容器注入边界是否违反 02 §13.2 的最小权限要求",
            ", ".join(forbidden),
        )

    app = FastAPI(
        title=SERVICE_TITLE,
        version=SERVICE_VERSION,
        # 本服务仅暴露内部接口且不映射宿主机端口，无需对外提供交互式文档
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.add_middleware(TraceIdMiddleware)
    register_exception_handlers(app)
    app.include_router(internal_v1_router, prefix=INTERNAL_API_PREFIX)
    return app


app = create_app()
