package com.scholarclaw.modules.agent.dto;

/**
 * 确定性工具动作（`06` TASK-706：意图必须落到具体工具，而不是停留在意图标签）。
 *
 * <p>`*_GUIDE` 表示该意图在 IM 文本通道暂不可执行（例如 RAG 需要课程 SSE 流），
 * 编排层只返回明确指引，**不伪造执行结果**。
 */
public enum AgentToolAction {
    DDL_EXTRACT,
    TASK_LIST,
    TASK_QUERY,
    TASK_DONE,
    TASK_CONFIRM,
    TASK_UPDATE,
    TASK_CANCEL,
    KB_INGEST_GUIDE,
    KB_QA_GUIDE,
    HELP,
    CLARIFY,
    IGNORE
}
