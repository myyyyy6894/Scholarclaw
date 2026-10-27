"""显式斜杠指令的确定性意图映射（`01` FR-IM-002 第一级：零 LLM 消耗）。

只做**文本层**解析：识别受支持的指令名与指令参数，不执行任何业务操作。
真正的任务查询 / 完成 / 确认仍由 Java 业务服务按用户身份与权限执行。

指令集严格限定为 PRD 列出的 6 个（`/ddl`、`/kb`、`/list`、`/done`、`/confirm`、
`/edit`）；未列出的斜杠文本不做猜测，交回 LLM 分类层处理。
"""

from __future__ import annotations

from dataclasses import dataclass

from app.schemas.agent_route import AgentIntent

#: 指令 -> MVP 意图。`/list`、`/done`、`/confirm`、`/edit` 都属于任务管理类。
SLASH_COMMAND_INTENTS: dict[str, AgentIntent] = {
    "/ddl": AgentIntent.DDL_EXTRACT,
    "/kb": AgentIntent.KB_QA,
    "/list": AgentIntent.TASK_MANAGE,
    "/done": AgentIntent.TASK_MANAGE,
    "/confirm": AgentIntent.TASK_MANAGE,
    "/edit": AgentIntent.TASK_MANAGE,
}

#: 规则命中的意图置信度：确定性规则不依赖模型，按 1.0 返回。
RULE_INTENT_CONFIDENCE = 1.0


@dataclass(frozen=True)
class SlashCommand:
    """一条被识别的斜杠指令。"""

    name: str
    argument: str

    @property
    def intent(self) -> AgentIntent:
        return SLASH_COMMAND_INTENTS[self.name]


def match_slash_command(raw_text: str) -> SlashCommand | None:
    """识别行首斜杠指令；不是受支持指令时返回 `None`。

    规则：

    * 允许前导/尾随空白；
    * 指令名大小写不敏感（`/DONE T102` 等同 `/done T102`）；
    * 指令名必须是独立 token（`/doneT102` 不算 `/done`）；
    * 其余文本原样作为指令参数保留，不在此层解释业务语义。
    """
    text = raw_text.strip()
    if not text.startswith("/"):
        return None
    parts = text.split(maxsplit=1)
    name = parts[0].lower()
    if name not in SLASH_COMMAND_INTENTS:
        return None
    argument = parts[1].strip() if len(parts) > 1 else ""
    return SlashCommand(name=name, argument=argument)
