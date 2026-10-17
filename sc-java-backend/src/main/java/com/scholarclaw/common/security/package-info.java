/**
 * 安全基础设施层：密钥加密与出站地址校验（{@code 02} §10.2 / §10.3）。
 *
 * <p><b>计划承载的能力与落地任务：</b>
 * <ul>
 *   <li>{@code AesGcmCryptoService}（TASK-203）：AES-256-GCM 加解密，密文格式
 *       {@code v1:{base64_iv}:{base64_ciphertext_with_tag}}，主密钥 {@code SC_MASTER_KEY}
 *       仅由本服务通过环境变量持有，Python 与任何其他容器都不得获得；</li>
 *   <li>{@code BaseUrlSecurityValidator}（TASK-204）：自定义 Base URL 的 7 步防 SSRF 校验——
 *       协议白名单、端口白名单、禁止跟随 HTTP 重定向（{@code follow_redirects=False}）、
 *       拦截云元数据地址 {@code 169.254.169.254} 与容器服务名/内网网段、本地 Ollama 需
 *       {@code SC_ALLOW_LOCAL_MODEL_BASE_URL} + {@code SC_LOCAL_MODEL_ALLOWED_ORIGINS} 双开关命中、
 *       解析后 IP 复核、响应脱敏；</li>
 *   <li>后续的 {@code SecurityContextUtils}（TASK-202）：当前用户 ID 的唯一合法来源是验签后的
 *       JWT {@code sub}，严禁信任请求体或外部传入的 {@code X-User-Id}（{@code 04} §2.7.1/§2.7.2）。</li>
 * </ul>
 *
 * <p><b>零泄露红线（{@code NFR-SEC-02}）：</b>明文 API Key 不得出现在普通日志、异常堆栈、
 * Redis/MQ 载荷、ChromaDB 元数据或任何客户端可见响应中；密文同样不得入日志。
 *
 * <p>本任务（TASK-101）只建立包边界，不生成空实现。
 */
package com.scholarclaw.common.security;
