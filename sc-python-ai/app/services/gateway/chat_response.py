"""OpenAI 兼容聊天补全响应的公共解析辅助。

供意图分类与 DDL 文本提取共用；课表图片解析依赖 ``finish_reason`` 与截断行恢复，
保持独立实现而不在此混入截断容错分支。
"""

from __future__ import annotations

from typing import Any


class ChatResponseError(ValueError):
    """聊天补全响应缺少有效的 ``choices[0].message.content``。"""

    def __init__(self, code: str, message: str, *, raw_content: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.raw_content = raw_content


def extract_chat_content(response: Any) -> str:
    """校验并取出 ``choices[0].message.content`` 非空文本。"""
    if not isinstance(response, dict):
        raise ChatResponseError("missing_choices", "模型响应缺少 choices")
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ChatResponseError("missing_choices", "模型响应缺少 choices")
    first = choices[0]
    if not isinstance(first, dict):
        raise ChatResponseError("invalid_choice", "模型响应 choices 结构不可识别")
    message = first.get("message")
    if not isinstance(message, dict):
        raise ChatResponseError("missing_message", "模型响应缺少 message")
    content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        raise ChatResponseError(
            "empty_content",
            "模型响应缺少文本内容",
            raw_content=content,
        )
    return content


def strip_code_fence(text: str) -> str:
    """剥离包裹 JSON 的首尾 Markdown 代码围栏（如 ````json ... ````）。"""
    stripped = text.strip()
    if not stripped.startswith("```"):
        return stripped
    lines = stripped.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()
