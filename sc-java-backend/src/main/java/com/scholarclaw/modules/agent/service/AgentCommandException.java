package com.scholarclaw.modules.agent.service;

/** 斜杠指令参数非法时抛出；编排层回复明确指引，不执行任何写操作。 */
public class AgentCommandException extends RuntimeException {

    public AgentCommandException(String message) {
        super(message);
    }
}
