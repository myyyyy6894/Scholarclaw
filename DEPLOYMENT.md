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
| `sc-java-backend` | **过渡实现**：`nc -z 127.0.0.1 8080`（只证明进程已监听） |
| `sc-python-ai` | `python3 -c urllib…`（该镜像内无 curl/wget） |
| `sc-web-nginx` | `wget /` |

两点需要注意：

1. **Java 的健康探针会在 TASK-105 换成正式接口** `GET /internal/api/v1/health`（`04` §4.9.8）。
   该接口对容器本机环回免 `X-Internal-Token`（`04` `API-CONF-03`），因此 Compose 里无需转义令牌。
2. **HTTP 200 不等于依赖健康**：按 `04` 第 7 章，Java/Python 的健康接口恒返回 200，依赖状态只在
   响应体 `data.status` / `data.dependencies` 中体现。因此若要让 Compose 按依赖状态判定，
   需要检查响应体字段，而不是只用 `curl -fsS`。

## 8. 构建期网络说明（本机特有）

宿主机经代理访问外网，**但容器内不会继承宿主机代理**，两次实测踩坑：

* `npm ci` / `pip install` 在容器内直连官方源超时 → 通过 build arg 注入国内镜像：
  `.env` 中 `SC_PIP_INDEX_URL`（默认官方源）、`SC_NPM_REGISTRY`（默认官方源）。
  仓库默认值保持官方源，镜像源只写在本地 `.env` 里。
* **Java 镜像刻意不做多阶段 Maven 构建**：本机经代理解析 Maven 依赖仅约 20 KB/s，
  在容器内重新下载全部依赖需数小时。因此约定先在宿主机 `mvn package`，镜像只 `COPY` 该 jar。
  网络条件良好的机器或 CI 上可改为多阶段构建（`sc-java-backend/Dockerfile` 顶部有说明）。

## 9. 已知限制与未验证项（不得当作已完成）

| 项 | 现状 |
|---|---|
| HTTPS / 443 | **未启用**。文档提到 80/443 双端口，但项目未定义证书来源；当前只暴露 80。生产化需补 TLS 终止 |
| 容器日志轮转 | **未配置** `logging` 上限。长期运行建议加 `logging.options.max-size`，否则可能持续占用磁盘 |
| 数据库迁移 | **未执行**。业务表结构属 TASK-201；当前只有空库 `scholarclaw` 与用户 |
| Java 与数据层连接 | Java 容器已收到 DB/Redis/MQ 凭据，但**代码尚未引入相应客户端**（TASK-201/104 之后），因此健康检查不反映这些依赖 |
| OneBot / NapCat 容器 | **有意未纳入**编排（文档标注为可选）。镜像 tag 需在 TASK-803 落地时一并确认 |
| 内部通信正式验证 | 本任务只做了冒烟：Java→Python 带令牌得 200、不带得 403、`X-Trace-Id` 可透传。系统化验证属 TASK-105 |
| 性能基线 | 未测。属 TASK-905 |

## 10. 本次实测结论（2026-10-18）

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
