#!/bin/sh
# 由官方 nginx 镜像在启动前自动执行（/docker-entrypoint.d/*.sh）。
# 作用：把运行期配置写成 runtime-config.js，改值只需重启容器，不必重建前端镜像。
# 只写入非密钥信息（跳转地址、部署方联系 QQ）；空白值会让前端回落到构建期默认值或隐藏入口。
set -eu

target=/usr/share/nginx/html/runtime-config.js

# --- 部署方联系 QQ（可选）：只保留数字，空值 = 前端不显示联系方式 ---
qq="${SC_OPERATOR_QQ:-}"
qq=$(printf '%s' "$qq" | tr -cd '0-9')

# --- 项目主页地址（可选）：只接受 http(s)，其它值一律视为未配置 ---
project_url="${SC_PROJECT_URL:-}"
project_url=$(printf '%s' "$project_url" | tr -d '\r\n')
case "$project_url" in
  http://*|https://*) ;;
  *) project_url="" ;;
esac
url_escaped=$(printf '%s' "$project_url" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

cat > "$target" <<EOF
// 由 sc-web-nginx 启动脚本生成（SC_OPERATOR_QQ / SC_PROJECT_URL）；请勿手工编辑。
window.__SC_OPERATOR_QQ__ = "${qq}";
window.__SC_PROJECT_URL__ = "${url_escaped}";
EOF

echo "[runtime-config] SC_OPERATOR_QQ=${qq:-<empty>} SC_PROJECT_URL=${project_url:-<empty>}"
