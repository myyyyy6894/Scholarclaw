#!/usr/bin/env bash
# NapCat WebUI 运维入口：读取/设置访问令牌，并输出带令牌的登录地址。
#
# 用途：免去每次去 webui.json 或 docker logs 里翻令牌。
#   scripts/napcat/webui.sh            # 打印带令牌的 WebUI 登录地址
#   scripts/napcat/webui.sh token      # 只打印令牌
#   scripts/napcat/webui.sh set <令牌> # 设置新令牌并重启 NapCat 容器
#
# 边界：令牌是 NapCat 的**管理员凭据**，控制着机器人 QQ；只给部署/运维人员用，
# 不要通过业务前端或 API 下发给普通用户。
set -euo pipefail

CONTAINER="${SC_NAPCAT_CONTAINER:-sc-napcat-onebot}"
CONFIG="/app/napcat/config/webui.json"
HOST="${SC_NAPCAT_WEBUI_HOST:-127.0.0.1}"
PORT="${SC_NAPCAT_WEBUI_PORT:-6099}"

usage() {
  cat <<'EOF'
用法：
  scripts/napcat/webui.sh            打印带令牌的 WebUI 登录地址
  scripts/napcat/webui.sh token      只打印令牌
  scripts/napcat/webui.sh set <令牌> 设置新令牌并重启 NapCat 容器
环境变量：SC_NAPCAT_CONTAINER / SC_NAPCAT_WEBUI_HOST / SC_NAPCAT_WEBUI_PORT
EOF
}

require_container() {
  if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    echo "错误：容器 $CONTAINER 不存在；先运行 docker compose --profile onebot up -d sc-napcat-onebot" >&2
    exit 1
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER")" != "true" ]; then
    echo "错误：容器 $CONTAINER 未运行" >&2
    exit 1
  fi
}

read_field() {
  docker exec "$CONTAINER" python3 -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    print(json.load(fh).get(sys.argv[2], ""))
' "$CONFIG" "$1"
}

print_url() {
  local token
  token="$(read_field token)"
  if [ -z "$token" ]; then
    echo "错误：$CONFIG 中没有 token 字段（WebUI 可能已禁用）" >&2
    exit 1
  fi
  printf 'http://%s:%s/webui?token=%s\n' "$HOST" "$PORT" "$token"
}

set_token() {
  local new_token="$1"
  if [ -z "$new_token" ]; then
    echo "错误：令牌不能为空" >&2
    exit 1
  fi
  case "$new_token" in
    *[[:space:]]*|*[?!\&=\#/\'\"\\]*)
      echo "错误：令牌不能包含空白、引号或 ? ! & = # / 等 URL 特殊字符" >&2
      exit 1
      ;;
  esac
  if [ "${#new_token}" -lt 8 ]; then
    echo "错误：令牌至少 8 个字符" >&2
    exit 1
  fi
  docker exec -i "$CONTAINER" python3 - "$CONFIG" "$new_token" <<'PY'
import json, sys
path, token = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as fh:
    data = json.load(fh)
data["token"] = token
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as fh:
    json.dump(data, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
import os
os.replace(tmp, path)
PY
  echo "已写入新令牌，重启 $CONTAINER 生效 ..."
  docker restart "$CONTAINER" >/dev/null
  echo "完成；新登录地址："
  print_url
}

case "${1:-url}" in
  url)   require_container; print_url ;;
  token) require_container; read_field token; echo ;;
  set)   require_container; set_token "${2:-}" ;;
  -h|--help|help) usage ;;
  *)     echo "未知命令：$1" >&2; usage >&2; exit 2 ;;
esac
