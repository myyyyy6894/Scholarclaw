/**
 * 第 4 层：公共基础设施与领域事件契约层（{@code 02} §4.1）。
 *
 * <p><b>依赖规则（由 {@code ModuleDependencyTest} 断言）：</b>本包及其子包不得依赖
 * {@code com.scholarclaw.modules..} 中的任何类型，即公共层永远不知道业务模块的存在。
 *
 * <p><b>边界（{@code 05} §3.2）：</b>只承载通用能力（统一响应、异常映射、追踪、脱敏、枚举词汇表、
 * 加密/校验/存储/外部客户端等基础设施），不得堆放具体业务流程，也不得成为跨领域写库的捷径。
 *
 * <p>子包分工：
 * <ul>
 *   <li>{@code client}：下沉的跨进程通信客户端（Python AI 引擎、OneBot）；</li>
 *   <li>{@code config}：Jackson、时区、后续的 Redis/RabbitMQ/线程池/WebClient 配置；</li>
 *   <li>{@code enums}：跨模块共享的锁定枚举词汇表；</li>
 *   <li>{@code event}：进程内领域事件契约；</li>
 *   <li>{@code exception}：错误码字典、业务异常与全局异常映射；</li>
 *   <li>{@code filter}：请求追踪、内部令牌鉴权（认证过滤器属 TASK-202）；</li>
 *   <li>{@code logging}：日志脱敏；</li>
 *   <li>{@code response}：统一响应包装 {@code Result<T>}；</li>
 *   <li>{@code security}：AES-256-GCM 加解密与 Base URL 防 SSRF 校验；</li>
 *   <li>{@code storage}：共享卷文件读写与删除。</li>
 * </ul>
 */
package com.scholarclaw.common;
