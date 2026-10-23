"""`API-INT-AI-003` RAG 流式问答请求（`04` §4.9.3）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator

from app.schemas.probe import ModelEndpointPayload


class EmbeddingConfigPayload(ModelEndpointPayload):
    dim: int = Field(ge=1)


class ChatTurnPayload(BaseModel):
    role: Literal["USER", "ASSISTANT", "SYSTEM"]
    content: str = Field(min_length=1, max_length=4000)


class RagQaStreamRequest(BaseModel):
    user_id: str = Field(pattern=r"^\d{1,19}$")
    course_id: str = Field(pattern=r"^\d{1,19}$")
    course_name: str = Field(min_length=1, max_length=255)
    course_index_version: int = Field(ge=1)
    valid_doc_ids: list[str] = Field(default_factory=list)
    query: str = Field(min_length=1, max_length=2000)
    chat_history_window: list[ChatTurnPayload] = Field(default_factory=list)
    chat_config: ModelEndpointPayload
    embedding_config: EmbeddingConfigPayload

    @field_validator("query")
    @classmethod
    def _query_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("query 不能为空白字符")
        return value.strip()

    @field_validator("valid_doc_ids")
    @classmethod
    def _doc_ids_must_be_decimal(cls, values: list[str]) -> list[str]:
        for value in values:
            if not value.isdigit() or not 1 <= len(value) <= 19 or int(value) <= 0:
                raise ValueError("valid_doc_ids 必须全部为十进制正整数")
        return list(dict.fromkeys(values))
