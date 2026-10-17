# ScholarClaw（智学爪）

面向计算机/工科学生的双端（IM + PC Web）AI 学习助手。单机 Docker Compose 部署，Java + Python 异构架构。

**当前进度：P0 已完成，P1（工程骨架与容器底座）进行中。** 仓库尚无任何 git 提交，也无完整可运行的服务。

---

## 1. 架构与职责边界

| 组成 | 技术基线 | 职责 | 硬约束 |
|---|---|---|---|
| `sc-java-backend` | Java 21 + Spring Boot 3.3.x + Spring Security 6 + MyBatis-Plus 3.5.x | 业务与状态权威中心（SoT）：认证鉴权、BYOK 加解密、课程/文档/任务状态机、定时调度、MQ 生产与 DLQ | 单进程**模块化单体**（包级 Strict DAG）；唯一连接 MySQL；唯一持有 `SC_MASTER_KEY` |
| `sc-python-ai` | Python 3.11 + FastAPI 0.115.x + Pydantic v2 + httpx + pypdf | 无状态 AI 计算引擎：意图路由、DDL 结构化提取、PDF/MD/TXT 解析切分、Embedding、ChromaDB 读写、RAG 与引用校验 | **不得**连接 MySQL/Redis；**不得**持有主密钥或数据库凭据；共享卷只读 |
| Web 工作台 | Vue 3 + TypeScript + Vite + Pinia + Element Plus | 登录与 BYOK 配置、课程与文档管理、SSE 溯源问答、DDL 看板、站内通知 | 不维护多套前端；`BIGINT` ID 全程按十进制字符串处理 |
| 基础设施 | MySQL 8.0 / Redis 7.2 / RabbitMQ 3.13 / ChromaDB 0.5.x | MySQL 唯一权威源；Redis 只存带 TTL 的运行时状态（不做 MySQL 二级缓存）；RabbitMQ **仅**用于文档解析/重试/重建；ChromaDB 仅作可重建的派生向量索引 | 见 `docs/02` 第 8、13 章 |

**两条不可违反的规则：**

1. **Single-Writer**：每张业务表只由一个 Java 模块拥有写入权；跨模块只允许注入对方公开的 `*Service`，严禁跨模块注入 `*Mapper`。
2. **Strict DAG**：模块依赖是有向无环的，由 `sc-java-backend` 中的 ArchUnit 测试自动断言，违反即构建失败。

---

## 2. 仓库结构

```
ScholarClaw/
├── docs/                     # 强约束设计文档（01 需求 / 02 架构 / 03 数据库 / 04 API / 05 规范 / 06 任务计划）
│                             # 注意：按项目决定未纳入版本管理（见 .gitignore），但文件实体在工作区
├── TASK_TRACKER.md           # 需求—任务—接口—数据—测试追踪表；任务状态的唯一事实来源
├── ENVIRONMENT.md            # 仓库现状核验、ENV-01~ENV-12 差异风险、可复现命令清单
├── AGENT_HANDOFF.md          # 跨会话交接记忆入口
├── .agent-handoff/           # 交接状态文件（snapshot / risks / backlog / validation / decisions / workspace …）
├── sc-java-backend/          # Java 业务与状态权威中心（已建立骨架）
├── sc-python-ai/             # Python 无状态 AI 计算引擎（已建立服务骨架）
└── (待建) Web 前端工程、docker-compose.yml
```

**开发方式：文档驱动。** `docs/01`~`docs/06` 是唯一依据；如发现文档之间不一致，先说明原因与方案并等待确认，不得擅自修改文档。

---

## 3. 构建与验证命令

### 3.1 Java（已实测）

```bash
# 编译
mvn -f sc-java-backend/pom.xml -B -DskipTests clean compile

# 全量单元测试（含架构契约断言）
mvn -f sc-java-backend/pom.xml -B test
```

前置：JDK 21（本机 21.0.12.1）、Maven 3.8.7。依赖经 `~/.m2/settings.xml` 的阿里云镜像下载。

**尚未验证：** 启动应用（`mvn spring-boot:run`）。当前无 MySQL/Redis 连接配置与环境变量注入，启动验证属 TASK-104 / TASK-105。

### 3.2 Python（已实测）

```bash
# 建虚拟环境并安装（本机 Python 3.13；容器内 pin 3.11，见 ENVIRONMENT.md ENV-02）
python3 -m venv sc-python-ai/.venv
sc-python-ai/.venv/bin/python -m pip install -e 'sc-python-ai[dev]'

# 运行测试
sc-python-ai/.venv/bin/python -m pytest          # 需在 sc-python-ai/ 目录下执行

# 本地启动（仅绑定环回，不暴露到局域网；容器内由 Compose 决定绑定与端口）
cd sc-python-ai
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 18000
```

**尚未验证：** Web 前端工程与 `docker-compose.yml` 不存在（TASK-103 / TASK-104）；`sc-python-ai` 的
ChromaDB 客户端（TASK-504）、RabbitMQ 消费者（TASK-502）与模型探测接口（TASK-302）均未落地。

---

## 4. 配置与环境变量

真实密钥只通过环境变量注入，**绝不写入仓库**。当前已使用的配置项：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `sc.timezone` | `SC_TIMEZONE` | `Asia/Shanghai` | 用户时区与时间序列化基准。**必须显式固定**，严禁依赖宿主机/JVM 默认时区（见 `ENVIRONMENT.md` ENV-01） |
| `sc.internal.token` | `SC_INTERNAL_SERVICE_TOKEN` | 空 | Java ↔ Python 内部调用鉴权令牌（请求头 `X-Internal-Token`）。**留空时 `InternalTokenAuthFilter` 对所有 `/internal/**` 一律拒绝（失败关闭）**，不会"跳过校验" |
| `sc.storage`（Python） | `SC_STORAGE_RAW_PATH` | `/data/scholarclaw/storage/raw` | 共享卷只读挂载点；`sc-python-ai` 只读访问，绝不写入或删除源文件 |
| `sc.log-level`（Python） | `SC_LOG_LEVEL` | `INFO` | 本服务日志级别 |
| 模型出站超时（Python） | `SC_MODEL_CONNECT_TIMEOUT_SECONDS` / `SC_MODEL_READ_TIMEOUT_SECONDS` | `5` / `30` | 受控模型网关的出站超时 |
| 本地模型白名单（Python） | `SC_ALLOW_LOCAL_MODEL_BASE_URL` / `SC_LOCAL_MODEL_ALLOWED_ORIGINS` | `false` / 空 | 默认关闭；显式开启后才允许校验本地 Ollama 白名单（消费方为 TASK-301 的 SSRF 校验） |

其余环境变量（`SC_MASTER_KEY`、`SC_JWT_SECRET`、`SC_DB_PASSWORD` 等）随对应任务引入，注入边界见 `docs/02` §13.2。
**`sc-python-ai` 只从上述白名单变量读取配置**，其 `app/core/config.py` 对白名单外的名字直接报错，
并有源码级测试断言它不引用主密钥或数据库凭据。

---

## 5. 任务状态与进度

- 任务清单、依赖关系与验收条件：`docs/06`。
- 当前状态、需求追踪、未决事项台账：`TASK_TRACKER.md`。
- 环境差异与风险：`ENVIRONMENT.md`。

阶段路线：P0 仓库核验 → P1 工程骨架与容器底座 → P2 认证/BYOK → P3 Python 网关与模型探测 → P4 课程与知识库 → P5 异步解析与向量索引 → P6 RAG 与 SSE → P7 DDL 闭环 → P8 提醒/通知/OneBot → P9 清理补偿与 MVP 验收。
