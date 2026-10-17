/**
 * 下沉的跨进程通信客户端层（{@code 02} §4.1 解耦规则 1）。
 *
 * <p><b>为什么下沉到公共层：</b>{@code PythonAiEngineClient}（调 Python {@code /internal/ai/v1/**}）
 * 与 {@code OneBotApiClient}（调 OneBot 发送私聊消息、拉取聊天文件、解析合并转发）原本分属
 * {@code agent} 与 {@code channel}，会导致这两个模块互相反向依赖。将它们下沉到第 4 层后，
 * 任何业务模块都能单向调用，Strict DAG 得以成立。
 *
 * <p><b>实现落地任务：</b>TASK-301 / TASK-303（{@code PythonAiEngineClient}）、TASK-803
 * （{@code OneBotApiClient}）。本任务（TASK-101）只建立包边界与依赖约定，不生成空实现或占位类。
 *
 * <p><b>约束：</b>
 * <ul>
 *   <li>禁止任何业务模块重复封装第二套互相竞争的 HTTP 客户端（{@code 05} §3.3）；</li>
 *   <li>明文 API Key 只在当次请求内透传，严禁写入日志、异常消息、Redis、MQ 或 ChromaDB 元数据
 *       （{@code NFR-SEC-02}）；出站地址必须先经过 Base URL 安全校验。</li>
 * </ul>
 */
package com.scholarclaw.common.client;
