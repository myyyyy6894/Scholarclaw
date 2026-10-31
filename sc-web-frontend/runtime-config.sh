#!/bin/sh
# 由官方 nginx 镜像在启动前自动执行（/docker-entrypoint.d/*.sh）。
# 作用：把 SC_NAPCAT_WEBUI_URL 写成运行期配置，改地址只需重启容器，不必重建前端镜像。
# 只写入非密钥的跳转地址；空白值会让前端回落到构建期 VITE_NAPCAT_WEBUI_URL / 默认值。
set -eu

target=/usr/share/nginx/html/runtime-config.js
value="${SC_NAPCAT_WEBUI_URL:-}"

# URL 不可能包含换行；去掉它们避免破坏 JS 字面量。
value=$(printf '%s' "$value" | tr -d '\r\n')
# 转义反斜杠与双引号，保证写入的是合法 JS 字符串。
escaped=$(printf '%s' "$value" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

cat > "$target" <<EOF
// 由 sc-web-nginx 启动脚本生成（SC_NAPCAT_WEBUI_URL）；请勿手工编辑。
window.__SC_NAPCAT_WEBUI_URL__ = "${escaped}";
EOF

echo "[runtime-config] SC_NAPCAT_WEBUI_URL=${value:-<empty: 使用构建期默认值>}"
