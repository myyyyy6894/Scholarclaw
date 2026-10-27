# ScholarClaw（智学爪）

面向计算机/工科学生的双端（IM + PC Web）AI 学习助手。单机 Docker Compose 部署，Java + Python 异构架构。

**当前进度：P0~P7 已验收 `DONE`（P7 由负责人 2026-10-28 验收，TASK-701~706 全部 `DONE`）；P8 `TODO`。** P6 覆盖单路稠密检索、父块权威回查、RAG 生成与引用校验、SSE 问答、Web 问答工作台与 Golden Dataset 链路基线；TASK-606 仍缺真实课程 PDF、人工标注与真实供应商 Key，**其替身基线不代表真实质量，风险保留**。P7 覆盖 DDL 结构化提取与四级时间、持久化状态机、三层幂等与变更草案、Web DDL 工作台与 Single Agent Router（规则优先 + 单次 LLM 分类 + Java 确定性工具执行）。**真实栈 E2E 已用本地 mock 模型执行（API 46/46、浏览器 20/20），并完成 2026-10-28 第五轮审阅修复与 P7 验收；真实供应商模型、OneBot 入站与 P8 提醒仍未执行**；任务状态以 `TASK_TRACKER.md` §1 为准。

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
├── sc-web-frontend/          # Vue 3 PC Web 工作台（已建立骨架）
├── docker-compose.yml        # 单机部署编排（7 个容器 + 双网络 + 5 个持久化卷）
├── .env.example              # 部署环境变量示例（真实值放 .env，已 gitignore）
├── DEPLOYMENT.md             # 部署手册：端口边界、卷权限、注入边界、健康检查链、问题定位
└── INTERNAL_API_VERIFICATION.md  # 内部通信与日志追踪的验证记录（TASK-105 交付物）
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

**启动验证：** 已由容器承载实测（TASK-104/105）：`sc-java-backend` 容器 `healthy`，健康接口
`GET /internal/api/v1/health` 实测返回自身依赖（MySQL/Redis/RabbitMQ）与跨服务观测 `data.peers`。
本机直接 `mvn spring-boot:run` 仍不作为验证路径（缺少 DB/Redis 注入，探针会按未注入不注册处理）。
接口与日志实测结论见 `INTERNAL_API_VERIFICATION.md`。

### 3.2 Python（已实测）

```bash
# 建虚拟环境（本机 Python 3.13；容器内 pin 3.11，见 ENVIRONMENT.md ENV-02）
python3 -m venv sc-python-ai/.venv
sc-python-ai/.venv/bin/python -m pip install -e 'sc-python-ai[dev]'

# 推荐：可复现测试入口（测试依赖来自 requirements-dev.lock.txt，不污染运行时镜像）
scripts/test_python.sh host        # 宿主 venv
scripts/test_python.sh container   # Python 3.11 测试镜像（scholarclaw/sc-python-ai-test:1.0.0）
scripts/test_python.sh all         # 两者都跑

# 等价的手动命令
cd sc-python-ai && .venv/bin/python -m pytest

# 本地启动（仅绑定环回，不暴露到局域网；容器内由 Compose 决定绑定与端口）
cd sc-python-ai
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 18000
```

**测试依赖：** 生产镜像 `scholarclaw/sc-python-ai:1.0.0` **不安装 pytest**。容器内测试用独立的
`sc-python-ai/Dockerfile.test` 构建测试镜像，依赖固定为 `sc-python-ai/requirements-dev.lock.txt`；
不要再用临时目录挂载 pytest 的方式作为唯一验证手段。

**尚未验证：** ChromaDB 客户端已在 TASK-504 落地并通过真实容器验证；RabbitMQ 生产端、解析消费者、Java 回调与向量索引已分别在 TASK-501~504 落地，DLQ 与课程状态收敛已在 TASK-505 落地；TASK-506 已用真实 PDF 验证完整闭环，消费者默认开启。模型探测接口已在 P3 落地。容器化与编排已由 TASK-104 完成（见 §3.4），容器内 Python 3.11 环境已用锁文件 `sc-python-ai/requirements.lock.txt` 复现并跑通全部测试。

### 3.3 Web 前端（已实测）

```bash
cd sc-web-frontend
npm install                   # 本机经代理访问 npmjs 极慢，可用 --registry=https://registry.npmmirror.com
npm run dev                   # 开发服务器，仅绑定 127.0.0.1:5173
npm run check                 # 完整门禁：类型检查 + ESLint + 单元测试
npm run type-check            # vue-tsc --noEmit
npm run lint                  # eslint . --max-warnings 0（含类型感知规则）
npm run test                  # vitest run
npm run build                 # 先类型检查，再 vite build 产出 dist/
npm run preview               # 预览构建产物
```

ESLint 启用了**类型感知规则**，其中 `no-floating-promises` / `no-misused-promises` 专门拦截
"忘了 await 的异步调用"——这类 bug `tsc` 查不出来，而本项目前端全是异步（接口请求、SSE 流式问答）。
没有引入 Prettier（避免新增依赖与全量重排）。

**已验证：** 与真实后端联调（经 Nginx）：登录/注册、BYOK 探测与保存、密钥掩码、偏好更新、
销毁 Key 均可正常走通；浏览器端到端交互用系统 Chrome 执行（Playwright 临时环境，未加入项目依赖）。
页面路由：`/`（首页）、`/login`（登录/注册）、`/courses`（课程空间）、`/knowledge`（课程知识库）、`/settings`（个人设置），除登录/注册外均需登录。
生产态已由 Nginx 托管（见 §3.4）。

### 3.4 部署（Docker Compose，已实测）

```bash
cp .env.example .env && chmod 600 .env   # 填入真实值；SC_MASTER_KEY 必须是 32 字节 Base64
mvn -f sc-java-backend/pom.xml -B -DskipTests package   # Java 镜像依赖宿主机预构建的 jar
docker compose build
docker compose up -d
docker compose ps                        # 等到 7 个容器都 healthy
```

入口：`http://<宿主机>:80`。七个容器（MySQL / Redis / RabbitMQ / ChromaDB / Java / Python / Nginx）
与端口边界、卷权限、环境变量注入边界、健康检查依赖链、已知限制**详见 `DEPLOYMENT.md`**。

要点提醒：**只有 Nginx 暴露宿主机端口**；`/internal/**` 被 Nginx 阻断为 404；
Java 与 Python 共享同一 uid（10001）但 Python 对共享卷是只读挂载。

内部通信自检（两个方向都由容器 healthcheck 每 15s 自动验证，也可手动复现）：

```bash
# Java：自身依赖（判定 data.status）+ 跨服务观测 data.peers（不参与判定）
docker exec sc-java-backend wget -q -O- http://127.0.0.1:8080/internal/api/v1/health
# → {"code":"OK","data":{"service":"sc-java-backend","status":"UP",
#      "dependencies":{"mysql":"UP","redis":"UP","rabbitmq":"UP"},
#      "peers":{"python_ai":"UP"}},…}

# Python：自身依赖（共享卷只读挂载 + ChromaDB）
docker exec sc-python-ai python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8000/internal/ai/v1/health').read().decode())"

# Python 容器 healthcheck 本体：解析 data.status，并携带令牌+追踪号真实调用 Java 健康接口
docker exec sc-python-ai python3 /app/healthcheck.py
# → HEALTHY: 自身依赖 UP，且与 sc-java-backend 内部健康接口双向调通
```

两条容易踩的边界（都已在 `DEPLOYMENT.md` 写明）：

- 健康接口**恒返回 HTTP 200**，依赖状态只在 `data.status` 里；Compose 的 healthcheck 必须解析响应体，
  只看 HTTP 状态等于只看进程存活。
- `data.status` **只由服务自身依赖决定**，`data.peers` 只上报跨服务连通性。Java 的健康状态不得依赖
  Python 是否在跑：`02` §13.3 要求 Python 等 Java 健康后才启动，若互相判定就会冷启动死锁（已实测复现）。

验证细节与证据见 `INTERNAL_API_VERIFICATION.md`；排查手册见 `DEPLOYMENT.md` §9。

### 3.5 数据库初始化与结构核对（已实测）

```bash
# 迁移脚本随 Java 启动自动执行（有序 SQL + 版本表 sc_schema_migration，不引入 Flyway）
docker compose logs sc-java-backend | grep 数据库迁移
docker compose exec -T sc-mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -D scholarclaw \
  -e "SELECT version, script, success, execution_ms, applied_at FROM sc_schema_migration"'

# 手工执行（生产建议：用特权账号先迁移，再在 .env 里设 SC_DB_MIGRATION_ENABLED=false）
docker compose exec -T sc-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' \
  < sc-java-backend/src/main/resources/db/migration/V1.0.0__init_mvp_schema.sql

# 结构核对：把真实库与 docs/03 的数据字典/索引表/约束名逐项比对（只读，退出码 0=一致）
python3 scripts/db/verify_mvp_schema.py
```

V1.0.0 只建 **7 张 MVP 表**；`v1.1` 的 `t_ddl_subtask` / `t_exam_wrong_book` 按 `03` §1.4
留待 `V1.1.0` 增量脚本（`MigrationScriptTest` 有断言守着，防止提前建表）。

### 3.6 认证闭环冒烟（已实测）

```bash
B=http://127.0.0.1:80/api/v1        # 经 Nginx → Java
curl -s -X POST $B/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"zhangsan_cs","password":"Passw0rd2026"}'          # 201 + access_token
curl -s -X POST $B/auth/login    -H 'Content-Type: application/json' \
     -d '{"username":"zhangsan_cs","password":"Passw0rd2026"}'          # 200 + access_token
curl -s $B/user/config -H "Authorization: Bearer <token>"               # 200，只返回本人数据
curl -s -X POST $B/auth/logout -H "Authorization: Bearer <token>"       # 200；之后同一 token → 401 AUTH_TOKEN_REVOKED
```

身份只来自 JWT（`04` §2.7.2）：伪造 `X-User-Id` 或请求体塞 `user_id` 都不会改变当前用户。
密码为 BCrypt(10) 哈希；注册**不预建**默认课程（`03` §9.3）。完整 E2E 结果见 `TASK_TRACKER.md` §6 的 TASK-202 记录。

### 3.7 BYOK 模型配置冒烟（已实测）

```bash
B=http://127.0.0.1:80/api/v1
T="Authorization: Bearer <token>"

# 探测（不写库）：公网地址必须是 https；本地 Ollama 需先加入白名单
curl -s -X POST $B/user/model-config/probe -H "$T" -H 'Content-Type: application/json' \
  -d '{"probe_type":"EMBEDDING","provider":"OPENAI_COMPATIBLE","base_url":"https://api.siliconflow.cn/v1","api_key":"<KEY>","model_name":"BAAI/bge-m3"}'

# 保存：先探测成功再落库；Key 只以 AES-256-GCM 密文入库，响应只回掩码
curl -s -X PUT $B/user/model-config -H "$T" -H 'Content-Type: application/json' \
  -d '{"chat_provider":"DEEPSEEK","chat_base_url":"https://api.deepseek.com/v1","chat_api_key":"<KEY>","chat_model":"deepseek-chat","default_embedding_provider":"OPENAI_COMPATIBLE","embedding_base_url":"https://api.siliconflow.cn/v1","reuse_chat_api_key":true,"default_embedding_model":"BAAI/bge-m3"}'

# 销毁已保存的 Key（幂等）：target = ALL / CHAT / EMBEDDING
curl -s -X DELETE "$B/user/model-config/keys?target=ALL" -H "$T"
```

SSRF 拦截返回 `400 E-SYS-02`；探测失败不会覆盖已有配置。完整 E2E 结果见 `TASK_TRACKER.md` §6 的 TASK-203 记录。

---

## 4. 配置与环境变量

真实密钥只通过环境变量注入，**绝不写入仓库**。当前已使用的配置项：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `sc.timezone` | `SC_TIMEZONE` | `Asia/Shanghai` | 用户时区与时间序列化基准。**必须显式固定**，严禁依赖宿主机/JVM 默认时区（见 `ENVIRONMENT.md` ENV-01） |
| `sc.internal.token` | `SC_INTERNAL_SERVICE_TOKEN` | 空 | Java ↔ Python 内部调用鉴权令牌（请求头 `X-Internal-Token`）。**留空时 `InternalTokenAuthFilter` 对所有 `/internal/**` 一律拒绝（失败关闭）**，不会"跳过校验" |
| `sc.storage`（Python） | `SC_STORAGE_RAW_PATH` | `/data/scholarclaw/storage/raw` | 共享卷只读挂载点；`sc-python-ai` 只读访问，绝不写入或删除源文件。Java 侧根目录固定为 `/data/scholarclaw/storage`，**不提供环境变量覆盖** |
| `sc.log-level`（Python） | `SC_LOG_LEVEL` | `INFO` | 本服务日志级别 |
| 模型出站超时（Python） | `SC_MODEL_CONNECT_TIMEOUT_SECONDS` / `SC_MODEL_READ_TIMEOUT_SECONDS` | `5` / `30` | 受控模型网关的出站超时 |
| 模型探测超时（Java） | `SC_MODEL_CONNECT_TIMEOUT_MILLIS` / `SC_MODEL_READ_TIMEOUT_MILLIS` | `5000` / `10000` | Java → Python 模型探测客户端的连接/读取超时 |
| 本地模型白名单（Java + Python） | `SC_ALLOW_LOCAL_MODEL_BASE_URL` / `SC_LOCAL_MODEL_ALLOWED_ORIGINS` | `false` / 空 | 默认关闭；显式开启后才允许校验本地 Ollama 白名单。Java `BaseUrlSecurityValidator` 做第一道，Python `ssrf_guard` 在出站前做 DNS 级第二道 |
| BYOK 主密钥（Java） | `SC_MASTER_KEY` | 无（必须注入） | 32 字节 Base64；仅 `sc-java-backend` 持有。缺失或长度不对时**拒绝启动**；一旦用于加密不可更换 |
| 对端基址（Java） | `SC_PYTHON_AI_BASE_URL` | 空（compose 注入 `http://sc-python-ai:8000`） | Java 的健康探针真实调用 Python 健康接口；结果进 `data.peers`，不参与 `data.status` |
| 对端基址（Python） | `SC_JAVA_BACKEND_BASE_URL` | 空（compose 注入 `http://sc-java-backend:8080`） | 仅供容器 healthcheck（`sc-python-ai/healthcheck.py`）验证反向调通；TASK-503 的 `JavaInternalApiClient` 复用同一变量 |
| 派生索引基址（Python） | `SC_CHROMADB_BASE_URL` | 空（compose 注入 `http://sc-chromadb:8000`） | ChromaDB 心跳探针基址；留空则不注册该探针，避免单机只跑本服务时误报 DOWN |
| 文档解析 MQ（Java） | `SC_MQ_INGEST_ENABLED` | `true` | TASK-501 生产端总开关；关闭时不连接 RabbitMQ |
| 文档解析队列（Java） | `SC_MQ_INGEST_EXCHANGE` / `SC_MQ_INGEST_QUEUE` / `SC_MQ_INGEST_DLQ` | `rag.ingest.exchange` / `rag.ingest.queue` / `rag.ingest.dlq` | `MQ-INGEST-001` 队列与死信声明 |
| 僵尸任务巡检（Java） | `SC_ZOMBIE_SCAN_ENABLED` | `true` | 补投 `UPLOADED` 且未入队的解析任务 |
| 解析消费者（Python） | `SC_INGEST_CONSUMER_ENABLED` | `true` | TASK-502 消费者开关；TASK-506 联调通过后默认开启，可显式关闭 |
| 页数上限（Python） | `SC_INGEST_MAX_PAGES` | `300` | 解析阶段拒绝超限 PDF（`E-RAG-02`） |
| 依赖探针超时（Java） | `SC_DEPENDENCY_PROBE_TIMEOUT_MILLIS` | `1500` | TCP 可达性探针超时（Redis/RabbitMQ 两项；MySQL 已改为连接池级探针） |
| JWT 有效期（Java） | `SC_JWT_TTL_SECONDS` | `604800` | 访问令牌有效期（秒），默认 7 天，与 `04` §2.7.2 一致 |
| 数据库迁移开关（Java） | `SC_DB_MIGRATION_ENABLED` | `true` | 启动期自动执行 `db/migration/V*.sql`；生产改由运维先手工迁移时设为 `false` |
| MySQL 探针方式（Java） | `SC_DB_POOL_PROBE` | `true` | `true`＝连接池级（能证明凭据正确）；`false`＝退回 TCP 可达性 |
| Redis 探针方式（Java） | `SC_REDIS_POOL_PROBE` | `true` | `true`＝连接池级 `PING`（能证明凭据正确）；`false`＝退回 TCP 可达性 |

其余环境变量（`SC_MASTER_KEY`、`SC_JWT_SECRET`、`SC_DB_PASSWORD` 等）随对应任务引入，注入边界见 `docs/02` §13.2。
**`sc-python-ai` 只从上述白名单变量读取配置**，其 `app/core/config.py` 对白名单外的名字直接报错，
并有源码级测试断言它不引用主密钥或数据库凭据。

前端构建期变量（示例见 `sc-web-frontend/.env.example`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | 浏览器请求业务接口的前缀；开发态由 Vite 代理，生产态由 Nginx 反向代理 |
| `VITE_DEV_PROXY_TARGET` | `http://127.0.0.1:8080` | 开发态代理目标（Java 后端） |
| `VITE_APP_TITLE` | `ScholarClaw 智学爪` | 页面标题 |

⚠️ **Vite 只会把 `VITE_` 前缀的变量注入客户端包**，因此**任何密钥都不得以 `VITE_` 命名**——
模型密钥由 Java 加密保存、只在内部请求内透传，前端只展示掩码。

---

## 5. 任务状态与进度

- 任务清单、依赖关系与验收条件：`docs/06`。
- 当前状态、需求追踪、未决事项台账：`TASK_TRACKER.md`。
- 环境差异与风险：`ENVIRONMENT.md`。

阶段路线：P0 仓库核验 → P1 工程骨架与容器底座 → P2 认证/BYOK → P3 Python 网关与模型探测 → P4 课程与知识库（`DONE`）→ P5 异步解析与向量索引 → P6 RAG 与 SSE → P7 DDL 闭环 → P8 提醒/通知/OneBot → P9 清理补偿与 MVP 验收。
