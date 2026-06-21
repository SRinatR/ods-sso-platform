#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Deployment aborted: .env is missing" >&2
  exit 1
fi

required=(
  PUBLIC_DOMAIN
  ROOT_DOMAIN
  WWW_DOMAIN
  ACCOUNT_DOMAIN
  ADMIN_DOMAIN
  API_DOMAIN
  DOCS_DOMAIN
  STATUS_DOMAIN
  SSO_DOMAIN
  SCIM_DOMAIN
  WEBHOOKS_DOMAIN
  ISSUER
  ACCOUNT_URL
  API_URL
  ALLOWED_ORIGINS
  POSTGRES_PASSWORD
  SESSION_SECRET
  TOKEN_PEPPER
  TOTP_ENCRYPTION_KEY
  JWT_PRIVATE_KEY
  JWT_PUBLIC_KEY
)

for name in "${required[@]}"; do
  if ! grep -q "^${name}=.\+" .env; then
    echo "Deployment aborted: ${name} is not configured" >&2
    exit 1
  fi
done

public_domain="$(grep '^PUBLIC_DOMAIN=' .env | cut -d= -f2-)"
root_domain="$(grep '^ROOT_DOMAIN=' .env | cut -d= -f2-)"
www_domain="$(grep '^WWW_DOMAIN=' .env | cut -d= -f2-)"
canonical_url="https://${public_domain}"

if [ "${public_domain}" != "auth.${root_domain}" ]; then
  echo "Deployment aborted: PUBLIC_DOMAIN must equal auth.${root_domain}" >&2
  exit 1
fi

if [ "${www_domain}" != "www.${root_domain}" ]; then
  echo "Deployment aborted: WWW_DOMAIN must equal www.${root_domain}" >&2
  exit 1
fi

declare -A service_domains=(
  [ACCOUNT_DOMAIN]="accounts"
  [ADMIN_DOMAIN]="admin"
  [API_DOMAIN]="api"
  [DOCS_DOMAIN]="docs"
  [STATUS_DOMAIN]="status"
  [SSO_DOMAIN]="sso"
  [SCIM_DOMAIN]="scim"
  [WEBHOOKS_DOMAIN]="webhooks"
)

for name in "${!service_domains[@]}"; do
  value="$(grep "^${name}=" .env | cut -d= -f2-)"
  expected="${service_domains[${name}]}.${root_domain}"
  if [ "${value}" != "${expected}" ]; then
    echo "Deployment aborted: ${name} must equal ${expected}" >&2
    exit 1
  fi
done

for name in ISSUER ACCOUNT_URL API_URL ALLOWED_ORIGINS; do
  value="$(grep "^${name}=" .env | cut -d= -f2-)"
  if [ "${value}" != "${canonical_url}" ]; then
    echo "Deployment aborted: ${name} must equal ${canonical_url}" >&2
    exit 1
  fi
done

cp -f Caddyfile.production Caddyfile

docker compose config --quiet

backup_dir="${BACKUP_DIR:-/var/backups/ods-platform}"
umask 077
mkdir -p "${backup_dir}"
if docker compose ps --status running --services | grep -qx postgres; then
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_path="${backup_dir}/ods_sso-pre-deploy-${timestamp}.sql.gz"
  docker compose exec -T postgres sh -lc \
    'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip -9 > "${backup_path}"
  test -s "${backup_path}"
  echo "Database backup created at ${backup_path}"
fi

docker compose up -d --build --remove-orphans
docker compose exec -T postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select version, description, success from flyway_schema_history order by installed_rank desc limit 1"'
docker compose exec -T postgres sh -lc \
  'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select uuidv7()"'

uuid_table_count="$(
  docker compose exec -T postgres sh -lc \
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL' |
select count(*)
from information_schema.columns
where table_schema = current_schema()
  and column_name = 'internal_id'
  and udt_name = 'uuid'
  and table_name = any(array[
    'tenants',
    'users',
    'user_sessions',
    'account_tokens',
    'mfa_methods',
    'backup_codes',
    'login_history',
    'audit_logs',
    'user_consents',
    'security_policies',
    'trusted_devices',
    'risk_assessments',
    'domain_outbox',
    'used_refresh_tokens',
    'federation_providers',
    'key_metadata',
    'partner_organizations',
    'partner_memberships',
    'partner_applications'
  ]);
SQL
  tr -d '[:space:]'
)"

if [ "${uuid_table_count}" != "19" ]; then
  echo "Deployment failed UUIDv7 schema verification: expected 19 UUID-backed tables, found ${uuid_table_count}" >&2
  exit 1
fi

api_url="$(grep '^API_URL=' .env | cut -d= -f2-)"
for _ in $(seq 1 30); do
  if curl --fail --silent "${api_url}/ready" >/dev/null; then
    echo "Deployment is ready at ${api_url}"
    exit 0
  fi
  sleep 5
done

echo "Deployment failed readiness verification" >&2
docker compose ps
docker compose logs --tail=200 backend caddy
exit 1
