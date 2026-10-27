package com.scholarclaw.modules.agent.dto;

import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import java.math.BigDecimal;

/**
 * 编排结果（供 `channel` 模块转成 IM 文本卡片）。
 *
 * <p>{@code replyText} 是唯一对外文案来源；{@code action} 明确表示实际执行的工具动作
 * （`CLARIFY` / `*_GUIDE` 表示未执行写操作），避免把“意图识别成功”伪装成“工具执行成功”。
 */
public record AgentReply(
        AgentIntent intent,
        BigDecimal intentConfidence,
        AgentToolAction action,
        String replyText,
        DdlTaskVO task,
        DdlTaskBoardVO board,
        boolean requiresConfirmation) {
}
