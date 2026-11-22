"""Internal stateless schedule suggestions, isolated by caller identity."""

from base64 import b64decode
from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.security import require_internal_identity
from app.schemas.common import InternalResponse
from app.schemas.schedule_extract import (
    ScheduleFileAssistData, ScheduleFileAssistRequest, ScheduleFileExtractData, ScheduleFileExtractRequest,
    ScheduleImageExtractRequest, ScheduleTextExtractData, ScheduleTextExtractRequest,
)
from app.services.schedule.file_assist import assist_file
from app.services.schedule.file_extractor import ALIASES, extract_file
from app.services.schedule.image_extractor import extract_image
from app.services.schedule.text_extractor import extract_text

router = APIRouter(tags=["internal-schedule"])


@router.post("/schedule/extract-text", response_model=InternalResponse[ScheduleTextExtractData])
def extract_schedule_text(
    body: ScheduleTextExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleTextExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    return InternalResponse.ok(ScheduleTextExtractData(rows=extract_text(body.raw_text, body.week_count)))


@router.post("/schedule/extract-image", response_model=InternalResponse[ScheduleTextExtractData])
async def extract_schedule_image(
    body: ScheduleImageExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleTextExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    return InternalResponse.ok(await extract_image(get_settings(), body))


@router.post("/schedule/extract-file", response_model=InternalResponse[ScheduleFileExtractData])
def extract_schedule_file(
    body: ScheduleFileExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleFileExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    if body.mapping and (set(body.mapping) - set(ALIASES) or len(set(body.mapping.values())) != len(body.mapping)):
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "课程列映射无效。")
    try:
        result = extract_file(body.source_format, b64decode(body.content, validate=True),
                              body.table_index, body.header_row, body.mapping)
    except ValueError as exc:
        message = str(exc)
        # Decode and conversion errors can include original data.
        if not message or not message[0].isascii():
            raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, message or "文件无法读取。") from None
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "文件损坏、编码不受支持或内容与扩展名不一致。") from None
    return InternalResponse.ok(ScheduleFileExtractData.model_validate(result))


@router.post("/schedule/assist-file", response_model=InternalResponse[ScheduleFileAssistData])
async def assist_schedule_file(
    body: ScheduleFileAssistRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleFileAssistData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    return InternalResponse.ok(await assist_file(get_settings(), body))
