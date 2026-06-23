#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file="${ENV_FILE:-/etc/ods-platform/production.env}"
if [ ! -r "${env_file}" ]; then
  echo "Backup aborted: ${env_file} is not readable" >&2
  exit 1
fi

compose() {
  docker compose --env-file "${env_file}" "$@"
}

read_env() {
  grep -m1 "^${1}=" "${env_file}" | cut -d= -f2-
}

backup_dir="${BACKUP_DIR:-$(read_env BACKUP_DIR)}"
backup_dir="${backup_dir:-/var/backups/ods-platform}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
filename="ods_sso-${timestamp}.sql.gz"
path="${backup_dir}/${filename}"

umask 077
mkdir -p "${backup_dir}"
compose exec -T postgres sh -lc \
  'pg_dump --clean --if-exists -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip -9 > "${path}"
test -s "${path}"
sha256sum "${path}" > "${path}.sha256"

compose run --rm --no-deps minio-client -c '
  set -eu
  mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
  mc mb --ignore-existing "local/$MINIO_BUCKET"
  mc cp "/backup/'"${filename}"'" "local/$MINIO_BUCKET/database/'"${filename}"'"
  mc cp "/backup/'"${filename}"'.sha256" "local/$MINIO_BUCKET/database/'"${filename}"'.sha256"
  if [ -n "$BACKUP_S3_ENDPOINT" ] && [ -n "$BACKUP_S3_ACCESS_KEY" ] &&
     [ -n "$BACKUP_S3_SECRET_KEY" ] && [ -n "$BACKUP_S3_BUCKET" ]; then
    mc alias set remote "$BACKUP_S3_ENDPOINT" "$BACKUP_S3_ACCESS_KEY" "$BACKUP_S3_SECRET_KEY"
    mc mb --ignore-existing "remote/$BACKUP_S3_BUCKET"
    mc cp "/backup/'"${filename}"'" "remote/$BACKUP_S3_BUCKET/database/'"${filename}"'"
    mc cp "/backup/'"${filename}"'.sha256" "remote/$BACKUP_S3_BUCKET/database/'"${filename}"'.sha256"
  fi
'

find "${backup_dir}" -type f -name 'ods_sso-*.sql.gz*' -mtime +14 -delete
echo "Backup completed: ${path}"
