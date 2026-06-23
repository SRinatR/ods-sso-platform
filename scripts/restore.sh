#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

env_file="${ENV_FILE:-/etc/ods-platform/production.env}"
object_name="${1:-}"
source_alias="${RESTORE_SOURCE:-local}"

if [ "${RESTORE_CONFIRM:-}" != "restore-ods-production" ]; then
  echo "Restore aborted: set RESTORE_CONFIRM=restore-ods-production" >&2
  exit 1
fi
if [ -z "${object_name}" ]; then
  echo "Usage: RESTORE_CONFIRM=restore-ods-production $0 ods_sso-YYYYMMDDTHHMMSSZ.sql.gz" >&2
  exit 1
fi

compose() {
  docker compose --env-file "${env_file}" "$@"
}

compose run --rm --no-deps minio-client -c '
  set -eu
  alias_name="'"${source_alias}"'"
  if [ "$alias_name" = "remote" ]; then
    mc alias set remote "$BACKUP_S3_ENDPOINT" "$BACKUP_S3_ACCESS_KEY" "$BACKUP_S3_SECRET_KEY"
    bucket="$BACKUP_S3_BUCKET"
  else
    mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    bucket="$MINIO_BUCKET"
  fi
  mc cp "$alias_name/$bucket/database/'"${object_name}"'" "/backup/'"${object_name}"'"
  mc cp "$alias_name/$bucket/database/'"${object_name}"'.sha256" "/backup/'"${object_name}"'.sha256"
'

backup_dir="${BACKUP_DIR:-/var/backups/ods-platform}"
(
  cd "${backup_dir}"
  sha256sum -c "${object_name}.sha256"
)
gzip -dc "${backup_dir}/${object_name}" | compose exec -T postgres sh -lc \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

echo "Restore completed from ${source_alias}:${object_name}"
