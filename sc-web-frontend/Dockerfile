# ============================================================================
# sc-web-nginx 镜像：构建 Vue 静态产物并交给 Nginx 托管
#
# 与 Java 侧不同，这里用真正的多阶段构建——npm 依赖可以从镜像源快速拉取
# （实测 106 个包 11 秒），无需依赖宿主机预构建产物。
# ============================================================================

# ---------- 阶段 1：构建 ----------
FROM node:20-alpine AS build

WORKDIR /app

# 文档基线：Node 20 LTS 用于构建（docs/02 §1.3）
ARG NPM_REGISTRY=https://registry.npmjs.org/
# 构建期注入的 NapCat WebUI 跳转地址（非密钥）；远程部署时由 compose build args 覆盖。
ARG VITE_NAPCAT_WEBUI_URL=http://127.0.0.1:6099
ENV VITE_NAPCAT_WEBUI_URL=${VITE_NAPCAT_WEBUI_URL}

# 先复制依赖清单再安装，改业务代码时不会让依赖层缓存失效。
# 注意：package-lock.json 的 resolved 指向 npmmirror（生成时的网络条件所致，见 RISK-19），
# npm 会按锁文件里的地址下载；这里同时开放 --registry 覆盖开关。
COPY package.json package-lock.json ./
RUN npm ci --registry="${NPM_REGISTRY}" --no-audit --no-fund

COPY . .
RUN npm run build

# ---------- 阶段 2：运行 ----------
FROM nginx:1.26-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# 运行期配置：启动时按 SC_NAPCAT_WEBUI_URL 生成 runtime-config.js（非密钥跳转地址）。
COPY --chmod=755 runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=10s \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
