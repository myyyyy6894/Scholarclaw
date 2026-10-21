# E2E 脚本

这些脚本用于**可重复**验证认证、BYOK 与前端页面，不写入真实密钥，也不修改生产 `.env`。

前置：整栈已启动（`docker compose up -d`），Nginx 入口可达（默认 `http://127.0.0.1`）。

## 1. 认证 + BYOK 冒烟（无需浏览器）

```bash
python3 scripts/e2e/auth_byok_smoke.py
# 或指定入口
python3 scripts/e2e/auth_byok_smoke.py --base-url http://127.0.0.1
```

覆盖：注册、重复注册、错误密码、未认证访问、伪造 `X-User-Id`、登录、
公共探测拒绝 `BOTH`、SSRF 拦截（元数据/内部服务名/内部端口）、偏好成对与非法时区校验、
Key 销毁幂等、登出后旧 token `AUTH_TOKEN_REVOKED`。

脚本只创建临时账号并打印用户名。清理示例（需 MySQL 权限）：

```bash
docker compose exec -T sc-mysql sh -lc \
  'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
   -e "DELETE FROM t_user_config WHERE username LIKE \"e2e_smoke_%\";"'
```

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
printf '\nSC_ALLOW_LOCAL_MODEL_BASE_URL=true\nSC_LOCAL_MODEL_ALLOWED_ORIGINS=http://sc-e2e-mock-model:9000\n' >> /tmp/sc-e2e.env

docker run -d --name sc-e2e-mock-model --network scholarclaw-internal \
  -v "$PWD/scripts/e2e/mock_model_server.py:/server.py:ro" \
  python:3.11-slim python3 /server.py

docker compose --env-file /tmp/sc-e2e.env up -d --force-recreate sc-java-backend sc-python-ai

/tmp/sc-e2e-venv/bin/python scripts/e2e/browser_byok_e2e.py \
  --mock-base-url http://sc-e2e-mock-model:9000/v1
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

## 4. 课程空间冒烟（TASK-401）

在临时 mock 模型白名单生效、mock 容器运行后：

```bash
python3 scripts/e2e/course_smoke.py \
  --mock-base-url "http://<mock-container-ip>:9000/v1"
```

覆盖：保存已验证 Embedding 配置、建课锁定维度、同名 409、`existing_course_count`、
文档列表与真实统计、切换模型版本递增与文档 `PARSING`/旧父块清空、跨用户隔离、
删除后 DDL 任务解绑保留与文档 `DELETING`、重复删除 404。
脚本只使用假 Key；ID 用十进制字符串；验证后按上面的临时 env 流程恢复容器。

## 5. 安全约束

* 脚本中的 API Key 固定为 `sk-*-not-a-real-key`，只进入被 SSRF 前置拦截的请求体，不会出站；
* 不读取、不打印 `.env` 中的任何真实密钥；
* mock 容器与临时 env 文件验证后必须清理，避免本地模型白名单长期开启。
