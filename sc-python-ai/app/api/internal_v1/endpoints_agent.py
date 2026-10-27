"""内部意图路由与 DDL 结构化提取 `POST /internal/ai/v1/agent/route-and-extract`。

对应 `04` §4.9.2 `API-INT-AI-002`：

* `FORCE_DDL_EXTRACT`：跳过路由，直接执行保守 DDL 结构化提取（TASK-701/702）；
* `AUTO_ROUTE`：显式斜杠指令优先，其次调用**一次** LLM 结构化分类（TASK-706）；
  仅当意图为 `DDL_EXTRACT` 且置信度 ≥ 0.65 时才附带提取结果，低置信度返回
  `ddl_payload = null`，由 Java 侧暂存原文并追问（`E-IM-04`）；
* 本接口不写 MySQL、不访问 Redis/ChromaDB、不发布 RabbitMQ 消息；
* 模型配置按当次请求的 `chat_config` 透传，不读取任何全局默认 Key。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.security import require_internal_identity
from app.schemas.agent_route import AgentIntent, RouteAndExtractData
from app.schemas.common import InternalResponse
from app.schemas.ddl_extract import DdlExtractRequest, ExtractionMode
from app.services.agent.router import SingleAgentRouter
from app.services.ddl.extractor import (
    DdlExtractionRequest,
    DdlExtractor,
    DdlExtractionResult,
)
from app.services.gateway.model_endpoint import ModelEndpoint

router = APIRouter(tags=["internal-agent"])


def get_ddl_extractor() -> DdlExtractor:
    """FastAPI 依赖：测试可覆盖此函数注入 FakeGateway。"""
    return DdlExtractor(get_settings())


def get_agent_router(
    extractor: Annotated[DdlExtractor, Depends(get_ddl_extractor)],
) -> SingleAgentRouter:
    """FastAPI 依赖：复用同一请求内的提取器；测试可覆盖此函数注入 Fake 路由器。"""
    return SingleAgentRouter(get_settings(), extractor)


@router.post(
    "/agent/route-and-extract",
    response_model=InternalResponse[RouteAndExtractData],
    summary="内部单一意图路由与 DDL 结构化提取",
    description=(
        "FORCE_DDL_EXTRACT 直接提取；AUTO_ROUTE 先走确定性斜杠规则，再调用一次 LLM "
        "意图分类，低置信度不执行 DDL 提取且不返回伪造结果。"
    ),
)
async def route_and_extract(
    body: DdlExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
    extractor: Annotated[DdlExtractor, Depends(get_ddl_extractor)],
    agent_router: Annotated[SingleAgentRouter, Depends(get_agent_router)],
) -> InternalResponse[RouteAndExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR,
            "请求体 user_id 与 X-User-Id 不一致，已拒绝 DDL 提取。",
        )

    if body.mode is ExtractionMode.FORCE_DDL_EXTRACT:
        result: DdlExtractionResult = await extractor.extract(
            DdlExtractionRequest(
                user_id=body.user_id,
                raw_text=body.raw_text,
                reference_time=body.reference_time,
                user_timezone=body.user_timezone,
                candidate_courses=tuple(body.candidate_courses),
                chat_endpoint=ModelEndpoint(
                    provider=body.chat_config.provider,
                    base_url=body.chat_config.base_url,
                    api_key=body.chat_config.api_key,
                    model_name=body.chat_config.model_name,
                ),
            )
        )
        return InternalResponse.ok(
            RouteAndExtractData(
                intent=AgentIntent.DDL_EXTRACT,
                intent_confidence=result.intent_confidence,
                ddl_payload=result.payload,
            ),
            message="Extraction completed",
        )

    decision = await agent_router.route(body)
    return InternalResponse.ok(
        RouteAndExtractData(
            intent=decision.intent,
            intent_confidence=decision.intent_confidence,
            ddl_payload=decision.ddl_payload,
        ),
        message="Routing completed",
    )
