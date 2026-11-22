package com.scholarclaw.modules.agent.dto;

/**
 * 编排层入站消息（`02` §5.2 `AgentOrchestratorService`）。
 *
 * <p>由 `channel` 模块（TASK-803）在完成验签、白名单与接入层去重后构造；
 * 本对象只携带业务路由所需字段，不包含原始平台报文。
 */
public record AgentInboundMessage(
        Long userId,
        String sourcePlatform,
        String sourceMsgId,
        String rawText,
        String referenceTime,
        String courseId,
        boolean forceCreate,
        QqSession qqSession) {
    public AgentInboundMessage(Long userId, String sourcePlatform, String sourceMsgId, String rawText,
                               String referenceTime, String courseId, boolean forceCreate) {
        this(userId, sourcePlatform, sourceMsgId, rawText, referenceTime, courseId, forceCreate, null);
    }

    public record QqSession(String appId, Long bindingVersion, String openid) { }
}
