#!/bin/sh
# 由官方 nginx 镜像在启动前自动执行（/docker-entrypoint.d/*.sh）。
# 作用：把运行期配置写成 runtime-config.js，改值只需重启容器，不必重建前端镜像。
# 只写入非密钥信息（跳转地址、部署方联系 QQ）；空白值会让前端回落到构建期默认值或隐藏入口。
set -eu

target=/usr/share/nginx/html/runtime-config.js

# --- NapCat WebUI 地址（可选）---
value="${SC_NAPCAT_WEBUI_URL:-}"
# URL 不可能包含换行；去掉它们避免破坏 JS 字面量。
value=$(printf '%s' "$value" | tr -d '\r\n')
# 转义反斜杠与双引号，保证写入的是合法 JS 字符串。
escaped=$(printf '%s' "$value" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

# --- 部署方联系 QQ（可选）：只保留数字，空值 = 前端不显示联系方式 ---
qq="${SC_OPERATOR_QQ:-}"
qq=$(printf '%s' "$qq" | tr -cd '0-9')

# --- 机器人 QQ（可选）：只保留数字，用于指南里的「加机器人好友」提示 ---
bot_qq="${SC_BOT_QQ:-}"
bot_qq=$(printf '%s' "$bot_qq" | tr -cd '0-9')

# --- 项目主页地址（可选）：只接受 http(s)，其它值一律视为未配置 ---
project_url="${SC_PROJECT_URL:-}"
project_url=$(printf '%s' "$project_url" | tr -d '\r\n')
case "$project_url" in
  http://*|https://*) ;;
  *) project_url="" ;;
esac
url_escaped=$(printf '%s' "$project_url" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

cat > "$target" <<EOF
// 由 sc-web-nginx 启动脚本生成（SC_NAPCAT_WEBUI_URL / SC_OPERATOR_QQ / SC_PROJECT_URL）；请勿手工编辑。
window.__SC_NAPCAT_WEBUI_URL__ = "${escaped}";
window.__SC_OPERATOR_QQ__ = "${qq}";
window.__SC_PROJECT_URL__ = "${url_escaped}";
window.__SC_BOT_QQ__ = "${bot_qq}";
EOF

echo "[runtime-config] SC_NAPCAT_WEBUI_URL=${value:-<empty: 使用构建期默认值>} SC_OPERATOR_QQ=${qq:-<empty>} SC_PROJECT_URL=${project_url:-<empty>} SC_BOT_QQ=${bot_qq:-<empty>}"
