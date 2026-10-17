"""ScholarClaw 无状态 AI 计算引擎（`sc-python-ai`）。

服务定位与红线（`02` §5.1、`05` §3.1）：

* **无状态**：不落任何业务状态，不直连 MySQL / Redis；
* **不持有凭据**：不读取 `SC_MASTER_KEY`、数据库凭据或 JWT 密钥，需要业务状态或模型配置时
  只能通过受 `X-Internal-Token` 保护的 Java 内部接口按需获取；
* **受控出站**：所有对外模型请求必须经过受控网关（SSRF 校验 + 禁止跟随重定向）；
* **单一入口**：只暴露 `/internal/ai/v1/**`，全部经 `X-Internal-Token` 校验
  （`/internal/ai/v1/health` 按 `04` `API-CONF-03` 默认方案允许容器本机环回免 Token）。

包结构（`02` §5.3）：`core`（配置/安全/日志/异常）、`api`（内部接口）、`schemas`（Pydantic 契约）、
`services`（AI 编排与受控网关）、`infra`（ChromaDB / Java 内部接口 / MQ / 只读存储客户端）。
"""
