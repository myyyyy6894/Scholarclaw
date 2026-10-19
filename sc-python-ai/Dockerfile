# ============================================================================
# sc-python-ai 运行镜像（Python 3.11 + FastAPI）
#
# 与 docs/02 §5.1 的一致性约束：本容器
#   * 不连接 MySQL / Redis（镜像内不含任何数据库或缓存客户端，有源码级测试守护）；
#   * 不持有 SC_MASTER_KEY、数据库凭据或 JWT 密钥（这些变量在 compose 中根本不注入给它）；
#   * 对共享卷只读挂载，绝不写入或删除原始课件。
# ============================================================================
FROM python:3.11-slim

ARG APP_UID=10001
ARG APP_GID=10001
# 允许构建时指定 PyPI 镜像源（本机经代理直连较慢）；默认使用官方源
ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 与 sc-java-backend 使用同一 uid/gid，保证共享卷的读写/只读对齐
RUN groupadd -g ${APP_GID} app \
 && useradd -u ${APP_UID} -g app --create-home --shell /usr/sbin/nologin app

WORKDIR /app

# 依赖声明与源码分开 COPY：改源码不会让依赖层失效
COPY pyproject.toml /app/pyproject.toml
# 锁文件：版本全部固定，保证镜像可复现（生成与升级方式见文件头注释）
COPY requirements.lock.txt /app/requirements.lock.txt
COPY app /app/app

# 先按锁文件装第三方依赖（--no-deps 之外不再二次解析），再用 --no-deps 装本服务自身：
# 若直接 `pip install .`，pip 会按 pyproject 的版本区间重新解析，锁文件就白锁了。
RUN pip install --index-url "${PIP_INDEX_URL}" -r /app/requirements.lock.txt \
 && pip install --index-url "${PIP_INDEX_URL}" --no-deps .

# 容器健康检查脚本（Compose healthcheck 入口）：显式解析 data.status，并持续验证
# Python → Java 方向的双向健康调通（02 §15.1 阶段 1 准出条件）
COPY healthcheck.py /app/healthcheck.py

# 只读挂载点目录（仅用于路径存在性检查，Python 不会写入其中）
RUN mkdir -p /data/scholarclaw/storage/raw && chown -R app:app /data/scholarclaw/storage

USER ${APP_UID}:${APP_GID}

EXPOSE 8000

# 注意：容器内必须监听 0.0.0.0 才能被同网络的 Java 访问；
# 本机开发时则应绑定 127.0.0.1（见 README §3.2），两者场景不同。
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
