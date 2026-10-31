#!/usr/bin/env bash
# 手工迁移（生产/无 Java 启动权限时使用），与 DatabaseMigrationRunner 共用版本表契约。
#
# 规则（03 §10）：
#   1. 按版本号数值升序执行未成功应用的 V*.sql；
#   2. 每条脚本执行后写 sc_schema_migration（版本、文件名、SHA-256、耗时、success）；
#   3. 已成功版本的 checksum 与当前文件不一致时拒绝继续（防脚本漂移）；
#   4. 失败版本记录 success=0 + 错误摘要，修复后删除该版本行再重跑。
#
# 用法（仓库根目录）：
#   bash scripts/db/manual_migrate.sh
# 前置：.env 含 SC_DB_ROOT_PASSWORD；容器 sc-mysql 已启动。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
MIGRATION_DIR="sc-java-backend/src/main/resources/db/migration"

if [[ -f .env ]]; then
  # shellcheck disable=SC1091
  set -a; source .env; set +a
fi
: "${SC_DB_ROOT_PASSWORD:?请在 .env 中设置 SC_DB_ROOT_PASSWORD}"

mysql_exec() {
  docker compose exec -T -e MYSQL_PWD="$SC_DB_ROOT_PASSWORD" sc-mysql \
    mysql --default-character-set=utf8mb4 -N -B -uroot -D scholarclaw "$@"
}

mysql_exec <<'SQL'
CREATE TABLE IF NOT EXISTS `sc_schema_migration` (
  `version`      VARCHAR(32)  NOT NULL COMMENT '脚本版本号(如 1.0.0)',
  `description`  VARCHAR(255) NOT NULL COMMENT '脚本用途简述',
  `script`       VARCHAR(255) NOT NULL COMMENT '脚本文件名',
  `checksum`     CHAR(64)     NOT NULL COMMENT '脚本内容 SHA-256',
  `success`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否执行成功: 0-失败, 1-成功',
  `execution_ms` INT          DEFAULT NULL COMMENT '执行耗时(毫秒)',
  `error_message` VARCHAR(512) DEFAULT NULL COMMENT '失败原因摘要(已截断)',
  `applied_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='数据库迁移版本记录表(03 §10：有序 SQL + 版本表，不引入 Flyway)';
SQL

shopt -s nullglob
files=("$MIGRATION_DIR"/V*.sql)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "未找到迁移脚本：$MIGRATION_DIR" >&2
  exit 1
fi
IFS=$'\n' files=($(printf '%s\n' "${files[@]}" | sort -V)); unset IFS

for file in "${files[@]}"; do
  name="$(basename "$file")"
  version="${name#V}"; version="${version%%__*}"
  description="${name#*__}"; description="${description%.sql}"
  checksum="$(sha256sum "$file" | awk '{print $1}')"

  row="$(mysql_exec -e "SELECT checksum, success FROM sc_schema_migration WHERE version='${version}'" || true)"
  if [[ -n "$row" ]]; then
    applied_checksum="${row%%$'\t'*}"
    applied_success="${row##*$'\t'}"
    if [[ "$applied_checksum" != "$checksum" ]]; then
      echo "拒绝继续：版本 ${version} 已执行，但当前文件 checksum 与版本表不一致。" >&2
      echo "  版本表：${applied_checksum}" >&2
      echo "  当前文件：${checksum}" >&2
      echo "改历史脚本必须新增增量脚本（03 §10.2）。" >&2
      exit 1
    fi
    if [[ "$applied_success" == "1" ]]; then
      echo "SKIP ${version} ${name}（已成功执行）"
      continue
    fi
    echo "RETRY ${version} ${name}（上次失败，重新执行）"
  fi

  started="$(date +%s%3N)"
  if docker compose exec -T -e MYSQL_PWD="$SC_DB_ROOT_PASSWORD" sc-mysql \
      mysql --default-character-set=utf8mb4 -uroot -D scholarclaw < "$file"; then
    elapsed=$(( $(date +%s%3N) - started ))
    mysql_exec -e "INSERT INTO sc_schema_migration
      (version, description, script, checksum, success, execution_ms, error_message)
      VALUES ('${version}', '${description}', '${name}', '${checksum}', 1, ${elapsed}, NULL)
      ON DUPLICATE KEY UPDATE description=VALUES(description), script=VALUES(script),
        checksum=VALUES(checksum), success=1, execution_ms=VALUES(execution_ms),
        error_message=NULL, applied_at=CURRENT_TIMESTAMP(3)"
    echo "OK   ${version} ${name}（${elapsed} ms）"
  else
    elapsed=$(( $(date +%s%3N) - started ))
    mysql_exec -e "INSERT INTO sc_schema_migration
      (version, description, script, checksum, success, execution_ms, error_message)
      VALUES ('${version}', '${description}', '${name}', '${checksum}', 0, ${elapsed},
        'manual_migrate.sh 执行失败，请查看容器输出')
      ON DUPLICATE KEY UPDATE success=0, execution_ms=VALUES(execution_ms),
        error_message=VALUES(error_message), applied_at=CURRENT_TIMESTAMP(3)"
    echo "FAIL ${version} ${name}；已记录 success=0，修复后删除该版本行再重跑。" >&2
    exit 1
  fi
done

echo
echo "手工迁移完成。当前版本表："
mysql_exec -e "SELECT version, script, success, execution_ms, applied_at
  FROM sc_schema_migration ORDER BY version"
