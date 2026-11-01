#!/usr/bin/env bash
#
# NapCat 掉线看门狗。
#
# 背景：QQ 会话过期或触发风控时，NapCat 会一直重试"快速登录"并失败，
# 容器本身仍是 Up，Docker 的 restart 策略救不回来——必须有人踢它一脚。
# 本脚本检测 OneBot 的登录态，掉线就重启容器（重启会重新尝试快速登录）。
#
# 用法（服务器上，cron 每 2 分钟）：
#   */2 * * * * cd /opt/ScholarClaw && bash scripts/napcat/watchdog.sh >> /var/log/sc-napcat-watchdog.log 2>&1
#
# 三条边界（都是踩过的坑）：
#   1. **启动中不重启**：容器刚起来时接口本来就没响应，贸然重启会让扫码窗口永远开不出来；
#   2. **连续失败要停手**：QQ 要求重新扫码时重启多少次都没用，第 3 次之后只报警不动手；
#   3. 状态写在 /tmp，重启容器不受影响；人工扫码成功后计数会自动清零；
#   4. **系统过载时不动手**：被 DoS 打到负载 29 时，OneBot 接口只是被拖慢而非掉线，
#      此时重启容器会打断快速登录，反而让 QQ 多花几分钟才回来（实测踩过）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

[[ -f .env ]] || { echo "$(date '+%F %T') 缺少 .env"; exit 1; }
# shellcheck disable=SC1091
set -a; source .env; set +a

PORT="${SC_NAPCAT_HTTP_PORT:-3000}"
TOKEN="${SC_ONEBOT_ACCESS_TOKEN:-}"
CONTAINER="sc-napcat-onebot"
COUNT_FILE="/tmp/sc-napcat-watchdog.failures"
OK_FILE="/tmp/sc-napcat-watchdog.ok"
GRACE_SECONDS=180

stamp() { date '+%F %T'; }

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
  echo "$(stamp) 容器未运行，尝试启动"
  docker compose --profile onebot up -d "$CONTAINER" >/dev/null 2>&1 || true
  exit 0
fi

# 过载保护：负载超过核数 2 倍时，接口无响应大概率是机器卡，不是账号掉线
cores=$(nproc 2>/dev/null || echo 2)
load1=$(awk '{print int($1)}' /proc/loadavg 2>/dev/null || echo 0)
if (( load1 >= cores * 2 )); then
  echo "$(stamp) 系统负载过高（load=${load1}，核数=${cores}），跳过本次检查"
  exit 0
fi

# 启动宽限期：容器刚起时接口不可用是正常的，此时重启只会打断扫码
started_at=$(docker inspect -f '{{.State.StartedAt}}' "$CONTAINER" 2>/dev/null || echo "")
if [[ -n "$started_at" ]]; then
  started_epoch=$(date -d "$started_at" +%s 2>/dev/null || echo 0)
  now_epoch=$(date +%s)
  if (( started_epoch > 0 && now_epoch - started_epoch < GRACE_SECONDS )); then
    exit 0
  fi
fi

resp=$(curl -s -m 10 -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${PORT}/get_login_info" || true)
logged_in=$(printf '%s' "$resp" | grep -o '"user_id":[0-9]*' | grep -o '[0-9]*' | head -1 || true)

if [[ -n "$logged_in" && "$logged_in" != "0" ]]; then
  # 账号校验：扫码时登错号是真实踩过的坑（把个人大号当机器人登了，接收了私人消息）
  expected="${SC_NAPCAT_ACCOUNT:-}"
  if [[ -n "$expected" && "$logged_in" != "$expected" ]]; then
    if [[ ! -f "$OK_FILE.mismatch" ]]; then
      echo "$(stamp) ⚠️ 登录账号与配置不符：实际 ${logged_in}，期望 ${expected}；请到 WebUI 退出并用正确账号重扫"
      touch "$OK_FILE.mismatch"
    fi
    exit 0
  fi
  rm -f "$OK_FILE.mismatch"
  if [[ ! -f "$OK_FILE" ]]; then
    echo "$(stamp) QQ 已登录 user_id=${logged_in}"
    touch "$OK_FILE"
  fi
  rm -f "$COUNT_FILE"
  exit 0
fi

failures=$(( $(cat "$COUNT_FILE" 2>/dev/null || echo 0) + 1 ))
echo "$failures" > "$COUNT_FILE"
rm -f "$OK_FILE"

if (( failures <= 3 )); then
  echo "$(stamp) QQ 未登录（第 ${failures} 次），重启容器尝试快速登录"
  docker compose --profile onebot restart "$CONTAINER" >/dev/null 2>&1 || true
else
  echo "$(stamp) QQ 连续 ${failures} 次未登录，大概率需要人工扫码；已停止重启。"
  echo "$(stamp)   取 WebUI 地址：cd /opt/ScholarClaw && bash scripts/napcat/webui.sh url"
fi
exit 0
