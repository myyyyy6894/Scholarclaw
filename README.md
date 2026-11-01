# ScholarClaw（智学爪）

把课件变成能问答的知识库，顺带帮你盯住作业截止时间。

**在线体验：<http://121.41.221.243/>** —— 注册即可用，不需要邮箱。
（部署在一台 2 核 4G 的小机器上，向量由 CPU 计算，解析课件偏慢属正常现象。）

---

## 功能

- **课程空间**：每门课独立的知识库与向量索引，互不干扰
- **知识库**：上传 PDF / MD / TXT（≤50 MB），自动解析、切块、入库；失败可重试、可重建
- **AI 学习**：基于课件问答，回答带引用可回溯原文；没有依据时直接拒答，不编造
- **DDL 任务**：从自然语言里提取截止时间（支持"下周三""月底"），看板与日历视图
- **站内通知**：截止前 24 小时 / 6 小时 / 逾期提醒，支持免打扰时段
- **模型自带（BYOK）**：用户填自己的对话模型 Key，AES-GCM 加密存储，接口只回显掩码

向量模型由部署方统一提供，普通用户不需要自己申请 Embedding Key；有能力的用户也可以自己覆盖。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Element Plus |
| 业务后端 | Java 21 + Spring Boot 3.5 + MyBatis-Plus + Spring Security |
| AI 服务 | Python 3.11 + FastAPI + pypdf + ChromaDB |
| 基础设施 | MySQL 8.0 / Redis 7.2 / RabbitMQ 3.13 / ChromaDB 0.5 / Nginx |
| 部署 | Docker Compose 单机 |

对外只暴露一个端口（Nginx 的 80），数据库、中间件、Python 全部在内部网络。

---

## 快速开始

环境要求：Docker Engine ≥ 26、Compose ≥ 2.26、JDK 21 + Maven 3.9，内存 ≥ 4 GiB。

```bash
git clone <仓库地址> && cd ScholarClaw

# 1. 配置环境变量（.env 已 gitignore，不要提交）
cp .env.example .env && chmod 600 .env
#    必填 7 项，缺一个 compose 会拒绝启动：
#      SC_MASTER_KEY        openssl rand -base64 32
#      SC_JWT_SECRET        openssl rand -base64 48
#      SC_DB_PASSWORD / SC_DB_ROOT_PASSWORD / SC_REDIS_PASSWORD
#      SC_RABBITMQ_PASSWORD / SC_INTERNAL_SERVICE_TOKEN

# 2. 打包 Java（镜像只 COPY 预构建 jar）
mvn -f sc-java-backend/pom.xml -B -DskipTests package

# 3. 构建并启动
docker compose build && docker compose up -d
docker compose ps     # 等 7 个容器 healthy
```

浏览器打开 `http://<服务器IP>/`，注册后在「设置」里填一个对话模型 Key 就能用。

想给所有用户免配置向量模型，加一个内置 Ollama（可选 profile）：

```bash
docker compose --profile embedding up -d sc-ollama
docker compose exec sc-ollama ollama pull nomic-embed-text
```

再按 [`DEPLOYMENT.md` §5.1](DEPLOYMENT.md) 在 `.env` 里配好五项即可。

> **不想自己折腾？** 把仓库地址丢给 Claude Code / Cursor / Codex 之类的编码助手，让它按
> `DEPLOYMENT.md` 帮你部署——文档里写清了每一步和环境变量。

**上线前请自行补齐**：HTTPS（仓库只提供 80）、备份（`scripts/backup/backup.sh` + 异地存储）、
服务器安全组只放行 22/80/443。

更多运维细节见 [`DEPLOYMENT.md`](DEPLOYMENT.md)。

---

## 已知边界

- 课件解析走 CPU 推理，几十页的文档需要几分钟，多份会排队；不支持扫描件 OCR
- RAG 质量数字来自合成语料，未用真实供应商做过标定
- 课程表、全局搜索、公开系统状态接口尚未实现，界面会标注「暂未开发」

---

## 许可证

[Apache License 2.0](LICENSE)
