# ScholarClaw（智学爪）

面向计算机/工科学生的 AI 学习助手：上传课件 → 自动解析入库 → 基于课程知识库的溯源问答 → 从聊天里提取 DDL 并做提醒。

单机 Docker Compose 部署，Java（业务与状态）+ Python（AI 计算）+ Vue 3 工作台三端一体。

---

## 功能

**Web 工作台**

- **课程空间**：多课程隔离，每门课独立的向量模型绑定与索引版本
- **知识库**：PDF / MD / TXT 上传（≤50 MB），异步解析、父块切分、向量入库，失败可重试、可重建
- **AI 学习**：SSE 流式问答，回答带引用卡片，可追溯到原始课件片段；低相关度直接拒答而不是编造
- **DDL 任务**：从自然语言里提取截止时间（支持"下周三"、"月底"这类相对时间），看板 / 月历 / 待确认收件箱 / 变更草案三向决策
- **站内通知**：DDL 的 T-24h / T-6h / 逾期提醒，支持免打扰时段
- **个人设置**：BYOK 模型配置（Key 服务端加密存储，前端只显示掩码）、主题切换（浅色/深色/跟随系统）

**QQ 机器人（可选）**

通过 OneBot 11 协议端（如 NapCat）接入，私聊发消息即可提取 DDL、接收主动提醒。不启用时 Web 功能完整可用。

### 尚未实现

课程表、全局搜索、公开的系统状态接口。界面上对应位置会明确标注「暂未开发」，不会用假数据填充。

---

## 架构

| 组件 | 技术 | 职责边界 |
|---|---|---|
| `sc-java-backend` | Java 21 + Spring Boot 3.5 + MyBatis-Plus | 唯一业务权威源：认证、BYOK 加解密、课程/文档/任务状态机、定时调度、MQ |
| `sc-python-ai` | Python 3.11 + FastAPI | 无状态 AI 计算：意图路由、DDL 提取、PDF 解析切分、Embedding、RAG 与引用校验；**不连 MySQL/Redis，不持主密钥** |
| `sc-web-frontend` | Vue 3 + TypeScript + Vite + Pinia + Element Plus | PC 工作台；产物由 Nginx 托管 |
| 基础设施 | MySQL 8.0 / Redis 7.2 / RabbitMQ 3.13 / ChromaDB 0.5 | MySQL 是唯一事实源；Redis 只存带 TTL 的运行时状态；Chroma 是可重建的派生索引 |

对外只暴露一个端口（Nginx 的 80）；数据库、中间件、Python 全部只在内部网络，不映射宿主机端口。

---

## 快速开始

### 前置要求

| 项 | 要求 |
|---|---|
| Docker Engine | ≥ 26（用到 `volume.subpath`） |
| Docker Compose | ≥ 2.26（`docker compose` v2 插件） |
| JDK / Maven | 21 / 3.9+（Java 镜像只 `COPY` 预构建 jar，需先在宿主机打包） |
| 内存 | ≥ 4 GiB（空闲实测约 0.6 GiB，各容器上限合计约 6 GiB） |
| 磁盘 | ≥ 10 GiB（镜像约 2.4 GB + 数据卷 + 课件） |

### 部署

```bash
git clone <repo> && cd ScholarClaw

# 1. 环境变量（.env 已 gitignore，绝不要提交）
cp .env.example .env && chmod 600 .env
#    必须替换这 7 项，缺一个 compose 会直接拒绝启动：
#      SC_MASTER_KEY        32 字节 Base64  →  openssl rand -base64 32
#      SC_JWT_SECRET        至少 32 字节    →  openssl rand -base64 48
#      SC_DB_PASSWORD / SC_DB_ROOT_PASSWORD
#      SC_REDIS_PASSWORD / SC_RABBITMQ_PASSWORD / SC_INTERNAL_SERVICE_TOKEN

# 2. 校验编排
docker compose config

# 3. 构建 Java 可执行 jar（必须在宿主机执行）
mvn -f sc-java-backend/pom.xml -B -DskipTests package

# 4. 构建并启动
docker compose build
docker compose up -d

# 5. 等 7 个容器全部 healthy
docker compose ps
```

打开 `http://<服务器IP>:${SC_WEB_HTTP_PORT}` 即可访问，注册后先在**个人设置**里填一个对话模型 API Key 就能用。

> `SC_MASTER_KEY` 是用户 API Key 的加密主密钥。**丢失后所有已保存的用户密钥永久无法解密**，务必离线备份。

### 常用配置

| 变量 | 作用 |
|---|---|
| `SC_WEB_HTTP_PORT` | 对外端口，默认 80 |
| `SC_PLATFORM_EMBEDDING_*` | 平台自带的向量模型（见下），留空则要求用户自己配 |
| `SC_OPERATOR_QQ` | 部署者联系 QQ，显示在「帮助与反馈」与设置页；留空则隐藏 |
| `SC_JWT_TTL_SECONDS` | 登录有效期，默认 7 天 |
| `SC_NAPCAT_*` | 可选 QQ 机器人配置，见 `DEPLOYMENT.md` |

**平台自带向量模型**：如果你自己部署了 Embedding 服务，想让用户只填对话模型 Key，就配齐这五项：

```dotenv
SC_PLATFORM_EMBEDDING_PROVIDER=OPENAI_COMPATIBLE   # 或 ZHIPU / OLLAMA
SC_PLATFORM_EMBEDDING_BASE_URL=https://your-embed.example.com/v1
SC_PLATFORM_EMBEDDING_MODEL=bge-m3
SC_PLATFORM_EMBEDDING_DIM=1024
SC_PLATFORM_EMBEDDING_API_KEY=sk-xxx
```

启用后，新注册账号自动带上这套 Embedding；已有账号在**下次登录时补齐**（只补没有有效配置的账号，
用户自己配好的不会被覆盖）。设置页会标出「平台默认」，用户也可以自行覆盖。

不想自己准备向量服务的话，仓库内置了一个可选的 Ollama 容器（2C4G 机器建议小模型）：

```bash
docker compose --profile embedding up -d sc-ollama
docker compose exec sc-ollama ollama pull qwen3-embedding:0.6b
```

完整配置见 [`DEPLOYMENT.md` §5.1](DEPLOYMENT.md#51-平台自带向量模型可选推荐)。

地址是内网/本地时（如 `http://127.0.0.1:11434`），还需放行白名单，否则建课时的安全校验会拒绝：

```dotenv
SC_ALLOW_LOCAL_MODEL_BASE_URL=true
SC_LOCAL_MODEL_ALLOWED_ORIGINS=http://127.0.0.1:11434
```

---

## 上线前请自行补齐

**已内置**：Nginx 按客户端 IP 限流——登录/注册 30 次/分钟、全量 API 20 次/秒、单 IP 并发 50，
超限返回 `429 RATE_LIMITED`。数值在 `sc-web-frontend/nginx.conf` 顶部，按需调整。

这些不在仓库默认配置里，公网部署前需要自己处理：

- **HTTPS**：仓库只提供 80 端口。推荐宿主机 Nginx + Let's Encrypt（免费），完整步骤见
  [`DEPLOYMENT.md` §9](DEPLOYMENT.md#9-配置-https宿主机-nginx--lets-encrypt)。
  配好后把 `SC_WEB_HTTP_BIND` 设为 `127.0.0.1`，公网就无法绕过证书直连 HTTP。
- **备份**：`bash scripts/backup/backup.sh` 一键备份数据库、课件共享卷与 `.env`（含主密钥），
  支持保留天数与定时任务；恢复步骤见 [`DEPLOYMENT.md` §10](DEPLOYMENT.md#10-备份与恢复)。
- **日志轮转**：Compose 未配置 `logging` 上限，长期运行请自行限制。

更多部署与排障细节见 [`DEPLOYMENT.md`](DEPLOYMENT.md)。

---

## 安全设计要点

- 用户的模型 API Key 用 AES-GCM 加密后落库，只有 Java 服务持有主密钥；接口只返回掩码
- Python 服务不接触数据库、主密钥与用户凭据，共享卷只读挂载
- 自定义模型 Base URL 走 SSRF 白名单校验（默认拒绝内网/回环地址）
- `/internal/**` 内部接口由 Nginx 显式阻断，外部不可达
- BIGINT 主键全程按字符串处理，避免 JS 精度丢失

---

## 已知边界

- RAG 质量数字来自合成语料与本地 mock，**未用真实供应商做过质量标定**，不代表上线后的实际效果
- IM 投递语义为「正常路径至少一次 + 本地去重」，不是严格恰好一次
- 依赖库存在若干无修复版本的已知漏洞（ChromaDB / 部分 Java 依赖），已做网络隔离与收敛，公网部署请自行评估

---

## 许可证

[Apache License 2.0](LICENSE)
