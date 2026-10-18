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
COPY app /app/app

RUN pip install --index-url "${PIP_INDEX_URL}" .

# 只读挂载点目录（仅用于路径存在性检查，Python 不会写入其中）
RUN mkdir -p /data/scholarclaw/storage/raw && chown -R app:app /data/scholarclaw/storage

USER ${APP_UID}:${APP_GID}

EXPOSE 8000

# 注意：容器内必须监听 0.0.0.0 才能被同网络的 Java 访问；
# 本机开发时则应绑定 127.0.0.1（见 README §3.2），两者场景不同。
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
