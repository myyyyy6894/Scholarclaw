# E2E 脚本

这些脚本用于**可重复**验证认证、BYOK 与前端页面，不写入真实密钥，也不修改生产 `.env`。

前置：整栈已启动（`docker compose up -d`），Nginx 入口可达（默认 `http://127.0.0.1`）。

> **mock 地址必须是私网 IP 字面量。** Java `BaseUrlSecurityValidator` 对本地/私有地址做精确
> Origin 白名单匹配，普通 Docker 服务名（如 `sc-e2e-mock-model`）可能被 `E-SYS-02` 拒绝；
> 请用 `docker inspect` 取得 mock 容器 IP 后写入 `SC_LOCAL_MODEL_ALLOWED_ORIGINS` 并传给脚本。

## 1. 认证 + BYOK 冒烟（无需浏览器）

```bash
python3 scripts/e2e/auth_byok_smoke.py
# 或指定入口
python3 scripts/e2e/auth_byok_smoke.py --base-url http://127.0.0.1
```

覆盖：注册、重复注册、错误密码、未认证访问、伪造 `X-User-Id`、登录、
公共探测拒绝 `BOTH`、SSRF 拦截（元数据/内部服务名/内部端口）、偏好成对与非法时区校验、
Key 销毁幂等、登出后旧 token `AUTH_TOKEN_REVOKED`。

脚本只创建临时账号并打印用户名；**不会自动删除账号**。清理方式见 §6（需维护者授权）。

## 2. 浏览器 E2E（Playwright）

固定依赖版本：**`playwright==1.51.0`**（仅用于测试，不写入项目依赖）。
使用系统 Chrome（Playwright 的 `channel="chrome"`），无需下载 Chromium。

```bash
python3 -m venv /tmp/sc-e2e-venv
/tmp/sc-e2e-venv/bin/pip install playwright==1.51.0
/tmp/sc-e2e-venv/bin/python scripts/e2e/browser_byok_e2e.py
```

不带 `--mock-base-url` 时验证：注册并进入设置页、未配置空状态、偏好保存、登出、无 console error。

### 2.1 需要验证模型探测/保存时（mock 模型）

mock 服务（`scripts/e2e/mock_model_server.py`）只监听内部网络，不需要宿主机端口。
**不要直接改 `.env`**；用临时 env 文件注入白名单，验证后恢复：

```bash
cd /home/muyi/study/ScholarClaw
cp .env /tmp/sc-e2e.env && chmod 600 /tmp/sc-e2e.env
printf '\nSC_ALLOW_LOCAL_MODEL_BASE_URL=true\nSC_LOCAL_MODEL_ALLOWED_ORIGINS=http://<mock-private-ip>:9000\n' >> /tmp/sc-e2e.env

docker run -d --name sc-e2e-mock-model --network scholarclaw-internal \
  -v "$PWD/scripts/e2e/mock_model_server.py:/server.py:ro" \
  python:3.11-slim python3 /server.py

docker compose --env-file /tmp/sc-e2e.env up -d --force-recreate sc-java-backend sc-python-ai

/tmp/sc-e2e-venv/bin/python scripts/e2e/browser_byok_e2e.py \
  --mock-base-url http://<mock-private-ip>:9000/v1
```

清理（恢复原 `.env` 注入并删除 mock）：

```bash
docker compose up -d --force-recreate sc-java-backend sc-python-ai
docker rm -f sc-e2e-mock-model
rm -f /tmp/sc-e2e.env
rm -rf /tmp/sc-e2e-venv
```

清理测试账号：

```bash
docker compose exec -T sc-mysql sh -lc \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
   -e "DELETE FROM t_user_config WHERE username LIKE \"e2e_browser_%\";"'
```

## 3. P3 追踪与 mock 安全验证

无需浏览器，使用已启动的整栈与容器内 Python：

```bash
# Java → Python 的 tr_cron_* 透传：响应头、响应体、两侧日志
python3 scripts/e2e/trace_passthrough_smoke.py

# 真实网关/探测代码的 mock 验证（在容器内运行）：
# 500/501/505 不重试；429/502/503/504 最多 3 次；BOTH 8s 总预算；
# SSRF 控制字符/编码穿越；日志与异常不含 Key
docker compose exec -T sc-python-ai python3 - < scripts/e2e/p3_mock_smoke.py
```

`trace_passthrough_smoke.py` 只使用容器内已有的 `SC_INTERNAL_SERVICE_TOKEN`，不读取、不打印 `.env`；`p3_mock_smoke.py` 只绑定容器内回环地址的临时 mock，不访问真实供应商。

## 4. P4 API/状态机冒烟（TASK-401~404）

在临时 mock 模型白名单生效、mock 容器运行后：

```bash
# MOCK_IP 必须是私网 IP 字面量，且已写入 SC_LOCAL_MODEL_ALLOWED_ORIGINS
MOCK_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' sc-e2e-mock-model)
python3 scripts/e2e/course_smoke.py --mock-base-url "http://$MOCK_IP:9000/v1"
```

覆盖：保存已验证 Embedding 配置、建课锁定维度、同名 409、`existing_course_count`、
真实 multipart 上传（路径穿越文件名净化、文件落盘）、重复上传 409（含 `existing_doc_id`）、
伪造/非法扩展名拦截、`DOC_DELETING` 默认可见、六态过滤、重试/重建 P4 返回 501 门禁、
切换模型版本递增与文档 `PARSING`/快照清空/旧父块清空、旧 Key 绑定新模型名 400、
旧索引版本不计入可用统计、跨用户隔离、删除后 DDL 任务解绑保留、重复删除 404。

**注意：** 脚本使用 SQL 直接构造 `READY` 夹具，验证的是 P4 API 与状态机，
**不代表“上传→解析→READY”真实闭环**（真实闭环见 §4.2）。运行前必须在临时 env 中设置
`SC_INGEST_CONSUMER_ENABLED=false`，否则后台消费者会与 SQL 夹具竞争同一文档状态；
运行后清空 `rag.ingest.queue` 中残留的测试消息。

脚本只使用假 Key；ID 用十进制字符串；验证后按上面的临时 env 流程恢复容器。

### 4.1 P4 浏览器 E2E（可复现）

```bash
MOCK_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' sc-e2e-mock-model)
/tmp/sc-e2e-venv/bin/python scripts/e2e/browser_p4_e2e.py --mock-base-url "http://$MOCK_IP:9000/v1"
```

覆盖：登录、空课程状态、页面创建课程、上传文字版 PDF、真实解析到 READY、
失败文档重试入口、READY 文档重建入口、`DELETING` 默认可见、
课程切换、退出登录后课程上下文清空、0 console error。
脚本会注册临时账号并保存 mock 模型配置；结束后删除测试文档与课程。
截图默认写入 `/tmp/sc-p4-{knowledge,deleting,courses}.png`。

### 4.2 P5 文档异步处理端到端（TASK-506）

前置与 §4 相同，但必须**开启消费者**（`SC_INGEST_CONSUMER_ENABLED=true`，TASK-506 起为默认值），
且 mock 容器运行中：

```bash
MOCK_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' sc-e2e-mock-model)
python3 scripts/e2e/ingest_e2e.py --mock-base-url "http://$MOCK_IP:9000/v1"
```

覆盖：真实文字版 PDF 上传→MQ→解析→父块入库→Embedding→ChromaDB→READY；
READY 页数/块数/向量数与课程统计；重复消息幂等；v2 重建期间迟到 v1 回调清理孤儿向量；
扫描件 `E-RAG-01` 失败；消费者停机期间保持 `PARSING` 并在恢复后自动完成；
mock 服务故障导致 `FAILED` 后手动重试恢复；PARSING 僵尸巡检补投与 `retry_count` 递增；
删除文档/课程的向量与 Collection 清理；默认 Embedding 变更的“未确认拒绝 + 确认后批量迁移”。
脚本只读 MySQL/ChromaDB 做断言（`mysql_query`/`chroma_count`），不修改业务数据。

注意：脚本会停止/启动 `sc-python-ai` 与 `sc-e2e-mock-model` 两个容器，运行期间页面暂不可用。
巡检用例需要临时 env 设置 `SC_ZOMBIE_SCAN_STALE_MINUTES=1` 与
`SC_ZOMBIE_SCAN_INTERVAL_MS=5000`（见脚本文件头与 TASK_TRACKER 记录）。

## 5. 测试账号保留与清理（手动）

所有 E2E 脚本都**只创建临时账号，不自动删除账号**，原因：
删除账号会连带影响其课程、文档、DDL 与会话数据，必须由维护者在明确授权后执行，
不能由测试脚本在无人值守时静默完成。脚本退出时会打印账号名并标注“保留”。

已知限制：长期运行会积累 `e2e_smoke_*`、`e2e_course_*`、`e2e_p4_browser_*` 账号。
清理前先用 SELECT 确认目标：

```bash
docker compose exec -T sc-mysql sh -lc \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e \
   "SELECT user_id, username FROM t_user_config WHERE username LIKE \"e2e_%\";"'
```

确认后按“子表 → 主表”顺序删除（示例，执行前自行备份并确认前缀范围）：

```bash
docker compose exec -T sc-mysql sh -lc \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e \
   "DELETE p FROM t_knowledge_parent_chunk p JOIN t_user_config u ON p.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE d FROM t_knowledge_doc d JOIN t_user_config u ON d.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE t FROM t_ddl_task t JOIN t_user_config u ON t.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE n FROM t_notification_log n JOIN t_user_config u ON n.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE m FROM t_chat_message m JOIN t_user_config u ON m.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE c FROM t_course_namespace c JOIN t_user_config u ON c.user_id=u.user_id WHERE u.username LIKE \"e2e_%\";
    DELETE FROM t_user_config WHERE username LIKE \"e2e_%\";"'
```

> 禁止在生产库或未授权环境执行上述删除；脚本本身绝不调用任何账号删除接口。

## 6. 安全约束

* 脚本中的 API Key 固定为 `sk-*-not-a-real-key`，只进入被 SSRF 前置拦截的请求体，不会出站；
* 不读取、不打印 `.env` 中的任何真实密钥；
* mock 容器与临时 env 文件验证后必须清理，避免本地模型白名单长期开启。
