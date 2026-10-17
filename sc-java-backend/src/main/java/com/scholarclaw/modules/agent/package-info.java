/**
 * 模块 6：智能体编排与会话（第 2 层）。
 *
 * <p><b>职责：</b>{@code t_chat_message}（v1.1 追加 {@code t_exam_wrong_book}）的唯一读写拥有者；
 * 接收 {@code UnifiedMessageDTO}，执行斜杠指令优先路由与待确认上下文匹配；调用
 * {@code PythonAiEngineClient} 执行自然语言意图识别、DDL 提取与 RAG SSE 问答流转发；
 * 监听 {@code UserDeactivatingEvent} 清理会话记录。
 *
 * <p><b>允许依赖：</b>{@code ddl}、{@code knowledge}、{@code course}、{@code userconfig}、
 * {@code notification}、{@code common}。
 *
 * <p><b>禁止：</b>不负责底层 HTTP 客户端封装（已下沉至 {@code common.client}）；
 * 不得直接写任务表或文档表；不得自行实现绕过领域服务的持久化。
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_chat_message}、{@code t_exam_wrong_book}；
 * Redis {@code sc:u:{uid}:pending_intent}、{@code sc:u:{uid}:chat_win:*}。
 *
 * <p>落地任务：TASK-604（SSE 转发）、TASK-605（RAG 工作台）、TASK-706（Single Agent Router）。
 */
package com.scholarclaw.modules.agent;
