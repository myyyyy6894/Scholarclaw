# ScholarClaw 部署手册

单机 Docker Compose 部署说明。依据 `docs/02` §2.4（部署拓扑）、§13（Compose 部署设计）。

| 属性 | 内容 |
|---|---|
| 对应任务 | TASK-104（建立 Docker Compose 与基础设施） |
| 首次实测日期 | 2026-10-18 |
| 实测环境 | Docker Engine 29.1.3 / Docker Compose 2.40.3 / 宿主机 28 核 15 GiB |
| 状态 | 七个容器已实测全部达到 `healthy`；未验证项见 §9 |

---

## 1. 前置要求

| 项 | 要求 | 说明 |
|---|---|---|
| Docker Engine | ≥ 26 | 本编排使用 `volume.subpath` 把共享卷的 `raw` 子目录只读挂给 Python（见 §5） |
| Docker Compose | ≥ 2.26 | 同上；使用 `docker compose`（v2 插件），不是旧的 `docker-compose` |
| 可用内存 | ≥ 3 GiB | 各容器 `mem_limit` 合计约 7 GiB，但实测全部运行仅占约 0.9 GiB（上限是闸门不是预留） |
| 磁盘 | ≥ 10 GiB | 镜像约 1.9 GiB + MySQL/Chroma 数据卷 |

镜像基线（`docs/02` §13.1，全部 pin 具体标签，不使用 `latest`）：
`mysql:8.0`、`redis:7.2-alpine`、`rabbitmq:3.13-management-alpine`、`chromadb/chroma:0.5.23`、
`nginx:1.26-alpine`、`eclipse-temurin:21-jre-alpine`、`python:3.11-slim`。

## 2. 首次部署

```bash
# 1) 准备环境变量（.env 已被 .gitignore 排除，绝不入库）
cp .env.example .env
#    至少替换以下值；SC_MASTER_KEY 必须是 32 字节 Base64：
#      openssl rand -base64 32
#      openssl rand -base64 48   # 用于 SC_JWT_SECRET
chmod 600 .env

# 2) 校验编排与环境变量插值
docker compose config

# 3) 构建 Java 可执行 jar（**必须在宿主机做**，原因见 §8）
mvn -f sc-java-backend/pom.xml -B -DskipTests package

# 4) 构建三个应用镜像并启动
docker compose build
docker compose up -d

# 5) 等待全部健康（依赖链会自动排序启动）
docker compose ps
```

健康后访问入口：**`http://<宿主机 IP>:${SC_WEB_HTTP_PORT:-80}`**。

## 3. 日常操作

```bash
docker compose ps                       # 状态与健康
docker compose logs -f sc-java-backend  # 跟踪某容器日志
docker compose restart sc-python-ai     # 重启单个服务
docker compose up -d --build sc-web-nginx   # 改前端后重建
docker compose down                     # 停止并删除容器（保留数据卷）
docker compose down -v                  # ⚠️ 连数据卷一起删除，会丢失 MySQL 与课件数据
```

## 4. 网络与端口边界

| 容器 | 网络 | 宿主机端口 | 容器内端口 |
|---|---|---|---|
| `sc-web-nginx` | `scholarclaw-public` | **`${SC_WEB_HTTP_PORT}:80`（唯一对外入口）** | 80 |
| `sc-java-backend` | public + internal | 不暴露 | 8080 |
| `sc-python-ai` | internal | 不暴露 | 8000 |
| `sc-mysql` | internal | 不暴露 | 3306 |
| `sc-redis` | internal | 不暴露 | 6379 |
| `sc-rabbitmq` | internal | 不暴露 | 5672 / 15672 |
| `sc-chromadb` | internal | 不暴露 | 8000 |

Nginx 另外**显式阻断** `/internal/**`（返回 404），内部接口不可能从容器外到达。

调试提示：若需临时连 MySQL/RabbitMQ，请临时加 `127.0.0.1:端口` 映射（**不要**写 `0.0.0.0`），
用完即撤；本机 3306/6379 曾长期被其他项目占用，切勿盲目映射默认端口。

## 5. 持久化卷与权限

| 卷 | 挂载点 | 权限 |
|---|---|---|
| `vol_mysql_data` | `sc-mysql:/var/lib/mysql` | MySQL 独占 |
| `vol_redis_data` | `sc-redis:/data` | Redis 独占（AOF 开启） |
| `vol_rabbitmq_data` | `sc-rabbitmq:/var/lib/rabbitmq` | RabbitMQ 独占 |
| `vol_chroma_data` | `sc-chromadb:/chroma/chroma` | ChromaDB 独占（**可重建**派生索引） |
| `vol_shared_storage` | `sc-java-backend:/data/scholarclaw/storage`（rw）<br>`sc-python-ai:/data/scholarclaw/storage/raw`（ro） | Java 读写删除；Python 只读 |

**`volume.subpath` 不能省。** 若直接把卷根挂到 Python 的 `/data/scholarclaw/storage/raw`，
Java 写的 `<卷根>/raw/x.pdf` 在 Python 眼里会变成 `/…/raw/raw/x.pdf`——两侧路径对不上，
且 Python 会看到整个存储根目录，超出「只读 `/raw`」的最小权限设计。本任务实测时先踩了这个坑。

Java 与 Python 容器使用**同一 uid/gid（10001）**，保证跨容器的读写/只读语义与属主一致。

## 6. 环境变量注入边界

| 变量 | 注入容器 | 严禁注入 |
|---|---|---|
| `SC_MASTER_KEY` | 仅 `sc-java-backend` | Python 及其他全部容器 |
| `SC_JWT_SECRET` | 仅 `sc-java-backend` | 同上 |
| `SC_DB_PASSWORD` / `SC_DB_ROOT_PASSWORD` | `sc-mysql`、`sc-java-backend`（root 口令仅 `sc-mysql`） | Python |
| `SC_REDIS_PASSWORD` | `sc-redis`、`sc-java-backend` | Python |
| `SC_RABBITMQ_*` | `sc-rabbitmq`、`sc-java-backend`、`sc-python-ai` | 其他 |
| `SC_INTERNAL_SERVICE_TOKEN` | `sc-java-backend`、`sc-python-ai` | 其他 |
| `SC_ALLOW_LOCAL_MODEL_BASE_URL` / `SC_LOCAL_MODEL_ALLOWED_ORIGINS` | `sc-java-backend`、`sc-python-ai` | 其他 |
| `SC_IM_PUSH_ENABLED` / `SC_ONEBOT_ACCESS_TOKEN` | 仅 `sc-java-backend` | Python |

实现方式：`docker-compose.yml` 里逐项 `environment:` 显式声明，**任何容器都不用 `env_file`**。
实测结果：`sc-python-ai` 容器内 `env` 只有 11 个应用变量（`SC_LOG_LEVEL`、`SC_INTERNAL_SERVICE_TOKEN`、
`SC_RABBITMQ_*`、`SC_TIMEZONE`、`SC_STORAGE_RAW_PATH`、本地模型白名单两项、`TZ`），
不含任何主密钥或数据库凭据。

## 7. 健康检查与启动依赖链

```
sc-mysql / sc-redis / sc-rabbitmq  (healthy)
        └→ sc-java-backend (healthy)  &  sc-chromadb (healthy)
                    └→ sc-python-ai  &  sc-web-nginx
```

| 容器 | 探针 |
|---|---|
| `sc-mysql` | `mysqladmin ping`（带口令） |
| `sc-redis` | `redis-cli --no-auth-warning ping` |
| `sc-rabbitmq` | `rabbitmq-diagnostics -q ping` |
| `sc-chromadb` | `curl -f /api/v1/heartbeat` |
| `sc-java-backend` | `wget -qO- …/internal/api/v1/health \| grep -q '"status":"UP"'`（**解析响应体的 `data.status`**） |
| `sc-python-ai` | `python3 /app/healthcheck.py`（该镜像内无 curl/wget；脚本行为见下） |
| `sc-web-nginx` | `wget /` |

三点需要注意：

1. **HTTP 200 不等于依赖健康**：按 `04` 第 7 章，Java/Python 的健康接口恒返回 200，依赖状态只在
   响应体 `data.status` 中体现。两个应用的 healthcheck 都**必须解析响应体**，只用 `curl -fsS` /
   `wget -O /dev/null` 等于只看进程存活（TASK-104 的过渡写法，已在复核中修正）。
2. **`data.status` 只由服务自身依赖决定，`data.peers` 不参与判定**：
   * `sc-java-backend`：自身依赖 = MySQL / Redis / RabbitMQ（`04` §4.9.8 列明）；对 `sc-python-ai`
     的连通性放 `data.peers.python_ai`，只观测不判定。
   * `sc-python-ai`：自身依赖 = 共享卷只读挂载 / ChromaDB；`data.peers` 当前恒为空。
   * **为什么不能把 Python 纳入 Java 的 `data.status`**：`02` §13.3 要求 `sc-python-ai` 等
     `sc-java-backend` 健康后才启动；若 Java 的健康又依赖 Python 在跑，冷启动就会死锁。
     该组合已实测复现：`dependency failed to start: container sc-java-backend is unhealthy`，
     Python 与 Nginx 都无法启动（证据见 `INTERNAL_API_VERIFICATION.md` §3.6）。
3. **两个方向都由 healthcheck 持续验证**：
   * Java → Python：Java 的健康探针真实跨容器调用 Python 健康接口，校验 `X-Internal-Token`
     被接受且 `X-Trace-Id` 被原样回显，结果进 `data.peers.python_ai`。
   * Python → Java：`sc-python-ai/healthcheck.py` 携带 `X-Internal-Token` 与一个新生成的
     `X-Trace-Id` 调用 Java 的健康接口，校验响应信封与追踪号回显；
     它**不要求** Java 的 `data.status` 为 UP（Java 的 MySQL 故障不该把 Python 判成不健康）。

   由此 `02` §15.1 阶段 1 的「双向 `/health` 携带 `X-Internal-Token` 与 `X-Trace-Id` 调通」
   不是一次性验收动作，而是每 15s 自动重验的常驻守卫。

手动复现双向调通：

```bash
docker exec sc-python-ai python3 /app/healthcheck.py        # → HEALTHY: …双向调通
docker exec sc-java-backend wget -qO- http://127.0.0.1:8080/internal/api/v1/health
docker inspect sc-python-ai --format '{{json .State.Health}}' | tail -c 400   # 看每次探针的输出
```

## 8. 构建期网络说明（本机特有）

宿主机经代理访问外网，**但容器内不会继承宿主机代理**，两次实测踩坑：

* `npm ci` / `pip install` 在容器内直连官方源超时 → 通过 build arg 注入国内镜像：
  `.env` 中 `SC_PIP_INDEX_URL`（默认官方源）、`SC_NPM_REGISTRY`（默认官方源）。
  仓库默认值保持官方源，镜像源只写在本地 `.env` 里。
* **Java 镜像刻意不做多阶段 Maven 构建**：本机经代理解析 Maven 依赖仅约 20 KB/s，
  在容器内重新下载全部依赖需数小时。因此约定先在宿主机 `mvn package`，镜像只 `COPY` 该 jar。
  网络条件良好的机器或 CI 上可改为多阶段构建（`sc-java-backend/Dockerfile` 顶部有说明）。

## 9. 问题定位（日志与排查）

### 9.1 看日志

```bash
docker compose logs -f --tail=200 sc-java-backend      # 跟踪单个服务
docker compose logs --since 10m sc-python-ai           # 最近 10 分钟
docker logs sc-java-backend 2>&1 | grep 'tr_9a1b2c3d4e5f'   # 按追踪号捞一次请求
```

两侧日志格式一致：`… [trace_id=tr_xxxxxxxxxxxx] [user_id=…] <logger> - <message>`。
无用户身份时 `user_id` 统一为 `-`。**同一次请求在 Java 与 Python 的日志里是同一个 `trace_id`**，
因此可以直接跨容器 join（示例见 `INTERNAL_API_VERIFICATION.md` §3.3）。

### 9.2 常见故障与定位路径

| 现象 | 先看什么 | 常见原因 |
|---|---|---|
| 某容器 `unhealthy` | `docker inspect <容器> --format '{{json .State.Health}}'` | 依赖未就绪（启动顺序）、探针命令失效、应用启动失败 |
| 健康接口返回 `status: DOWN` | 响应体 `data.dependencies` 里哪一项为 DOWN | 对应依赖未启动 / 网络断开；`python_ai` DOWN 多为令牌不一致或 Python 自报不健康 |
| `403 AUTH_FORBIDDEN` | 请求来源与路径 | `X-Internal-Token` 缺失或错误；令牌未注入（失败关闭）；**环回免令牌例外只覆盖健康检查路径** |
| 前端 502 / 502 Bad Gateway | `docker compose ps sc-java-backend` | Java 未就绪或已退出 |
| 访问 `/internal/**` 得 404 | — | **这是预期行为**：Nginx 显式阻断内部接口 |
| 上传大文件被拒 | Nginx 日志报 413 | `client_max_body_size`（当前 60m，对应单文件 ≤ 50MB 的上限） |
| 端口占用起不来 | `ss -ltn \| grep :80` | 改 `.env` 的 `SC_WEB_HTTP_PORT` |
| 容器反复重启 | `docker compose logs <服务>` | 环境变量缺失（Compose 会对 `:?` 变量直接报错拒绝启动） |
| 构建时 npm/pip 超时 | 构建日志 | 容器内不继承宿主机代理；在 `.env` 设 `SC_PIP_INDEX_URL` / `SC_NPM_REGISTRY` 指向可达镜像源 |
| 冷启动卡在 `dependency failed to start: container sc-java-backend is unhealthy` | `docker inspect sc-java-backend --format '{{json .State.Health}}'` 的探针输出 | **健康判定成环**：Java 的 `data.status` 若包含 `python_ai`，而 Python 又等 Java 健康才启动，就会互相等待。判定范围必须是"仅自身依赖"，跨服务只放 `data.peers`（见 §7） |
| 依赖明明活着，健康检查却报 DOWN | 该依赖的容器能否被本服务直连（`docker exec <服务> …`） | **代理环境变量污染**：`httpx`/`urllib` 默认继承 `HTTP_PROXY`/`ALL_PROXY`，JDK 默认会读 `-Dhttp.proxyHost`。内部探针已在两侧显式直连（Python `trust_env=False`、Java `ProxySelector.of(null)`）；本机实测过 `ALL_PROXY=socks://…` 导致 `Unknown scheme for proxy URL` 的误报 |
| 日志里 `user_id` 一直是 `-` | 该请求是否经过认证 | **预期行为（当前阶段）**：入口只绑定 `trace_id`，真实用户 ID 由认证过滤器（TASK-202）验签成功后调用 `TraceContext.bindUserId` 写入。内部调用则按 `04` §6.1.2 由 Python 在令牌校验通过后读取 `X-User-Id` |

### 9.3 排查时的安全边界

* **不要把日志或响应体里的敏感值贴到外部**：密钥虽已被日志脱敏过滤器兜底，但排查时应只引用
  `trace_id` 与错误码；
* 环境变量注入的密钥在 `docker inspect` 中可见（Docker 固有特性），读取需要 docker 权限；
* 临时调试端口映射只允许写 `127.0.0.1:端口`，**绝不要用 `0.0.0.0`**，用完即撤。

## 10. 已知限制与未验证项（不得当作已完成）

| 项 | 现状 |
|---|---|
| HTTPS / 443 | **未启用**。文档提到 80/443 双端口，但项目未定义证书来源；当前只暴露 80。生产化需补 TLS 终止 |
| 容器日志轮转 | **未配置** `logging` 上限。长期运行建议加 `logging.options.max-size`，否则可能持续占用磁盘 |
| 数据库迁移 | **未执行**。业务表结构属 TASK-201；当前只有空库 `scholarclaw` 与用户 |
| Java 与数据层连接 | Java 容器已收到 DB/Redis/MQ 凭据，但**代码尚未引入相应客户端**（TASK-201 之后）。健康检查对这三者目前只做 **TCP 可达性**验证：证明网络通、进程在监听，**不证明**凭据正确或数据库可读写 |
| OneBot / NapCat 容器 | **有意未纳入**编排（文档标注为可选）。镜像 tag 需在 TASK-803 落地时一并确认 |
| 内部通信验证 | ✅ 已完成（TASK-105 + 2026-10-19 复核）：契约、环回例外范围、日志关联与脱敏、只读挂载、**双向 `/health` 常驻验证**、依赖故障判定均已实测，见 `INTERNAL_API_VERIFICATION.md`。**Python → Java 的「业务」内部调用（`API-INT-JAVA-001/002/003`）尚未实现**（属 TASK-503），当前 Python → Java 只覆盖健康检查 |
| Python 依赖锁定范围 | `sc-python-ai/requirements.lock.txt` 锁的是**运行时**依赖（16 个包，全部固定版本）。两点边界：① 不含制品哈希（`pip freeze` 拿不到哈希，无法防"同名同版本被重传"）；② 开发/测试依赖（`pytest`、`pytest-asyncio`）仍是版本区间，未纳入锁文件——等引入 CI 时再评估 |
| `data.peers` 的覆盖面 | 只有 `sc-java-backend` 会上报 `peers.python_ai`；`sc-python-ai` 的 `peers` 恒为空（Python → Java 方向由容器 healthcheck 承担，避免两个健康接口互调形成无限递归）。若日后希望 Python 也上报该观测，需先为健康接口定义"禁止递归"的显式契约 |
| 性能基线 | 未测。属 TASK-905 |

## 11. 本次实测结论（2026-10-18）

| 验收条件 | 结果 |
|---|---|
| 必要容器能够启动并达到健康状态 | ✅ 7/7 全部 `healthy`；实测内存合计约 864 MiB |
| 内部服务不暴露不必要的公网端口 | ✅ 仅 `sc-web-nginx` 映射 `0.0.0.0:80`；`ss -ltn` 未见 3306/6379/5672/8000/8080 |
| Java 对共享卷读写、Python 只读 | ✅ Java 写入成功 → Python 读到同一文件；Python 写入被拒（read-only file system） |
| Python 未获得主密钥或数据库凭据 | ✅ 容器内环境变量清单已逐项核对 |
| 真实密钥未写入 Compose 或仓库 | ✅ 8 个真实密钥值在仓库文件中 0 命中；`.env` 已 gitignore |

附加验证：Nginx 托管前端 200 且 SPA 回退正常、`/internal/**` 被阻断为 404、`/api` 反代到 Java
返回统一信封、Java→Python 内部调用令牌校验与 `X-Trace-Id` 透传均符合契约、MySQL 时区为
`+08:00` 且字符集为 `utf8mb4_0900_ai_ci`、Redis 未认证访问被拒（`NOAUTH`）。

## 12. 复核实测结论（2026-10-19：健康判定、依赖故障、可复现构建）

| 场景 | 操作 | 实测结果 |
|---|---|---|
| **冷启动死锁（反例）** | 临时把 Java healthcheck 改成解析 `data.status`，同时 Java 的 `data.status` 仍包含 `python_ai`（当时实现的语义） | ❌ 复现：`dependency failed to start: container sc-java-backend is unhealthy`；健康日志里响应体为 `"status":"DOWN","dependencies":{…,"python_ai":"DOWN"}`；`sc-python-ai` 与 `sc-web-nginx` 停在 `Created` 状态从未启动 |
| **冷启动（修复后）** | 判定范围改为"仅自身依赖"后再 `down` + `up -d` | ✅ 7/7 `healthy`，启动顺序符合 `02` §13.3 |
| MySQL 故障 | `docker compose stop sc-mysql` | ✅ `data.status=DOWN`、`dependencies.mysql=DOWN`；容器转 `unhealthy`；`start` 后自愈为 `healthy` |
| Redis 故障 | `docker compose stop sc-redis` | ✅ 同上（`redis=DOWN` → `unhealthy`，FailingStreak 达 13 后翻转；恢复后 FailingStreak 归 0） |
| RabbitMQ 故障 | `docker compose stop sc-rabbitmq` | ✅ `data.status=DOWN`、`dependencies.rabbitmq=DOWN`；恢复后 `UP` |
| ChromaDB 故障 | `docker compose stop sc-chromadb` | ✅ Python `data.status=DOWN`、`chromadb=DOWN`；Python 容器转 `unhealthy`（FailingStreak 15）；应用日志 `健康检查依赖不可用 … chromadb: DOWN`；恢复后两者均恢复 |
| Python 整机故障 | `docker compose stop sc-python-ai` | ✅ Java **保持 `healthy`**（自身依赖全 UP），但 `peers.python_ai=DOWN` → 无级联误报，且降级可观测；恢复后 `peers.python_ai=UP`。**这是刻意的边界**：Python 挂掉不把 Java 判成不健康（见 §7 说明 2/3） |
| 双向健康守卫 | `docker exec sc-python-ai python3 /app/healthcheck.py` | ✅ `HEALTHY: 自身依赖 UP，且与 sc-java-backend 内部健康接口双向调通`；`docker inspect` 显示该行每 15s 输出一次 |
| 可复现构建 | 用 `requirements.lock.txt` 重建 Python 镜像后 `pip freeze` 与新镜像内实际环境比对 | ✅ 完全一致（`diff` 无差异）；Python 3.11 容器内按锁文件装依赖后 `pytest` **113 项通过** |
| 时间戳时区 | `date -Is`（宿主与容器）、`docker inspect` 健康日志时间戳 | 宿主与两容器均为 `+08:00`；**Docker daemon 自身的元数据时间戳为 `-07:00`**（daemon 时区），不影响应用——应用时区由 `TZ`/`SC_TIMEZONE` 显式注入，这正是 ENV-01 要求显式固定时区的原因 |
