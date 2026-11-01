# 部署手册

单机 Docker Compose 部署。快速上手见 [README](README.md)，本文是运维细节。

---

## 1. 前置要求

| 项 | 要求 | 说明 |
|---|---|---|
| Docker Engine | ≥ 26 | 编排使用 `volume.subpath` 把共享卷的 `raw` 子目录只读挂给 Python |
| Docker Compose | ≥ 2.26 | 使用 v2 插件（`docker compose`），不是旧的 `docker-compose` |
| JDK / Maven | 21 / 3.9+ | Java 镜像只 `COPY` 预构建 jar；没有 Maven 时可本地构建后只上传 jar |
| 可用内存 | ≥ 4 GiB | 各容器 `mem_limit` 合计约 6 GiB，实测全部运行约 0.6 GiB（上限是闸门不是预留） |
| 磁盘 | ≥ 10 GiB | 镜像约 2.4 GB + MySQL/Chroma 数据卷 + 课件 |

镜像基线（全部 pin 具体标签）：`mysql:8.0`、`redis:7.2-alpine`、`rabbitmq:3.13-management-alpine`、
`chromadb/chroma:0.5.23`、`nginx:1.26-alpine`、`eclipse-temurin:21-jre-alpine`、`python:3.11-slim`。

## 2. 首次部署

```bash
cp .env.example .env
# 至少替换 SC_MASTER_KEY / SC_JWT_SECRET / 四个密码 / SC_INTERNAL_SERVICE_TOKEN
chmod 600 .env

docker compose config                      # 校验变量插值
mvn -f sc-java-backend/pom.xml -B -DskipTests package
docker compose build
docker compose up -d
docker compose ps                          # 等待 7 个容器 healthy
```

健康后访问 `http://<宿主机 IP>:${SC_WEB_HTTP_PORT:-80}`。

数据库迁移在 Java 启动时自动执行（`db/migration/V*.sql`，版本记录在 `sc_schema_migration`）。
生产环境若希望由运维用特权账号先迁移，把 `SC_DB_MIGRATION_ENABLED` 设为 `false`。

构建期如果 npm / pip 直连慢，可在 `.env` 指定镜像源（容器内不继承宿主机代理）：

```dotenv
SC_NPM_REGISTRY=https://registry.npmmirror.com/
SC_PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
```

## 3. 日常操作

```bash
docker compose ps                            # 状态与健康
docker compose logs -f sc-java-backend       # 跟踪日志（Java / Python 共用同一 trace_id）
docker compose restart sc-python-ai          # 重启单个服务
docker compose up -d --build sc-web-nginx    # 改前端后重建
docker compose down                          # 停止（保留数据卷）
docker compose down -v                       # ⚠️ 连数据卷删除，会丢 MySQL 与课件
```

改 `SC_PLATFORM_EMBEDDING_*`、`SC_OPERATOR_QQ` 这类运行时配置后，重启对应容器即可：

```bash
docker compose up -d sc-java-backend   # 平台向量模型
docker compose up -d sc-web-nginx      # 联系 QQ / NapCat WebUI 地址
```

## 4. 可选：QQ 机器人（NapCat）

NapCat 是可选 `onebot` profile，默认不启动：

```bash
docker compose --profile onebot up -d sc-napcat-onebot
```

| 项 | 值 |
|---|---|
| 容器名 | `sc-napcat-onebot` |
| 网络 | 仅 `scholarclaw-internal`，与 Java 同网 |
| WebUI | `http://127.0.0.1:${SC_NAPCAT_WEBUI_PORT:-6099}`，首次扫码登录 |
| Action API | `http://127.0.0.1:${SC_NAPCAT_HTTP_PORT:-3000}`，默认只绑回环 |
| 持久化卷 | `vol_napcat_qq`（登录态）、`vol_napcat_config` |
| 快速登录 | `.env` 填 `SC_NAPCAT_ACCOUNT=<QQ号>`，重启后复用登录态，失效自动回落二维码 |
| WebUI 令牌 | 首次用 `SC_NAPCAT_WEBUI_TOKEN` 固定；已部署用 `scripts/napcat/webui.sh` 读取或重置 |

⚠️ 两点：

1. 镜像是 `mlikiowa/napcat-docker:latest`，**生产前请 pin 具体版本**；
2. WebUI 令牌等于机器人 QQ 的控制权，只给运维，不要通过业务前端或 API 下发给普通用户。

远程访问 WebUI 推荐 SSH 隧道（`ssh -L 6099:127.0.0.1:6099 <host>`）。确实要直连时改
`SC_NAPCAT_WEBUI_BIND=0.0.0.0`，并加防火墙或只放行可信内网。

**不用 QQ 也完全没问题**：不启这个 profile、`SC_IM_PUSH_ENABLED=false`，Web 端功能不受影响，
只是用户收不到 QQ 提醒。

## 5. 与聊天模型 / 向量模型的关系

- **对话模型**：使用者自带（BYOK），在「个人设置 → 模型配置」填 Base URL、模型名、API Key，保存时后端会真实探测连通性
- **向量模型**：可以由部署者统一提供（`SC_PLATFORM_EMBEDDING_*`），也可以由用户自行配置
- 自定义 Base URL 默认拒绝内网/回环地址（SSRF 防护）；确需连接本地 Ollama 时用
  `SC_ALLOW_LOCAL_MODEL_BASE_URL=true` + `SC_LOCAL_MODEL_ALLOWED_ORIGINS=<origin>` 精确放行

## 6. 排障

| 现象 | 先看什么 | 常见原因 |
|---|---|---|
| 某容器 `unhealthy` | `docker inspect <容器> --format '{{json .State.Health}}'` | 依赖未就绪、探针命令失效、应用启动失败 |
| 健康接口 `status: DOWN` | 响应体 `data.dependencies` | 对应依赖未启动或网络断开 |
| `403 AUTH_FORBIDDEN` | 请求来源与路径 | `X-Internal-Token` 缺失或错误 |
| 前端 502 | `docker compose ps sc-java-backend` | Java 未就绪或已退出 |
| `/internal/**` 返回 404 | — | 预期行为，Nginx 显式阻断 |
| 上传大文件 413 | Nginx 日志 | 单文件上限 50 MB（`client_max_body_size 60m`） |
| 端口被占用 | `ss -ltn \| grep :80` | 改 `.env` 的 `SC_WEB_HTTP_PORT` |
| 容器反复重启 | `docker compose logs <服务>` | 环境变量缺失（Compose 对必填变量直接拒绝启动） |
| Java 启动报 `SC_PLATFORM_EMBEDDING_*` | 启动日志 | 平台向量模型只配了一部分，五项要么全配要么全空 |
| 构建时 npm/pip 超时 | 构建日志 | 配 `SC_NPM_REGISTRY` / `SC_PIP_INDEX_URL` |

日志格式：`… [trace_id=tr_xxxxxxxxxxxx] [user_id=…] <logger> - <message>`。
同一次请求在 Java 与 Python 的日志里是同一个 `trace_id`，可以直接跨容器对照。

排查时不要把日志或响应体里的敏感值贴到外部；临时调试端口只绑定 `127.0.0.1`，用完即撤。

## 7. 上线前必须补齐

| 项 | 现状 | 建议 |
|---|---|---|
| HTTPS | 未内置，只有 80 端口 | 前置 TLS 终止（云 LB 或宿主机 Nginx） |
| 备份 | 无脚本 | 定时 `mysqldump` + 备份 `vol_shared_storage` 与 `.env` 的 `SC_MASTER_KEY` |
| 注册限流 | 无 | 网关层限制登录/注册频率，防止刷账号 |
| 日志轮转 | 未配置 | 给容器加 `logging.options.max-size` |
| NapCat 版本 | `latest` | pin 具体版本 |
| 依赖漏洞 | ChromaDB / 部分 Java 依赖无修复版本 | 已做网络隔离与访问收敛，公网部署请自行评估 |

## 8. 升级

```bash
git pull
mvn -f sc-java-backend/pom.xml -B -DskipTests package
docker compose build
docker compose up -d
```

新增迁移会在 Java 启动时自动执行。**升级前先备份数据库**：迁移脚本只有正向执行，回滚脚本不保证覆盖每个版本。
