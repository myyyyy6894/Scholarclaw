package com.scholarclaw.common.im;

/**
 * 「用户自带的官方机器人」投递能力。
 *
 * <p>接口放在 {@code common} 而不是直接依赖 {@code channel} 模块：
 * {@code notification} 只需要"能不能发、发出去没有"，实现细节（WebSocket 连接、
 * token 刷新、openid 绑定）属于 {@code channel}。这样模块依赖方向保持不变。
 */
public interface OfficialBotSender {

    /** 用户是否配置了可用的官方机器人（凭据齐全）。 */
    boolean isConfigured(Long userId);

    /**
     * 主动推送到用户。
     *
     * @return 是否真正投递；用户尚未与机器人建立会话（无 openid）时返回 false
     */
    boolean sendProactive(Long userId, String content);
}
