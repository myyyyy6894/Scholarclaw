#!/usr/bin/env bash
#
# ScholarClaw 备份：MySQL 数据 + 课件共享卷 + .env（含主密钥）。
#
# 为什么要备份这三样：
#   * MySQL   —— 账号、课程、文档记录、DDL 任务、会话、通知的唯一权威源；
#   * 共享卷  —— 用户上传的课件原文件，丢了要所有人重传；
#   * .env    —— 其中的 SC_MASTER_KEY 是用户 API Key 的加密主密钥，
#                丢失后所有已保存的 Key 永久无法解密（用户必须全部重填）。
# ChromaDB 向量库**不备份**：它是可从课件重建的派生数据。
#
# 用法（仓库根目录）：
#   bash scripts/backup/backup.sh
#   BACKUP_DIR=/var/backups/scholarclaw bash scripts/backup/backup.sh
#
# 环境变量：
#   BACKUP_DIR              备份输出目录，默认 <仓库>/backups
#   BACKUP_RETENTION_DAYS   保留天数，默认 7（按文件名时间戳判断，不用文件 mtime）
#   BACKUP_STORAGE          1=备份课件卷（默认），0=只备份数据库与 .env
#
# 前置：.env 存在且 sc-mysql / sc-java-backend 正在运行。
# 恢复步骤见 DEPLOYMENT.md §10。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
fi

if [[ ! -f .env ]]; then
  echo "错误：$ROOT/.env 不存在。备份必须包含 .env 中的 SC_MASTER_KEY。" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

: "${SC_DB_NAME:=scholarclaw}"
: "${SC_DB_USER:=scholarclaw}"
: "${SC_DB_PASSWORD:?请在 .env 中设置 SC_DB_PASSWORD}"

BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"
BACKUP_STORAGE="${BACKUP_STORAGE:-1}"

# 备份里含主密钥与数据库全量数据，默认只允许本用户读写
umask 077
mkdir -p "$BACKUP_DIR"

TS="$(date +%Y%m%dT%H%M%S)"
PREFIX="scholarclaw-$TS"

running_services() {
  docker compose ps --services --status running 2>/dev/null || true
}
SERVICES="$(running_services)"

if ! grep -qx 'sc-mysql' <<<"$SERVICES"; then
  echo "错误：sc-mysql 未运行，先执行 docker compose up -d" >&2
  exit 1
fi

echo "[1/4] 备份 MySQL（$SC_DB_NAME，--single-transaction 不锁表）"
DUMP="$BACKUP_DIR/$PREFIX-mysql.sql.gz"
docker compose exec -T -e MYSQL_PWD="$SC_DB_PASSWORD" sc-mysql \
  mysqldump --single-transaction --quick --routines --triggers --no-tablespaces \
  --default-character-set=utf8mb4 -u"$SC_DB_USER" "$SC_DB_NAME" \
  | gzip -9 >"$DUMP"

echo "[2/4] 备份 .env（含 SC_MASTER_KEY，注意保管）"
cp .env "$BACKUP_DIR/$PREFIX-env"
chmod 600 "$BACKUP_DIR/$PREFIX-env"

STORAGE=""
if [[ "$BACKUP_STORAGE" == "1" ]]; then
  if grep -qx 'sc-java-backend' <<<"$SERVICES"; then
    echo "[3/4] 备份课件共享卷（vol_shared_storage）"
    STORAGE="$BACKUP_DIR/$PREFIX-storage.tar.gz"
    docker compose exec -T sc-java-backend \
      tar czf - -C /data/scholarclaw/storage . >"$STORAGE"
  else
    echo "[3/4] 跳过课件卷：sc-java-backend 未运行（可设 BACKUP_STORAGE=0 显式跳过）" >&2
    exit 1
  fi
else
  echo "[3/4] 跳过课件卷（BACKUP_STORAGE=0）"
fi

echo "[4/4] 清理超过 ${RETENTION_DAYS} 天的旧备份"
# 按文件名里的时间戳清理，而不是文件 mtime：拷贝/恢复备份目录不会让旧备份"永不过期"
deleted=0
while IFS= read -r old; do
  [[ -z "$old" ]] && continue
  ts="$(basename "$old" | sed -n 's/^scholarclaw-\([0-9]\{8\}T[0-9]\{6\}\)-.*/\1/p')"
  [[ -z "$ts" ]] && continue
  cutoff="$(date -d "$RETENTION_DAYS days ago" +%Y%m%dT%H%M%S)"
  if [[ "$ts" < "$cutoff" ]]; then
    rm -f "$old"
    deleted=$((deleted + 1))
  fi
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'scholarclaw-*')

# 空文件说明管道中途失败（docker exec 出错但 gzip 仍建了文件）
for f in "$DUMP" "$STORAGE"; do
  [[ -z "$f" ]] && continue
  if [[ ! -s "$f" ]]; then
    echo "错误：备份文件为空 $f" >&2
    exit 1
  fi
done

{
  echo "backup_time=$TS"
  echo "commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "db=$SC_DB_NAME"
  for f in "$DUMP" "$STORAGE"; do
    [[ -z "$f" ]] && continue
    echo "$(basename "$f")=$(du -h "$f" | cut -f1)"
  done
  echo "env=$(basename "$BACKUP_DIR/$PREFIX-env")"
} >"$BACKUP_DIR/$PREFIX-manifest.txt"

echo
echo "备份完成：$BACKUP_DIR"
ls -lh "$BACKUP_DIR/$PREFIX"* | awk '{print "  " $5 "\t" $9}'
echo "已清理 ${deleted} 个过期文件。"
echo
echo "提醒：这些文件包含数据库全量与主密钥，请复制到**另一台机器或对象存储**；"
echo "      只留在本机会随磁盘一起坏掉。"
