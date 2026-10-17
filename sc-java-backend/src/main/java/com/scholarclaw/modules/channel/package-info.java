/**
 * 模块 8：渠道入站适配（第 1 层）。
 *
 * <p><b>职责：</b>OneBot 11 入站 Webhook/WS 事件验签、白名单校验（经 {@code UserConfigService}
 * 查绑定用户）、Redis 入站幂等去重（{@code sc:idemp:msg:*}）与 {@code InboundStatus} 六态记录；
 * 调用 {@code OneBotApiClient} 拉取聊天文件或单层转发文本节点，转交
 * {@code AgentOrchestratorService} 或 {@code KnowledgeDocService}。
 *
 * <p><b>允许依赖：</b>{@code agent}、{@code knowledge}、{@code userconfig}、{@code common}。
 *
 * <p><b>禁止：</b>不包含任何业务逻辑，不直接访问任何 MySQL Mapper（{@code 02} §4.3）。
 * 未命中白名单的消息必须记录 {@code AUTH_REJECTED} 后静默丢弃，不消耗任何 LLM Token。
 *
 * <p><b>唯一写入权：</b>Redis {@code sc:idemp:msg:{platform}:{sender_id}:{msg_id}}。
 *
 * <p>落地任务：TASK-803（OneBot 11 适配器）、TASK-805（联调）。
 */
package com.scholarclaw.modules.channel;
